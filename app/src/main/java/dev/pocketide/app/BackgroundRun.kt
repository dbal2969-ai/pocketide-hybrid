package dev.pocketide.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Why a run was cut off while PocketIDE was in the background, and the setting that stops it (1.5.97).
 *
 * The agent already runs in a foreground service (AgentService), but battery optimisation still lets Android, and Samsung's
 * "sleeping apps" above all, close the app when the person switches away. Android records why a process ended
 * (ApplicationExitInfo); CrashReport only shows crashes, so a background close left no trace.
 */
object BackgroundRun {
    /** True when PocketIDE is exempt from battery optimisation (or the phone has none). */
    fun allowed(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) { true }

    /** Asks Android to let PocketIDE keep running in the background; falls back to the list of apps. */
    fun ask(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { }
        }
    }

    val samsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /** Samsung closes apps on its own list as well; where to take PocketIDE off it. */
    const val SAMSUNG_TIP = "On Samsung phones also open Settings → Battery → Background usage limits and add PocketIDE to \"Never sleeping apps\"."

    /** Why the app was last closed, if that happened after [since] (a run's start), in plain words; null when not known. */
    fun lastExit(ctx: Context, since: Long): String? {
        if (Build.VERSION.SDK_INT < 30 || since <= 0) return null
        val last = try {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()
        } catch (e: Exception) { null } ?: return null
        if (last.timestamp < since) return null
        return reason(last.reason, last.description)
    }

    /** Exit reasons that mean Android (or the phone's battery saver) closed the app, not the person and not a crash. */
    val ANDROID_CLOSED = setOf(ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_FREEZER, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_DEPENDENCY_DIED)

    /**
     * True when the last time the app ended, after [since], Android closed it (1.5.99: such a run is carried on automatically).
     * False when unknown (Android before 11), when the person closed it, or when it crashed.
     */
    fun closedByAndroid(ctx: Context, since: Long): Boolean {
        if (Build.VERSION.SDK_INT < 30 || since <= 0) return false
        val last = try {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getHistoricalProcessExitReasons(null, 0, 1).firstOrNull()
        } catch (e: Exception) { null } ?: return false
        return last.timestamp >= since && last.reason in ANDROID_CLOSED
    }

    /** The words for an exit reason (ApplicationExitInfo.REASON_*). */
    fun reason(code: Int, description: String? = null): String = when (code) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "the phone ran low on memory and Android closed PocketIDE in the background"
        ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> "PocketIDE was closed from recent apps or force-stopped"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android closed PocketIDE for using too much battery or processor in the background"
        ApplicationExitInfo.REASON_FREEZER -> "Android froze PocketIDE in the background"
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "PocketIDE crashed (the crash report was shown when it reopened)"
        ApplicationExitInfo.REASON_ANR -> "PocketIDE stopped responding and Android closed it"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "an app permission was changed, which restarts the app"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED, ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_SIGNALED ->
            "Android or the phone's battery saver closed PocketIDE in the background" + (description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        else -> "Android closed PocketIDE in the background"
    }
}
