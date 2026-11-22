package dev.pocketide.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.ScrollView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Records why the app closed so it can be shown (and copied) on the next start.
 * Kotlin/Java crashes are written by an uncaught-exception handler; native crashes
 * (for example inside a JNI library) come from Android's own exit records on Android 11+.
 */
object CrashReport {
    @Volatile private var installed = false
    private fun file(ctx: Context) = File(ctx.filesDir, "last-crash.txt")
    private fun seenFile(ctx: Context) = File(ctx.filesDir, "last-crash-seen.txt")

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                file(app).writeText(header(app) + "Thread: ${thread.name}\n\n" + dev.pocketide.app.core.SecretMask.mask(sw.toString()))
            } catch (ignored: Throwable) { /* nothing more we can do */ }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun header(ctx: Context): String {
        val v = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" }
        return "PocketIDE $v (${ctx.packageName})\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n"
    }

    /** The report from the last crash, or null. Clears it so it is shown once. */
    private fun take(ctx: Context): String? {
        val f = file(ctx)
        if (f.isFile) {
            val text = try { f.readText() } catch (e: Exception) { null }
            f.delete()
            if (!text.isNullOrBlank()) { markNativeSeen(ctx); return text }
        }
        return nativeCrash(ctx)
    }

    private fun latestExit(ctx: Context): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return try { am.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() } catch (e: Exception) { null }
    }

    private fun markNativeSeen(ctx: Context) {
        val last = latestExit(ctx) ?: return
        try { seenFile(ctx).writeText(last.timestamp.toString()) } catch (e: Exception) { }
    }

    private fun nativeCrash(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val last = latestExit(ctx) ?: return null
        val seen = try { seenFile(ctx).readText().trim().toLong() } catch (e: Exception) { 0L }
        if (last.timestamp <= seen) return null
        try { seenFile(ctx).writeText(last.timestamp.toString()) } catch (e: Exception) { }
        val crash = last.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || last.reason == ApplicationExitInfo.REASON_CRASH ||
            last.reason == ApplicationExitInfo.REASON_ANR || last.reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
        if (!crash) return null
        val kind = when (last.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_ANR -> "app not responding"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "start-up failure"
            else -> "crash"
        }
        val sb = StringBuilder(header(ctx))
        sb.append("Exit: $kind, status ${last.status}\n")
        last.description?.let { sb.append("Description: $it\n") }
        // Android 12+ keeps the tombstone (native) or ANR trace; show the start of it.
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                last.traceInputStream?.use { s ->
                    val bytes = s.readBytes()
                    val text = String(bytes, Charsets.UTF_8)
                    val printable = text.count { it == '\n' || it == '\t' || (it >= ' ' && it != '�') }
                    if (text.isNotEmpty() && printable > text.length * 0.9) sb.append("\n").append(text.take(12_000))
                    else if (bytes.isNotEmpty()) sb.append("\n(native crash record is ${bytes.size} bytes of binary data)\n")
                }
            } catch (e: Exception) { }
        }
        return sb.toString()
    }

    /** Shows the last crash, if any, with a Copy button. Call from an Activity's onCreate. */
    fun showIfAny(activity: Activity) {
        val text = take(activity) ?: return
        val tv = Ui.text(activity, text, 11f, Ui.TEXT, true)
        tv.setTextIsSelectable(true)
        tv.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 8), Ui.dp(activity, 16), Ui.dp(activity, 8))
        val scroll = ScrollView(activity)
        scroll.addView(tv)
        AlertDialog.Builder(activity)
            .setTitle("PocketIDE closed unexpectedly last time")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("PocketIDE crash", text))
                Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
