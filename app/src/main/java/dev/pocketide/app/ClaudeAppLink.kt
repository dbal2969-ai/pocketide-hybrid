package dev.pocketide.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import dev.pocketide.app.core.ClaudeLink

/** Opens the Claude app, and shares a chat into it through the Android share sheet. */
object ClaudeAppLink {
    fun installed(ctx: Context): Boolean = try { ctx.packageManager.getPackageInfo(ClaudeLink.PACKAGE, 0); true } catch (e: Exception) { false }

    fun open(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ClaudeLink.PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(ClaudeLink.WEB))
        launch(ctx, intent)
    }

    fun send(ctx: Context, title: String, markdown: String) {
        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, dev.pocketide.app.core.SecretMask.mask(ClaudeLink.shareBody(title, markdown)))
        if (installed(ctx)) { share.setPackage(ClaudeLink.PACKAGE); launch(ctx, share) }
        else launch(ctx, Intent.createChooser(share, "Send to…"))
    }

    private fun launch(ctx: Context, intent: Intent) {
        try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { Toast.makeText(ctx, "No app here can open that.", Toast.LENGTH_LONG).show() }
    }
}
