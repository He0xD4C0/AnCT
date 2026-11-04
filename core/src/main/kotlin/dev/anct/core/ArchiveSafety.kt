package dev.anct.core

object ArchiveSafety {
    fun unsafeEntryReason(name: String): String? {
        if (name.isBlank()) return "Empty archive entry"
        if (name.startsWith('/') || name.startsWith("\\\\")) return "Absolute archive entry"
        if (name.contains('\u0000')) return "Archive entry contains NUL"
        if (name.split('/', '\\').any { it == ".." }) return "Archive entry contains path traversal"
        return null
    }

    fun isKnownBackupEntry(name: String): Boolean = name in setOf(
        BackupManifest.MANIFEST_ENTRY,
        BackupManifest.INSTANCE_ENTRY,
        BackupManifest.PAYLOAD_ENTRY,
    )
}

