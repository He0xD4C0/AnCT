package dev.anct.runtime.nativebackend

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.anct.core.ActualState
import dev.anct.core.CommandResult
import dev.anct.core.NetworkMode
import dev.anct.core.PosixShell
import dev.anct.core.RuntimeBackend
import dev.anct.core.RuntimeCapabilities
import dev.anct.core.RuntimeCommand
import dev.anct.core.RuntimeCreateRequest
import dev.anct.core.RuntimeKind
import dev.anct.core.RuntimeProbe
import dev.anct.core.RuntimeSpec
import dev.anct.core.RuntimeStatus
import dev.anct.core.TerminalLaunchSpec
import dev.anct.core.Validation
import dev.anct.core.capabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

class NativeRuntimeBackend(
    private val context: Context,
    private val executor: RootCommandExecutor = RootCommandExecutor(),
) : RuntimeBackend {
    override val kind: RuntimeKind = RuntimeKind.NATIVE_SYSTEM
    override val capabilities: RuntimeCapabilities = kind.capabilities()

    override suspend fun probe(): RuntimeProbe {
        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            return RuntimeProbe(false, "Native system containers require arm64")
        }
        if (officialAppInstalled()) {
            return RuntimeProbe(false, "Official Droidspaces is installed. Remove it before enabling native containers.")
        }
        if (!executor.hasRoot()) {
            return RuntimeProbe(false, "Root access is not granted")
        }
        val externalModule = executor.sh(
            "test -d ${quote("/data/adb/modules/droidspaces")} || " +
                "(test -d ${quote(MODULE_ROOT)} && test ! -f ${quote("$MODULE_ROOT/.anct")})",
        )
        if (externalModule.isSuccess) {
            return RuntimeProbe(false, "Another Droidspaces root module is installed")
        }
        val binary = executor.sh("test -x ${quote(BINARY)}")
        if (!binary.isSuccess) {
            return RuntimeProbe(false, "Native runtime is not installed")
        }
        val check = executor.run(RuntimeCommand(BINARY, listOf("--json", "check")))
        if (!check.isSuccess) {
            return RuntimeProbe(false, check.stderr.ifBlank { "Kernel capability check failed" })
        }
        val json = runCatching { JSONObject(check.stdout) }.getOrNull()
            ?: return RuntimeProbe(false, "Kernel capability check returned invalid JSON")
        if (json.optInt("requirements_met", 0) != 1) {
            return RuntimeProbe(false, "Kernel is missing required container capabilities")
        }
        return RuntimeProbe(
            available = true,
            message = "Native runtime ready (experimental)",
            version = executor.run(RuntimeCommand(BINARY, listOf("version"))).stdout.trim(),
        )
    }

    override suspend fun installRuntime(onProgress: (String) -> Unit): RuntimeProbe {
        if (officialAppInstalled()) {
            return RuntimeProbe(false, "Remove the official Droidspaces app first")
        }
        if (!executor.hasRoot()) return RuntimeProbe(false, "Root access is not granted")
        val officialModule = executor.sh("test -e ${quote("/data/adb/modules/droidspaces")}")
        if (officialModule.isSuccess) {
            return RuntimeProbe(false, "Another Droidspaces root module is installed")
        }
        val installed = probe()
        if (installed.available) return installed
        onProgress("Installing native runtime")
        val binaryAsset = installAsset("native/droidspaces-aarch64", "$BIN_DIR/droidspaces")
            ?: return RuntimeProbe(false, "Bundled Droidspaces binary is missing")
        if (!binaryAsset.isSuccess) return RuntimeProbe(false, binaryAsset.stderr)
        val busyboxAsset = installAsset("native/busybox-aarch64", "$BIN_DIR/busybox")
            ?: return RuntimeProbe(false, "Bundled BusyBox is missing")
        if (!busyboxAsset.isSuccess) return RuntimeProbe(false, busyboxAsset.stderr)

        val moduleDir = File(context.cacheDir, "anct-module").apply {
            deleteRecursively()
            mkdirs()
        }
        copyAssetTree("native/module", moduleDir)
        val installModule = executor.sh(
            "rm -rf ${quote(MODULE_ROOT)} && mkdir -p ${quote(MODULE_ROOT)} && " +
                "cp -af ${quote(moduleDir.absolutePath)}/. ${quote(MODULE_ROOT)}/ && " +
                "touch ${quote("$MODULE_ROOT/.anct")} && " +
                "chmod 0755 ${quote("$MODULE_ROOT/post-fs-data.sh")} ${quote("$MODULE_ROOT/service.sh")}",
        )
        moduleDir.deleteRecursively()
        if (!installModule.isSuccess) return RuntimeProbe(false, installModule.stderr)
        executor.sh("mkdir -p ${quote(WORKSPACE)} && printf '1\\n' > ${quote("$WORKSPACE/.daemon_mode")}")
        onProgress("Native runtime installed. Reboot once before using system containers.")
        return probe()
    }

    override suspend fun create(
        request: RuntimeCreateRequest,
        onProgress: (String) -> Unit,
    ): CommandResult = withContext(Dispatchers.IO) {
        val errors = Validation.validateRuntimeSpec(request.spec)
        if (errors.isNotEmpty()) return@withContext failure(errors.joinToString("; "))
        val archive = request.localArchivePath
            ?: return@withContext failure("Native containers require a local rootfs archive")
        installRuntime(onProgress)
        onProgress("Inspecting rootfs archive")
        validateArchive(archive).let { if (!it.isSuccess) return@withContext it }

        val spec = request.spec
        val staging = "$WORKSPACE/Containers/.anct-staging-${UUID.randomUUID()}"
        val containerDir = "$WORKSPACE/Containers/${spec.name}"
        val rootfs = "$containerDir/rootfs"
        val config = containerConfig(spec, rootfs)
        val configFile = File(context.cacheDir, "container.config").apply { writeText(config) }
        val envFile = File(context.cacheDir, "container.env").apply {
            writeText(
                spec.environment.entries.joinToString("\n") { (key, value) -> "$key=$value" },
            )
        }
        val setup = executor.sh(
            "set -eu; " +
                "mkdir -p ${quote("$staging/rootfs")}; " +
                "$BUSYBOX tar -xpf ${quote(archive)} -C ${quote("$staging/rootfs")}; " +
                "cp ${quote(configFile.absolutePath)} ${quote("$staging/container.config")}; " +
                "cp ${quote(envFile.absolutePath)} ${quote("$staging/container.env")}; " +
                "chmod 0644 ${quote("$staging/container.config")} ${quote("$staging/container.env")}; " +
                "rm -rf ${quote(containerDir)}; " +
                "mv ${quote(staging)} ${quote(containerDir)}; " +
                "chcon -R u:object_r:vold_data_file:s0 ${quote(rootfs)} 2>/dev/null || true",
        )
        configFile.delete()
        envFile.delete()
        if (!setup.isSuccess) {
            executor.sh("rm -rf ${quote(staging)}")
            failure(setup.stderr.ifBlank { "Could not create native container" })
        } else {
            CommandResult(0, "Created ${spec.name}", "")
        }
    }

    override suspend fun start(
        spec: RuntimeSpec,
        onProgress: (String) -> Unit,
    ): CommandResult {
        val managed = verifyManaged(spec.name)
        if (!managed.isSuccess) return managed
        onProgress("Starting ${spec.name}")
        return executor.run(RuntimeCommand(BINARY, listOf("--config", configPath(spec.name), "start")))
    }

    override suspend fun stop(spec: RuntimeSpec): CommandResult {
        val managed = verifyManaged(spec.name)
        if (!managed.isSuccess) return managed
        return executor.run(RuntimeCommand(BINARY, listOf("--name", spec.name, "stop")))
    }

    override suspend fun status(spec: RuntimeSpec): RuntimeStatus {
        val probe = probe()
        if (!probe.available) return RuntimeStatus(ActualState.Failed(probe.message))
        val result = executor.run(RuntimeCommand(BINARY, listOf("--json", "show")))
        if (!result.isSuccess) {
            return RuntimeStatus(ActualState.Failed(result.stderr.ifBlank { "Droidspaces show failed" }))
        }
        return runCatching {
            val running = JSONObject(result.stdout).getJSONArray("running")
            val entry = (0 until running.length())
                .map(running::getJSONObject)
                .firstOrNull { it.optString("name") == spec.name }
            if (entry == null) {
                RuntimeStatus(ActualState.Stopped)
            } else {
                RuntimeStatus(
                    ActualState.Running(
                        pid = entry.optString("pid").toIntOrNull(),
                        detail = entry.optString("os").ifBlank { null },
                    ),
                )
            }
        }.getOrElse {
            RuntimeStatus(ActualState.Failed("Could not parse Droidspaces status"))
        }
    }

    override suspend fun terminal(spec: RuntimeSpec): TerminalLaunchSpec {
        val inner = PosixShell.command(
            BINARY,
            listOf("--name", spec.name, "enter", spec.guestUser),
        )
        return TerminalLaunchSpec(
            executable = "/system/bin/su",
            arguments = listOf("-c", inner),
            environment = emptyMap(),
            workingDirectory = null,
            requiresRoot = true,
        )
    }

    override suspend fun readLogs(spec: RuntimeSpec, maxBytes: Int): String {
        val managed = verifyManaged(spec.name)
        if (!managed.isSuccess) return managed.stderr
        val path = "$WORKSPACE/Logs/${spec.name}/log"
        return executor.sh(
            "if [ -f ${quote(path)} ]; then tail -c $maxBytes ${quote(path)}; fi",
        ).stdout
    }

    override suspend fun export(spec: RuntimeSpec, destinationPath: String): CommandResult {
        val managed = verifyManaged(spec.name)
        if (!managed.isSuccess) return managed
        val rootfs = "$WORKSPACE/Containers/${spec.name}/rootfs"
        File(destinationPath).parentFile?.mkdirs()
        val uid = android.os.Process.myUid()
        val gid = android.os.Process.myUid()
        return executor.sh(
            "set -o pipefail; rm -f ${quote(destinationPath)}; " +
                "cd ${quote(rootfs)} && $BUSYBOX tar -cf - . | $BUSYBOX gzip > ${quote(destinationPath)} && " +
                "chown $uid:$gid ${quote(destinationPath)}",
        )
    }

    override suspend fun import(
        spec: RuntimeSpec,
        archivePath: String,
        onProgress: (String) -> Unit,
    ): CommandResult = create(
        RuntimeCreateRequest(spec = spec, localArchivePath = archivePath),
        onProgress,
    )

    override suspend fun delete(spec: RuntimeSpec): CommandResult {
        val managed = verifyManaged(spec.name)
        if (!managed.isSuccess) return managed
        executor.run(RuntimeCommand(BINARY, listOf("--name", spec.name, "stop")))
        return executor.sh("rm -rf ${quote("$WORKSPACE/Containers/${spec.name}")}")
    }

    private suspend fun validateArchive(path: String): CommandResult {
        val listing = executor.sh("$BUSYBOX tar -tvf ${quote(path)}")
        if (!listing.isSuccess) return failure("Rootfs archive cannot be read")
        val unsafe = listing.stdout.lineSequence().firstOrNull { name ->
            val mode = name.firstOrNull()
            val archivePath = name.substringAfterLast(' ', missingDelimiterValue = name)
            mode in setOf('b', 'c', 'p') ||
                dev.anct.core.ArchiveSafety.unsafeEntryReason(archivePath) != null
        }
        return if (unsafe == null) CommandResult(0, "", "") else failure("Unsafe archive path: $unsafe")
    }

    private suspend fun verifyManaged(name: String): CommandResult {
        if (Validation.validateInstanceName(name).isNotEmpty()) return failure("Invalid instance name")
        val config = configPath(name)
        val result = executor.sh(
            "test -f ${quote(config)} && grep -q '^anct_managed=1$' ${quote(config)} && " +
                "grep -q '^anct_creator=dev.anct$' ${quote(config)}",
        )
        return if (result.isSuccess) CommandResult(0, "", "") else failure("Container is not managed by AnCT")
    }

    private fun containerConfig(spec: RuntimeSpec, rootfs: String): String = buildString {
        appendLine("name=${spec.name}")
        appendLine("hostname=${spec.name}")
        appendLine("rootfs_path=$rootfs")
        appendLine("custom_init=/sbin/init")
        appendLine("net_mode=${spec.networkMode.toDroidspacesMode()}")
        if (spec.networkMode == NetworkMode.NAT && spec.portMappings.isNotEmpty()) {
            appendLine("port_forwards=${spec.portMappings.joinToString(",") { "${it.hostPort}:${it.containerPort}/${it.protocol}" }}")
        }
        if (spec.mounts.isNotEmpty()) {
            appendLine("bind_mounts=${spec.mounts.joinToString(",") { "${it.source}:${it.destination}${if (it.readOnly) ":ro" else ""}" }}")
        }
        spec.resourceLimits.memoryBytes?.let { appendLine("memory_limit=$it") }
        spec.resourceLimits.cpuQuota?.let { cpus ->
            val quota = (cpus * 100_000).toLong().coerceAtLeast(1)
            appendLine("cpu_quota=$quota")
            appendLine("cpu_period=100000")
        }
        spec.resourceLimits.pids?.let { appendLine("pids_limit=$it") }
        appendLine("env_file=$WORKSPACE/Containers/${spec.name}/container.env")
        appendLine("run_at_boot=${if (spec.autoStart) 1 else 0}")
        appendLine("run_at_boot_priority=${spec.autoStartPriority}")
        appendLine("anct_managed=1")
        appendLine("anct_creator=dev.anct")
        appendLine("anct_schema=1")
    }

    private suspend fun installAsset(assetPath: String, target: String): CommandResult? {
        val file = File(context.cacheDir, File(assetPath).name)
        return runCatching {
            context.assets.open(assetPath).use { input ->
                file.outputStream().use(input::copyTo)
            }
            val result = executor.sh(
                "mkdir -p ${quote(BIN_DIR)} && " +
                    "cp ${quote(file.absolutePath)} ${quote("$target.new")} && " +
                    "chmod 0755 ${quote("$target.new")} && mv -f ${quote("$target.new")} ${quote(target)}",
            )
            result
        }.getOrNull().also { file.delete() }
    }

    private fun copyAssetTree(path: String, destination: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            context.assets.open(path).use { input ->
                destination.outputStream().use(input::copyTo)
            }
            return
        }
        destination.mkdirs()
        children.forEach { child -> copyAssetTree("$path/$child", File(destination, child)) }
    }

    private fun officialAppInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.droidspaces.app", 0)
        true
    }.getOrDefault(false)

    private fun configPath(name: String): String = "$WORKSPACE/Containers/$name/container.config"

    private fun NetworkMode.toDroidspacesMode(): String = when (this) {
        NetworkMode.SHARED -> "nat"
        NetworkMode.NONE -> "none"
        NetworkMode.NAT -> "nat"
        NetworkMode.HOST -> "host"
    }

    private fun quote(value: String): String = PosixShell.quote(value)

    private fun failure(message: String): CommandResult = CommandResult(1, "", message)

    companion object {
        private const val WORKSPACE = "/data/local/Droidspaces"
        private const val BIN_DIR = "$WORKSPACE/bin"
        private const val BINARY = "$BIN_DIR/droidspaces"
        private const val BUSYBOX = "$BIN_DIR/busybox"
        private const val MODULE_ROOT = "/data/adb/modules/anct-droidspaces"
    }
}
