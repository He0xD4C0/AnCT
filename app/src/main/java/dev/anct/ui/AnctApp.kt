package dev.anct.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.anct.data.StoredInstance
import dev.anct.core.ActualState
import dev.anct.core.CatalogEntry
import dev.anct.core.DesiredState
import dev.anct.core.MountSpec
import dev.anct.core.NetworkMode
import dev.anct.core.PortMapping
import dev.anct.core.ResourceLimits
import dev.anct.core.RestartPolicy
import dev.anct.core.RuntimeProbe
import dev.anct.core.RuntimeSpec
import dev.anct.core.RuntimeKind
import dev.anct.service.TerminalSessionService
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private sealed interface Route {
    data object Home : Route
    data object Settings : Route
    data object Create : Route
    data class Details(val id: String) : Route
    data class Terminal(val id: String) : Route
}

private val RouteSaver = Saver<Route, String>(
    save = {
        when (it) {
            Route.Home -> "home"
            Route.Settings -> "settings"
            Route.Create -> "create"
            is Route.Details -> "details:${it.id}"
            is Route.Terminal -> "terminal:${it.id}"
        }
    },
    restore = {
        when {
            it == "home" -> Route.Home
            it == "settings" -> Route.Settings
            it == "create" -> Route.Create
            it.startsWith("details:") -> Route.Details(it.removePrefix("details:"))
            it.startsWith("terminal:") -> Route.Terminal(it.removePrefix("terminal:"))
            else -> Route.Home
        }
    },
)

@Composable
fun AnctApp(viewModel: AnctViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var route: Route by rememberSaveable(stateSaver = RouteSaver) { mutableStateOf(Route.Home) }
    var pendingExportForDelete by remember { mutableStateOf<StoredInstance?>(null) }
    val snackbar = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val instance = pendingExportForDelete
        pendingExportForDelete = null
        if (uri != null && instance != null) viewModel.exportAndDelete(instance, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    BackHandler(enabled = route != Route.Home) {
        route = when (route) {
            is Route.Terminal -> Route.Home
            else -> Route.Home
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (route == Route.Home || route == Route.Settings) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == Route.Home,
                        onClick = { route = Route.Home },
                        icon = { Icon(Icons.Outlined.Terminal, null) },
                        label = { Text(t("实例", "Instances")) },
                    )
                    NavigationBarItem(
                        selected = route == Route.Settings,
                        onClick = { route = Route.Settings },
                        icon = { Icon(Icons.Outlined.Settings, null) },
                        label = { Text(t("设置", "Settings")) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            when (val current = route) {
                Route.Home -> InstanceListScreen(
                    state = state,
                    onRefresh = viewModel::refreshProbes,
                    onImport = { importLauncher.launch(arrayOf("application/zip")) },
                    onCreate = { route = Route.Create },
                    onOpen = { route = Route.Details(it.entity.id) },
                    onStart = viewModel::start,
                    onStop = viewModel::stop,
                    onTerminal = {
                        viewModel.openTerminal(context, it.entity.id)
                        route = Route.Terminal(it.entity.id)
                    },
                )

                Route.Settings -> SettingsScreen(
                    state = state,
                    onCheck = viewModel::refreshProbes,
                    onInstall = viewModel::installRuntime,
                    onNativeExperimental = viewModel::setNativeExperimentalEnabled,
                    onAllowUnverifiedImports = viewModel::setAllowUnverifiedImports,
                    onAddCatalog = viewModel::addCatalog,
                    onUninstall = {
                        context.startActivity(
                            Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}")),
                        )
                    },
                )

                Route.Create -> CreateScreen(
                    state = state,
                    onBack = { route = Route.Home },
                    onCreate = { name, kind, source, uri, user, command, workdir, restart, autoStart, sha256 ->
                        viewModel.create(name, kind, source, uri, user, command, workdir, restart, autoStart, sha256)
                        route = Route.Home
                    },
                )

                is Route.Details -> {
                    val instance = state.instances.firstOrNull { it.entity.id == current.id }
                    if (instance == null) {
                        MissingInstance(onBack = { route = Route.Home })
                    } else {
                        DetailScreen(
                            instance = instance,
                            viewModel = viewModel,
                            onBack = { route = Route.Home },
                            onStart = { viewModel.start(instance) },
                            onStop = { viewModel.stop(instance) },
                            onTerminal = {
                                viewModel.openTerminal(context, instance.entity.id)
                                route = Route.Terminal(instance.entity.id)
                            },
                            onExportRequested = {
                                pendingExportForDelete = null
                                exportLauncher.launch("anct-backup-v1.zip")
                            },
                            onLogs = { viewModel.loadLogs(instance) },
                            onDelete = { viewModel.delete(instance) },
                            onExportThenDelete = {
                                pendingExportForDelete = instance
                                exportLauncher.launch("anct-backup-v1.zip")
                            },
                        )
                    }
                }

                is Route.Terminal -> {
                    val instance = state.instances.firstOrNull { it.entity.id == current.id }
                    TerminalScreen(
                        instance = instance,
                        onBack = {
                            viewModel.closeTerminal(context, current.id)
                            route = Route.Details(current.id)
                        },
                    )
                }
            }

            if (state.busy) {
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.progress.ifBlank { t("处理中", "Working") },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            state.logText?.let { log ->
                AlertDialog(
                    onDismissRequest = viewModel::clearLogs,
                    title = { Text(state.logTitle ?: t("日志", "Logs")) },
                    text = {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(420.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(log, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = viewModel::clearLogs) {
                            Text(t("关闭", "Close"))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun InstanceListScreen(
    state: UiSnapshot,
    onRefresh: () -> Unit,
    onImport: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (StoredInstance) -> Unit,
    onStart: (StoredInstance) -> Unit,
    onStop: (StoredInstance) -> Unit,
    onTerminal: (StoredInstance) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("AnCT", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        t("本地 Linux 环境与系统容器", "Local Linux environments and system containers"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onImport) {
                    Icon(Icons.Outlined.FileUpload, t("导入备份", "Import backup"))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeProbeChip(
                    title = t("Linux 环境", "Linux environment"),
                    probe = state.probes[RuntimeKind.PROOT_LINUX],
                )
                RuntimeProbeChip(
                    title = t("系统容器", "System container"),
                    probe = state.probes[RuntimeKind.NATIVE_SYSTEM],
                )
            }
        }
        if (state.instances.isEmpty()) {
            item {
                EmptyInstances(onCreate)
            }
        } else {
            items(state.instances, key = { it.entity.id }) { instance ->
                InstanceCard(instance, onOpen, onStart, onStop, onTerminal)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                    Text(t("检查运行时", "Check runtimes"))
                }
                Button(
                    onClick = onCreate,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("new_instance"),
                ) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(t("新建实例", "New instance"))
                }
            }
        }
    }
}

@Composable
private fun RuntimeProbeChip(title: String, probe: RuntimeProbe?) {
    val ready = probe?.available == true
    AssistChip(
        onClick = {},
        label = { Text("$title ${if (ready) "✓" else "!"}") },
        leadingIcon = {
            Icon(
                if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
                null,
                modifier = Modifier.size(18.dp),
                tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
    )
}

@Composable
private fun EmptyInstances(onCreate: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Terminal, null, modifier = Modifier.size(36.dp))
            Text(t("还没有实例", "No instances yet"), style = MaterialTheme.typography.titleLarge)
            Text(
                t(
                    "PRoot 环境无需 Root；原生系统容器需要已有的 Root 和兼容内核。",
                    "PRoot environments need no root. Native system containers require existing root and a compatible kernel.",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCreate) { Text(t("创建第一个实例", "Create the first instance")) }
        }
    }
}

@Composable
private fun InstanceCard(
    instance: StoredInstance,
    onOpen: (StoredInstance) -> Unit,
    onStart: (StoredInstance) -> Unit,
    onStop: (StoredInstance) -> Unit,
    onTerminal: (StoredInstance) -> Unit,
) {
    val running = instance.entity.statusSnapshot is ActualState.Running
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("instance_${instance.entity.name}")
            .clickable { onOpen(instance) },
        border = BorderStroke(
            1.dp,
            if (running) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(instance.entity.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        kindLabel(instance.entity.kind),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(instance.entity.statusSnapshot, instance.entity.kind)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Text(
                statusText(instance.entity.statusSnapshot),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    OutlinedButton(
                        onClick = { onStop(instance) },
                        modifier = Modifier.testTag("stop_instance"),
                    ) {
                        Icon(Icons.Outlined.Stop, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("停止", "Stop"))
                    }
                } else {
                    Button(
                        onClick = { onStart(instance) },
                        modifier = Modifier.testTag("start_instance"),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("启动", "Start"))
                    }
                }
                OutlinedButton(
                    onClick = { onTerminal(instance) },
                    enabled = running || instance.entity.kind == RuntimeKind.PROOT_LINUX,
                    modifier = Modifier.testTag("terminal_instance"),
                ) {
                    Icon(Icons.Outlined.Terminal, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("终端", "Terminal"))
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: ActualState, kind: RuntimeKind) {
    val color = when (status) {
        is ActualState.Running -> MaterialTheme.colorScheme.primary
        is ActualState.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Text(
            if (kind == RuntimeKind.NATIVE_SYSTEM) "EXPERIMENTAL" else statusLabel(status),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CreateScreen(
    state: UiSnapshot,
    onBack: () -> Unit,
    onCreate: (
        String,
        RuntimeKind,
        String,
        Uri?,
        String,
        String,
        String,
        RestartPolicy,
        Boolean,
        String?,
    ) -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(RuntimeKind.PROOT_LINUX.name) }
    var name by rememberSaveable { mutableStateOf("") }
    var guestUser by rememberSaveable { mutableStateOf("root") }
    var startCommand by rememberSaveable { mutableStateOf("") }
    var workingDirectory by rememberSaveable { mutableStateOf("/root") }
    var customSource by rememberSaveable { mutableStateOf("") }
    var sourceMode by rememberSaveable { mutableStateOf("catalog") }
    var selectedCatalogId by rememberSaveable { mutableStateOf<String?>(null) }
    var archiveUri by rememberSaveable { mutableStateOf<String?>(null) }
    var restartOnFailure by rememberSaveable { mutableStateOf(false) }
    var autoStart by rememberSaveable { mutableStateOf(false) }
    val selectedKind = RuntimeKind.valueOf(kind)
    val catalog = state.catalog.filter { it.backend == selectedKind }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        archiveUri = uri?.toString()
    }
    val nativeAllowed = selectedKind != RuntimeKind.NATIVE_SYSTEM || state.nativeExperimentalEnabled

    ScreenScaffold(t("创建实例", "Create instance"), onBack) {
        LazyColumn(
            modifier = Modifier.testTag("create_list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(t("类型", "Type"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeKind.entries.forEach { runtime ->
                        FilterChip(
                            selected = selectedKind == runtime,
                            onClick = {
                                kind = runtime.name
                                selectedCatalogId = null
                                sourceMode = "catalog"
                            },
                            label = { Text(kindLabel(runtime)) },
                        )
                    }
                }
                Text(
                    if (selectedKind == RuntimeKind.PROOT_LINUX) {
                        t(
                            "无需 Root。共享 Android 网络，不保证 systemd/OpenRC。",
                            "No root. Uses Android networking and does not guarantee systemd/OpenRC.",
                        )
                    } else {
                        t(
                            "实验性。需要已有 Root、兼容内核，并会安装独立启动模块。",
                            "Experimental. Requires existing root, a compatible kernel and installs a separate boot module.",
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedKind == RuntimeKind.NATIVE_SYSTEM) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    label = { Text(t("实例名称", "Instance name")) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("instance_name"),
                )
            }
            item {
                Text(t("来源", "Source"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = sourceMode == "catalog",
                        onClick = { sourceMode = "catalog" },
                        label = { Text(t("目录", "Catalog")) },
                    )
                    FilterChip(
                        selected = sourceMode == "file",
                        onClick = { sourceMode = "file" },
                        label = { Text(t("本地归档", "Local archive")) },
                    )
                    if (selectedKind == RuntimeKind.PROOT_LINUX) {
                        FilterChip(
                            selected = sourceMode == "manual",
                            onClick = { sourceMode = "manual" },
                            label = { Text(t("OCI 引用", "OCI reference")) },
                        )
                    }
                }
            }
            if (sourceMode == "catalog") {
                if (catalog.isEmpty()) {
                    item {
                        WarningCard(
                            t(
                                "目录中没有该类型的镜像，请使用本地 rootfs 归档。",
                                "The catalog has no image for this type. Use a local rootfs archive.",
                            ),
                        )
                    }
                }
                items(catalog, key = { it.id }) { entry ->
                    CatalogCard(entry, selectedCatalogId == entry.id) {
                        selectedCatalogId = entry.id
                    }
                }
            } else if (sourceMode == "file") {
                item {
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("application/gzip", "application/x-xz", "application/octet-stream", "application/zip")) }) {
                        Icon(Icons.Outlined.FileUpload, null)
                        Spacer(Modifier.width(8.dp))
                        Text(archiveUri?.let { t("已选择归档", "Archive selected") } ?: t("选择归档", "Choose archive"))
                    }
                }
            } else {
                item {
                    OutlinedTextField(
                        value = customSource,
                        onValueChange = { customSource = it },
                        label = { Text(t("公共 OCI 镜像", "Public OCI image")) },
                        supportingText = { Text("alpine:3.24, debian:13, ghcr.io/...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Text(t("启动配置", "Startup"), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = guestUser,
                    onValueChange = { guestUser = it },
                    label = { Text(t("默认 guest 用户", "Default guest user")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = startCommand,
                    onValueChange = { startCommand = it },
                    label = { Text(t("后台启动命令（可空）", "Background command (optional)")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = workingDirectory,
                    onValueChange = { workingDirectory = it },
                    label = { Text(t("工作目录", "Working directory")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                ToggleRow(
                    title = t("失败时有限重试", "Limited retry on failure"),
                    subtitle = t("最多 3 次指数退避", "Up to three attempts with exponential backoff"),
                    checked = restartOnFailure,
                    onCheckedChange = { restartOnFailure = it },
                )
                if (selectedKind == RuntimeKind.NATIVE_SYSTEM) {
                    ToggleRow(
                        title = t("开机自动启动", "Start at boot"),
                        subtitle = t("仅原生系统容器支持", "Available only for native system containers"),
                        checked = autoStart,
                        onCheckedChange = { autoStart = it },
                    )
                }
            }
            if (!nativeAllowed) {
                item {
                    WarningCard(
                        t(
                            "请先在设置中启用实验性原生后端。",
                            "Enable the experimental native backend in Settings first.",
                        ),
                    )
                }
            }
            item {
                val selectedEntry = catalog.firstOrNull { it.id == selectedCatalogId }
                val source = when (sourceMode) {
                    "catalog" -> selectedEntry?.ociReference ?: selectedEntry?.rootfsUrl.orEmpty()
                    "manual" -> customSource
                    else -> ""
                }
                Button(
                    onClick = {
                        onCreate(
                            name,
                            selectedKind,
                            source,
                            archiveUri?.let(Uri::parse),
                            guestUser,
                            startCommand,
                            workingDirectory,
                            if (restartOnFailure) RestartPolicy.ON_FAILURE_LIMITED else RestartPolicy.NEVER,
                            autoStart,
                            selectedEntry?.sha256,
                        )
                    },
                    enabled = nativeAllowed &&
                        name.isNotBlank() &&
                        ((sourceMode == "catalog" && selectedCatalogId != null) ||
                            (sourceMode == "manual" && customSource.isNotBlank()) ||
                            (sourceMode == "file" && archiveUri != null)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("create_instance"),
                ) {
                    Text(t("创建", "Create"))
                }
            }
        }
    }
}

@Composable
private fun CatalogCard(entry: CatalogEntry, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("catalog_${entry.id}")
            .clickable(onClick = onClick),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${entry.name} ${entry.version}", fontWeight = FontWeight.SemiBold)
                Text(
                    entry.ociReference ?: entry.rootfsUrl.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.sha256.isNullOrBlank()) {
                    Text(
                        t("未附目录签名摘要", "No catalog digest attached"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (selected) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DetailScreen(
    instance: StoredInstance,
    viewModel: AnctViewModel,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTerminal: () -> Unit,
    onExportRequested: () -> Unit,
    onLogs: () -> Unit,
    onDelete: () -> Unit,
    onExportThenDelete: () -> Unit,
) {
    val original = remember(instance.entity.configJson) { viewModel.decode(instance) }
    var draft by remember(instance.entity.configJson) { mutableStateOf(original) }
    var deleteDialog by remember { mutableStateOf(false) }
    var mountSource by remember { mutableStateOf("") }
    var mountDestination by remember { mutableStateOf("") }
    var portHost by remember { mutableStateOf("") }
    var portGuest by remember { mutableStateOf("") }
    val running = instance.entity.statusSnapshot is ActualState.Running

    ScreenScaffold(instance.entity.name, onBack, actions = {
        IconButton(onClick = { deleteDialog = true }) {
            Icon(Icons.Outlined.Delete, t("删除", "Delete"))
        }
    }) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(kindLabel(instance.entity.kind), style = MaterialTheme.typography.titleMedium)
                            Text(statusText(instance.entity.statusSnapshot), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusBadge(instance.entity.statusSnapshot, instance.entity.kind)
                    }
                    Text(
                        t("来源", "Source") + ": " + original.source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (running) {
                            Button(onClick = onStop, modifier = Modifier.testTag("stop_instance")) {
                                Text(t("停止", "Stop"))
                            }
                        } else {
                            Button(onClick = onStart, modifier = Modifier.testTag("start_instance")) {
                                Text(t("启动", "Start"))
                            }
                        }
                        OutlinedButton(
                            onClick = onTerminal,
                            enabled = running || instance.entity.kind == RuntimeKind.PROOT_LINUX,
                            modifier = Modifier.testTag("terminal_instance"),
                        ) { Text(t("终端", "Terminal")) }
                        OutlinedButton(onClick = onExportRequested) {
                            Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(t("导出", "Export"))
                        }
                        OutlinedButton(onClick = onLogs) {
                            Text(t("日志", "Logs"))
                        }
                    }
                }
            }

            if (instance.entity.kind == RuntimeKind.NATIVE_SYSTEM) {
                WarningCard(
                    t(
                        "实验性原生后端尚未完成实机验收。容器内 root 可能影响 Android 宿主，请只运行可信镜像。",
                        "The experimental native backend has not completed physical-device acceptance. Guest root can affect Android; only run trusted images.",
                    ),
                )
            }

            Text(t("配置", "Configuration"), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = draft.guestUser,
                onValueChange = { draft = draft.copy(guestUser = it) },
                label = { Text(t("默认 guest 用户", "Default guest user")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.startCommand,
                onValueChange = { draft = draft.copy(startCommand = it) },
                label = { Text(t("后台启动命令", "Background command")) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.workingDirectory,
                onValueChange = { draft = draft.copy(workingDirectory = it) },
                label = { Text(t("工作目录", "Working directory")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow(
                title = t("失败时有限重试", "Limited retry on failure"),
                subtitle = t("默认关闭，最多尝试 3 次", "Off by default, at most three attempts"),
                checked = draft.restartPolicy == RestartPolicy.ON_FAILURE_LIMITED,
                onCheckedChange = {
                    draft = draft.copy(
                        restartPolicy = if (it) RestartPolicy.ON_FAILURE_LIMITED else RestartPolicy.NEVER,
                    )
                },
            )
            if (instance.entity.kind == RuntimeKind.NATIVE_SYSTEM) {
                ToggleRow(
                    title = t("开机自动启动", "Start at boot"),
                    subtitle = t("由原生 Root daemon 执行", "Executed by the native root daemon"),
                    checked = draft.autoStart,
                    onCheckedChange = { draft = draft.copy(autoStart = it) },
                )
            }
            ResourceLimitFields(draft, onChanged = { draft = it })
            MountFields(
                draft = draft,
                mountSource = mountSource,
                mountDestination = mountDestination,
                onSource = { mountSource = it },
                onDestination = { mountDestination = it },
                onAdd = {
                    if (mountSource.isNotBlank() && mountDestination.isNotBlank()) {
                        draft = draft.copy(
                            mounts = draft.mounts + MountSpec(mountSource, mountDestination),
                        )
                        mountSource = ""
                        mountDestination = ""
                    }
                },
                onRemove = { index -> draft = draft.copy(mounts = draft.mounts.filterIndexed { i, _ -> i != index }) },
            )
            if (instance.entity.kind == RuntimeKind.NATIVE_SYSTEM) {
                Text(t("基础网络", "Base networking"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(NetworkMode.NAT, NetworkMode.HOST, NetworkMode.NONE).forEach { mode ->
                        FilterChip(
                            selected = draft.networkMode == mode,
                            onClick = { draft = draft.copy(networkMode = mode) },
                            label = { Text(mode.name) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = portHost,
                        onValueChange = { portHost = it.filter(Char::isDigit) },
                        label = { Text("Host") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = portGuest,
                        onValueChange = { portGuest = it.filter(Char::isDigit) },
                        label = { Text("Guest") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            val host = portHost.toIntOrNull()
                            val guest = portGuest.toIntOrNull()
                            if (host != null && guest != null) {
                                draft = draft.copy(
                                    portMappings = draft.portMappings + PortMapping(host, guest),
                                )
                                portHost = ""
                                portGuest = ""
                            }
                        },
                    ) { Text(t("添加", "Add")) }
                }
                draft.portMappings.forEachIndexed { index, mapping ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${mapping.hostPort} → ${mapping.containerPort}/${mapping.protocol}", Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                draft = draft.copy(
                                    portMappings = draft.portMappings.filterIndexed { i, _ -> i != index },
                                )
                            },
                        ) { Icon(Icons.Outlined.Delete, null) }
                    }
                }
            }
            Button(
                onClick = { viewModel.save(draft) },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (running) t("停止后可编辑", "Stop to edit") else t("保存配置", "Save configuration"))
            }
        }
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text(t("删除实例", "Delete instance")) },
            text = {
                Text(
                    t(
                        "删除后 rootfs 与配置不可恢复。可先导出整包备份。",
                        "The rootfs and configuration cannot be recovered after deletion. Export a backup first if needed.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteDialog = false
                    onDelete()
                }) { Text(t("直接删除", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { deleteDialog = false }) { Text(t("取消", "Cancel")) }
                    TextButton(onClick = {
                        deleteDialog = false
                        onExportThenDelete()
                    }) { Text(t("先导出", "Export first")) }
                }
            },
        )
    }
}

@Composable
private fun ResourceLimitFields(spec: RuntimeSpec, onChanged: (RuntimeSpec) -> Unit) {
    if (spec.kind == RuntimeKind.PROOT_LINUX) {
        Text(
            t(
                "PRoot 不支持可靠的 CPU、内存和 PID 硬限制。",
                "PRoot cannot enforce reliable CPU, memory or PID limits.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var memoryMb by remember(spec.id) {
        mutableStateOf(spec.resourceLimits.memoryBytes?.div(1024 / 1024).toString().takeUnless { it == "null" } ?: "")
    }
    var cpus by remember(spec.id) { mutableStateOf(spec.resourceLimits.cpuQuota?.toString().orEmpty()) }
    var pids by remember(spec.id) { mutableStateOf(spec.resourceLimits.pids?.toString().orEmpty()) }
    Text(t("资源限制", "Resource limits"), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = memoryMb,
            onValueChange = {
                memoryMb = it.filter(Char::isDigit)
                onChanged(
                    spec.copy(
                        resourceLimits = spec.resourceLimits.copy(
                            memoryBytes = memoryMb.toLongOrNull()?.times(1024L * 1024L),
                        ),
                    ),
                )
            },
            label = { Text("MiB") },
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = cpus,
            onValueChange = {
                cpus = it
                onChanged(spec.copy(resourceLimits = spec.resourceLimits.copy(cpuQuota = cpus.toDoubleOrNull())))
            },
            label = { Text("CPU") },
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = pids,
            onValueChange = {
                pids = it.filter(Char::isDigit)
                onChanged(spec.copy(resourceLimits = spec.resourceLimits.copy(pids = pids.toIntOrNull())))
            },
            label = { Text("PIDs") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MountFields(
    draft: RuntimeSpec,
    mountSource: String,
    mountDestination: String,
    onSource: (String) -> Unit,
    onDestination: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    Text(t("挂载", "Bind mounts"), style = MaterialTheme.typography.titleSmall)
    draft.mounts.forEachIndexed { index, mount ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${mount.source} → ${mount.destination}", style = MaterialTheme.typography.bodySmall)
                if (mount.readOnly) Text(t("只读", "Read-only"), style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { onRemove(index) }) { Icon(Icons.Outlined.Delete, null) }
        }
    }
    OutlinedTextField(
        value = mountSource,
        onValueChange = onSource,
        label = { Text(t("宿主绝对路径", "Host absolute path")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = mountDestination,
        onValueChange = onDestination,
        label = { Text(t("guest 绝对路径", "Guest absolute path")) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = onAdd, enabled = mountSource.isNotBlank() && mountDestination.isNotBlank()) {
        Icon(Icons.Outlined.Add, null)
        Spacer(Modifier.width(4.dp))
        Text(t("添加挂载", "Add mount"))
    }
}

@Composable
private fun SettingsScreen(
    state: UiSnapshot,
    onCheck: () -> Unit,
    onInstall: (RuntimeKind) -> Unit,
    onNativeExperimental: (Boolean) -> Unit,
    onAllowUnverifiedImports: (Boolean) -> Unit,
    onAddCatalog: (String) -> Unit,
    onUninstall: () -> Unit,
) {
    var confirmNative by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var catalogUrl by rememberSaveable { mutableStateOf("") }
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(t("运行时", "Runtimes"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        item { RuntimeSettingsCard(RuntimeKind.PROOT_LINUX, state.probes[RuntimeKind.PROOT_LINUX], onInstall) }
        item {
            RuntimeSettingsCard(RuntimeKind.NATIVE_SYSTEM, state.probes[RuntimeKind.NATIVE_SYSTEM], onInstall)
        }
        item {
            ToggleRow(
                title = t("启用实验性原生后端", "Enable experimental native backend"),
                subtitle = t(
                    "需要已有 Root、兼容内核；尚未完成实机验收",
                    "Requires existing root and a compatible kernel; physical acceptance is pending",
                ),
                checked = state.nativeExperimentalEnabled,
                onCheckedChange = {
                    if (it) confirmNative = true else onNativeExperimental(false)
                },
            )
        }
        item {
            OutlinedTextField(
                value = catalogUrl,
                onValueChange = { catalogUrl = it },
                label = { Text(t("自定义目录 URL", "Custom catalog URL")) },
                supportingText = {
                    Text(t("仅接受 HTTPS；未签名目录会显示来源警告", "HTTPS only; unsigned catalogs are marked as unverified"))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onAddCatalog(catalogUrl) },
                enabled = catalogUrl.startsWith("https://"),
            ) {
                Text(t("添加目录", "Add catalog"))
            }
        }
        item {
            ToggleRow(
                title = t("允许覆盖导入完整性警告", "Allow integrity warnings during import"),
                subtitle = t(
                    "校验失败会阻止导入，除非明确启用此开关；仍拒绝危险归档路径",
                    "Checksum and signature failures block imports unless this is enabled; unsafe archive paths remain blocked",
                ),
                checked = state.allowUnverifiedImports,
                onCheckedChange = onAllowUnverifiedImports,
            )
        }
        item {
            OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) {
                Text(t("重新检查", "Check again"))
            }
        }
        item {
            Text(t("安全说明", "Safety"), style = MaterialTheme.typography.titleMedium)
            WarningCard(
                t(
                    "本产品不负责防火墙、公网发布、Root 获取或内核修改。PRoot root 是身份映射，不是宿主 Root。",
                    "This app does not manage firewalls, public ingress, root acquisition or kernels. PRoot root is ID mapping, not host root.",
                ),
            )
        }
        item {
            Text(t("卸载", "Uninstall"), style = MaterialTheme.typography.titleMedium)
            Text(
                t(
                    "系统卸载不会执行 App 回调。PRoot 私有数据会随卸载删除；原生模块与 /data/local/Droidspaces 数据可能保留。请先导出并停止实例。",
                    "The system uninstaller does not run app callbacks. PRoot data is removed with the app; native modules and /data/local/Droidspaces may remain. Export and stop instances first.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirmUninstall = true }) {
                Text(t("准备卸载", "Prepare uninstall"))
            }
        }
    }

    if (confirmNative) {
        AlertDialog(
            onDismissRequest = { confirmNative = false },
            icon = { Icon(Icons.Outlined.Warning, null) },
            title = { Text(t("启用实验性原生后端", "Enable experimental native backend")) },
            text = {
                Text(
                    t(
                        "原生容器使用真实宿主 Root，内核和 Root 管理器适配由你负责。恶意 guest root 可影响 Android。",
                        "Native containers use real host root. Kernel and root-manager compatibility are your responsibility. A hostile guest root can affect Android.",
                    ),
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmNative = false
                    onNativeExperimental(true)
                }) { Text(t("我理解风险", "I understand")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmNative = false }) { Text(t("取消", "Cancel")) }
            },
        )
    }
    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text(t("确认卸载", "Confirm uninstall")) },
            text = {
                Text(
                    t(
                        "请确认所有需要保留的实例均已导出。原生后端可能残留 Root 模块，需要重新安装本 App 或从 Root 管理器手动清理。",
                        "Confirm every instance you need is exported. Native components may remain and require reinstalling this app or manual root-manager cleanup.",
                    ),
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmUninstall = false
                    onUninstall()
                }) { Text(t("继续卸载", "Continue")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text(t("取消", "Cancel")) }
            },
        )
    }
}

@Composable
private fun RuntimeSettingsCard(
    kind: RuntimeKind,
    probe: RuntimeProbe?,
    onInstall: (RuntimeKind) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(kindLabel(kind), style = MaterialTheme.typography.titleMedium)
                    Text(
                        probe?.message ?: t("尚未检查", "Not checked"),
                        color = if (probe?.available == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (probe?.available == true) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                } else {
                    OutlinedButton(onClick = { onInstall(kind) }) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("安装", "Install"))
                    }
                }
            }
            probe?.version?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (kind == RuntimeKind.NATIVE_SYSTEM) {
                Text(
                    t("实验性", "Experimental"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Warning, null)
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, t("返回", "Back"))
            }
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
        content()
    }
}

@Composable
private fun MissingInstance(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(t("实例不存在", "Instance not found"))
        Button(onClick = onBack) { Text(t("返回", "Back")) }
    }
}

@Composable
private fun TerminalScreen(
    instance: StoredInstance?,
    onBack: () -> Unit,
) {
    if (instance == null) {
        MissingInstance(onBack)
        return
    }
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var binder by remember { mutableStateOf<TerminalSessionService.SessionBinder?>(null) }
    val context = LocalContext.current
    var bound by remember { mutableStateOf(false) }
    val connection = remember {
        object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                bound = true
                binder = service as? TerminalSessionService.SessionBinder
                session = binder?.session(instance.entity.id)
            }

            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                bound = false
                binder = null
                session = null
            }
        }
    }
    DisposableEffect(context, instance.entity.id) {
        context.bindService(
            Intent(context, TerminalSessionService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        onDispose { context.unbindService(connection) }
    }
    LaunchedEffect(bound, binder, instance.entity.id) {
        if (bound) {
            repeat(20) {
                if (session != null) return@LaunchedEffect
                delay(100)
                session = binder?.session(instance.entity.id)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) }
            Text(instance.entity.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val current = session
            if (current == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            } else {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("terminal_view"),
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            setTextSize(26)
                            setTerminalViewClient(SimpleTerminalViewClient(this))
                            attachSession(current)
                        }
                    },
                    update = { view ->
                        val existing = view.currentSession
                        if (existing !== current) view.attachSession(current)
                        view.onScreenUpdated()
                    },
                )
            }
        }
    }
}

private class SimpleTerminalViewClient(
    private val view: TerminalView,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float {
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent) {}
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}
}

private fun statusLabel(status: ActualState): String = when (status) {
    is ActualState.Running -> "RUNNING"
    ActualState.Starting -> "STARTING"
    ActualState.Stopping -> "STOPPING"
    is ActualState.Failed -> "FAILED"
    ActualState.Stopped -> "STOPPED"
    ActualState.Unknown -> "UNKNOWN"
}

@Composable
private fun statusText(status: ActualState): String = when (status) {
    is ActualState.Running -> status.detail ?: "PID ${status.pid ?: "?"}"
    ActualState.Starting -> t("正在启动", "Starting")
    ActualState.Stopping -> t("正在停止", "Stopping")
    is ActualState.Failed -> status.message
    ActualState.Stopped -> t("已停止", "Stopped")
    ActualState.Unknown -> t("状态未知", "Unknown")
}

@Composable
private fun kindLabel(kind: RuntimeKind): String = when (kind) {
    RuntimeKind.PROOT_LINUX -> t("Linux 环境", "Linux environment")
    RuntimeKind.NATIVE_SYSTEM -> t("系统容器", "System container")
}

@Composable
private fun t(zh: String, en: String): String {
    val language = LocalConfiguration.current.locales[0].language
    return if (language == "zh") zh else en
}
