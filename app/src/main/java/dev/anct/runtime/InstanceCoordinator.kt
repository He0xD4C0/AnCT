package dev.anct.runtime

import android.content.Context
import dev.anct.data.InstanceRepository
import dev.anct.data.OperationJournal
import dev.anct.core.ActualState
import dev.anct.core.AppHostedRuntimeBackend
import dev.anct.core.CommandResult
import dev.anct.core.DesiredState
import dev.anct.core.RuntimeCreateRequest
import dev.anct.core.RuntimeSpec
import dev.anct.core.RuntimeStatus
import dev.anct.core.TerminalLaunchSpec
import dev.anct.core.Validation
import dev.anct.service.RuntimeSupervisorService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class InstanceCoordinator(
    private val context: Context,
    private val repository: InstanceRepository,
    private val journal: OperationJournal,
    val runtimes: RuntimeRegistry,
) {
    private val globalMutex = Mutex()
    private val instanceLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(id: String): Mutex = instanceLocks.computeIfAbsent(id) { Mutex() }

    suspend fun create(
        request: RuntimeCreateRequest,
        onProgress: (String) -> Unit = {},
    ): CommandResult = globalMutex.withLock {
        val errors = Validation.validateRuntimeSpec(request.spec)
        if (errors.isNotEmpty()) return@withLock CommandResult(1, "", errors.joinToString("; "))
        val existing = repository.get(request.spec.id)
        if (existing != null) return@withLock CommandResult(1, "", "An instance with this name already exists")
        val operation = journal.begin(request.spec.id, "CREATE ${request.spec.kind}")
        val result = runCatching {
            runtimes.get(request.spec.kind).create(request, onProgress)
        }.getOrElse { CommandResult(1, "", it.message ?: "Create failed") }
        if (result.isSuccess) {
            repository.upsert(request.spec, DesiredState.STOPPED)
            repository.setStatus(request.spec.id, RuntimeStatus(ActualState.Stopped))
        }
        journal.finish(operation, result.isSuccess, result.stderr.ifBlank { result.stdout })
        result
    }

    suspend fun start(spec: RuntimeSpec): CommandResult = lockFor(spec.id).withLock {
        repository.setStatus(spec.id, RuntimeStatus(ActualState.Starting))
        repository.setDesired(spec.id, DesiredState.RUNNING)
        val backend = runtimes.get(spec.kind)
        if (backend is AppHostedRuntimeBackend) {
            RuntimeSupervisorService.start(context, backend.foregroundStartCommand(spec), spec.id)
            return@withLock CommandResult(0, "Start requested", "")
        }
        val operation = journal.begin(spec.id, "START")
        val result = runCatching { backend.start(spec) }
            .getOrElse { CommandResult(1, "", it.message ?: "Start failed") }
        if (result.isSuccess) {
            repository.setStatus(spec.id, backend.status(spec))
        } else {
            repository.setStatus(spec.id, RuntimeStatus(ActualState.Failed(result.stderr)))
            repository.setDesired(spec.id, DesiredState.STOPPED)
        }
        journal.finish(operation, result.isSuccess, result.stderr.ifBlank { result.stdout })
        result
    }

    suspend fun stop(spec: RuntimeSpec): CommandResult = lockFor(spec.id).withLock {
        repository.setStatus(spec.id, RuntimeStatus(ActualState.Stopping))
        repository.setDesired(spec.id, DesiredState.STOPPED)
        val backend = runtimes.get(spec.kind)
        if (backend is AppHostedRuntimeBackend) {
            RuntimeSupervisorService.stop(context, spec.id)
        }
        val operation = journal.begin(spec.id, "STOP")
        val result = runCatching { backend.stop(spec) }
            .getOrElse { CommandResult(1, "", it.message ?: "Stop failed") }
        repository.setStatus(
            spec.id,
            if (result.isSuccess) RuntimeStatus(ActualState.Stopped)
            else RuntimeStatus(ActualState.Failed(result.stderr)),
        )
        journal.finish(operation, result.isSuccess, result.stderr.ifBlank { result.stdout })
        result
    }

    suspend fun refresh(spec: RuntimeSpec): RuntimeStatus = lockFor(spec.id).withLock {
        val status = runCatching { runtimes.get(spec.kind).status(spec) }
            .getOrElse { RuntimeStatus(ActualState.Failed(it.message ?: "Status probe failed")) }
        repository.setStatus(spec.id, status)
        status
    }

    suspend fun refreshAll() {
        repository.getAll().forEach { stored ->
            refresh(repository.decode(stored.entity))
        }
    }

    suspend fun delete(spec: RuntimeSpec): CommandResult = lockFor(spec.id).withLock {
        val status = runCatching { runtimes.get(spec.kind).status(spec) }.getOrNull()
        if (status?.actualState is ActualState.Running || status?.actualState == ActualState.Starting) {
            return@withLock CommandResult(1, "", "Stop the instance before deleting it")
        }
        val operation = journal.begin(spec.id, "DELETE")
        val result = runCatching { runtimes.get(spec.kind).delete(spec) }
            .getOrElse { CommandResult(1, "", it.message ?: "Delete failed") }
        if (result.isSuccess) repository.delete(spec.id)
        journal.finish(operation, result.isSuccess, result.stderr.ifBlank { result.stdout })
        result
    }

    suspend fun update(spec: RuntimeSpec): CommandResult = lockFor(spec.id).withLock {
        val status = runtimes.get(spec.kind).status(spec)
        if (status.actualState is ActualState.Running) {
            return@withLock CommandResult(1, "", "Stop the instance before changing its configuration")
        }
        val errors = Validation.validateRuntimeSpec(spec)
        if (errors.isNotEmpty()) return@withLock CommandResult(1, "", errors.joinToString("; "))
        repository.upsert(spec, repository.get(spec.id)?.entity?.desiredState ?: DesiredState.STOPPED)
        CommandResult(0, "Saved", "")
    }

    suspend fun terminal(spec: RuntimeSpec): TerminalLaunchSpec =
        runtimes.get(spec.kind).terminal(spec)

    suspend fun backendProbe(kind: dev.anct.core.RuntimeKind) =
        runtimes.get(kind).probe()

    suspend fun installRuntime(kind: dev.anct.core.RuntimeKind, onProgress: (String) -> Unit) =
        runtimes.get(kind).installRuntime(onProgress)
}

