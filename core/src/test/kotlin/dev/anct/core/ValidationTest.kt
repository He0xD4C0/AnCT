package dev.anct.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidationTest {
    @Test
    fun `normalizes names to portable ids`() {
        assertEquals("ubuntu-dev_1", Validation.normalizeInstanceName("  Ubuntu dev_1 "))
        assertEquals("alpine", Validation.normalizeInstanceName("Alpine"))
    }

    @Test
    fun `rejects traversal and reserved names`() {
        assertTrue(Validation.validateInstanceName("..").isNotEmpty())
        assertTrue(Validation.validateInstanceName("cache").isNotEmpty())
        assertTrue(Validation.validateMount(MountSpec("/tmp/a", "../etc")).isNotEmpty())
    }

    @Test
    fun `proot does not accept fake resource guarantees`() {
        val spec = RuntimeSpec(
            id = "1",
            name = "debian",
            kind = RuntimeKind.PROOT_LINUX,
            source = "debian:13",
            resourceLimits = ResourceLimits(memoryBytes = 1024),
            autoStart = true,
        )
        val errors = Validation.validateRuntimeSpec(spec)
        assertEquals(2, errors.size)
    }
}

