package dev.pocketide.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.pocketide.app.core.SupaSync
import java.io.File

/** Sync chats, model profiles and project backups through the user's own free Supabase project. */
object SyncScreen {
    fun show(ctx: Context, settings: AppSettings, store: ProjectStore, onChanged: () -> Unit) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 8)) }
        box.addView(Ui.text(ctx, "Sync uses your own free Supabase project, so your data stays in your account. " +
            "1) Create a project at supabase.com. 2) Tap Copy setup SQL and run it in SQL Editor. 3) Paste the project address and the anon public key (Project settings → API). 4) Create an account, then sync. " +
            "API keys are not synced.", 12f, Ui.MUTED, false))
        fun field(hint: String, value: String, pw: Boolean = false) = EditText(ctx).apply {
            this.hint = hint; setText(value); setSingleLine(true)
            if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }.also { box.addView(it, Ui.lp(Ui.MATCH, Ui.WRAP)) }
        val url = field("Project address, https://xxxx.supabase.co", settings.supaUrl)
        val anon = field("anon public key", settings.supaAnon)
        val email = field("Email", settings.supaEmail)
        val pass = field("Password (not saved)", "", true)
        val out = Ui.text(ctx, if (settings.supaRefresh.isBlank()) "Not signed in." else "Signed in as ${settings.supaEmail}.", 13f, Ui.TEXT, false)
        box.addView(out, Ui.lp(Ui.MATCH, Ui.WRAP))
        val main = Handler(Looper.getMainLooper())
        fun save() { settings.supaUrl = url.text.toString(); settings.supaAnon = anon.text.toString() }
        fun work(label: String, block: (SupaClient) -> String) {
            save(); out.text = "$label…"; out.setTextColor(Ui.MUTED)
            Thread {
                val r = try { block(SupaClient(settings)) to true } catch (e: SupaClient.Fail) { (e.message ?: "Failed") to false }
                    catch (e: Exception) { ("Could not connect: ${e.message ?: e.javaClass.simpleName}") to false }
                main.post { out.text = r.first; out.setTextColor(if (r.second) Ui.GOOD else Ui.BAD); if (r.second) onChanged() }
            }.start()
        }
        fun row(vararg b: Pair<String, () -> Unit>) {
            val h = Ui.horizontal(ctx)
            for ((t, a) in b) h.addView(Ui.button(ctx, t) { a() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
            box.addView(h, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        }
        row("Copy setup SQL" to {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("sql", SupaSync.setupSql()))
            Toast.makeText(ctx, "SQL copied. Paste it in Supabase → SQL Editor and run it.", Toast.LENGTH_LONG).show()
        })
        row("Sign in" to { work("Signing in") { it.signIn(email.text.toString(), pass.text.toString(), false) } },
            "Create account" to { work("Creating account") { it.signIn(email.text.toString(), pass.text.toString(), true) } })
        row("Sync chats & profiles" to { work("Syncing") { it.syncChats(File(Projects.metaDir(ctx), "chats").also { d -> d.mkdirs() }) } })
        row("Back up this project" to { work("Backing up") { it.backup(Projects.key(ctx), store) } },
            "Restore a backup" to {
                save(); out.text = "Looking for backups…"
                Thread {
                    val r = try { SupaClient(settings).backups() to "" } catch (e: Exception) { null to (e.message ?: "Failed") }
                    main.post {
                        val items = r.first
                        if (items == null) { out.text = r.second; out.setTextColor(Ui.BAD); return@post }
                        if (items.isEmpty()) { out.text = "No backups yet."; return@post }
                        out.text = "${items.size} backup(s)."
                        AlertDialog.Builder(ctx).setTitle("Restore which backup?")
                            .setItems(items.map { it.name }.toTypedArray()) { _, i -> work("Restoring") { c -> c.restore(items[i].name, store) } }
                            .setNegativeButton("Close", null).show()
                    }
                }.start()
            })
        AlertDialog.Builder(ctx).setTitle("Sync (Supabase)").setView(ScrollView(ctx).also { it.addView(box) })
            .setNegativeButton("Close") { _, _ -> save() }.setOnDismissListener { save() }.show()
    }
}
