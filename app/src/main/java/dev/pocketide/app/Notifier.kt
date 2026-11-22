package dev.pocketide.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** One-line notifications for finished builds and for the assistant. Silently does nothing when notifications are not allowed. */
object Notifier {
    private const val CHANNEL = "builds"

    fun buildFinished(ctx: Context, id: Long, text0: String) {
        // 1.5.150 (M1): a notification shows on the lock screen; keys and tokens in the text (an error that quotes one) are masked here
        val text = dev.pocketide.app.core.SecretMask.mask(text0)
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Finished builds", NotificationManager.IMPORTANCE_DEFAULT))
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            val pi = intent?.let { android.app.PendingIntent.getActivity(ctx, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE) }
            b.setSmallIcon(android.R.drawable.stat_notify_sync_noanim).setContentTitle("PocketIDE build").setContentText(text).setAutoCancel(true)
            if (pi != null) b.setContentIntent(pi)
            nm.notify((id % Int.MAX_VALUE).toInt(), b.build())
        } catch (e: Exception) { }
    }

    private const val ASSISTANT = "assistant"
    private const val ASSISTANT_ID = 4242

    /** True while a PocketIDE screen is in front; assistant notifications are only sent when it is not. */
    @Volatile var visible = true
    private var tracking = false

    fun track(act: android.app.Activity) {
        if (tracking) return
        tracking = true
        act.application.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) { visible = true }
            override fun onActivityPaused(a: android.app.Activity) { visible = false }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }

    /** Android 13+ asks before an app may notify. Asked once, the first time the person sends the assistant a job. */
    fun askOnce(act: android.app.Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (act.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val sp = act.getSharedPreferences("notify", Context.MODE_PRIVATE)
        if (sp.getBoolean("asked", false)) return
        sp.edit().putBoolean("asked", true).apply()
        try { act.requestPermissions(arrayOf(perm), 7310) } catch (e: Exception) { }
    }

    /** "The assistant finished", "needs your OK", "paused at the spending limit": only while PocketIDE is in the background. */
    fun assistant(ctx: Context, text0: String) {
        val text = dev.pocketide.app.core.SecretMask.mask(text0)   // 1.5.150 (M1), as in buildFinished
        if (visible) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ASSISTANT) == null)
                nm.createNotificationChannel(NotificationChannel(ASSISTANT, "Assistant needs you or finished", NotificationManager.IMPORTANCE_DEFAULT))
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, ASSISTANT) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.also { it.flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT }
            val pi = intent?.let { android.app.PendingIntent.getActivity(ctx, 1, it, android.app.PendingIntent.FLAG_IMMUTABLE) }
            b.setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("PocketIDE assistant").setContentText(text.take(200)).setAutoCancel(true)
            if (Build.VERSION.SDK_INT >= 16) b.setStyle(Notification.BigTextStyle().bigText(text.take(600)))
            if (pi != null) b.setContentIntent(pi)
            nm.notify(ASSISTANT_ID, b.build())
        } catch (e: Exception) { }
    }
}
