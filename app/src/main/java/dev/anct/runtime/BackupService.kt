package dev.anct.runtime

import android.content.Context
import dev.anct.BuildConfig
import dev.anct.data.InstanceRepository
import dev.anct.core.ActualState
import dev.anct.core.ArchiveSafety
import dev.anct.core.BackupManifest
import dev.anct.core.CommandResult
import dev.anct.core.RuntimeSpec
import dev.anct.core.Validation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupService(
    private val context: Context,
    private val repository: InstanceRepository,
    private val coordinator: InstanceCoordinator,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    suspend fun createArchive(
        spec: RuntimeSpec,
        restartAfterExport: Boolean = true,
        onProgress: (String) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val wasRunning = coordinator.refresh(spec).actualState is ActualState.Running
        if (wasRunning) {
            val stopped = coordinator.stop(spec)
            check(stopped.isSuccess) { stopped.stderr.ifBlank { "Could not stop instance for backup" } }
        }

        val work = File(context.cacheDir, "backup-${UUID.randomUUID()}").apply { mkdirs() }
        val payload = File(work, BackupManifest.PAYLOAD_ENTRY)
        val output = File(work, BackupManifest.FILE_NAME)
        try {
            onProgress("Exporting instance payload")
            val export = coordinator.runtimes.get(spec.kind).export(spec, payload.absolutePath)
            check(export.isSuccess && payload.isFile && payload.length() > 0) {
                "Runtime export failed (code=${export.exitCode}). " +
                    "stdout=${export.stdout.take(2_000)} stderr=${export.stderr.take(2_000)}"
            }
            val digest = digest(payload)
            val manifest = BackupManifest(
                backend = spec.kind,
                appVersion = BuildConfig.VERSION_NAME,
                runtimeVersion = spec.runtimeVersion,
                createdAtEpochMs = System.currentTimeMillis(),
                payloadSize = payload.length(),
                payloadSha256 = digest,
                instance = spec,
            )
            ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
                zip.putNextEntry(ZipEntry(BackupManifest.INSTANCE_ENTRY))
                zip.write(json.encodeToString(spec).toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(BackupManifest.PAYLOAD_ENTRY))
                payload.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(BackupManifest.MANIFEST_ENTRY))
                zip.write(json.encodeToString(manifest).toByteArray())
                zip.closeEntry()
            }
            check(output.length() > 0) { "Backup archive is empty" }
            val result = File(context.cacheDir, "anct-${spec.name}-${UUID.randomUUID()}.zip")
            result.delete()
            check(output.renameTo(result)) { "Could not publish backup archive" }
            onProgress("Backup complete")
            result
        } finally {
            payload.delete()
            work.delete()
            if (wasRunning && restartAfterExport) {
                runCatching { coordinator.start(spec) }
            }
        }
    }

    suspend fun importArchive(
        source: InputStream,
        allowUnverified: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): ImportResult = withContext(Dispatchers.IO) {
        val archive = File(context.cacheDir, "import-${UUID.randomUUID()}.zip")
        try {
            source.use { input -> FileOutputStream(archive).use(input::copyTo) }
            inspectAndImport(archive, allowUnverified, onProgress)
        } finally {
            archive.delete()
        }
    }

    private suspend fun inspectAndImport(
        archive: File,
        allowUnverified: Boolean,
        onProgress: (String) -> Unit,
    ): ImportResult {
        val work = File(context.cacheDir, "import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                check(entries.size == 3) { "Backup contains unexpected entries" }
                check(entries.map { it.name }.toSet() == setOf(
                    BackupManifest.MANIFEST_ENTRY,
                    BackupManifest.INSTANCE_ENTRY,
                    BackupManifest.PAYLOAD_ENTRY,
                )) {
                    "Backup contains unexpected entries"
                }
                check(entries.all { ArchiveSafety.isKnownBackupEntry(it.name) }) {
                    "Backup contains unexpected entries"
                }
                val manifestEntry = zip.getEntry(BackupManifest.MANIFEST_ENTRY)
                    ?: error("Backup manifest is missing")
                check(manifestEntry.size in 0..MAX_MANIFEST_BYTES) { "Backup manifest is too large" }
                val manifest = json.decodeFromString<BackupManifest>(
                    zip.getInputStream(manifestEntry).bufferedReader().readText(),
                )
                check(manifest.format == BackupManifest.FORMAT) { "Unknown backup format" }
                check(manifest.formatVersion == BackupManifest.VERSION) { "Unsupported backup version" }
                val instanceEntry = zip.getEntry(BackupManifest.INSTANCE_ENTRY)
                    ?: error("Backup instance configuration is missing")
                check(instanceEntry.size in 0..MAX_MANIFEST_BYTES) { "Backup configuration is too large" }
                val instanceSpec = json.decodeFromString<RuntimeSpec>(
                    zip.getInputStream(instanceEntry).bufferedReader().readText(),
                )
                check(instanceSpec == manifest.instance) { "Backup instance configuration does not match its manifest" }
                check(manifest.backend == manifest.instance.kind) { "Backup backend does not match its instance" }
                val spec = manifest.instance
                val validation = Validation.validateRuntimeSpec(spec)
                check(validation.isEmpty()) { validation.joinToString("; ") }
                check(repository.get(spec.id) == null && repository.getByName(spec.name) == null) {
                    "An instance with this name already exists"
                }

                val payloadEntry = zip.getEntry(BackupManifest.PAYLOAD_ENTRY)
                    ?: error("Backup payload is missing")
                check(!payloadEntry.isDirectory && payloadEntry.size >= 0) { "Invalid backup payload" }
                check(payloadEntry.size > 0) { "Backup payload is empty" }
                check(payloadEntry.size == manifest.payloadSize) { "Backup payload size mismatch" }
                val payload = File(work, BackupManifest.PAYLOAD_ENTRY)
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(payloadEntry).use { input ->
                    BufferedInputStream(input).use { buffered ->
                        FileOutputStream(payload).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                val read = buffered.read(buffer)
                                if (read < 0) break
                                total += read
                                check(total <= MAX_PAYLOAD_BYTES) { "Backup payload is too large" }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(manifest.payloadSha256, ignoreCase = true) && !allowUnverified) {
                    error("Backup payload checksum mismatch")
                }
                if (!actualHash.equals(manifest.payloadSha256, ignoreCase = true)) {
                    onProgress("Warning: importing an archive whose checksum does not match")
                }

                onProgress("Restoring ${spec.name}")
                val result = coordinator.runtimes.get(spec.kind).import(spec, payload.absolutePath, onProgress)
                check(result.isSuccess) { result.stderr.ifBlank { "Runtime restore failed" } }
                repository.upsert(spec)
                repository.setStatus(spec.id, dev.anct.core.RuntimeStatus(ActualState.Stopped))
                return ImportResult(
                    spec = spec,
                    warnings = if (!actualHash.equals(manifest.payloadSha256, ignoreCase = true)) {
                        listOf("Payload checksum mismatch was explicitly overridden")
                    } else {
                        emptyList()
                    },
                )
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 1024 * 1024L
        private const val MAX_PAYLOAD_BYTES = 32L * 1024 * 1024 * 1024
    }
}

data class ImportResult(
    val spec: RuntimeSpec,
    val warnings: List<String> = emptyList(),
)
