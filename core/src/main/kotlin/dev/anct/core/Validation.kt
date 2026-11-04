package dev.anct.core

import java.text.Normalizer
import java.util.Locale

object Validation {
    private val instanceName = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
    private val envKey = Regex("^[A-Za-z_][A-Za-z0-9_]{0,127}$")
    private val reservedNames = setOf(
        "containers",
        "logs",
        "cache",
        "shm",
        "sessions",
        "locks",
        "bin",
        ".",
        "..",
    )

    fun normalizeInstanceName(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_.-]+"), "-")
            .trim('-', '.', '_')
            .take(64)

    fun validateInstanceName(value: String): List<String> = buildList {
        if (value.isBlank()) add("Instance name is required")
        if (!instanceName.matches(value)) {
            add("Name must start with a letter or digit and contain only A-Z, a-z, 0-9, _, . or -")
        }
        if (value.lowercase(Locale.ROOT) in reservedNames) add("Name is reserved")
    }

    fun validateAbsoluteGuestPath(value: String, field: String): List<String> = buildList {
        if (!value.startsWith('/')) add("$field must be an absolute Linux path")
        if (value.split('/').any { it == ".." }) add("$field must not contain ..")
        if (value.contains('\u0000')) add("$field contains an invalid character")
    }

    fun validateMount(mount: MountSpec): List<String> = buildList {
        if (!mount.source.startsWith('/')) add("Mount source must be absolute")
        if (mount.source.split('/').any { it == ".." }) add("Mount source must not contain ..")
        if (mount.source.any { it == ':' || it == ',' }) {
            add("Mount source must not contain ':' or ','")
        }
        if (mount.destination.any { it == ':' || it == ',' }) {
            add("Mount destination must not contain ':' or ','")
        }
        addAll(validateAbsoluteGuestPath(mount.destination, "Mount destination"))
    }

    fun validateEnvironment(environment: Map<String, String>): List<String> = buildList {
        environment.keys.forEach { key ->
            if (!envKey.matches(key)) add("Invalid environment key: $key")
        }
        environment.values.forEach { value ->
            if (value.contains('\u0000')) add("Environment values must not contain NUL")
            if ('\n' in value || '\r' in value) add("Environment values must be single-line")
        }
    }

    fun validatePorts(
        kind: RuntimeKind,
        ports: List<PortMapping>,
    ): List<String> = buildList {
        if (ports.isNotEmpty() && kind == RuntimeKind.PROOT_LINUX) {
            add("PRoot Linux environments use Android's shared network and do not configure port forwards")
        }
        ports.forEach { port ->
            if (port.hostPort !in 1..65535) add("Host port must be between 1 and 65535")
            if (port.containerPort !in 1..65535) add("Container port must be between 1 and 65535")
            if (port.protocol !in setOf("tcp", "udp")) add("Port protocol must be tcp or udp")
        }
    }

    fun validateRuntimeSpec(spec: RuntimeSpec): List<String> = buildList {
        addAll(validateInstanceName(spec.name))
        addAll(validateAbsoluteGuestPath(spec.workingDirectory, "Working directory"))
        if (spec.startCommand.contains('\u0000')) add("Start command must not contain NUL")
        spec.mounts.forEach { addAll(validateMount(it)) }
        addAll(validateEnvironment(spec.environment))
        addAll(validatePorts(spec.kind, spec.portMappings))
        if (spec.kind == RuntimeKind.PROOT_LINUX && spec.autoStart) {
            add("PRoot Linux environments cannot auto-start at boot")
        }
        if (spec.kind == RuntimeKind.PROOT_LINUX &&
            (spec.resourceLimits.memoryBytes != null ||
                spec.resourceLimits.cpuQuota != null ||
                spec.resourceLimits.pids != null)
        ) {
            add("PRoot Linux environments cannot enforce hard CPU, memory or PID limits")
        }
        if (spec.kind == RuntimeKind.PROOT_LINUX && spec.networkMode != NetworkMode.SHARED) {
            add("PRoot Linux environments use Android's shared network")
        }
    }
}

