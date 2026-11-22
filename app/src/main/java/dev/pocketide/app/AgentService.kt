package dev.pocketide.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Keeps an assistant request alive while PocketIDE is in the background. Android freezes or kills apps that are not in
 * front, so a long request used to stop silently when the person switched apps (GitHub Mobile and Cursor show live
 * progress for the same reason). While a request runs, this foreground service shows an ongoing notification with what
 * the assistant is doing and a Stop button. It stops when the request ends, and on its own after [IDLE_MS] without news.
 */
class AgentService : Service() {
    companion object {
        private const val CHANNEL = "agent-run"
        private const val ID = 7202
        private const val ACTION_UPDATE = "dev.pocketide.app.agent.UPDATE"
        private const val ACTION_STOP = "dev.pocketide.app.agent.STOP"
        /** Sent instead of stopService when the service may not have reached startForeground yet (see [stop]). */
        private const val ACTION_QUIT = "dev.pocketide.app.agent.QUIT"
        private const val EXTRA_TEXT = "text"
        /** No progress for this long means the request ended without telling us; stop rather than linger. */
        private const val IDLE_MS = 30 * 60 * 1000L

        /** Set by the chat screen: what Stop in the notification does. */
        @Volatile var onStop: (() -> Unit)? = null
        @Volatile private var running = false
        @Volatile private var lastText = ""
        /** True once startForeground has run for the current start. */
        @Volatile private var foreground = false
        private val main = Handler(Looper.getMainLooper())

        /** Starts the service, or updates its text when it already runs. Silently does nothing when Android refuses. */
        fun update(ctx: Context, text: String) {
            val t = dev.pocketide.app.core.SecretMask.mask(text.trim()).ifEmpty { "Working…" }.take(120)   // 1.5.150 (M1): shown on the lock screen
            if (running && t == lastText) return
            lastText = t
            try {
                val i = Intent(ctx, AgentService::class.java).setAction(ACTION_UPDATE).putExtra(EXTRA_TEXT, t)
                if (!running && Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                running = true
            } catch (e: Exception) { running = false }
        }

        /** New progress text for a request that is already running; never starts the service. */
        fun progress(ctx: Context, text: String) { if (running) update(ctx, text) }

        /**
         * Android crashes an app whose service was started with startForegroundService but stopped before it called
         * startForeground. A very short request can end that fast, so in that case the service is asked to quit itself:
         * it calls startForeground first, then stops.
         */
        fun stop(ctx: Context) {
            if (!running) return
            running = false
            lastText = ""
            if (foreground) {
                try { ctx.stopService(Intent(ctx, AgentService::class.java)) } catch (e: Exception) { }
                return
            }
            try { ctx.startService(Intent(ctx, AgentService::class.java).setAction(ACTION_QUIT)) }
            catch (e: Exception) { try { ctx.stopService(Intent(ctx, AgentService::class.java)) } catch (e2: Exception) { } }
        }
    }

    private val idle = Runnable { running = false; stopSelf() }
    private var wake: android.os.PowerManager.WakeLock? = null

    /** Keeps the processor awake while the assistant works, so a job continues after the screen turns off. Released when the job ends. */
    private fun holdAwake() {
        try {
            val w = wake ?: (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "pocketide:agent").also { it.setReferenceCounted(false); wake = it }
            w.acquire(IDLE_MS + 60_000L)
        } catch (e: Exception) { }
    }

    private fun letSleep() { try { wake?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }; wake = null }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            main.post { onStop?.invoke() }
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: lastText.ifEmpty { "Working…" }
        val n = build(text)
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, n)
            foreground = true
        } catch (e: Exception) { running = false; stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_QUIT || !running) {
            // the request already ended: leave the foreground state cleanly and stop
            @Suppress("DEPRECATION") stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_MS)
        holdAwake()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(idle)
        letSleep()
        running = false
        foreground = false
        super.onDestroy()
    }

    private fun build(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Assistant at work", NotificationManager.IMPORTANCE_LOW))
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val open = packageManager.getLaunchIntentForPackage(packageName)?.also { it.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT }
        val openPi = open?.let { PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE) }
        val stopPi = PendingIntent.getService(this, 3, Intent(this, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PocketIDE assistant is working")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (openPi != null) b.setContentIntent(openPi)
        @Suppress("DEPRECATION")
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
        return b.build()
    }
}
