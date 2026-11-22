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
import dev.pocketide.app.core.ProotCommand
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * GPL route: keeps long-running Linux processes (the Ollama server, dev servers) alive while the
 * user is on other screens or in other apps. The processes are started from the app process through
 * PRoot; this foreground service, with its ongoing notification, keeps Android from freezing or
 * killing the app while any of them runs. It stops itself when the last one ends.
 */
class LinuxService : Service() {

    /** One background process. */
    class Running(val name: String, val title: String, val process: Process, val log: File)

    companion object {
        private const val CHANNEL = "linux"
        private const val NOTIFICATION_ID = 7101
        private const val ACTION_UPDATE = "dev.pocketide.app.linux.UPDATE"
        private const val ACTION_STOP_ALL = "dev.pocketide.app.linux.STOP_ALL"
        private const val MAX_LOG = 512 * 1024L

        private val procs = ConcurrentHashMap<String, Running>()
        private val main = Handler(Looper.getMainLooper())

        /** Called on the main thread whenever a process starts or ends. */
        val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

        fun isRunning(name: String): Boolean = procs[name]?.process?.isAlive == true

        fun running(): List<Running> = procs.values.filter { it.process.isAlive }.sortedBy { it.title }

        fun logFile(ctx: Context, name: String): File = File(File(ctx.filesDir, "linux/logs"), "$name.log")

        /** The end of a process's log, for error messages. */
        fun logTail(ctx: Context, name: String, chars: Int = 1500): String {
            val f = logFile(ctx, name)
            if (!f.isFile) return ""
            return try {
                java.io.RandomAccessFile(f, "r").use { r ->
                    val len = r.length()
                    val start = maxOf(0L, len - chars * 2)
                    r.seek(start)
                    val b = ByteArray((len - start).toInt())
                    r.readFully(b)
                    String(b, Charsets.UTF_8).takeLast(chars)
                }
            } catch (e: Exception) { "" }
        }

        /**
         * Starts guestCommand inside Linux (PRoot) in the background, writing its output to the log.
         * Returns null on success or a readable error. Must be called with Linux installed.
         */
        fun start(
            ctx: Context, linux: LinuxRuntime, name: String, title: String,
            guestCommand: String, guestEnv: Map<String, String> = emptyMap(),
            projectDir: File? = null  // v0.4 W3: the current project (Run); null keeps the old single-project folder
        ): String? {
            if (isRunning(name)) return null
            if (!linux.prootPresent) return "PRoot is not in this build."
            if (!linux.installed) return "Linux is not installed yet."
            val app = ctx.applicationContext
            val project = (projectDir ?: Projects.current(app)).also { it.mkdirs() }  // v0.4 W3
            val home = File(app.filesDir, "home").also { it.mkdirs() }
            linux.tmp.mkdirs()
            val log = logFile(app, name)
            log.parentFile?.mkdirs()
            // keep one previous log, and start fresh so the file stays small
            if (log.isFile) { val old = File(log.parentFile, "$name.old.log"); old.delete(); log.renameTo(old) }
            val argv = ProotCommand.args(linux.launcher.absolutePath, linux.rootfs.absolutePath, project.absolutePath,
                linux.guestTmp(project).absolutePath, guestCommand, "dumb", guestEnv)
            val proc = try {
                val pb = ProcessBuilder(argv)
                pb.directory(project)
                pb.redirectErrorStream(true)
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                pb.environment()["HOME"] = home.absolutePath
                pb.environment()["TMPDIR"] = home.absolutePath
                pb.environment().putAll(ProotCommand.env(linux.launcher.parentFile!!.absolutePath, linux.tmp.absolutePath))
                pb.start()
            } catch (e: Exception) {
                return "Could not start $title: ${e.message}"
            }
            try { proc.outputStream.close() } catch (e: Exception) { }
            val r = Running(name, title, proc, log)
            procs[name] = r
            watch(app, r)
            update(app)
            changed()
            return null
        }

        /** Stops one process and everything it started. */
        fun stop(ctx: Context, name: String) {
            val r = procs[name] ?: return
            Thread {
                BoundedProcessRunner.killTree(r.process)
                // watch() sees the exit and updates the notification
            }.start()
        }

        fun stopAll(ctx: Context) { for (n in procs.keys.toList()) stop(ctx, n) }

        private fun watch(app: Context, r: Running) {
            Thread({
                // the log can only grow while the process runs; cut it back if a chatty server fills it
                while (true) {
                    val done = try { r.process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) { false }
                    if (done) break
                    if (r.log.length() > MAX_LOG) try { java.io.FileOutputStream(r.log, false).close() } catch (e: Exception) { }
                }
                procs.remove(r.name, r)
                update(app)
                changed()
            }, "pocketide-bg-${r.name}").also { it.isDaemon = true }.start()
        }

        private fun changed() { main.post { for (l in listeners) try { l() } catch (e: Exception) { } } }

        /** Starts (or refreshes, or ends) the foreground service to match what is running. */
        private fun update(app: Context) {
            main.post {
                val i = Intent(app, LinuxService::class.java).setAction(ACTION_UPDATE)
                try {
                    // Never stopService() here: stopping before onStartCommand has called startForeground
                    // crashes the app on recent Android. onStartCommand calls startForeground, then
                    // stopSelf() when nothing is left.
                    if (running().isNotEmpty() && Build.VERSION.SDK_INT >= 26) app.startForegroundService(i)
                    else app.startService(i)
                } catch (e: Exception) {
                    // Android may refuse to start a foreground service while the app is in the background;
                    // the processes still run, they just are not protected.
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) stopAll(this)
        val list = running()
        // startForeground must be called after startForegroundService even when we are about to stop
        val n = notification(list)
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (list.isEmpty() && intent?.action != ACTION_STOP_ALL) stopSelf()
        // processes do not survive the app process, so never restart an empty service
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // swiping the app away keeps the servers running; the notification's Stop ends them
        super.onTaskRemoved(rootIntent)
    }

    private fun notification(list: List<Running>): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Background Linux processes", NotificationManager.IMPORTANCE_LOW).also {
                it.description = "Shown while Ollama or another Linux server runs in the background."
            })
        }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, LinuxService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when {
            list.isEmpty() -> "PocketIDE: stopping"
            list.size == 1 -> "PocketIDE: ${list[0].title} running"
            else -> "PocketIDE: ${list.size} servers running"
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle(title)
            .setContentText(if (list.isEmpty()) "" else list.joinToString(", ") { it.title } + " · tap Stop to end")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }
}
