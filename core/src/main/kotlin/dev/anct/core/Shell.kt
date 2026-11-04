package dev.anct.core

object PosixShell {
    fun quote(value: String): String {
        if (value.isEmpty()) return "''"
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun command(executable: String, arguments: List<String>): String =
        buildList {
            add(quote(executable))
            arguments.forEach { add(quote(it)) }
        }.joinToString(" ")
}

