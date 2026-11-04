package dev.anct.core

data class RuntimeCreateRequest(
    val spec: RuntimeSpec,
    val localArchivePath: String? = null,
    val ociReference: String? = null,
    val allowUnverified: Boolean = false,
)

interface RuntimeBackend {
    val kind: RuntimeKind
    val capabilities: RuntimeCapabilities

    suspend fun probe(): RuntimeProbe

    suspend fun installRuntime(onProgress: (String) -> Unit = {}): RuntimeProbe

    suspend fun create(
        request: RuntimeCreateRequest,
        onProgress: (String) -> Unit = {},
    ): CommandResult

    suspend fun start(
        spec: RuntimeSpec,
        onProgress: (String) -> Unit = {},
    ): CommandResult

    suspend fun stop(spec: RuntimeSpec): CommandResult

    suspend fun status(spec: RuntimeSpec): RuntimeStatus

    suspend fun terminal(spec: RuntimeSpec): TerminalLaunchSpec

    suspend fun readLogs(spec: RuntimeSpec, maxBytes: Int = 256 * 1024): String

    suspend fun export(spec: RuntimeSpec, destinationPath: String): CommandResult

    suspend fun import(
        spec: RuntimeSpec,
        archivePath: String,
        onProgress: (String) -> Unit = {},
    ): CommandResult

    suspend fun delete(spec: RuntimeSpec): CommandResult
}

interface AppHostedRuntimeBackend : RuntimeBackend {
    fun foregroundStartCommand(spec: RuntimeSpec): RuntimeCommand
}

fun RuntimeCommand.toTerminalSpec(): TerminalLaunchSpec =
    TerminalLaunchSpec(
        executable = executable,
        arguments = arguments,
        environment = environment,
        workingDirectory = workingDirectory,
        requiresRoot = requiresRoot,
    )
