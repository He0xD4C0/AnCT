package dev.anct.core

import kotlinx.serialization.Serializable

@Serializable
data class BackupManifest(
    val format: String = FORMAT,
    val formatVersion: Int = VERSION,
    val backend: RuntimeKind,
    val appVersion: String,
    val runtimeVersion: String,
    val createdAtEpochMs: Long,
    val payloadName: String = "payload.tar.gz",
    val payloadSize: Long,
    val payloadSha256: String,
    val instance: RuntimeSpec,
) {
    companion object {
        const val FORMAT = "anct-backup"
        const val VERSION = 1
        const val FILE_NAME = "anct-backup-v1.zip"
        const val MANIFEST_ENTRY = "manifest.json"
        const val INSTANCE_ENTRY = "instance.json"
        const val PAYLOAD_ENTRY = "payload.tar.gz"
    }
}

data class BackupValidation(
    val manifest: BackupManifest?,
    val errors: List<String>,
    val warnings: List<String>,
) {
    val canImport: Boolean
        get() = manifest != null && errors.isEmpty()
}

