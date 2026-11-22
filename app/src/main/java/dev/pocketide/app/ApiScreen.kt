package dev.pocketide.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.pocketide.app.core.HttpCall
import dev.pocketide.app.core.Secrets
import java.io.File

/**
 * The API tester (1.5.112, HttpCall): method, address, headers and body; Send shows the status, headers and body. {{NAME}} is filled
 * from the project's secrets, which are masked in the answer. Recent requests are kept per project (without secret values).
 */
object ApiScreen {
    fun show(ctx: Context, projectKey: String) {
        val meta = Projects.metaDir(ctx, projectKey)
        val historyFile = File(meta, "api-history.json")
        var history = HttpCall.historyParse(try { historyFile.takeIf { it.isFile }?.readText() } catch (e: Exception) { null })
        fun secrets(): Map<String, String> = try { Secrets(File(meta, "secrets.json")).all() } catch (e: Exception) { emptyMap() }

        val pad = Ui.dp(ctx, 12)
        val box = Ui.vertical(ctx).also { it.setPadding(pad, Ui.dp(ctx, 4), pad, 0) }
        var method = "GET"
        val methodBtn = Ui.button(ctx, method, false) {}
        val url = EditText(ctx).apply { hint = "http://localhost:3000/api/items"; setSingleLine(true); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val row = Ui.horizontal(ctx)
        row.addView(methodBtn, Ui.lp(Ui.WRAP, Ui.WRAP))
        row.addView(url, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        box.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
        val headers = EditText(ctx).apply { hint = "Headers, one per line (Authorization: Bearer {{API_KEY}})"; minLines = 1; maxLines = 4; textSize = 13f; typeface = Typeface.MONOSPACE }
        val body = EditText(ctx).apply { hint = "Body (JSON or text)"; minLines = 2; maxLines = 8; textSize = 13f; typeface = Typeface.MONOSPACE }
        box.addView(headers, Ui.lp(Ui.MATCH, Ui.WRAP)); box.addView(body, Ui.lp(Ui.MATCH, Ui.WRAP))
        val note = Ui.text(ctx, "Use {{NAME}} for a project secret. Servers the project runs on this phone are at localhost.", 11f, Ui.MUTED, false)
        box.addView(note)
        val actions = Ui.horizontal(ctx)
        box.addView(actions, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        val out = TextView(ctx).apply { setTextIsSelectable(true); textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(Ui.TEXT) }
        val sv = ScrollView(ctx).also { it.addView(out) }
        box.addView(sv, LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(ctx, 300)).also { it.topMargin = Ui.dp(ctx, 6) })

        methodBtn.setOnClickListener {
            AlertDialog.Builder(ctx).setItems(HttpCall.METHODS.toTypedArray()) { _, i -> method = HttpCall.METHODS[i]; methodBtn.text = method }.show()
        }
        fun current(): HttpCall.Request? {
            val u = HttpCall.normalize(url.text.toString()) ?: return null
            return HttpCall.Request(method, u, HttpCall.parseHeaders(headers.text.toString()), body.text.toString())
        }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        lateinit var send: TextView
        send = Ui.button(ctx, "Send", true) {
            val r = current() ?: run { out.text = "Type an address, such as http://localhost:3000/ or https://api.example.com/items."; return@button }
            history = HttpCall.remember(history, r)
            try { historyFile.writeText(HttpCall.historyJson(history)) } catch (e: Exception) { }
            out.text = "Sending ${r.method} ${r.url}…"; send.isEnabled = false
            val s = secrets()
            Thread {
                val resp = HttpCall.send(HttpCall.filled(r, s))
                val text = if (resp.code == 0) HttpCall.failure(r, resp) else HttpCall.describe(resp, s)
                main.post { out.text = text; out.setTextColor(if (resp.code in 200..399) Ui.TEXT else Ui.BAD); send.isEnabled = true; sv.scrollTo(0, 0) }
            }.start()
        }
        actions.addView(send, Ui.lp(Ui.WRAP, Ui.WRAP))
        actions.addView(Ui.button(ctx, "Recent", false) {
            if (history.isEmpty()) { Toast.makeText(ctx, "No requests yet.", Toast.LENGTH_SHORT).show(); return@button }
            AlertDialog.Builder(ctx).setTitle("Recent requests").setItems(history.map { it.method + " " + it.url.take(60) }.toTypedArray()) { _, i ->
                val r = history[i]; method = r.method; methodBtn.text = method; url.setText(r.url)
                headers.setText(r.headers.joinToString("\n") { (k, v) -> "$k: $v" }); body.setText(r.body)
            }.show()
        }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
        actions.addView(Ui.button(ctx, "Copy as curl", false) {
            val r = current() ?: return@button
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("curl", HttpCall.curl(r)))
            Toast.makeText(ctx, "Copied (secrets stay as {{NAME}}).", Toast.LENGTH_SHORT).show()
        }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
        history.firstOrNull()?.let { r -> method = r.method; methodBtn.text = method; url.setText(r.url); headers.setText(r.headers.joinToString("\n") { (k, v) -> "$k: $v" }); body.setText(r.body) }

        AlertDialog.Builder(ctx).setTitle("API tester").setView(ScrollView(ctx).also { it.addView(box) }).setNegativeButton("Close", null).show()
    }
}
