package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import dev.pocketide.app.core.Checkpoints
import dev.pocketide.app.core.DocEdit
import java.io.File

/**
 * The ✎ Edit menu for documents: pick an exact action (add a heading, replace text, set a cell, add a slide…), fill in a small form,
 * and the file is changed without any AI. The old file is saved as a checkpoint first, so ⋯ → Checkpoints can put it back.
 */
object DocEditDialog {
    private val main = Handler(Looper.getMainLooper())
    private val BIG = setOf("text", "rows", "values", "cells", "body")

    fun show(ctx: Context, store: ProjectStore, path: String, onChanged: () -> Unit) {
        val actions = DocEdit.actions(path)
        if (actions.isEmpty()) { Toast.makeText(ctx, "This file type has no edit actions.", Toast.LENGTH_SHORT).show(); return }
        val labels = actions.map { label(it.name) + (if (it.help.isNotEmpty()) " — " + it.help else "") }.toTypedArray()
        AlertDialog.Builder(ctx).setTitle("Edit " + path.substringAfterLast('/')).setItems(labels) { _, i -> form(ctx, store, path, actions[i], onChanged) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun label(name: String) = name.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun form(ctx: Context, store: ProjectStore, path: String, action: DocEdit.Action, onChanged: () -> Unit) {
        if (action.params.isEmpty()) { run(ctx, store, path, action, emptyMap(), onChanged); return }
        val box = Ui.vertical(ctx)
        box.setPadding(Ui.dp(ctx, 18), Ui.dp(ctx, 8), Ui.dp(ctx, 18), 0)
        val fields = LinkedHashMap<String, EditText>()
        val needsNumbers = action.params.any { it.name in setOf("index", "row", "slide", "cell", "position", "to", "column") }
        if (needsNumbers) {
            val outline = Ui.text(ctx, "", 11f, Ui.TEXT, true)
            val sv = ScrollView(ctx)
            sv.addView(outline)
            sv.visibility = android.view.View.GONE
            val chip = Ui.chip(ctx, "Show numbers") {
                if (sv.visibility == android.view.View.GONE) {
                    outline.text = "Reading…"
                    sv.visibility = android.view.View.VISIBLE
                    Thread {
                        val bytes = store.readBytes(path)
                        val msg = if (bytes == null) "Could not read the file." else DocEdit.apply(path, bytes, "outline", emptyMap()).message
                        main.post { outline.text = msg }
                    }.start()
                } else sv.visibility = android.view.View.GONE
            }
            box.addView(chip, Ui.lp(Ui.WRAP, Ui.WRAP))
            box.addView(sv, Ui.lp(Ui.MATCH, Ui.dp(ctx, 160)))
        }
        for (p in action.params) {
            box.addView(Ui.text(ctx, p.name.replaceFirstChar { it.uppercase() } + if (p.required) "" else " (optional)", 12f, Ui.MUTED).also { it.setPadding(0, Ui.dp(ctx, 10), 0, 0) })
            if (p.help.isNotEmpty()) box.addView(Ui.text(ctx, p.help, 11f, Ui.MUTED))
            val et = EditText(ctx)
            et.setTextColor(Ui.TEXT)
            et.setHintTextColor(Ui.MUTED)
            et.textSize = 14f
            if (p.name in BIG) { et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; et.minLines = 2; et.maxLines = 6 }
            else { et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; et.setSingleLine() }
            fields[p.name] = et
            box.addView(et, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        val sv = ScrollView(ctx)
        sv.addView(box)
        AlertDialog.Builder(ctx).setTitle(label(action.name)).setView(sv)
            .setPositiveButton("Apply") { _, _ -> run(ctx, store, path, action, fields.mapValues { it.value.text.toString() }.filter { it.value.isNotEmpty() }, onChanged) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun run(ctx: Context, store: ProjectStore, path: String, action: DocEdit.Action, args: Map<String, String>, onChanged: () -> Unit) {
        Thread {
            val before = store.readBytes(path)
            val res = if (before == null) null else DocEdit.apply(path, before, action.name, args)
            main.post {
                when {
                    res == null -> Toast.makeText(ctx, "Could not read $path.", Toast.LENGTH_LONG).show()
                    !res.ok -> AlertDialog.Builder(ctx).setTitle("Not applied").setMessage(res.message).setPositiveButton("OK", null).show()
                    action.name == "outline" -> {
                        val t = Ui.text(ctx, res.message, 11f, Ui.TEXT, true)
                        t.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 8))
                        t.setTextIsSelectable(true)
                        val sv = ScrollView(ctx); sv.addView(t)
                        AlertDialog.Builder(ctx).setTitle("Numbers in " + path.substringAfterLast('/')).setView(sv).setPositiveButton("Close", null).show()
                    }
                    else -> {
                        try { Checkpoints(File(Projects.metaDir(ctx), "checkpoints")).save("Edited $path (${label(action.name)})", mapOf(path to before)) } catch (e: Exception) { /* undo point is best effort */ }
                        if (store.writeBytes(path, res.bytes!!)) {
                            Toast.makeText(ctx, res.message + " (Undo: ⋯ → Checkpoints in the AI tab.)", Toast.LENGTH_LONG).show()
                            onChanged()
                        } else Toast.makeText(ctx, "Could not write $path.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }
}
