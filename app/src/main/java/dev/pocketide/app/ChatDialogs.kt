package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import dev.pocketide.app.core.ChatSession
import dev.pocketide.app.core.Checkpoint
import dev.pocketide.app.core.Providers
import java.text.DateFormat
import java.util.Date

// v0.4 W2: dialogs used by the AI tab — saved chats, checkpoints, quick model switch.
object ChatDialogs {
    private fun ago(t: Long): String {
        val s = (System.currentTimeMillis() - t) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(t))
        }
    }

    fun prompt(ctx: Context, title: String, initial: String, ok: String, onOk: (String) -> Unit) {
        val box = FrameLayout(ctx)
        box.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), 0)
        val et = EditText(ctx)
        et.setText(initial)
        et.setSingleLine()
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        et.setSelection(initial.length)
        box.addView(et)
        AlertDialog.Builder(ctx).setTitle(title).setView(box)
            .setPositiveButton(ok) { _, _ -> onOk(et.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Saved chats, newest first. Tap to open; long-press to rename or delete. */
    fun chats(ctx: Context, chats: List<ChatSession>, currentId: String, onOpen: (ChatSession) -> Unit, onNew: () -> Unit,
              onRename: (ChatSession, String) -> Unit, onDelete: (ChatSession) -> Unit, onExportAll: (() -> Unit)? = null) {
        val labels = chats.map { c ->
            val n = c.entries.count { it.role == "user" && it.note != "auto" }
            (if (c.id == currentId) "● " else "") + c.title + "\n" + ago(c.updated) + " · " + n + " message" + (if (n == 1) "" else "s")
        }.toTypedArray()
        val b = AlertDialog.Builder(ctx).setTitle("Chats in this project")
            .setNeutralButton("New chat") { _, _ -> onNew() }
            .setNegativeButton("Close", null)
        if (chats.isEmpty()) b.setMessage("No saved chats yet.") else b.setItems(labels) { _, i -> onOpen(chats[i]) }
        if (onExportAll != null && chats.isNotEmpty()) b.setPositiveButton("Export all") { _, _ -> onExportAll() }
        val dlg = b.create()
        dlg.show()
        dlg.listView?.setOnItemLongClickListener { _, _, i, _ ->
            dlg.dismiss()
            val c = chats[i]
            AlertDialog.Builder(ctx).setTitle(c.title).setItems(arrayOf("Open", "Rename", "Delete")) { _, k ->
                when (k) {
                    0 -> onOpen(c)
                    1 -> prompt(ctx, "Rename chat", c.title, "Rename") { if (it.isNotEmpty()) onRename(c, it) }
                    else -> AlertDialog.Builder(ctx).setTitle("Delete \"${c.title}\"?").setMessage("The chat is removed. Files are not changed.")
                        .setPositiveButton("Delete") { _, _ -> onDelete(c) }.setNegativeButton("Cancel", null).show()
                }
            }.show()
            true
        }
    }

    /** Checkpoints, newest first. Restoring one puts the project back to before that turn or step. */
    /** [onRestore] gets the checkpoint and true when the conversation should be rewound too (Claude Code's /rewind: code, chat or both). */
    fun checkpoints(ctx: Context, list: List<Checkpoint>, onRestore: (Checkpoint, Boolean) -> Unit) {
        if (list.isEmpty()) {
            AlertDialog.Builder(ctx).setTitle("Checkpoints")
                .setMessage("No checkpoints yet. One is saved automatically before each AI turn or plan step that changes files.")
                .setPositiveButton("Close", null).show()
            return
        }
        val labels = list.map { c ->
            c.label.take(70) + "\n" + ago(c.time) + " · " + c.paths.size + " file" + (if (c.paths.size == 1) "" else "s") + ": " + c.paths.take(3).joinToString(", ") +
                (if (c.paths.size > 3) "…" else "")
        }.toTypedArray()
        AlertDialog.Builder(ctx).setTitle("Restore to before…").setItems(labels) { _, i ->
            val c = list[i]
            val newer = list.indexOf(c)
            AlertDialog.Builder(ctx).setTitle("Restore the project?")
                .setMessage("Files changed by \"${c.label.take(60)}\"" + (if (newer > 0) " and the $newer later turn${if (newer == 1) "" else "s"}" else "") +
                    " go back to how they were before. Files created since then are deleted. Changes made by commands are not tracked." +
                    "\n\nA checkpoint of the current state is saved first, so this can be undone.")
                .setPositiveButton(if (c.chatMark != null) "Files only" else "Restore") { _, _ -> onRestore(c, false) }
                .also { b -> if (c.chatMark != null) b.setNeutralButton("Files and chat") { _, _ -> onRestore(c, true) } }
                .setNegativeButton("Cancel", null).show()
        }.setNegativeButton("Close", null).show()
    }

    /** Choose the cheap and strong profiles for smart routing, and switch it on or off. */
    fun routing(ctx: Context, settings: AppSettings, onChanged: () -> Unit) {
        val profiles = dev.pocketide.app.core.Profiles.parse(settings.profilesJson)
        fun pickProfile(title: String, set: (String) -> Unit) {
            if (profiles.size < 1) { AlertDialog.Builder(ctx).setMessage("Save at least one profile first: Model chip → Save current as a profile.").setPositiveButton("OK", null).show(); return }
            val names = listOf("(none: keep the current model)") + profiles.map { it.name }
            AlertDialog.Builder(ctx).setTitle(title).setItems(names.toTypedArray()) { _, i -> set(if (i == 0) "" else profiles[i - 1].name); onChanged(); routing(ctx, settings, onChanged) }.show()
        }
        val rows = listOf<Pair<String, () -> Unit>>(
            "Smart routing is ${if (settings.routeOn) "ON" else "OFF"}. Tap to switch." to { settings.routeOn = !settings.routeOn; onChanged(); routing(ctx, settings, onChanged) },
            "Cheap profile (questions, small edits): ${settings.routeCheap.ifEmpty { "none" }}" to { pickProfile("Cheap profile") { settings.routeCheap = it } },
            "Strong profile (builds, many files, documents, bugs): ${settings.routeStrong.ifEmpty { "none" }}" to { pickProfile("Strong profile") { settings.routeStrong = it } }
        )
        AlertDialog.Builder(ctx).setTitle("Smart routing")
            .setItems(rows.map { it.first }.toTypedArray()) { _, i -> rows[i].second() }
            .setNegativeButton("Close", null).show()
    }

    /**
     * Quick switch. 1.5.210 (the owner's review of the judges' build): Hybrid AI comes first and its row opens its own screen (it has
     * no model list or name to type); then saved profiles, then providers, each saying whether its key is added; then the tools
     * (search, save a profile, smart routing); the demo last. Before, every provider without a key said "(no key)", which reads as
     * "needs no key", and "Save current (Hybrid AI · ) as a profile" had an empty model.
     */
    fun model(ctx: Context, settings: AppSettings, onChanged: () -> Unit) {
        val profiles = dev.pocketide.app.core.Profiles.parse(settings.profilesJson)
        val ps = Providers.all
        val rows = ArrayList<Pair<String, () -> Unit>>()
        val hybrid = ps.firstOrNull { it.id == "hybrid" }?.takeIf { HybridAi.configured(ctx) }
        if (hybrid != null) rows.add(((if (settings.provider == "hybrid") "● " else "") + "⚡ Hybrid AI: Gemini Nano on this phone + Gemini in the cloud (no key needed)") to {
            settings.provider = "hybrid"; onChanged()
            (ctx as? MainActivity)?.let { HybridScreen.show(it) }
        })
        for (pr in profiles) rows.add("★ ${pr.name}  ·  ${pr.model.ifBlank { Providers.byId(pr.provider).name }}" to {
            settings.provider = pr.provider
            if (pr.model.isNotBlank()) settings.setModel(pr.provider, pr.model)
            if (pr.base.isNotBlank()) settings.setBaseFor(pr.provider, pr.base)
            onChanged()
        })
        for (p in ps) {
            if (p.id == "demo" || p.id == "hybrid") continue
            val m = settings.model(p.id).ifBlank { p.model }
            val keyNote = if (p.keyless) "  (no key needed)" else if (settings.key(p.id).isBlank()) "  (add a key first)" else "  (key added)"
            val label = (if (p.id == settings.provider) "● " else "") + p.name + (if (m.isNotEmpty()) "  ·  $m" else "") + keyNote
            rows.add(label to {
                settings.provider = p.id
                val d = android.app.AlertDialog.Builder(ctx).setTitle("Model for ${p.name}")
                val items = arrayOf("Pick from ${p.name}'s list", "Type a name")
                d.setItems(items) { _, w ->
                    if (w == 0) ModelPicker.pick(ctx, p, settings.key(p.id), settings.baseFor(p.id), settings.model(p.id)) { picked -> settings.setModel(p.id, picked); onChanged() }
                    else prompt(ctx, "Model for ${p.name}", settings.model(p.id).ifBlank { p.model }, "Use") { t -> settings.setModel(p.id, t); onChanged() }
                }.setNegativeButton("Cancel") { _, _ -> onChanged() }.show()
            })
        }
        rows.add("🔎 Search all providers (name or kind)…" to {
            ModelPicker.searchAll(ctx, settings) { p, m -> settings.provider = p.id; settings.setModel(p.id, m); onChanged() }
        })
        val cur = Providers.byId(settings.provider)
        if (settings.provider != "demo" && settings.provider != "hybrid") rows.add("＋ Save current (${cur.name} · ${settings.model(cur.id).ifBlank { cur.model }}) as a profile…" to {
            prompt(ctx, "Profile name (for example Cheap fast, Smart slow)", "", "Save") { n ->
                if (n.isNotBlank()) {
                    val m = settings.model(cur.id).ifBlank { cur.model }
                    val b = if (cur.custom || cur.defaultBase.isNotEmpty()) settings.baseFor(cur.id) else ""
                    settings.profilesJson = dev.pocketide.app.core.Profiles.write(dev.pocketide.app.core.Profiles.upsert(profiles, dev.pocketide.app.core.ModelProfile(n.trim(), cur.id, m, b)))
                    onChanged()
                }
            }
        })
        rows.add("⇄ Smart routing: ${if (settings.routeOn) "on" else "off"}…" to { routing(ctx, settings, onChanged) })
        if (profiles.isNotEmpty()) rows.add("✕ Delete a profile…" to {
            AlertDialog.Builder(ctx).setTitle("Delete which profile?").setItems(profiles.map { it.name }.toTypedArray()) { _, i ->
                settings.profilesJson = dev.pocketide.app.core.Profiles.write(dev.pocketide.app.core.Profiles.remove(profiles, profiles[i].name))
                onChanged()
            }.setNegativeButton("Close", null).show()
        })
        ps.firstOrNull { it.id == "demo" }?.let { d -> rows.add(((if (settings.provider == "demo") "● " else "") + "Demo: sample replies, no AI") to { settings.provider = "demo"; onChanged() }) }
        AlertDialog.Builder(ctx).setTitle("Switch model").setItems(rows.map { it.first }.toTypedArray()) { _, i -> rows[i].second() }
            .setNegativeButton("Close", null).show()
    }
}
