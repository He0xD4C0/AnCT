package dev.anct.core

import kotlinx.serialization.Serializable

@Serializable
data class CatalogDocument(
    val schemaVersion: Int = 1,
    val generatedAt: String,
    val entries: List<CatalogEntry>,
)

@Serializable
data class CatalogEntry(
    val id: String,
    val name: String,
    val version: String,
    val backend: RuntimeKind,
    val architecture: String = "aarch64",
    val sourceUrl: String? = null,
    val ociReference: String? = null,
    val rootfsUrl: String? = null,
    val sha256: String? = null,
    val signature: String? = null,
    val sizeBytes: Long? = null,
    val minimumRuntimeVersion: String? = null,
    val description: String = "",
)

