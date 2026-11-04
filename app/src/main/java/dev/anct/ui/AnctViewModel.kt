package dev.anct.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.anct.AnctApplication
import dev.anct.anctGraph
import dev.anct.data.StoredInstance
import dev.anct.core.ActualState
import dev.anct.core.CatalogEntry
import dev.anct.core.DesiredState
import dev.anct.core.MountSpec
import dev.anct.core.NetworkMode
import dev.anct.core.ResourceLimits
import dev.anct.core.RestartPolicy
import dev.anct.core.RuntimeCreateRequest
import dev.anct.core.RuntimeKind
import dev.anct.core.RuntimeProbe
import dev.anct.core.RuntimeSpec
import dev.anct.core.TerminalLaunchSpec
import dev.anct.service.TerminalSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import okhttp3.OkHttpClient
import okhttp3.Request

data class UiSnapshot(
    val instances: List<StoredInstance> = emptyList(),
    val probes: Map<RuntimeKind, RuntimeProbe> = emptyMap(),
    val nativeExperimentalEnabled: Boolean = false,
    val allowUnverifiedImports: Boolean = false,
    val catalog: List<CatalogEntry> = emptyList(),
    val busy: Boolean = false,
    val progress: String = "",
    val message: String? = null,
    val logText: String? = null,
    val logTitle: String? = null,
)

class AnctViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.anctGraph
    private val mutableState = MutableStateFlow(UiSnapshot())
    private val _terminal = MutableStateFlow<TerminalLaunchSpec?>(null)

    val state: StateFlow<UiSnapshot> = combine(
        graph.repository.instances,
        graph.settings.experimentalNativeEnabled,
        graph.settings.allowUnverifiedImports,
        mutableState,
    ) { instances, nativeEnabled, allowUnverified, current ->
        current.copy(
            instances = instances,
            nativeExperimentalEnabled = nativeEnabled,
            allowUnverifiedImports = allowUnverified,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiSnapshot())

    val terminal: StateFlow<TerminalLaunchSpec?> = _terminal

    init {
        viewModelScope.launch {
            val catalogResult = runCatching { graph.catalog.loadBundled().entries }
            mutableState.update {
                it.copy(
                    catalog = catalogResult.getOrDefault(emptyList()),
                    message = catalogResult.exceptionOrNull()?.let { error ->
                        "Bundled catalog verification failed: ${error.message}"
                    },
                )
            }
            refreshProbes()
            graph.coordinator.refreshAll()
        }
    }

    fun clearMessage() = mutableState.update { it.copy(message = null) }

    fun clearLogs() = mutableState.update { it.copy(logText = null, logTitle = null) }

    fun refreshProbes() {
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, progress = "Checking runtimes") }
            val probes = RuntimeKind.entries.associateWith { kind ->
                runCatching { graph.coordinator.backendProbe(kind) }
                    .getOrElse { RuntimeProbe(false, it.message ?: "Runtime probe failed") }
            }
            mutableState.update { it.copy(probes = probes, busy = false, progress = "") }
        }
    }

    fun installRuntime(kind: RuntimeKind) {
        viewModelScope.launch {
            updateBusy("Installing ${displayName(kind)}")
            val probe = runCatching {
                graph.coordinator.installRuntime(kind) { updateProgress(it) }
            }.getOrElse { RuntimeProbe(false, it.message ?: "Runtime installation failed") }
            mutableState.update {
                it.copy(
                    busy = false,
                    progress = "",
                    probes = it.probes + (kind to probe),
                    message = probe.message,
                )
            }
        }
    }

    fun setNativeExperimentalEnabled(enabled: Boolean) {
        viewModelScope.launch {
            graph.settings.setExperimentalNativeEnabled(enabled)
            if (enabled) refreshProbes()
        }
    }

    fun setAllowUnverifiedImports(enabled: Boolean) {
        viewModelScope.launch {
            graph.settings.setAllowUnverifiedImports(enabled)
        }
    }

    fun addCatalog(url: String) {
        viewModelScope.launch {
            val result = runCatching { graph.catalog.loadRemote(url.trim()) }
            mutableState.update { current ->
                if (result.isSuccess) {
                    val entries = (current.catalog + result.getOrThrow().entries)
                        .distinctBy { "${it.backend}:${it.id}:${it.version}" }
                    current.copy(catalog = entries, message = "Unsigned catalog added; review each source before use")
                } else {
                    current.copy(message = result.exceptionOrNull()?.message ?: "Could not load catalog")
                }
            }
        }
    }

    fun create(
        name: String,
        kind: RuntimeKind,
        source: String,
        localUri: Uri?,
        guestUser: String,
        startCommand: String,
        workingDirectory: String,
        restartPolicy: RestartPolicy,
        autoStart: Boolean,
        sourceSha256: String? = null,
    ) {
        viewModelScope.launch {
            val normalized = dev.anct.core.Validation.normalizeInstanceName(name)
            val localArchive = if (localUri != null) {
                copyUriToCache(localUri)
            } else if (source.startsWith("https://") && kind == RuntimeKind.NATIVE_SYSTEM) {
                runCatching { downloadSource(source, sourceSha256) }.getOrElse {
                    mutableState.update { state ->
                        state.copy(busy = false, progress = "", message = it.message ?: "Image download failed")
                    }
                    return@launch
                }
            } else {
                null
            }
            val spec = RuntimeSpec(
                id = UUID.randomUUID().toString(),
                name = normalized,
                kind = kind,
                source = source.ifBlank { localUri?.lastPathSegment ?: "" },
                guestUser = guestUser.ifBlank { "root" },
                startCommand = startCommand,
                workingDirectory = workingDirectory.ifBlank { "/root" },
                restartPolicy = restartPolicy,
                autoStart = autoStart,
                networkMode = if (kind == RuntimeKind.NATIVE_SYSTEM) NetworkMode.NAT else NetworkMode.SHARED,
                runtimeVersion = mutableState.value.probes[kind]?.version.orEmpty(),
            )
            updateBusy("Creating ${spec.name}")
            val result = graph.coordinator.create(
                RuntimeCreateRequest(
                    spec = spec,
                    localArchivePath = localArchive?.absolutePath,
                    ociReference = source.takeIf { localArchive == null },
                ),
                ::updateProgress,
            )
            localArchive?.delete()
            mutableState.update {
                it.copy(
                    busy = false,
                    progress = "",
                    message = if (result.isSuccess) "Created ${spec.name}" else result.stderr.ifBlank { "Create failed" },
                )
            }
        }
    }

    fun start(instance: StoredInstance) {
        val spec = graph.repository.decode(instance.entity)
        viewModelScope.launch {
            updateBusy("Starting ${spec.name}")
            val result = graph.coordinator.start(spec)
            mutableState.update {
                it.copy(
                    busy = false,
                    progress = "",
                    message = if (result.isSuccess) null else result.stderr.ifBlank { "Start failed" },
                )
            }
        }
    }

    fun stop(instance: StoredInstance) {
        val spec = graph.repository.decode(instance.entity)
        viewModelScope.launch {
            updateBusy("Stopping ${spec.name}")
            val result = graph.coordinator.stop(spec)
            mutableState.update {
                it.copy(
                    busy = false,
                    progress = "",
                    message = if (result.isSuccess) null else result.stderr.ifBlank { "Stop failed" },
                )
            }
        }
    }

    fun refresh(instance: StoredInstance) {
        viewModelScope.launch {
            val spec = graph.repository.decode(instance.entity)
            graph.coordinator.refresh(spec)
        }
    }

    fun loadLogs(instance: StoredInstance) {
        viewModelScope.launch {
            val spec = graph.repository.decode(instance.entity)
            val text = runCatching { graph.runtimes.get(spec.kind).readLogs(spec) }
                .getOrElse { "Could not read logs: ${it.message}" }
            mutableState.update {
                it.copy(logTitle = spec.name, logText = text.ifBlank { "No log entries yet." })
            }
        }
    }

    fun save(spec: RuntimeSpec) {
        viewModelScope.launch {
            val result = graph.coordinator.update(spec)
            mutableState.update {
                it.copy(message = if (result.isSuccess) "Saved" else result.stderr.ifBlank { "Save failed" })
            }
        }
    }

    fun delete(instance: StoredInstance) {
        viewModelScope.launch {
            updateBusy("Deleting ${instance.entity.name}")
            val spec = graph.repository.decode(instance.entity)
            val result = graph.coordinator.delete(spec)
            mutableState.update {
                it.copy(
                    busy = false,
                    progress = "",
                    message = if (result.isSuccess) "Deleted ${spec.name}" else result.stderr.ifBlank { "Delete failed" },
                )
            }
        }
    }

    fun export(
        instance: StoredInstance,
        uri: Uri,
        restartAfterExport: Boolean = true,
        onFinished: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            updateBusy("Stopping instance for backup")
            val spec = graph.repository.decode(instance.entity)
            val archive = runCatching {
                graph.backups.createArchive(spec, restartAfterExport, ::updateProgress)
            }.getOrElse {
                mutableState.update { state ->
                    state.copy(busy = false, progress = "", message = it.message ?: "Backup failed")
                }
                onFinished(false)
                return@launch
            }
            val copied = runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { output ->
                        archive.inputStream().use { it.copyTo(output) }
                    } ?: error("Could not open backup destination")
                }
            }
            archive.parentFile?.deleteRecursively()
            mutableState.update {
                it.copy(
                    busy = false,
                    progress = "",
                    message = copied.exceptionOrNull()?.message ?: "Backup exported",
                )
            }
            onFinished(copied.isSuccess)
        }
    }

    fun exportAndDelete(instance: StoredInstance, uri: Uri) {
        export(instance, uri, restartAfterExport = false) { success -> if (success) delete(instance) }
    }

    fun import(uri: Uri, allowUnverified: Boolean = state.value.allowUnverifiedImports) {
        viewModelScope.launch {
            updateBusy("Importing backup")
            val result = runCatching {
                val input = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: error("Could not open backup")
                graph.backups.importArchive(input, allowUnverified, ::updateProgress)
            }
            mutableState.update {
                it.copy(
                    busy = false,
                    progress = "",
                    message = result.exceptionOrNull()?.message
                        ?: "Imported ${result.getOrThrow().spec.name}",
                )
            }
        }
    }

    fun openTerminal(context: Context, id: String) {
        viewModelScope.launch {
            val stored = graph.repository.get(id) ?: return@launch
            val spec = graph.repository.decode(stored.entity)
            val launch = runCatching { graph.coordinator.terminal(spec) }.getOrElse {
                mutableState.update { state -> state.copy(message = it.message ?: "Could not open terminal") }
                return@launch
            }
            _terminal.value = launch
            TerminalSessionService.open(context, id, launch)
        }
    }

    fun closeTerminal(context: Context, id: String) {
        TerminalSessionService.close(context, id)
        _terminal.value = null
    }

    fun decode(instance: StoredInstance): RuntimeSpec = graph.repository.decode(instance.entity)

    private suspend fun copyUriToCache(uri: Uri): File = withContext(Dispatchers.IO) {
        val name = getApplication<Application>().contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "rootfs.tar"
        val suffix = when {
            name.endsWith(".tar.xz") -> ".tar.xz"
            name.endsWith(".tar.gz") -> ".tar.gz"
            name.endsWith(".oci.tar") -> ".oci.tar"
            name.endsWith(".zip") -> ".zip"
            else -> ".tar"
        }
        val target = File(getApplication<Application>().cacheDir, "import-${UUID.randomUUID()}$suffix")
        getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not read selected image" }
            target.outputStream().use(input::copyTo)
        }
        target
    }

    private suspend fun downloadSource(url: String, expectedSha256: String?): File = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Image URLs must use HTTPS" }
        val target = File(getApplication<Application>().cacheDir, "image-${UUID.randomUUID()}.tar.gz")
        val response = OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
        response.use {
            check(it.isSuccessful) { "Image download HTTP ${it.code}" }
            val body = it.body
            check(body.contentLength() <= MAX_IMAGE_BYTES || body.contentLength() < 0) { "Image is too large" }
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= MAX_IMAGE_BYTES) { "Image is too large" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    expectedSha256?.let { expected ->
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        check(actual.equals(expected, ignoreCase = true)) { "Image checksum mismatch" }
                    }
                }
            }
        }
        target
    }

    private fun updateBusy(text: String) = mutableState.update {
        it.copy(busy = true, progress = text, message = null)
    }

    private fun updateProgress(text: String) = mutableState.update {
        it.copy(progress = text.take(240))
    }

    private fun displayName(kind: RuntimeKind): String = when (kind) {
        RuntimeKind.PROOT_LINUX -> "Linux environment runtime"
        RuntimeKind.NATIVE_SYSTEM -> "native system runtime"
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024 * 1024
    }
}
