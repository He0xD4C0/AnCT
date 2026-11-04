package dev.anct.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.anct.MainActivity
import dev.anct.AnctApplication
import dev.anct.R
import dev.anct.core.ActualState
import dev.anct.core.AppHostedRuntimeBackend
import dev.anct.core.DesiredState
import dev.anct.core.RestartPolicy
import dev.anct.core.RuntimeCommand
import dev.anct.core.RuntimeSpec
import dev.anct.core.RuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class RuntimeSupervisorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val jobs = ConcurrentHashMap<String, Job>()
    private val processes = ConcurrentHashMap<String, Process>()
    private val restartAttempts = ConcurrentHashMap<String, Int>()
    private lateinit var graph: dev.anct.AppGraph

    override fun onCreate() {
        super.onCreate()
        graph = (application as AnctApplication).graph
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            intent.getStringExtra(EXTRA_ID)?.let(::stopInstance)
            if (jobs.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP_ALL) {
            jobs.keys.toList().forEach(::stopInstance)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification(activeCount = jobs.size + 1))
        if (intent?.action == ACTION_START) {
            val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
            val commandJson = intent.getStringExtra(EXTRA_COMMAND) ?: return START_NOT_STICKY
            val command = runCatching { json.decodeFromString<RuntimeCommand>(commandJson) }.getOrNull()
                ?: return START_NOT_STICKY
            launchInstance(id, command)
        } else if (intent == null) {
            restoreDesiredInstances()
        }
        return START_STICKY
    }

    private fun launchInstance(id: String, command: RuntimeCommand) {
        if (jobs[id]?.isActive == true) return
        jobs[id] = scope.launch {
            val spec = graph.repository.get(id)?.let { graph.repository.decode(it.entity) } ?: return@launch
            runProcess(spec, command)
        }
    }

    private suspend fun runProcess(spec: RuntimeSpec, command: RuntimeCommand) {
        val logDir = File(filesDir, "logs").apply { mkdirs() }
        val logFile = File(logDir, "${spec.name}.log")
        rotate(logFile)
        updateStatus(spec.id, RuntimeStatus(ActualState.Starting))
        val builder = ProcessBuilder(listOf(command.executable) + command.arguments)
            .redirectErrorStream(true)
        command.workingDirectory?.let { builder.directory(File(it)) }
        builder.environment().putAll(command.environment)
        val process = runCatching { builder.start() }.getOrElse { error ->
            updateStatus(spec.id, RuntimeStatus(ActualState.Failed(error.message ?: "Could not start process")))
            graph.repository.setDesired(spec.id, DesiredState.STOPPED)
            return
        }
        processes[spec.id] = process
        updateStatus(
            spec.id,
            RuntimeStatus(ActualState.Running(pid = null, detail = "App-hosted process")),
        )

        scope.launch {
            runCatching {
                FileOutputStream(logFile, true).buffered().use { output ->
                    process.inputStream.bufferedReader().forEachLine { line ->
                        output.write("[${System.currentTimeMillis()}] $line\n".toByteArray())
                        output.flush()
                    }
                }
            }
        }

        val exit = process.waitFor()
        processes.remove(spec.id)
        val desired = graph.repository.get(spec.id)?.entity?.desiredState
        if (desired == DesiredState.RUNNING && exit != 0 &&
            spec.restartPolicy == RestartPolicy.ON_FAILURE_LIMITED
        ) {
            val attempt = restartAttempts.merge(spec.id, 1, Int::plus) ?: 1
            if (attempt <= MAX_RESTARTS) {
                updateStatus(
                    spec.id,
                    RuntimeStatus(ActualState.Failed("Exited $exit; restart $attempt/$MAX_RESTARTS")),
                )
                delay((1L shl (attempt - 1)) * 2_000)
                if (graph.repository.get(spec.id)?.entity?.desiredState == DesiredState.RUNNING) {
                    jobs.remove(spec.id)
                    launchInstance(spec.id, command)
                }
                return
            }
        }
        restartAttempts.remove(spec.id)
        updateStatus(
            spec.id,
            if (exit == 0) RuntimeStatus(ActualState.Stopped)
            else RuntimeStatus(ActualState.Failed("Process exited with code $exit")),
        )
        if (exit != 0) graph.repository.setDesired(spec.id, DesiredState.STOPPED)
        jobs.remove(spec.id)
        updateNotification()
        if (jobs.isEmpty()) stopSelf()
    }

    private fun stopInstance(id: String) {
        jobs.remove(id)?.cancel()
        processes.remove(id)?.let { process ->
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
        scope.launch {
            graph.repository.setDesired(id, DesiredState.STOPPED)
            updateStatus(id, RuntimeStatus(ActualState.Stopped))
        }
        updateNotification()
    }

    private fun restoreDesiredInstances() {
        scope.launch {
            graph.repository.getAll()
                .filter { it.entity.desiredState == DesiredState.RUNNING && it.entity.kind == dev.anct.core.RuntimeKind.PROOT_LINUX }
                .forEach { stored ->
                    val spec = graph.repository.decode(stored.entity)
                    val backend = graph.runtimes.get(spec.kind)
                    if (backend is AppHostedRuntimeBackend) {
                        launchInstance(spec.id, backend.foregroundStartCommand(spec))
                    }
                }
        }
    }

    private suspend fun updateStatus(id: String, status: RuntimeStatus) {
        graph.repository.setStatus(id, status)
    }

    private fun rotate(file: File) {
        if (file.length() > MAX_LOG_BYTES) {
            val previous = File(file.parentFile, file.name + ".1")
            previous.delete()
            file.renameTo(previous)
        }
    }

    private fun updateNotification() {
        if (jobs.isEmpty()) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(jobs.size))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Running Linux environments",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun notification(activeCount: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAll = PendingIntent.getService(
            this,
            1,
            Intent(this, RuntimeSupervisorService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_host)
            .setContentTitle("AnCT")
            .setContentText("$activeCount Linux environment${if (activeCount == 1) "" else "s"} active")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Stop all", stopAll)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "anct.runtime"
        private const val NOTIFICATION_ID = 2101
        private const val MAX_LOG_BYTES = 8L * 1024 * 1024
        private const val MAX_RESTARTS = 3
        private const val ACTION_START = "dev.anct.action.START_RUNTIME"
        private const val ACTION_STOP = "dev.anct.action.STOP_RUNTIME"
        private const val ACTION_STOP_ALL = "dev.anct.action.STOP_ALL_RUNTIMES"
        private const val EXTRA_ID = "instance_id"
        private const val EXTRA_COMMAND = "runtime_command"
        private val json = Json { encodeDefaults = true }

        fun start(context: Context, command: RuntimeCommand, instanceId: String) {
            val intent = Intent(context, RuntimeSupervisorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ID, instanceId)
                .putExtra(EXTRA_COMMAND, json.encodeToString(command))
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context, instanceId: String) {
            context.startService(
                Intent(context, RuntimeSupervisorService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_ID, instanceId),
            )
        }
    }
}
