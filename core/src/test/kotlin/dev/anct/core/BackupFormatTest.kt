package dev.anct.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BackupFormatTest {
    @Test
    fun `manifest round trips with backend and instance config`() {
        val spec = RuntimeSpec(
            id = "abc",
            name = "debian",
            kind = RuntimeKind.NATIVE_SYSTEM,
            source = "debian.tar.gz",
            autoStart = true,
        )
        val manifest = BackupManifest(
            backend = spec.kind,
            appVersion = "0.1.0",
            runtimeVersion = "7.1",
            createdAtEpochMs = 1,
            payloadSize = 42,
            payloadSha256 = "a".repeat(64),
            instance = spec,
        )
        val json = Json { encodeDefaults = true }
        assertEquals(manifest, json.decodeFromString<BackupManifest>(json.encodeToString(manifest)))
    }

    @Test
    fun `archive safety rejects traversal but allows known entries`() {
        assertNotNull(ArchiveSafety.unsafeEntryReason("../escape"))
        assertNotNull(ArchiveSafety.unsafeEntryReason("/absolute"))
        assertNull(ArchiveSafety.unsafeEntryReason("payload.tar.gz"))
        assertEquals(true, ArchiveSafety.isKnownBackupEntry("manifest.json"))
        assertEquals(false, ArchiveSafety.isKnownBackupEntry("extra"))
    }
}
