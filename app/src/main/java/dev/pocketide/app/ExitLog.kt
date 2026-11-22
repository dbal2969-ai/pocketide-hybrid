package dev.pocketide.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import dev.pocketide.app.core.AppLog
import java.io.File

/**
 * Writes into the Diagnostics log why the previous run of the app ended, whatever the reason, once per exit. Crashes are shown
 * as reports where the app has them; this also covers the quiet ends (closed for memory, or stopped by the system or the user).
 */
object ExitLog {
    fun note(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val last = try { am.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() } catch (e: Exception) { null } ?: return
        val f = File(ctx.filesDir, "last-exit-noted.txt")
        val seen = try { f.readText().trim().toLong() } catch (e: Exception) { 0L }
        if (last.timestamp <= seen) return
        try { f.writeText(last.timestamp.toString()) } catch (e: Exception) { }
        val detail = last.description?.takeIf { it.isNotBlank() && !it.startsWith("[") }?.let { ", $it" } ?: ""
        AppLog.add("exit", "the previous run ended: ${reasonName(last.reason)} (status ${last.status})$detail", last.timestamp)
    }

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory, Android closed it"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too many resources"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a service it depends on stopped"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "start-up failed"
        ApplicationExitInfo.REASON_OTHER -> "other reason"
        else -> "reason $r"
    }
}
