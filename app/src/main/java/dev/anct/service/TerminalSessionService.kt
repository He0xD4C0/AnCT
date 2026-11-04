package dev.anct.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.anct.MainActivity
import dev.anct.R
import dev.anct.core.TerminalLaunchSpec
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

class TerminalSessionService : Service() {
    inner class SessionBinder : Binder() {
        fun session(id: String): TerminalSession? = sessions[id]
    }

    private val binder = SessionBinder()
    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) {
            val id = intent.getStringExtra(EXTRA_ID)
            if (id != null) {
                sessions.remove(id)?.let { session ->
                    session.write("\u0004")
                    session.finishIfRunning()
                }
            }
            if (sessions.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification(sessions.size.coerceAtLeast(1)))
        if (intent?.action == ACTION_OPEN) {
            val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
            val spec = intent.getStringExtra(EXTRA_LAUNCH)?.let {
                runCatching { json.decodeFromString<TerminalLaunchSpec>(it) }.getOrNull()
            } ?: return START_NOT_STICKY
            if (!sessions.containsKey(id)) {
                sessions[id] = createSession(id, spec)
            }
        }
        return START_NOT_STICKY
    }

    private fun createSession(id: String, spec: TerminalLaunchSpec): TerminalSession {
        val env = buildList {
            add("TERM=xterm-256color")
            add("COLORTERM=truecolor")
            spec.environment.forEach { (key, value) -> add("$key=$value") }
        }.toTypedArray()
        val client = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {}
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {
                sessions.remove(id)
                if (sessions.isEmpty()) stopSelf()
            }
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
            override fun onPasteTextFromClipboard(session: TerminalSession?) {}
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun getTerminalCursorStyle(): Int = 0
            override fun logError(tag: String, message: String) {}
            override fun logWarn(tag: String, message: String) {}
            override fun logInfo(tag: String, message: String) {}
            override fun logDebug(tag: String, message: String) {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        }
        return TerminalSession(
            spec.executable,
            spec.workingDirectory ?: "/",
            arrayOf(spec.executable) + spec.arguments.toTypedArray(),
            env,
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            client,
        )
    }

    private fun notification(count: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_host)
            .setContentTitle("AnCT terminal")
            .setContentText("$count terminal session${if (count == 1) "" else "s"} active")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Terminal sessions",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val CHANNEL_ID = "anct.terminal"
        private const val NOTIFICATION_ID = 2102
        private const val ACTION_OPEN = "dev.anct.action.OPEN_TERMINAL"
        private const val ACTION_CLOSE = "dev.anct.action.CLOSE_TERMINAL"
        private const val EXTRA_ID = "session_id"
        private const val EXTRA_LAUNCH = "terminal_launch"
        private val json = Json { encodeDefaults = true }

        fun open(context: Context, id: String, spec: TerminalLaunchSpec) {
            context.startService(
                Intent(context, TerminalSessionService::class.java)
                    .setAction(ACTION_OPEN)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_LAUNCH, json.encodeToString(spec)),
            )
        }

        fun close(context: Context, id: String) {
            context.startService(
                Intent(context, TerminalSessionService::class.java)
                    .setAction(ACTION_CLOSE)
                    .putExtra(EXTRA_ID, id),
            )
        }
    }
}
