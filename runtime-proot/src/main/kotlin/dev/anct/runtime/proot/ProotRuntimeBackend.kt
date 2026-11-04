package dev.anct.runtime.proot

import android.content.Context
import dev.anct.core.ActualState
import dev.anct.core.AppHostedRuntimeBackend
import dev.anct.core.CommandResult
import dev.anct.core.RuntimeBackend
import dev.anct.core.RuntimeCapabilities
import dev.anct.core.RuntimeCommand
import dev.anct.core.RuntimeCreateRequest
import dev.anct.core.RuntimeKind
import dev.anct.core.RuntimeProbe
import dev.anct.core.RuntimeSpec
import dev.anct.core.RuntimeStatus
import dev.anct.core.TerminalLaunchSpec
import dev.anct.core.capabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ProotRuntimeBackend(
    context: Context,
    private val installer: ProotBootstrapInstaller = ProotBootstrapInstaller(context),
    private val executor: ProcessExecutor = ProcessExecutor(),
) : AppHostedRuntimeBackend {
    private val appContext = context.applicationContext
    private val runtimeData = File(appContext.filesDir, "proot-data")
    private val instanceState = File(appContext.filesDir, "instances/proot")
    private val logs = File(appContext.filesDir, "logs/proot")

    override val kind: RuntimeKind = RuntimeKind.PROOT_LINUX
    override val capabilities: RuntimeCapabilities = kind.capabilities()

    override suspend fun probe(): RuntimeProbe {
        if (!installer.isInstalled()) {
            return RuntimeProbe(false, "Bundled PRoot runtime is not installed")
        }
        val python = executor.run(baseCommand(listOf("--version")))
        if (!python.isSuccess) {
            return RuntimeProbe(false, python.stderr.ifBlank { "Python runtime failed" })
        }
        val version = python.stdout.lineSequence().firstOrNull().orEmpty()
        return RuntimeProbe(true, "PRoot-Distro ready", version)
    }

    override suspend fun installRuntime(onProgress: (String) -> Unit): RuntimeProbe {
        installer.install(onProgress)
        instanceState.mkdirs()
        logs.mkdirs()
        return probe()
    }

    override suspend fun create(
        request: RuntimeCreateRequest,
        onProgress: (String) -> Unit,
    ): CommandResult {
        installRuntime(onProgress)
        val spec = request.spec
        val source = request.localArchivePath ?: request.ociReference ?: request.spec.source
        require(source.isNotBlank()) { "An OCI reference or local rootfs archive is required" }
        onProgress("Creating ${spec.name}")
        val result = runDistro(
            listOf(
                "install",
                source,
                "--name",
                spec.name,
                "--quiet",
            ),
            onProgress,
        )
        if (result.isSuccess) {
            File(instanceState, spec.name).mkdirs()
        }
        return result
    }

    override suspend fun start(
        spec: RuntimeSpec,
        onProgress: (String) -> Unit,
    ): CommandResult {
        installRuntime(onProgress)
        if (spec.startCommand.isBlank()) {
            return CommandResult(0, "", "")
        }
        val command = "-- "
        val arguments = mutableListOf("login", spec.name, "--user", spec.guestUser)
        spec.mounts.forEach { mount ->
            arguments += "--bind"
            arguments += "${mount.source}:${mount.destination}${if (mount.readOnly) ":ro" else ""}"
        }
        arguments += command
        arguments += "/bin/sh"
        arguments += "-lc"
        arguments += spec.startCommand
        return runDistro(arguments, onProgress)
    }

    override fun foregroundStartCommand(spec: RuntimeSpec): RuntimeCommand {
        val arguments = mutableListOf("login", spec.name, "--user", spec.guestUser)
        spec.mounts.forEach { mount ->
            arguments += "--bind"
            arguments += "${mount.source}:${mount.destination}${if (mount.readOnly) ":ro" else ""}"
        }
        arguments += "--"
        if (spec.startCommand.isBlank()) {
            arguments += "/bin/sh"
            arguments += "-l"
        } else {
            arguments += "/bin/sh"
            arguments += "-lc"
            arguments += spec.startCommand
        }
        return distroCommand(arguments)
    }

    override suspend fun stop(spec: RuntimeSpec): CommandResult =
        runDistro(listOf("kill", spec.name))

    override suspend fun status(spec: RuntimeSpec): RuntimeStatus {
        val probe = probe()
        if (!probe.available) return RuntimeStatus(ActualState.Failed(probe.message))
        val sessions = runDistro(listOf("ps"))
        if (!sessions.isSuccess) {
            return RuntimeStatus(ActualState.Failed(sessions.stderr.ifBlank { "Could not inspect sessions" }))
        }
        val match = sessions.stdout.lineSequence()
            .drop(1)
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { columns -> columns.size >= 2 && columns[1] == spec.name }
        return if (match == null) {
            RuntimeStatus(ActualState.Stopped)
        } else {
            RuntimeStatus(
                ActualState.Running(
                    pid = match.firstOrNull()?.toIntOrNull(),
                    detail = match.drop(2).joinToString(" "),
                ),
            )
        }
    }

    override suspend fun terminal(spec: RuntimeSpec): TerminalLaunchSpec {
        val arguments = mutableListOf("login", spec.name, "--user", spec.guestUser)
        spec.mounts.forEach { mount ->
            arguments += "--bind"
            arguments += "${mount.source}:${mount.destination}${if (mount.readOnly) ":ro" else ""}"
        }
        return distroCommand(arguments).toTerminalLaunch(retainTerminal = true)
    }

    override suspend fun readLogs(spec: RuntimeSpec, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val file = File(appContext.filesDir, "logs/${spec.name}.log")
        if (!file.isFile) return@withContext ""
        val start = (file.length() - maxBytes).coerceAtLeast(0)
        file.inputStream().use { input ->
            input.skip(start)
            input.readBytes().decodeToString()
        }
    }

    override suspend fun export(spec: RuntimeSpec, destinationPath: String): CommandResult {
        File(destinationPath).parentFile?.mkdirs()
        return runDistro(listOf("backup", spec.name, "--output", destinationPath, "--compress", "gzip"))
    }

    override suspend fun import(
        spec: RuntimeSpec,
        archivePath: String,
        onProgress: (String) -> Unit,
    ): CommandResult {
        onProgress("Restoring ${spec.name}")
        return runDistro(listOf("restore", archivePath), onProgress)
    }

    override suspend fun delete(spec: RuntimeSpec): CommandResult {
        stop(spec)
        val result = runDistro(listOf("remove", spec.name))
        if (result.isSuccess) {
            File(instanceState, spec.name).deleteRecursively()
            File(logs, "${spec.name}.log").delete()
        }
        return result
    }

    private suspend fun runDistro(
        arguments: List<String>,
        onProgress: (String) -> Unit = {},
    ): CommandResult = executor.run(
        distroCommand(arguments),
        onStdoutLine = onProgress,
        onStderrLine = onProgress,
    )

    private fun baseCommand(arguments: List<String>): RuntimeCommand =
        RuntimeCommand(
            executable = File(installer.prefix, "bin/python3").absolutePath,
            arguments = arguments,
            environment = environment(),
            workingDirectory = appContext.filesDir.absolutePath,
        )

    private fun distroCommand(arguments: List<String>): RuntimeCommand =
        RuntimeCommand(
            executable = File(installer.prefix, "bin/python3").absolutePath,
            arguments = listOf(
                "-c",
                "from proot_distro.cli import main; main()",
            ) + arguments,
            environment = environment(),
            workingDirectory = appContext.filesDir.absolutePath,
        )

    private fun environment(): Map<String, String> {
        runtimeData.mkdirs()
        return mapOf(
            "HOME" to File(appContext.filesDir, "home").absolutePath,
            "PATH" to "${installer.prefix}/bin:/system/bin:/system/xbin",
            "TERM" to "xterm-256color",
            "TMPDIR" to appContext.cacheDir.absolutePath,
            "PROOT_TMP_DIR" to File(appContext.cacheDir, "proot-tmp").apply { mkdirs() }.absolutePath,
            "PD_PROOT_BIN" to File(installer.prefix, "bin/proot").absolutePath,
            "PYTHONPATH" to installer.distroRuntime.absolutePath,
            "TERMUX_APP__PACKAGE_NAME" to appContext.packageName,
            "TERMUX__HOME" to File(appContext.filesDir, "home").absolutePath,
            "TERMUX__PREFIX" to installer.prefix.absolutePath,
            "TERMUX_VERSION" to "anct",
            "XDG_DATA_HOME" to File(runtimeData, "data").apply { mkdirs() }.absolutePath,
            "XDG_CACHE_HOME" to File(runtimeData, "cache").apply { mkdirs() }.absolutePath,
        )
    }
}

private fun RuntimeCommand.toTerminalLaunch(retainTerminal: Boolean): TerminalLaunchSpec =
    TerminalLaunchSpec(
        executable = executable,
        arguments = arguments,
        environment = environment,
        workingDirectory = workingDirectory,
        requiresRoot = requiresRoot,
    )
