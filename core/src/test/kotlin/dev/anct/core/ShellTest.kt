package dev.anct.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ShellTest {
    @Test
    fun `quotes single quotes safely`() {
        assertEquals("'a'\\''b'", PosixShell.quote("a'b"))
        assertEquals(
            "'/bin/echo' 'hello world' 'a'\\''b'",
            PosixShell.command("/bin/echo", listOf("hello world", "a'b")),
        )
    }
}

