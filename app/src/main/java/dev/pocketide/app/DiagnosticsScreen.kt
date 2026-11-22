package dev.pocketide.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.pocketide.app.core.ModelPick
import dev.pocketide.app.core.Providers
import dev.pocketide.app.core.SelfCheck
import java.io.File

/** Runs checks on the phone and shows a report that can be copied, so problems can be diagnosed without a computer. */
object DiagnosticsScreen {
    fun show(ctx: Context, settings: AppSettings, store: ProjectStore) {
        val tv = TextView(ctx).apply { setTextIsSelectable(true); textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE; setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 8)) }
        val sv = ScrollView(ctx).apply { addView(tv) }
        val report = StringBuilder()
        fun line(s: String) { report.append(s).append('\n'); tv.text = report.toString() }
        val v = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" }
        line("PocketIDE $v (${ctx.packageName})")
        line("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
        val p = Providers.byId(settings.provider)
        val model = settings.model(p.id).ifBlank { p.model }
        line("Provider: ${p.name}  Model: ${model.ifBlank { "(none)" }}  Key: ${if (settings.key(p.id).isBlank()) "none" else "set"}  Small requests: ${settings.tight(p.id)}")
        line("")
        val main = Handler(Looper.getMainLooper())
        Thread {
            val tmp = "diagnostics-test.txt"
            val wrote = store.write(tmp, "ok ${System.currentTimeMillis()}")
            val read = store.read(tmp)
            store.delete(tmp)
            main.post { line((if (wrote && read != null) "✓ " else "✗ ") + "Project files: write and read") }
            val ev = dev.pocketide.app.core.Eval.all()
            main.post { line(""); line("Assistant decision checks:"); line(dev.pocketide.app.core.Eval.format(ev)) }
            val res = SelfCheck.run()
            main.post { line("Document editors:"); line(SelfCheck.format(res)) }
            val crash = try { File(ctx.filesDir, "last-crash.txt").takeIf { it.isFile }?.readText()?.take(1500) } catch (e: Exception) { null }
            main.post { val lg = dev.pocketide.app.core.AppLog.format(); line(""); line(if (lg.isEmpty()) "✓ No problems logged since the app started" else "Recent problems:\n$lg") }
            main.post { line(""); line(if (crash == null) "✓ No crash saved" else "Last crash:\n$crash") }
        }.start()
        if (p.id != "demo") {
            line("Asking ${p.name} for its models…")
            val t0 = System.currentTimeMillis()
            ModelPicker.fetch(p, settings.key(p.id), settings.baseFor(p.id)) { list, err ->
                val ms = System.currentTimeMillis() - t0
                if (list == null) line("✗ Provider: $err")
                else {
                    line("✓ Provider answered in $ms ms with ${list.size} models")
                    if (model.isNotBlank()) {
                        if (list.contains(model)) line("✓ Model '$model' exists")
                        else line("✗ Model '$model' is not in the list. Closest: " + ModelPick.closest(model, list).joinToString(", ").ifEmpty { "none" })
                    }
                }
            }
        }
        AlertDialog.Builder(ctx).setTitle("Diagnostics").setView(sv)
            .setPositiveButton("Copy report") { _, _ ->
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("report", report.toString()))
                Toast.makeText(ctx, "Report copied", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Close", null).show()
    }
}
