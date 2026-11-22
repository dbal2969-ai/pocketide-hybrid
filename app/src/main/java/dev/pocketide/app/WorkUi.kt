package dev.pocketide.app

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.pocketide.app.core.Collab
import java.io.File

/**
 * Planning screens, GitHub-style: issues (labels, comments, assign to the assistant, which works on a branch and opens the pull
 * request that closes the issue), the project board, #12 links that open issues and pull requests, Mermaid diagrams, and saving
 * a project as a template.
 */
object WorkUi {
    private fun toast(h: VcsUi.Host, s: String) = Toast.makeText(h.ctx, s, Toast.LENGTH_LONG).show()
    private fun pad(v: View, l: Int, t: Int, r: Int, b: Int) = v.setPadding(Ui.dp(v.context, l), Ui.dp(v.context, t), Ui.dp(v.context, r), Ui.dp(v.context, b))
    private fun scrollOf(v: View) = ScrollView(v.context).also { it.addView(v) }

    // ---------------------------------------------------------------- #12 links

    /** Makes "#12" in [tv] open issue or pull request 12. */
    fun linkRefs(h: VcsUi.Host, tv: TextView, then: () -> Unit = {}) {
        val text = tv.text.toString()
        if (!Regex("""#\d""").containsMatchIn(text)) return
        val c = CollabUi.collab(h)
        val span = SpannableString(text)
        for (m in Regex("""(?<![\w&])#(\d{1,5})\b""").findAll(text)) {
            val n = m.groupValues[1].toInt()
            if (c.issue(n) == null && c.pull(n) == null) continue
            span.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { if (c.pull(n) != null) CollabUi.pullDetail(h, n, then) else issueDetail(h, n, then) }
            }, m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = span; tv.movementMethod = LinkMovementMethod.getInstance()
    }

    // ---------------------------------------------------------------- Mermaid diagrams

    fun showDiagram(ctx: android.content.Context, code: String) {
        val web = android.webkit.WebView(ctx)
        web.settings.javaScriptEnabled = true
        val esc = code.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<script src='https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js'></script></head>" +
            "<body style='background:#fff;margin:8px'><pre class='mermaid'>$esc</pre>" +
            "<p id='off' style='display:none;font:13px sans-serif;color:#555'>The diagram library could not load (offline?). The diagram's text is above.</p>" +
            "<script>if(window.mermaid){mermaid.initialize({startOnLoad:true,theme:'default'})}else{document.getElementById('off').style.display='block'}</script></body></html>"
        web.loadDataWithBaseURL("https://localhost/", html, "text/html", "utf-8", null)
        AlertDialog.Builder(ctx).setTitle("Diagram").setView(web).setPositiveButton("Close", null).show()
    }

    // ---------------------------------------------------------------- issues

    fun issuesTab(h: VcsUi.Host, body: LinearLayout, line: (LinearLayout, Boolean, String, Int, String, String, () -> Unit) -> Unit, card: () -> LinearLayout, refresh: () -> Unit, filter: String?) {
        val top = Ui.horizontal(h.ctx); pad(top, 0, 10, 0, 0)
        top.addView(Ui.button(h.ctx, "New issue", true) { newIssue(h, refresh) })
        val c = CollabUi.collab(h)
        val labels = (Collab.LABELS + c.issues.flatMap { it.labels }).distinct()
        body.addView(top)
        val chips = Ui.horizontal(h.ctx); pad(chips, 0, 8, 0, 0)
        for (l in listOf("all") + labels) chips.addView(Ui.chip(h.ctx, if ((filter ?: "all") == l) "● $l" else l) { lastFilter = if (l == "all") null else l; refresh() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(h.ctx, 6) })
        body.addView(android.widget.HorizontalScrollView(h.ctx).also { it.isHorizontalScrollBarEnabled = false; it.addView(chips) })
        for ((title, list) in listOf("Open" to c.issues.filter { it.state == Collab.OPEN }, "Closed" to c.issues.filter { it.state != Collab.OPEN }.takeLast(40))) {
            val shown = list.filter { filter == null || filter in it.labels }
            body.addView(Ui.text(h.ctx, "${title.uppercase()} · ${shown.size}", 12f, Ui.MUTED).also { it.typeface = Typeface.DEFAULT_BOLD; pad(it, 2, 16, 2, 0) })
            val box = card()
            if (shown.isEmpty()) box.addView(Ui.text(h.ctx, if (title == "Open") "Nothing open. Issues are your to-do list: bugs, ideas, features. Assign one to the assistant and it works on a branch and opens a pull request that closes it." else "None.", 13f, Ui.MUTED).also { pad(it, 14, 14, 14, 14) })
            for ((i, x) in shown.sortedByDescending { it.updated }.withIndex())
                line(box, i == 0, if (x.state == Collab.OPEN) "◉" else "✓", if (x.state == Collab.OPEN) Ui.GOOD else 0xFFA371F7.toInt(), "#${x.number} ${x.title}",
                    (if (x.labels.isNotEmpty()) x.labels.joinToString(" ") { "[$it]" } + "  ·  " else "") + c.column(x) +
                        (if (x.assistant) "  ·  assistant" else "") + (x.pull?.let { "  ·  PR #$it" } ?: "") + "  ·  " + VcsUi.ago(x.updated / 1000)) { issueDetail(h, x.number, refresh) }
        }
    }

    /** The label filter the Issues tab shows. */
    @Volatile var lastFilter: String? = null

    fun newIssue(h: VcsUi.Host, then: () -> Unit, title0: String = "", body0: String = "", labels0: List<String> = emptyList()) {
        val box = Ui.vertical(h.ctx); pad(box, 18, 6, 18, 6)
        val title = EditText(h.ctx); title.hint = "Title"; title.setText(title0); box.addView(title)
        val body = EditText(h.ctx); body.hint = "Details: what happens, what should happen, steps"; body.minLines = 3; body.setText(body0)
        body.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; box.addView(body)
        val boxes = Collab.LABELS.map { l -> CheckBox(h.ctx).also { it.text = l; it.setTextColor(Ui.TEXT); it.isChecked = l in labels0; box.addView(it) } }
        AlertDialog.Builder(h.ctx).setTitle("New issue").setView(scrollOf(box)).setPositiveButton("Open issue") { _, _ ->
            if (title.text.isBlank()) { toast(h, "Give it a title."); return@setPositiveButton }
            val i = CollabUi.collab(h).openIssue(title.text.toString(), body.text.toString(), boxes.filter { it.isChecked }.map { it.text.toString() })
            toast(h, "Opened #${i.number}."); then()
        }.setNegativeButton("Cancel", null).show()
    }

    fun issueDetail(h: VcsUi.Host, n: Int, then: () -> Unit) {
        val c = CollabUi.collab(h); val i = c.issue(n) ?: return
        val box = Ui.vertical(h.ctx); pad(box, 18, 6, 18, 10)
        box.addView(Ui.text(h.ctx, "${i.state.uppercase()} · ${c.column(i)}" + (if (i.labels.isNotEmpty()) " · " + i.labels.joinToString(", ") else "") +
            (if (i.assistant) " · assigned to the assistant on ${i.branch}" else "") + " · opened ${VcsUi.ago(i.created / 1000)}", 12f, Ui.MUTED))
        if (i.body.isNotBlank()) box.addView(Ui.text(h.ctx, i.body.trim(), 14f, Ui.TEXT).also { pad(it, 0, 8, 0, 4); it.setTextIsSelectable(true); linkRefs(h, it, then) })
        i.pull?.let { p -> box.addView(Ui.chip(h.ctx, "Pull request #$p" + (c.pull(p)?.state?.let { " ($it)" } ?: "")) { CollabUi.pullDetail(h, p, then) }) }
        for (cm in i.comments) box.addView(Ui.text(h.ctx, "${cm.by} · ${VcsUi.ago(cm.time / 1000)}\n${cm.body.trim()}", 13f, Ui.TEXT).also { pad(it, 0, 10, 0, 0); linkRefs(h, it, then) })
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (i.state == Collab.OPEN) {
            if (!i.assistant) actions.add("Assign to the assistant" to { assign(h, i.number, then) })
            else actions.add("Ask the assistant to continue" to { h.askAssistant("Continue working on issue #${i.number}: ${i.title}\n\n${i.body}") })
            actions.add("Move on the board…" to {
                val cols = listOf("To do", "In progress", "Done")
                AlertDialog.Builder(h.ctx).setItems(cols.toTypedArray()) { _, k -> val cc = CollabUi.collab(h); cc.issue(n)?.let { it.column = cols[k]; if (cols[k] == "Done") it.state = Collab.CLOSED; it.updated = System.currentTimeMillis() }; cc.save(); then() }.show()
            })
            actions.add("Close" to { val cc = CollabUi.collab(h); cc.issue(n)?.let { it.state = Collab.CLOSED; it.updated = System.currentTimeMillis() }; cc.save(); then() })
        } else actions.add("Reopen" to { val cc = CollabUi.collab(h); cc.issue(n)?.let { it.state = Collab.OPEN; it.column = ""; it.updated = System.currentTimeMillis() }; cc.save(); then() })
        actions.add("Comment" to {
            val e = EditText(h.ctx); e.minLines = 2; val b = Ui.vertical(h.ctx); pad(b, 20, 8, 20, 0); b.addView(e)
            AlertDialog.Builder(h.ctx).setTitle("Comment on #$n").setView(b).setPositiveButton("Send") { _, _ ->
                val cc = CollabUi.collab(h); cc.issue(n)?.let { it.comments.add(Collab.Comment("you", e.text.toString(), System.currentTimeMillis())); it.updated = System.currentTimeMillis() }; cc.save(); then(); issueDetail(h, n, then)
            }.setNegativeButton("Cancel", null).show()
        })
        actions.add("Labels…" to {
            val all = (Collab.LABELS + i.labels).distinct(); val sel = BooleanArray(all.size) { all[it] in i.labels }
            AlertDialog.Builder(h.ctx).setMultiChoiceItems(all.toTypedArray(), sel) { _, k, on -> sel[k] = on }.setPositiveButton("Save") { _, _ ->
                val cc = CollabUi.collab(h); cc.issue(n)?.let { it.labels.clear(); it.labels.addAll(all.filterIndexed { k, _ -> sel[k] }) }; cc.save(); then()
            }.show()
        })
        AlertDialog.Builder(h.ctx).setTitle("#${i.number} ${i.title}".take(80)).setView(scrollOf(box)).setPositiveButton("Close", null)
            .setNeutralButton("Actions…") { _, _ -> AlertDialog.Builder(h.ctx).setItems(actions.map { it.first }.toTypedArray()) { _, k -> actions[k].second() }.show() }.show()
    }

    /**
     * Gives an issue to the assistant: a branch is made for it (from the current branch), the assistant gets the issue as its
     * request, and its committed work opens the pull request that closes the issue.
     */
    fun assign(h: VcsUi.Host, n: Int, then: () -> Unit) {
        VcsUi.bg(h.ctx, {
            val v = h.vcs; if (!v.enabled()) v.start()
            require(v.status().isEmpty()) { "Commit or stash your changes first, so the assistant starts from a clean branch." }
            val c = CollabUi.collab(h); val i = c.issue(n)!!
            val base = v.branch() ?: "main"
            val name = c.issueBranch(i).let { b -> if (b in v.branches()) b + "-" + (System.currentTimeMillis() / 1000 % 10000) else b }
            v.createBranch(name); v.switchTo(name)
            i.assistant = true; i.branch = name; i.base = base; i.column = "In progress"; i.updated = System.currentTimeMillis(); c.save()
            Triple(i.title, i.body, name to base)
        }) { (title, body, names) ->
            h.changed(emptyList()); then()
            h.askAssistant("Work on issue #$n: $title\n\n$body\n\nYou are on the branch ${names.first} (made from ${names.second}). Do the work and check it. " +
                "When you finish, your work is committed and a pull request that closes #$n is opened for review.")
        }
    }

    // ---------------------------------------------------------------- the board

    fun boardTab(h: VcsUi.Host, body: LinearLayout, card: () -> LinearLayout, refresh: () -> Unit) {
        val c = CollabUi.collab(h)
        body.addView(Ui.text(h.ctx, "Issues by stage. Tap a card to open it; Actions → Move on the board to change its column.", 12f, Ui.MUTED).also { pad(it, 2, 10, 2, 0) })
        for (col in listOf("To do", "In progress", "Done")) {
            val items = c.issues.filter { c.column(it) == col }.sortedByDescending { it.updated }.let { if (col == "Done") it.take(20) else it }
            body.addView(Ui.text(h.ctx, "${col.uppercase()} · ${items.size}", 12f, Ui.MUTED).also { it.typeface = Typeface.DEFAULT_BOLD; pad(it, 2, 16, 2, 0) })
            val box = card(); pad(box, 10, 6, 10, 6)
            if (items.isEmpty()) box.addView(Ui.text(h.ctx, "—", 13f, Ui.MUTED).also { pad(it, 6, 6, 6, 6) })
            for (x in items) {
                val t = Ui.text(h.ctx, "#${x.number} ${x.title}" + (if (x.labels.isNotEmpty()) "\n" + x.labels.joinToString(" ") { "[$it]" } else "") +
                    (x.pull?.let { p -> "\nPR #$p " + (c.pull(p)?.state ?: "") } ?: "") + (if (x.assistant && x.state == Collab.OPEN) "\nthe assistant is on it" else ""), 13f, Ui.TEXT)
                t.background = Ui.rounded(Ui.BG, 8, h.ctx, Ui.LINE); pad(t, 10, 8, 10, 8)
                t.setOnClickListener { issueDetail(h, x.number, refresh) }
                box.addView(t, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(h.ctx, 6) })
            }
        }
    }

    // ---------------------------------------------------------------- templates

    fun templatesDir(ctx: android.content.Context) = File(ctx.filesDir, "templates").also { it.mkdirs() }

    /** The person's own templates (projects saved as templates). */
    fun userTemplates(ctx: android.content.Context): List<String> = templatesDir(ctx).listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    /** Saves a project's files (not its history) as a template for New project. */
    fun saveAsTemplate(ctx: android.content.Context, project: String, done: (String?) -> Unit) {
        val src = File(Projects.root(ctx), project)
        val dest = File(templatesDir(ctx), project)
        McpClient.background {
            val ok = try {
                dest.deleteRecursively()
                src.walkTopDown().onEnter { it == src || !(it.name == ".git" || it.name == "node_modules" || it.name == "build") }.filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(src).path; f.copyTo(File(dest, rel), true)
                }
                true
            } catch (e: Exception) { false }
            McpClient.onMain { done(if (ok) dest.name else null) }
        }
    }

    /** Fills a new project from a saved template. */
    fun fillFromTemplate(ctx: android.content.Context, template: String, project: String): Boolean = try {
        File(templatesDir(ctx), template).copyRecursively(File(Projects.root(ctx), project), overwrite = true)
    } catch (e: Exception) { false }
}
