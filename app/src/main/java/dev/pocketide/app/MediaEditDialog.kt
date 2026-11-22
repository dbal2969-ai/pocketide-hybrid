package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import dev.pocketide.app.core.MediaOps

/** The ✎ menu for pictures and PDFs: pick an action, fill in the numbers, and the result is written as a new file. */
object MediaEditDialog {
    private val main = Handler(Looper.getMainLooper())

    fun show(ctx: Context, store: ProjectStore, path: String, onChanged: (List<String>) -> Unit) {
        val actions = MediaOps.actions(path)
        if (actions.isEmpty()) { Toast.makeText(ctx, "This file has no picture actions.", Toast.LENGTH_SHORT).show(); return }
        val labels = actions.map { it.label + " — " + it.help + (if (it.lossy) " (redraws the pages)" else "") }.toTypedArray()
        AlertDialog.Builder(ctx).setTitle("Edit " + path.substringAfterLast('/'))
            .setItems(labels) { _, i -> form(ctx, store, path, actions[i], onChanged) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun form(ctx: Context, store: ProjectStore, path: String, action: MediaOps.Action, onChanged: (List<String>) -> Unit) {
        if (action.params.isEmpty()) { run(ctx, store, path, action, emptyMap(), onChanged); return }
        val box = Ui.vertical(ctx)
        box.setPadding(Ui.dp(ctx, 18), Ui.dp(ctx, 8), Ui.dp(ctx, 18), 0)
        if (action.lossy) box.addView(Ui.text(ctx, MediaOps.PDF_NOTE, 11f, Ui.MUTED, false))
        val fields = LinkedHashMap<String, EditText>()
        for (p in action.params) {
            box.addView(Ui.text(ctx, p.name.replaceFirstChar { it.uppercase() } + (if (p.required) "" else " (optional)"), 12f, Ui.MUTED)
                .also { it.setPadding(0, Ui.dp(ctx, 10), 0, 0) })
            if (p.help.isNotEmpty()) box.addView(Ui.text(ctx, p.help, 11f, Ui.MUTED))
            val et = EditText(ctx)
            et.setTextColor(Ui.TEXT); et.setHintTextColor(Ui.MUTED); et.textSize = 14f
            et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            et.setSingleLine()
            fields[p.name] = et
            box.addView(et, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        val sv = ScrollView(ctx); sv.addView(box)
        AlertDialog.Builder(ctx).setTitle(action.label).setView(sv)
            .setPositiveButton("Apply") { _, _ -> run(ctx, store, path, action, fields.mapValues { it.value.text.toString() }.filter { it.value.isNotEmpty() }, onChanged) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun run(ctx: Context, store: ProjectStore, path: String, action: MediaOps.Action, args: Map<String, String>, onChanged: (List<String>) -> Unit) {
        val busy = Toast.makeText(ctx, "Working…", Toast.LENGTH_SHORT)
        busy.show()
        Thread {
            val r = try { MediaEdit.apply(store, path, action.id, args) } catch (e: Throwable) { e }
            main.post {
                when (r) {
                    is MediaEdit.Result -> {
                        if (r.written.isNotEmpty()) onChanged(r.written)
                        AlertDialog.Builder(ctx).setTitle(action.label).setMessage(r.message)
                            .setPositiveButton("OK", null).show()
                    }
                    is MediaEdit.Failed -> AlertDialog.Builder(ctx).setTitle("Not done").setMessage(r.message ?: "It did not work.").setPositiveButton("OK", null).show()
                    else -> AlertDialog.Builder(ctx).setTitle("Not done")
                        .setMessage((r as? Throwable)?.message ?: "It did not work.").setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }
}
