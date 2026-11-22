package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.pocketide.app.core.Diff
import dev.pocketide.app.core.DiffOp
import dev.pocketide.app.core.RepoBrowser
import dev.pocketide.app.core.Vcs
import dev.pocketide.app.core.VcsStore
import dev.pocketide.app.core.VcsText

/**
 * The screens for a project's own history (GitHub-style, on the phone): commit with a diff of each file, the commit list,
 * one commit's changes, branches (switch, new, rename, delete, merge), merge conflicts, stashes, tags, a file's history and
 * who changed each line. Work runs in the background; [Host.changed] tells the app which files were rewritten.
 */
object VcsUi {
    class Host(
        val ctx: Context,
        val store: ProjectStore,
        val settings: AppSettings,
        /** Files rewritten by a history action (empty: reload everything). */
        val changed: (List<String>) -> Unit,
        /** Sends a request to the assistant. */
        val askAssistant: (String) -> Unit,
        val openFile: (String) -> Unit,
        /** Runs a shell command in the project (the project's tests, for checks); null where commands cannot run. */
        val runCommand: ((String, (Int, String) -> Unit) -> Unit)? = null,
        /** Saves a file kept outside the project (a release file) to the phone. */
        val saveFile: ((java.io.File) -> Unit)? = null
    ) {
        val vcs: Vcs get() = Vcs(store.root)
    }

    private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    /** Runs [work] off the main thread, then [done] on it with the result or the error message. */
    fun <T> bg(ctx: Context, work: () -> T, done: (T) -> Unit) {
        McpClient.background {
            val r = try { Result.success(work()) } catch (e: Throwable) { Result.failure(e) }
            McpClient.onMain { r.fold(done) { e -> toast(ctx, e.message ?: e.javaClass.simpleName) } }
        }
    }

    private fun pad(v: View, l: Int, t: Int, r: Int, b: Int) = v.setPadding(Ui.dp(v.context, l), Ui.dp(v.context, t), Ui.dp(v.context, r), Ui.dp(v.context, b))

    private fun scrollOf(ctx: Context, v: View): ScrollView = ScrollView(ctx).also { it.addView(v) }

    fun ago(t: Long): String = RepoBrowser.relativeTime(System.currentTimeMillis(), t * 1000)

    /** One-off model call (commit messages, summaries); [done] gets the text or null. */
    fun quickAsk(ctx: Context, settings: AppSettings, system: String, prompt: String, done: (String?) -> Unit) {
        val anth = dev.pocketide.app.core.Providers.byId(settings.provider).kind == dev.pocketide.app.core.ProviderKind.ANTHROPIC
        try {
            ModelClient.startNative(settings, system, listOf(dev.pocketide.app.core.Tools.userText(anth, prompt)), false, object : NativeListener {
                override fun onStatus(text: String) {}
                override fun onDelta(acc: String) {}
                override fun onTurn(turn: dev.pocketide.app.core.ToolTurn) { McpClient.onMain { done(turn.text.trim().ifBlank { null }) } }
                override fun onStopped(partial: String) { McpClient.onMain { done(null) } }
                override fun onError(message: String, needsSettings: Boolean, toolsRejected: Boolean) { McpClient.onMain { toast(ctx, "The model could not answer: " + message.take(120)); done(null) } }
            })
        } catch (e: Exception) { done(null) }
    }

    // ---------------------------------------------------------------- diffs

    fun diffView(ctx: Context, ops: List<DiffOp>?): View {
        val col = Ui.vertical(ctx)
        if (ops == null) { col.addView(Ui.text(ctx, "A binary or very large file: no line-by-line view.", 13f, Ui.MUTED)); return col }
        if (ops.none { it.tag != ' ' }) { col.addView(Ui.text(ctx, "No line changes.", 13f, Ui.MUTED)); return col }
        for (r in Diff.compact(ops).take(1500)) {
            val (prefix, color, bg) = when (r.tag) { '+' -> Triple("+ ", Ui.GOOD, 0x2214A44D); '-' -> Triple("- ", Ui.BAD, 0x22F85149); '~' -> Triple("", Ui.MUTED, 0); else -> Triple("  ", Ui.TEXT, 0) }
            val t = Ui.text(ctx, if (r.tag == '~') "⋯ ${r.skipped} unchanged line${if (r.skipped == 1) "" else "s"}" else prefix + r.text, 11f, color, true)
            if (bg != 0) t.setBackgroundColor(bg)
            col.addView(t)
        }
        return col
    }

    fun showDiff(ctx: Context, title: String, ops: List<DiffOp>?, extra: Pair<String, () -> Unit>? = null) {
        val v = diffView(ctx, ops); pad(v, 12, 8, 12, 8)
        val hs = android.widget.HorizontalScrollView(ctx); hs.addView(v)
        val b = AlertDialog.Builder(ctx).setTitle(title).setView(scrollOf(ctx, hs)).setPositiveButton("Close", null)
        extra?.let { (label, f) -> b.setNeutralButton(label) { _, _ -> f() } }
        b.show()
    }

    // ---------------------------------------------------------------- starting history

    fun startDialog(h: Host, then: () -> Unit) {
        AlertDialog.Builder(h.ctx).setTitle("Start version history?")
            .setMessage("PocketIDE keeps this project's history on your phone, like a GitHub repository: commits, branches, merges and more. " +
                "It is stored in a hidden .git folder (real git format). Build output such as node_modules is left out (.gitignore), and files over 20 MB " +
                "are stored once as large files. Nothing is sent anywhere.")
            .setPositiveButton("Start") { _, _ ->
                bg(h.ctx, { h.vcs.start() }) { toast(h.ctx, "History started."); then() }
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------------------------------------------------------------- commit

    fun commitDialog(h: Host, then: () -> Unit = {}) {
        bg(h.ctx, { val v = h.vcs; val st = v.status(); Triple(st, v.branch(), v.merging()) }) { (changes, branch, merging) ->
            if (changes.isEmpty() && !merging) { toast(h.ctx, "Nothing to commit: no files changed since the last commit."); return@bg }
            val box = Ui.vertical(h.ctx); pad(box, 18, 6, 18, 6)
            box.addView(Ui.text(h.ctx, (if (merging) "Finishing a merge. " else "") + "${changes.size} changed file${if (changes.size == 1) "" else "s"} on ${branch ?: "a detached commit"}. Tap a name to see the change.", 12f, Ui.MUTED))
            val checks = ArrayList<Pair<CheckBox, String>>()
            for (c in changes.take(400)) {
                val row = Ui.horizontal(h.ctx); row.gravity = Gravity.CENTER_VERTICAL
                val cb = CheckBox(h.ctx); cb.isChecked = true; row.addView(cb)
                val color = when (c.kind) { 'A' -> Ui.GOOD; 'D' -> Ui.BAD; else -> Ui.ACCENT }
                val t = Ui.text(h.ctx, "${c.kind}  ${c.path}", 13f, color, true)
                t.setOnClickListener { bg(h.ctx, { h.vcs.diffWorking(c.path) }) { showDiff(h.ctx, c.path, it, "Open" to { h.openFile(c.path) }) } }
                row.addView(t, Ui.lp(0, Ui.WRAP, 1f))
                box.addView(row); checks.add(cb to c.path)
            }
            if (changes.size > 400) box.addView(Ui.text(h.ctx, "…and ${changes.size - 400} more (included).", 12f, Ui.MUTED))
            val msg = EditText(h.ctx); msg.hint = "What changed and why"; msg.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            msg.minLines = 2
            if (merging) msg.setText(h.vcs.mergeMessage())
            box.addView(msg)
            val write = Ui.chip(h.ctx, "✨ Write message") {
                val picked = checks.filter { it.first.isChecked }.map { it.second }
                bg(h.ctx, { describe(h.vcs, picked) }) { summary ->
                    msg.hint = "Writing…"
                    quickAsk(h.ctx, h.settings, "You write git commit messages: a short first line (under 60 characters, imperative mood), then a blank line and one or two plain sentences on why, if useful. Reply with the message only.",
                        "Write the commit message for these changes:\n\n$summary") { t -> msg.hint = "What changed and why"; if (t != null) msg.setText(t.trim().trim('`').trim()) }
                }
            }
            box.addView(write, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(h.ctx, 6) })
            val dlg = AlertDialog.Builder(h.ctx).setTitle(if (merging) "Finish the merge" else "Commit").setView(scrollOf(h.ctx, box))
                .setPositiveButton("Commit", null).setNegativeButton("Cancel", null).create()
            dlg.setOnShowListener {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val picked = checks.filter { it.first.isChecked }.map { it.second } + changes.drop(400).map { it.path }
                    if (picked.isEmpty() && !merging) { toast(h.ctx, "Pick at least one file."); return@setOnClickListener }
                    val message = msg.text.toString().trim()
                    if (message.isEmpty()) { msg.error = "Say what changed"; return@setOnClickListener }
                    dlg.dismiss()
                    guardedCommit(h, message, if (merging) null else picked, Vcs.YOU, then)
                }
            }
            dlg.show()
        }
    }

    /** The files' changes as text for a model (capped). */
    fun describe(v: Vcs, paths: List<String>, cap: Int = 12000): String {
        val sb = StringBuilder()
        for (p in paths) {
            if (sb.length > cap) { sb.append("… and more files\n"); break }
            val ops = v.diffWorking(p)
            sb.append("## ").append(p).append('\n')
            if (ops == null) sb.append("(binary or large file)\n") else for (r in Diff.compact(ops).take(80)) sb.append(when (r.tag) { '~' -> "  … ${r.skipped} unchanged\n"; else -> "${r.tag} ${r.text}\n" })
        }
        return sb.toString().take(cap + 500)
    }

    /** Commits, after checking the files for secrets and the branch rules. */
    fun guardedCommit(h: Host, message: String, paths: List<String>?, author: VcsStore.Person, then: () -> Unit) {
        bg(h.ctx, {
            val v = h.vcs
            val files = (paths ?: v.status().map { it.path }).mapNotNull { p -> h.store.read(p)?.let { p to it } }
            dev.pocketide.app.core.SecurityScan.scan(files).filter { it.level == dev.pocketide.app.core.SecurityScan.Level.HIGH }
        }) { secrets ->
            fun commitNow() = bg(h.ctx, { h.vcs.commit(message, paths, author) }) { id ->
                toast(h.ctx, if (id == null) "Nothing to commit." else "Committed ${id.take(7)}.")
                if (id != null) CollabUi.afterCommit(h, id)
                then()
            }
            fun go() {
                val c = CollabUi.collab(h); val b = h.vcs.branch()
                if (b == null || b !in c.rules.protect || h.vcs.merging()) { commitNow(); return }
                AlertDialog.Builder(h.ctx).setTitle("$b is protected")
                    .setMessage("Changes reach $b through a pull request. Commit on a new branch and open a pull request?")
                    .setPositiveButton("New branch + pull request") { _, _ ->
                        val name = dev.pocketide.app.core.VcsFlow.workBranchName()
                        bg(h.ctx, { val v = h.vcs; v.createBranch(name); v.switchTo(name); v.commit(message, paths, author) }) { id ->
                            if (id == null) { toast(h.ctx, "Nothing to commit."); then(); return@bg }
                            val p = c.openPull(message.lineSequence().first(), message.substringAfter("\n", "").trim(), "", name, b, false)
                            CollabUi.afterCommit(h, id)
                            toast(h.ctx, "Committed on $name and opened pull request #${p.number}.")
                            then()
                        }
                    }.setNegativeButton("Cancel", null).show()
            }
            if (secrets.isEmpty()) go()
            else AlertDialog.Builder(h.ctx).setTitle("This looks like a secret")
                .setMessage("Committing keeps it in the history for good, even if you delete it later:\n\n" + dev.pocketide.app.core.SecurityScan.summary(secrets) +
                    "\n\nMove keys to Project secrets instead, then commit.")
                .setPositiveButton("Commit anyway") { _, _ -> go() }.setNegativeButton("Cancel", null).show()
        }
    }

    // ---------------------------------------------------------------- one commit

    fun commitDetail(h: Host, id: String, then: () -> Unit = {}) {
        bg(h.ctx, { val v = h.vcs; val c = v.store.readCommit(id) ?: throw IllegalStateException("No such commit."); Triple(c, v.changes(id), v.branch()) }) { (c, files, branch) ->
            val box = Ui.vertical(h.ctx); pad(box, 18, 6, 18, 6)
            box.addView(Ui.text(h.ctx, c.message.trim(), 14f, Ui.TEXT).also { it.setTextIsSelectable(true) })
            box.addView(Ui.text(h.ctx, "${c.short} · ${c.author.name} · ${ago(c.author.time)}" + (if (c.parents.size > 1) " · merge" else ""), 12f, Ui.MUTED).also { pad(it, 0, 6, 0, 8) })
            val add = files.sumOf { it.added }; val rem = files.sumOf { it.removed }
            box.addView(Ui.text(h.ctx, "${files.size} file${if (files.size == 1) "" else "s"} changed  +$add −$rem", 12f, Ui.MUTED))
            for (f in files.take(300)) {
                val t = Ui.text(h.ctx, "${f.kind}  ${f.path}   +${f.added} −${f.removed}", 12f, when (f.kind) { 'A' -> Ui.GOOD; 'D' -> Ui.BAD; else -> Ui.ACCENT }, true)
                pad(t, 0, 6, 0, 6)
                t.setOnClickListener { bg(h.ctx, { h.vcs.diff(f.before, f.after) }) { showDiff(h.ctx, "${c.short}: ${f.path}", it, "Restore this file" to {
                    bg(h.ctx, { h.vcs.restoreFile(f.path, c.id) }) { toast(h.ctx, "${f.path} is back as it was in ${c.short} (not committed)."); h.changed(listOf(f.path)); then() }
                }) } }
                box.addView(t)
            }
            val actions = listOf(
                "Undo this commit (revert)" to {
                    bg(h.ctx, { h.vcs.revert(c.id) }) { r -> afterMerge(h, r, then) }
                },
                "Copy onto ${branch ?: "this branch"} (cherry-pick)" to {
                    bg(h.ctx, { h.vcs.cherryPick(c.id) }) { r -> afterMerge(h, r, then) }
                },
                "New branch from here" to { newBranch(h, c.id, then) },
                "Tag or release this version…" to { tagDialog(h, c.id, then) },
                "Put the whole project back to this version" to {
                    AlertDialog.Builder(h.ctx).setTitle("Put the project back to ${c.short}?")
                        .setMessage("Every file becomes the way it was in this commit. Your branch stays where it is, so the difference shows as changes you can commit or throw away.")
                        .setPositiveButton("Put back") { _, _ -> bg(h.ctx, { h.vcs.restoreAll(c.id) }) { toast(h.ctx, "The project now looks like ${c.short}."); h.changed(emptyList()); then() } }
                        .setNegativeButton("Cancel", null).show()
                },
                "Ask the assistant to explain it" to {
                    bg(h.ctx, { commitText(h.vcs, c.id) }) { text -> h.askAssistant("Explain this commit in plain words: what it changed and why it matters. Do not change any files.\n\n$text") }
                }
            )
            val dlg = AlertDialog.Builder(h.ctx).setTitle(c.title.take(70)).setView(scrollOf(h.ctx, box))
                .setPositiveButton("Close", null).setNeutralButton("Actions…") { _, _ ->
                    AlertDialog.Builder(h.ctx).setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }.show()
                }.create()
            dlg.show()
        }
    }

    /** A commit as text for a model: message and capped diff. */
    fun commitText(v: Vcs, id: String, cap: Int = 12000): String {
        val c = v.store.readCommit(id) ?: return ""
        val sb = StringBuilder("Commit ${c.short} by ${c.author.name}: ${c.message.trim()}\n\n")
        for (f in v.changes(id)) {
            if (sb.length > cap) { sb.append("… more files\n"); break }
            sb.append("## ${f.kind} ${f.path}\n")
            val ops = v.diff(f.before, f.after)
            if (ops == null) sb.append("(binary)\n") else for (r in Diff.compact(ops).take(80)) sb.append(if (r.tag == '~') "  … ${r.skipped} unchanged\n" else "${r.tag} ${r.text}\n")
        }
        return sb.toString()
    }

    private fun afterMerge(h: Host, r: Vcs.MergeResult, then: () -> Unit) {
        h.changed(emptyList())
        if (r.conflicts.isEmpty()) { toast(h.ctx, r.message); then(); return }
        AlertDialog.Builder(h.ctx).setTitle("Changed on both sides").setMessage(r.message + "\n\n" + r.conflicts.take(20).joinToString("\n"))
            .setPositiveButton("Resolve") { _, _ -> conflictsDialog(h, then) }.setNegativeButton("Later", null).show()
        then()
    }

    // ---------------------------------------------------------------- branches

    fun branchMenu(h: Host, then: () -> Unit) {
        bg(h.ctx, { val v = h.vcs; v.branches() to v.branch() }) { (branches, cur) ->
            val names = branches.keys.sortedWith(compareBy({ it != cur }, { it }))
            val labels = names.map { (if (it == cur) "● " else "   ") + it } + listOf("＋ New branch…", "Stashed changes…", "Tags…", "History settings…")
            AlertDialog.Builder(h.ctx).setTitle("Branches").setItems(labels.toTypedArray()) { _, i ->
                when {
                    i < names.size -> branchActions(h, names[i], names[i] == cur, then)
                    i == names.size -> newBranch(h, null, then)
                    i == names.size + 1 -> stashes(h, then)
                    i == names.size + 2 -> tags(h, then)
                    else -> settingsDialog(h)
                }
            }.show()
        }
    }

    fun settingsDialog(h: Host) {
        val box = Ui.vertical(h.ctx); pad(box, 20, 8, 20, 0)
        val auto = CheckBox(h.ctx); auto.text = "Commit the assistant's finished work automatically (as \"PocketIDE assistant\")"; auto.setTextColor(Ui.TEXT)
        auto.isChecked = h.settings.historyAutoCommit; box.addView(auto)
        AlertDialog.Builder(h.ctx).setTitle("History settings").setView(box).setPositiveButton("Save") { _, _ -> h.settings.historyAutoCommit = auto.isChecked }
            .setNegativeButton("Cancel", null).show()
    }

    private fun branchActions(h: Host, name: String, current: Boolean, then: () -> Unit) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (!current) items.add("Switch to $name" to { switchTo(h, name, then) })
        if (!current) items.add("Merge $name into ${h.vcs.branch()}" to {
            AlertDialog.Builder(h.ctx).setTitle("Merge $name into ${h.vcs.branch()}?").setMessage("Its commits join this branch. Files changed on both sides are merged line by line; where the same lines differ you choose.")
                .setPositiveButton("Merge") { _, _ -> bg(h.ctx, { h.vcs.merge(name) }) { r -> afterMerge(h, r, then) } }.setNegativeButton("Cancel", null).show()
        })
        items.add("Commits on $name" to { logDialog(h, name, then) })
        items.add("Rename…" to { askName(h, "Rename $name", name) { n -> bg(h.ctx, { h.vcs.renameBranch(name, n) }) { toast(h.ctx, "Renamed to $n."); then() } } })
        if (!current) items.add("Delete" to {
            AlertDialog.Builder(h.ctx).setTitle("Delete branch $name?").setMessage("Commits only on this branch can no longer be reached from a branch (they stay in the history files).")
                .setPositiveButton("Delete") { _, _ -> bg(h.ctx, { h.vcs.deleteBranch(name) }) { toast(h.ctx, "Deleted $name."); then() } }.setNegativeButton("Cancel", null).show()
        })
        AlertDialog.Builder(h.ctx).setTitle(name).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun askName(h: Host, title: String, start: String, done: (String) -> Unit) {
        val e = EditText(h.ctx); e.setText(start); e.setSingleLine(); e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val box = Ui.vertical(h.ctx); pad(box, 20, 8, 20, 0); box.addView(e)
        AlertDialog.Builder(h.ctx).setTitle(title).setView(box).setPositiveButton("OK") { _, _ -> e.text.toString().trim().takeIf { it.isNotEmpty() }?.let(done) }.setNegativeButton("Cancel", null).show()
    }

    fun newBranch(h: Host, at: String?, then: () -> Unit) {
        askName(h, "New branch" + (at?.let { " from ${it.take(7)}" } ?: ""), "") { n ->
            bg(h.ctx, { h.vcs.createBranch(n, at); h.vcs.switchTo(n) }) { msg -> toast(h.ctx, msg); h.changed(emptyList()); then() }
        }
    }

    fun switchTo(h: Host, name: String, then: () -> Unit) {
        bg(h.ctx, { h.vcs.status() }) { dirty ->
            fun go(c: Vcs.Carry) = bg(h.ctx, { h.vcs.switchTo(name, c) }) { msg -> toast(h.ctx, msg); h.changed(emptyList()); then() }
            if (dirty.isEmpty()) { go(Vcs.Carry.CARRY); return@bg }
            AlertDialog.Builder(h.ctx).setTitle("You have ${dirty.size} uncommitted change${if (dirty.size == 1) "" else "s"}")
                .setItems(arrayOf("Bring them to $name", "Stash them (set aside)", "Throw them away", "Commit them first…")) { _, i ->
                    when (i) {
                        0 -> go(Vcs.Carry.CARRY)
                        1 -> go(Vcs.Carry.STASH)
                        2 -> AlertDialog.Builder(h.ctx).setTitle("Throw away ${dirty.size} change(s)?").setMessage("This cannot be undone.")
                            .setPositiveButton("Throw away") { _, _ -> go(Vcs.Carry.DISCARD) }.setNegativeButton("Cancel", null).show()
                        else -> commitDialog(h) { switchTo(h, name, then) }
                    }
                }.show()
        }
    }

    // ---------------------------------------------------------------- conflicts

    fun conflictsDialog(h: Host, then: () -> Unit) {
        bg(h.ctx, { val v = h.vcs; v.unresolved() to v.merging() }) { (files, merging) ->
            val box = Ui.vertical(h.ctx); pad(box, 18, 6, 18, 6)
            if (files.isEmpty()) box.addView(Ui.text(h.ctx, "No conflicts left." + if (merging) " Commit to finish the merge." else " Commit the result.", 13f, Ui.GOOD))
            else box.addView(Ui.text(h.ctx, "These files have lines changed on both sides, marked <<<<<<< yours ======= theirs >>>>>>>. For each, keep one side, ask the assistant, or edit it yourself.", 12f, Ui.MUTED))
            for (p in files) {
                box.addView(Ui.text(h.ctx, p, 14f, Ui.TEXT, true).also { pad(it, 0, 12, 0, 4) })
                val row = Ui.horizontal(h.ctx)
                fun side(ours: Boolean) = bg(h.ctx, { val t = h.store.read(p) ?: ""; h.store.write(p, VcsText.resolve(t, ours)) }) { h.changed(listOf(p)); conflictsDialog(h, then) }
                row.addView(Ui.chip(h.ctx, "Keep yours") { side(true) })
                row.addView(Ui.chip(h.ctx, "Keep theirs") { side(false) }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(h.ctx, 6) })
                row.addView(Ui.chip(h.ctx, "Ask AI") { h.askAssistant("Resolve the merge conflict in $p: it has <<<<<<< / ======= / >>>>>>> markers. Keep what both sides meant where they can live together, otherwise pick the version that fits the rest of the project, remove every marker, and check the file still works. Do not commit.") }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(h.ctx, 6) })
                row.addView(Ui.chip(h.ctx, "Open") { h.openFile(p) }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(h.ctx, 6) })
                box.addView(row)
            }
            val b = AlertDialog.Builder(h.ctx).setTitle(if (merging) "Merge conflicts" else "Conflicts").setView(scrollOf(h.ctx, box)).setNegativeButton("Close", null)
            if (files.isEmpty()) b.setPositiveButton("Commit…") { _, _ -> commitDialog(h, then) }
            if (merging) b.setNeutralButton("Abort merge") { _, _ ->
                AlertDialog.Builder(h.ctx).setTitle("Abort the merge?").setMessage("Every file goes back to how it was before the merge.")
                    .setPositiveButton("Abort") { _, _ -> bg(h.ctx, { h.vcs.abortMerge() }) { toast(h.ctx, "Merge aborted."); h.changed(emptyList()); then() } }.setNegativeButton("Cancel", null).show()
            }
            b.show()
        }
    }

    // ---------------------------------------------------------------- lists

    fun logDialog(h: Host, ref: String?, then: () -> Unit) {
        bg(h.ctx, { h.vcs.log(ref, 300) }) { list ->
            val labels = list.map { "${it.short}  ${it.title.take(60)}\n${it.author.name} · ${ago(it.author.time)}" }
            AlertDialog.Builder(h.ctx).setTitle("Commits" + (ref?.let { " on $it" } ?: "")).setItems(labels.toTypedArray()) { _, i -> commitDetail(h, list[i].id, then) }
                .setPositiveButton("Close", null).show()
        }
    }

    fun stashes(h: Host, then: () -> Unit) {
        bg(h.ctx, { h.vcs.stashes() }) { list ->
            val labels = list.reversed().map { "${it.message}\non ${it.branch} · ${RepoBrowser.relativeTime(System.currentTimeMillis(), it.time)}" }
            val b = AlertDialog.Builder(h.ctx).setTitle("Stashed changes")
            if (list.isEmpty()) b.setMessage("Nothing set aside. When you switch branches you can stash your changes, or use Stash now.")
            else b.setItems(labels.toTypedArray()) { _, i ->
                val s = list.reversed()[i]
                AlertDialog.Builder(h.ctx).setTitle(s.message).setItems(arrayOf("Bring back", "Delete")) { _, k ->
                    if (k == 0) bg(h.ctx, { h.vcs.unstash(s.id) }) { r -> afterMerge(h, r, then) }
                    else bg(h.ctx, { h.vcs.dropStash(s.id) }) { toast(h.ctx, "Deleted."); then() }
                }.show()
            }
            b.setPositiveButton("Stash now") { _, _ -> bg(h.ctx, { h.vcs.stash() }) { s -> toast(h.ctx, if (s == null) "Nothing to stash." else "Set aside."); h.changed(emptyList()); then() } }
            b.setNegativeButton("Close", null).show()
        }
    }

    fun tags(h: Host, then: () -> Unit) {
        bg(h.ctx, { val v = h.vcs; v.tags().map { (n, id) -> Triple(n, id, v.store.readCommit(id)) } }) { list ->
            val labels = list.map { (n, _, c) -> "$n  ·  ${c?.title?.take(50) ?: ""}" }
            val b = AlertDialog.Builder(h.ctx).setTitle("Tags")
            if (list.isEmpty()) b.setMessage("No tags yet. Open a commit → Actions → Tag or release this version.")
            else b.setItems(labels.toTypedArray()) { _, i -> list[i].third?.let { commitDetail(h, it.id, then) } }
            b.setPositiveButton("Close", null).show()
        }
    }

    fun tagDialog(h: Host, at: String, then: () -> Unit) {
        val box = Ui.vertical(h.ctx); pad(box, 20, 8, 20, 0)
        val name = EditText(h.ctx); name.hint = "Name, e.g. v1.0"; name.setSingleLine(); box.addView(name)
        val note = EditText(h.ctx); note.hint = "What is in this version (optional)"; box.addView(note)
        AlertDialog.Builder(h.ctx).setTitle("Tag ${at.take(7)}").setView(box).setPositiveButton("Tag") { _, _ ->
            val n = name.text.toString().trim(); if (n.isEmpty()) return@setPositiveButton
            bg(h.ctx, { h.vcs.tag(n, at, note.text.toString()) }) { toast(h.ctx, "Tagged $n."); then() }
        }.setNegativeButton("Cancel", null).show()
    }

    fun fileHistory(h: Host, path: String) {
        bg(h.ctx, { h.vcs.fileHistory(path) }) { list ->
            if (list.isEmpty()) { toast(h.ctx, "$path has no commits yet."); return@bg }
            val labels = list.map { "${it.short}  ${it.title.take(60)}\n${it.author.name} · ${ago(it.author.time)}" }
            AlertDialog.Builder(h.ctx).setTitle("History of $path").setNeutralButton("Step through versions") { _, _ -> versionSlider(h, path, list.reversed()) }.setItems(labels.toTypedArray()) { _, i ->
                val c = list[i]
                bg(h.ctx, { val v = h.vcs; v.diff(c.parents.firstOrNull()?.let { v.blobAt(it, path) }, v.blobAt(c.id, path)) }) { ops ->
                    showDiff(h.ctx, "${c.short}: $path", ops, "Restore this version" to {
                        bg(h.ctx, { h.vcs.restoreFile(path, c.id) }) { toast(h.ctx, "$path is back as it was in ${c.short} (not committed)."); h.changed(listOf(path)) }
                    })
                }
            }.setPositiveButton("Close", null).show()
        }
    }

    /** A slider over every version of a file, oldest to newest, showing what each one changed. */
    fun versionSlider(h: Host, path: String, versions: List<VcsStore.Commit>) {
        if (versions.isEmpty()) return
        val col = Ui.vertical(h.ctx); pad(col, 16, 6, 16, 6)
        val label = Ui.text(h.ctx, "", 12f, Ui.MUTED); col.addView(label)
        val seek = android.widget.SeekBar(h.ctx); seek.max = versions.size - 1; seek.progress = versions.size - 1; col.addView(seek)
        val holder = Ui.vertical(h.ctx); col.addView(android.widget.HorizontalScrollView(h.ctx).also { it.addView(holder) })
        fun show(k: Int) {
            val c = versions[k]
            label.text = "Version ${k + 1} of ${versions.size} · ${c.short} · ${c.title.take(50)} · ${c.author.name} · ${ago(c.author.time)}"
            bg(h.ctx, { val v = h.vcs; v.diff(c.parents.firstOrNull()?.let { v.blobAt(it, path) }, v.blobAt(c.id, path)) }) { ops -> holder.removeAllViews(); holder.addView(diffView(h.ctx, ops)) }
        }
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, user: Boolean) { if (user) show(p) }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        show(versions.size - 1)
        AlertDialog.Builder(h.ctx).setTitle(path).setView(scrollOf(h.ctx, col)).setPositiveButton("Close", null)
            .setNeutralButton("Restore this version") { _, _ -> val c = versions[seek.progress]; bg(h.ctx, { h.vcs.restoreFile(path, c.id) }) { toast(h.ctx, "$path is back as it was in ${c.short} (not committed)."); h.changed(listOf(path)) } }.show()
    }

    fun blame(h: Host, path: String) {
        bg(h.ctx, { h.vcs.blame(path) }) { rows ->
            if (rows.isEmpty()) { toast(h.ctx, "$path is not in the last commit."); return@bg }
            val col = Ui.vertical(h.ctx); pad(col, 10, 6, 10, 6)
            var last: String? = null
            rows.forEachIndexed { i, (line, c) ->
                val who = if (c == null) "        " else if (c.id == last) "".padEnd(8) else c.short.padEnd(8)
                val t = Ui.text(h.ctx, "${(i + 1).toString().padStart(4)} $who ${line}", 11f, if (c?.author?.email == Vcs.ASSISTANT.email) Ui.ACCENT else Ui.TEXT, true)
                if (c != null && c.id != last) t.setOnClickListener { commitDetail(h, c.id) }
                last = c?.id
                col.addView(t)
            }
            val hs = android.widget.HorizontalScrollView(h.ctx); hs.addView(col)
            AlertDialog.Builder(h.ctx).setTitle("Who changed each line: $path").setView(scrollOf(h.ctx, hs))
                .setMessage("Blue lines came from the assistant. Tap a commit id to open it.").setPositiveButton("Close", null).show()
        }
    }
}
