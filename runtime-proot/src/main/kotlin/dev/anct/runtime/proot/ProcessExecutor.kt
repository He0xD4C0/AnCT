package dev.anct.runtime.proot

import dev.anct.core.CommandResult
import dev.anct.core.RuntimeCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

class ProcessExecutor {
    suspend fun run(
        command: RuntimeCommand,
        onStdoutLine: (String) -> Unit = {},
        onStderrLine: (String) -> Unit = {},
    ): CommandResult = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(listOf(command.executable) + command.arguments)
            .redirectErrorStream(false)
        command.workingDirectory?.let { builder.directory(File(it)) }
        builder.environment().putAll(command.environment)
        val process = builder.start()
        coroutineScope {
            val out = async(Dispatchers.IO) {
                readLines(process.inputStream.bufferedReader(), onStdoutLine)
            }
            val err = async(Dispatchers.IO) {
                readLines(process.errorStream.bufferedReader(), onStderrLine)
            }
            val exit = process.waitFor()
            CommandResult(exit, out.await(), err.await())
        }
    }

    private fun readLines(
        reader: java.io.BufferedReader,
        onLine: (String) -> Unit,
    ): String = buildString {
        reader.forEachLine { line ->
            onLine(line)
            appendLine(line)
        }
    }
}

