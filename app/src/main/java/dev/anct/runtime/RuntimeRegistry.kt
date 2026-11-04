package dev.anct.runtime

import dev.anct.core.RuntimeBackend
import dev.anct.core.RuntimeKind

class RuntimeRegistry(backends: List<RuntimeBackend>) {
    private val byKind = backends.associateBy(RuntimeBackend::kind)

    fun get(kind: RuntimeKind): RuntimeBackend =
        byKind[kind] ?: error("No runtime backend registered for $kind")

    val all: List<RuntimeBackend> = backends
}

