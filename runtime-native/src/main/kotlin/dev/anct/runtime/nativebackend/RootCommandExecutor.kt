package dev.anct.runtime.nativebackend

import com.topjohnwu.superuser.Shell
import dev.anct.core.CommandResult
import dev.anct.core.PosixShell
import dev.anct.core.RuntimeCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootCommandExecutor {
    suspend fun run(command: RuntimeCommand): CommandResult = withContext(Dispatchers.IO) {
        val line = PosixShell.command(command.executable, command.arguments)
        val result = Shell.cmd(line).exec()
        CommandResult(
            exitCode = result.code,
            stdout = result.out.joinToString("\n"),
            stderr = result.err.joinToString("\n"),
        )
    }

    suspend fun sh(script: String): CommandResult = withContext(Dispatchers.IO) {
        val result = Shell.cmd(script).exec()
        CommandResult(
            exitCode = result.code,
            stdout = result.out.joinToString("\n"),
            stderr = result.err.joinToString("\n"),
        )
    }

    fun hasRoot(): Boolean = runCatching { Shell.isAppGrantedRoot() == true }.getOrDefault(false)
}

