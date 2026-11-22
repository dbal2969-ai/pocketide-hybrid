package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.Toast
import dev.pocketide.app.core.CfDeployRun
import dev.pocketide.app.core.CfErrors
import dev.pocketide.app.core.Secrets
import java.io.File

/**
 * Errors from the published site (1.5.125): what visitors' browsers sent to a project published with /deploy cloudflare, newest
 * first, each with "Fix this", which hands the report to the assistant. Reached from the deploy result, /deploy errors and
 * ⋮ → Run and check.
 */
object SiteErrorsScreen {
    private val main = Handler(Looper.getMainLooper())

    fun show(ctx: Context, meta: File, onFix: (String) -> Unit) {
        val info = try { CfErrors.Info.parse(File(meta, CfErrors.INFO_FILE).takeIf { it.isFile }?.readText()) } catch (e: Exception) { null }
        val secrets = try { Secrets(File(meta, "secrets.json")).all() } catch (e: Exception) { emptyMap() }
        val key = secrets[CfErrors.KEY_NAME]
        if (info == null || key == null) {
            message(ctx, "No error reports yet", "Error reports come from a project published with /deploy cloudflare from this version on. Publish it, then come back here.")
            return
        }
        Toast.makeText(ctx, "Asking ${info.url} for its errors…", Toast.LENGTH_SHORT).show()
        Thread {
            val (code, body) = CfDeployRun.siteErrors(info.url, key)
            val listing = if (code == 200) CfErrors.parse(body) else null
            main.post {
                when {
                    listing != null -> list(ctx, meta, info, key, listing, secrets, onFix)
                    code == 0 -> message(ctx, "Could not reach the site", "${info.url} did not answer. Check the connection and try again.")
                    code == 401 -> message(ctx, "The site did not accept the key", "The site was published with another key. Publish again with /deploy cloudflare.")
                    else -> message(ctx, "This site has no error reports", "${info.url} answered HTTP $code without the list: it was published before error reports existed. Publish again with /deploy cloudflare.")
                }
            }
        }.start()
    }

    private fun list(ctx: Context, meta: File, info: CfErrors.Info, key: String, l: CfErrors.Listing, secrets: Map<String, String>, onFix: (String) -> Unit) {
        val col = Ui.vertical(ctx).also { it.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 8)) }
        // said plainly every time: reports in memory do not survive a restart, and each copy of the Worker has its own
        if (l.storage != "kv") col.addView(Ui.text(ctx, "Kept in the Worker's memory only: these are lost when Cloudflare restarts the Worker, and each running copy keeps its own list. " +
            "A token with Workers KV Storage: Edit keeps them (publish again after changing it).", 12f, Ui.MUTED))
        lateinit var dlg: AlertDialog
        if (l.errors.isEmpty()) col.addView(Ui.text(ctx, "No errors reported. Pages send uncaught errors, failed promises, console.error and files that did not load.", 14f, Ui.TEXT))
        for (r in l.errors) {
            col.addView(Ui.text(ctx, Secrets.mask(CfErrors.summary(r, info.url), secrets), 13f, Ui.TEXT, true), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 10) })
            col.addView(Ui.chip(ctx, "Fix this") { dlg.dismiss(); onFix(Secrets.mask(CfErrors.prompt(r, info.url), secrets)) }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
        }
        val b = AlertDialog.Builder(ctx).setTitle("Errors from ${info.url.removePrefix("https://")}").setView(ScrollView(ctx).also { it.addView(col) })
            .setPositiveButton("Refresh") { _, _ -> show(ctx, meta, onFix) }.setNegativeButton("Close", null)
        if (l.errors.isNotEmpty()) b.setNeutralButton("Clear") { _, _ ->
            Thread { val (code, _) = CfDeployRun.siteErrors(info.url, key, "DELETE"); main.post { Toast.makeText(ctx, if (code in 200..299) "Cleared." else "Could not clear (HTTP $code).", Toast.LENGTH_SHORT).show() } }.start()
        }
        dlg = b.create(); dlg.show()
    }

    private fun message(ctx: Context, title: String, text: String) {
        AlertDialog.Builder(ctx).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }
}
