package dev.anct.core

import kotlinx.serialization.Serializable

@Serializable
enum class RuntimeKind {
    PROOT_LINUX,
    NATIVE_SYSTEM,
}

@Serializable
enum class DesiredState {
    STOPPED,
    RUNNING,
}

@Serializable
sealed interface ActualState {
    @Serializable
    data object Unknown : ActualState

    @Serializable
    data object Stopped : ActualState

    @Serializable
    data object Starting : ActualState

    @Serializable
    data class Running(
        val pid: Int? = null,
        val detail: String? = null,
    ) : ActualState

    @Serializable
    data object Stopping : ActualState

    @Serializable
    data class Failed(
        val message: String,
    ) : ActualState
}

@Serializable
enum class RestartPolicy {
    NEVER,
    ON_FAILURE_LIMITED,
}

@Serializable
enum class NetworkMode {
    SHARED,
    NONE,
    NAT,
    HOST,
}

@Serializable
data class MountSpec(
    val source: String,
    val destination: String,
    val readOnly: Boolean = false,
)

@Serializable
data class PortMapping(
    val hostPort: Int,
    val containerPort: Int,
    val protocol: String = "tcp",
)

@Serializable
data class ResourceLimits(
    val memoryBytes: Long? = null,
    val cpuQuota: Double? = null,
    val pids: Int? = null,
)

@Serializable
data class RuntimeCapabilities(
    val requiresRoot: Boolean,
    val isolatedNetwork: Boolean,
    val systemInit: Boolean,
    val hardResourceLimits: Boolean,
    val bootAutostart: Boolean,
    val survivesAppProcessDeath: Boolean,
    val experimental: Boolean = false,
)

@Serializable
data class RuntimeSpec(
    val id: String,
    val name: String,
    val kind: RuntimeKind,
    val source: String,
    val guestUser: String = "root",
    val startCommand: String = "",
    val workingDirectory: String = "/root",
    val environment: Map<String, String> = emptyMap(),
    val mounts: List<MountSpec> = emptyList(),
    val resourceLimits: ResourceLimits = ResourceLimits(),
    val restartPolicy: RestartPolicy = RestartPolicy.NEVER,
    val autoStart: Boolean = false,
    val autoStartPriority: Int = 0,
    val networkMode: NetworkMode = NetworkMode.SHARED,
    val portMappings: List<PortMapping> = emptyList(),
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val runtimeVersion: String = "",
)

@Serializable
data class RuntimeStatus(
    val actualState: ActualState,
    val observedAtEpochMs: Long = System.currentTimeMillis(),
    val detail: String? = null,
)

@Serializable
data class TerminalLaunchSpec(
    val executable: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: String?,
    val requiresRoot: Boolean,
)

@Serializable
data class RuntimeCommand(
    val executable: String,
    val arguments: List<String>,
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
    val requiresRoot: Boolean = false,
)

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean
        get() = exitCode == 0
}

data class RuntimeProbe(
    val available: Boolean,
    val message: String,
    val version: String? = null,
    val missingCapabilities: List<String> = emptyList(),
)

fun RuntimeKind.capabilities(): RuntimeCapabilities = when (this) {
    RuntimeKind.PROOT_LINUX -> RuntimeCapabilities(
        requiresRoot = false,
        isolatedNetwork = false,
        systemInit = false,
        hardResourceLimits = false,
        bootAutostart = false,
        survivesAppProcessDeath = false,
    )

    RuntimeKind.NATIVE_SYSTEM -> RuntimeCapabilities(
        requiresRoot = true,
        isolatedNetwork = true,
        systemInit = true,
        hardResourceLimits = true,
        bootAutostart = true,
        survivesAppProcessDeath = true,
        experimental = true,
    )
}

