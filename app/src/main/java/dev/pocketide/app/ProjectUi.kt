package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.pocketide.app.core.CodeCheck
import dev.pocketide.app.core.CrossSearch
import dev.pocketide.app.core.Deps
import dev.pocketide.app.core.GitBridge
import dev.pocketide.app.core.Insights
import dev.pocketide.app.core.ProjectSetup
import dev.pocketide.app.core.Secrets
import dev.pocketide.app.core.Snippets
import dev.pocketide.app.core.Vcs
import java.io.File

/**
 * A project's settings and insights, GitHub-style, plus what spans projects: secrets, the setup file, dependency updates, the
 * whole-project code check, history to and from GitHub, archive; insights (contributions, code frequency, hot files, branch
 * graph, activity); search across every project; snippets; and activity across projects.
 */
object ProjectUi {
    private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
    private fun pad(v: View, l: Int, t: Int, r: Int, b: Int) = v.setPadding(Ui.dp(v.context, l), Ui.dp(v.context, t), Ui.dp(v.context, r), Ui.dp(v.context, b))
    private fun scrollOf(v: View) = ScrollView(v.context).also { it.addView(v) }
    private fun title(ctx: Context, body: LinearLayout, t: String) = body.addView(Ui.text(ctx, t.uppercase(), 12f, Ui.MUTED).also { it.typeface = Typeface.DEFAULT_BOLD; pad(it, 2, 18, 2, 4) })

    fun meta(h: VcsUi.Host): File = Projects.metaDir(h.ctx, h.store.root.name)
    fun secrets(h: VcsUi.Host) = Secrets(File(meta(h), "secrets.json"))

    // ---------------------------------------------------------------- the Settings tab

    fun settingsTab(h: VcsUi.Host, body: LinearLayout, card: () -> LinearLayout, refresh: () -> Unit) {
        fun row(c: LinearLayout, label: String, sub: String, f: () -> Unit) {
            val col = Ui.vertical(h.ctx); pad(col, 14, 10, 14, 10)
            col.addView(Ui.text(h.ctx, label, 14f, Ui.TEXT)); if (sub.isNotEmpty()) col.addView(Ui.text(h.ctx, sub, 11f, Ui.MUTED))
            col.setOnClickListener { f() }; c.addView(col)
        }
        val v = h.vcs
        title(h.ctx, body, "History and branches")
        val a = card()
        if (!v.enabled()) row(a, "Start version history", "Commits, branches, pull requests and more for this project") { VcsUi.startDialog(h, refresh) }
        else {
            row(a, "Branch rules", "Protect branches, require checks or reviews, where the assistant works") { CollabUi.rulesDialog(h) }
            row(a, "History settings", "Commit the assistant's work automatically") { VcsUi.settingsDialog(h) }
            row(a, "Fork this project", "Your own copy with its history, linked back to this one") { CollabUi.fork(h, refresh) }
            if (v.upstream() != null) row(a, "Sync fork", "Bring in ${v.upstream()!!.name}'s new commits") { CollabUi.syncFork(h, refresh) }
            row(a, "History on GitHub", "Send this history to the linked GitHub repository, or bring its history here") { githubHistory(h, refresh) }
        }
        title(h.ctx, body, "Code and security")
        val b = card()
        row(b, "Secrets", "API keys for commands and servers, never in files, history or the assistant's view (${secrets(h).all().size} set)") { secretsDialog(h) }
        row(b, "Check the whole project", "Secrets, broken JSON, conflict markers, unfinished code: findings become issues") { codeCheck(h, refresh) }
        row(b, "Dependency updates", "Newer versions and known vulnerabilities in package.json and requirements.txt") { depsCheck(h, refresh) }
        title(h.ctx, body, "Setup")
        val c = card()
        val setup = ProjectSetup.parse(h.store.read(ProjectSetup.FILE))
        row(c, "Setup file", if (setup == null) "None: add ${ProjectSetup.FILE} to install what this project needs when it is opened" else "Packages: ${setup.packages.joinToString().ifEmpty { "none" }} · steps: ${setup.run.size}") { setupDialog(h) }
        title(h.ctx, body, "Project")
        val d = card()
        val archived = h.store.archived
        row(d, if (archived) "Unarchive" else "Archive", if (archived) "Make the project editable again" else "Read-only and moved to the end of the Projects list") {
            val f = File(meta(h), "archived"); if (archived) f.delete() else f.writeText("1"); toast(h.ctx, if (archived) "Unarchived." else "Archived: the project is read-only."); refresh()
        }
        val pinned = File(meta(h), "pinned").isFile
        row(d, if (pinned) "Unpin" else "Pin", "Pinned projects come first on the Projects screen") { val f = File(meta(h), "pinned"); if (pinned) f.delete() else f.writeText("1"); refresh() }
    }

    fun secretsDialog(h: VcsUi.Host) {
        val s = secrets(h); val all = s.all()
        val labels = all.keys.map { "🔑 $it" } + "＋ Add a secret"
        AlertDialog.Builder(h.ctx).setTitle("Secrets").setItems(labels.toTypedArray()) { _, i ->
            if (i == all.size) {
                val box = Ui.vertical(h.ctx); pad(box, 20, 8, 20, 0)
                val n = EditText(h.ctx); n.hint = "Name, e.g. OPENAI_API_KEY"; n.setSingleLine(); box.addView(n)
                val val0 = EditText(h.ctx); val0.hint = "Value"; val0.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; box.addView(val0)
                box.addView(Ui.text(h.ctx, "Commands and servers get it as an environment variable. It is never written to the project, never committed, and masked in output; the assistant only learns its name.", 11f, Ui.MUTED))
                AlertDialog.Builder(h.ctx).setTitle("Add a secret").setView(box).setPositiveButton("Save") { _, _ ->
                    try { s.set(n.text.toString().trim(), val0.text.toString()); toast(h.ctx, "Saved.") } catch (e: Exception) { toast(h.ctx, e.message ?: "Could not save.") }
                }.setNegativeButton("Cancel", null).show()
            } else {
                val name = all.keys.toList()[i]
                AlertDialog.Builder(h.ctx).setTitle(name).setMessage("Value hidden.").setPositiveButton("Delete") { _, _ -> s.remove(name); toast(h.ctx, "Deleted.") }.setNegativeButton("Close", null).show()
            }
        }.show()
    }

    fun setupDialog(h: VcsUi.Host) {
        val text = h.store.read(ProjectSetup.FILE)
        val s = ProjectSetup.parse(text)
        if (s == null) {
            AlertDialog.Builder(h.ctx).setTitle("Setup file").setMessage("${ProjectSetup.FILE} lists the Linux packages and steps this project needs. When it is opened (or forked) on a phone with Linux set up, PocketIDE offers to run it once. Make one from what the project uses?")
                .setPositiveButton("Make it") { _, _ ->
                    val tools = dev.pocketide.app.core.RunTargets.detect(h.store.list(), null, { p -> h.store.read(p) }).mapNotNull { it.toolId }.toSet()
                    h.store.write(ProjectSetup.FILE, ProjectSetup.draft(tools)); h.changed(listOf(ProjectSetup.FILE)); h.openFile(ProjectSetup.FILE)
                }.setNegativeButton("Cancel", null).show()
            return
        }
        AlertDialog.Builder(h.ctx).setTitle("Setup file").setMessage((if (s.note.isNotBlank()) s.note + "\n\n" else "") + "Packages: ${s.packages.joinToString().ifEmpty { "none" }}\nSteps:\n" + s.run.joinToString("\n") { "• $it" }.ifEmpty { "none" })
            .setPositiveButton("Run it now") { _, _ -> runSetup(h, text!!) }.setNeutralButton("Edit") { _, _ -> h.openFile(ProjectSetup.FILE) }.setNegativeButton("Close", null).show()
    }

    fun runSetup(h: VcsUi.Host, text: String) {
        val s = ProjectSetup.parse(text) ?: return
        val run = h.runCommand ?: run { toast(h.ctx, "Commands cannot run here."); return }
        toast(h.ctx, "Setting up… (Terminal tab)")
        run(ProjectSetup.command(s)) { code, out -> McpClient.onMain {
            if (code == 0) { File(meta(h), "setup-stamp").writeText(ProjectSetup.stamp(text)); toast(h.ctx, "Set up.") }
            else AlertDialog.Builder(h.ctx).setTitle("Setup failed (exit $code)").setMessage(out.takeLast(2000)).setPositiveButton("OK", null).show()
        } }
    }

    /** Offered once when a project with a new or changed setup file is opened. */
    fun offerSetup(h: VcsUi.Host) {
        val text = h.store.read(ProjectSetup.FILE) ?: return
        val s = ProjectSetup.parse(text) ?: return
        val stamp = File(meta(h), "setup-stamp")
        if (stamp.isFile && stamp.readText() == ProjectSetup.stamp(text)) return
        stamp.writeText(ProjectSetup.stamp(text))   // asked once per version of the file
        AlertDialog.Builder(h.ctx).setTitle("Set up ${h.store.root.name}?")
            .setMessage("Its setup file asks for:\nPackages: ${s.packages.joinToString().ifEmpty { "none" }}\nSteps:\n" + s.run.joinToString("\n") { "• $it" }.ifEmpty { "none" })
            .setPositiveButton("Set up") { _, _ -> runSetup(h, text) }.setNegativeButton("Not now", null).show()
    }

    // ---------------------------------------------------------------- code check, dependencies, GitHub

    fun codeCheck(h: VcsUi.Host, then: () -> Unit) {
        toast(h.ctx, "Checking every file…")
        VcsUi.bg(h.ctx, {
            val files = h.store.list().filter { !it.startsWith(".") && (h.store.resolve(it)?.length() ?: 0) < 1_000_000 }.take(4000)
                .mapNotNull { p -> h.store.read(p)?.takeIf { t -> !t.contains('\u0000') }?.let { p to it } }
            val found = CodeCheck.run(files)
            val c = CollabUi.collab(h); var opened = 0
            for (f in found) if (c.issues.none { it.state == dev.pocketide.app.core.Collab.OPEN && it.title == f.title }) { c.openIssue(f.title, f.detail, listOf("code check")); opened++ }
            found.size to opened
        }) { (n, opened) -> toast(h.ctx, if (n == 0) "No problems found." else "$n finding(s); $opened new issue(s) labelled \"code check\" (Issues tab)."); then() }
    }

    private fun http(url: String, body: String? = null): String? = try {
        val c = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000; c.setRequestProperty("Accept", "application/json")
        if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
    } catch (e: Exception) { null }

    fun depsCheck(h: VcsUi.Host, then: () -> Unit) {
        val deps = Deps.find(h.store.list()) { p -> h.store.read(p) }
        if (deps.isEmpty()) { toast(h.ctx, "No package.json or requirements.txt dependencies found."); return }
        toast(h.ctx, "Checking ${deps.size} dependencies…")
        VcsUi.bg(h.ctx, { Deps.check(deps, { http(it) }, { u, b -> http(u, b) }) }) { reports ->
            val todo = reports.filter { it.outdated || it.vulns.isNotEmpty() }
            val text = reports.joinToString("\n") { r -> (if (r.vulns.isNotEmpty()) "⚠ " else if (r.outdated) "↑ " else "✓ ") + "${r.dep.name} ${r.dep.wanted.ifEmpty { "(any)" }}" +
                (r.latest?.let { if (r.outdated) " → $it" else "" } ?: " (could not look up)") + (if (r.vulns.isNotEmpty()) "  vulnerabilities: " + r.vulns.take(4).joinToString() else "") }
            val b = AlertDialog.Builder(h.ctx).setTitle(if (todo.isEmpty()) "All up to date" else "${todo.size} to update").setMessage(text).setNegativeButton("Close", null)
            if (todo.isNotEmpty()) b.setPositiveButton("Give it to the assistant") { _, _ ->
                // an issue assigned to the assistant: its own branch, and a pull request that closes the issue when merged
                val i = CollabUi.collab(h).openIssue("Update ${todo.size} dependenc${if (todo.size == 1) "y" else "ies"}", Deps.request(todo), listOf("feature"))
                then(); WorkUi.assign(h, i.number, then)
            }
            b.show()
        }
    }

    fun githubHistory(h: VcsUi.Host, then: () -> Unit) {
        val repo = dev.pocketide.app.core.GitRepo.parse(h.settings.ghRepo)?.identity
        if (repo == null || h.settings.ghToken.isBlank()) { toast(h.ctx, "Link the project to a GitHub repository with a token first (Git button)."); return }
        val branch = h.settings.ghBranch.ifBlank { "main" }
        AlertDialog.Builder(h.ctx).setTitle("History on GitHub ($repo)").setItems(arrayOf("Send this history to GitHub ($branch)", "Bring GitHub's history of $branch here")) { _, i ->
            toast(h.ctx, "Working… (a big history takes a while)")
            VcsUi.bg(h.ctx, {
                val v = h.vcs; val br = GitBridge(dev.pocketide.app.core.GitHubHttp(h.settings.ghToken), repo, v)
                if (i == 0) { if (!v.enabled()) v.start(); "Sent ${br.exportHistory(branch)} commit(s) to $repo." }
                else {
                    val n = br.importHistory(branch, 200, if (v.enabled() && v.branch() == branch) "github-$branch" else branch)
                    "Brought $n commit(s) from $repo." + if (v.branch() == branch) " They are on github-$branch: merge it into $branch (⑂ menu)." else ""
                }
            }) { msg -> toast(h.ctx, msg); then() }
        }.setNegativeButton("Cancel", null).show()
    }

    // ---------------------------------------------------------------- the Insights tab

    fun insightsTab(h: VcsUi.Host, body: LinearLayout, card: () -> LinearLayout) {
        val v = h.vcs
        if (!v.enabled()) { body.addView(Ui.text(h.ctx, "Insights come from the project's history: start it in the History tab.", 13f, Ui.MUTED).also { pad(it, 2, 14, 2, 0) }); return }
        val wait = Ui.text(h.ctx, "Reading the history…", 13f, Ui.MUTED); pad(wait, 2, 14, 2, 0); body.addView(wait)
        VcsUi.bg(h.ctx, {
            val all = v.log(null, 1000); val recent = all.take(200)
            val labels = HashMap<String, MutableList<String>>()
            v.branches().forEach { (b, id) -> labels.getOrPut(id) { ArrayList() }.add(b) }
            v.tags().forEach { (t, id) -> (v.store.readCommit(id)?.id ?: id).let { labels.getOrPut(it) { ArrayList() }.add("tag $t") } }
            arrayOf(Insights.grid(Insights.perDay(all)), Insights.codeFrequency(v, recent).takeLast(12), Insights.hotFiles(v, recent), Insights.graph(all.take(60), labels), all)
        }) { r ->
            body.removeView(wait)
            @Suppress("UNCHECKED_CAST") val grid = r[0] as List<IntArray>
            @Suppress("UNCHECKED_CAST") val freq = r[1] as List<Insights.Week>
            @Suppress("UNCHECKED_CAST") val hot = r[2] as List<Triple<String, Int, Int>>
            @Suppress("UNCHECKED_CAST") val graph = r[3] as List<String>
            @Suppress("UNCHECKED_CAST") val all = r[4] as List<dev.pocketide.app.core.VcsStore.Commit>
            title(h.ctx, body, "Contributions · ${all.size} commits")
            body.addView(contributionGrid(h.ctx, grid))
            val ai = all.count { it.author.email == Vcs.ASSISTANT.email }
            body.addView(Ui.text(h.ctx, "$ai by the assistant, ${all.size - ai} by you. Last 16 weeks, a column per week.", 11f, Ui.MUTED))
            title(h.ctx, body, "Code frequency (lines per week)")
            val f = card(); pad(f, 12, 8, 12, 8)
            val max = (freq.maxOfOrNull { maxOf(it.added, it.removed) } ?: 1).coerceAtLeast(1)
            for (w in freq) f.addView(Ui.text(h.ctx, "${w.start.substring(5)} " + "+".repeat((w.added * 20 / max).coerceAtLeast(if (w.added > 0) 1 else 0)).padEnd(20) + " −" + "-".repeat(w.removed * 20 / max) + "  +${w.added} −${w.removed}", 11f, Ui.TEXT, true))
            if (freq.isEmpty()) f.addView(Ui.text(h.ctx, "No changes yet.", 12f, Ui.MUTED))
            title(h.ctx, body, "Most changed files")
            val hc = card(); pad(hc, 12, 8, 12, 8)
            for ((p, n, byAi) in hot) hc.addView(Ui.text(h.ctx, "$n× $p" + if (byAi > 0) "  ($byAi by the assistant)" else "", 12f, if (byAi * 2 > n && n >= 4) Ui.BAD else Ui.TEXT, true).also { t -> t.setOnClickListener { VcsUi.fileHistory(h, p) } })
            if (hot.any { it.third * 2 > it.second && it.second >= 4 }) hc.addView(Ui.text(h.ctx, "Red: the assistant keeps rewriting these. It may be going in circles there.", 11f, Ui.MUTED))
            title(h.ctx, body, "Branches (newest first)")
            val gc = card(); pad(gc, 12, 8, 12, 8)
            val hs = android.widget.HorizontalScrollView(h.ctx); val col = Ui.vertical(h.ctx)
            for ((k, line) in graph.withIndex()) col.addView(Ui.text(h.ctx, line, 11f, Ui.TEXT, true).also { t -> t.setOnClickListener { VcsUi.commitDetail(h, all[k].id) } })
            hs.addView(col); gc.addView(hs)
            title(h.ctx, body, "Activity")
            val act = card(); pad(act, 12, 8, 12, 8)
            val c = CollabUi.collab(h)
            val events = ArrayList<Pair<Long, String>>()
            all.take(30).forEach { events.add(it.author.time * 1000 to (if (it.parents.size > 1) "⑃ " else "● ") + it.title.take(60) + " · " + (if (it.author.email == Vcs.ASSISTANT.email) "assistant" else it.author.name)) }
            c.pulls.forEach { events.add(it.updated to "⇄ #${it.number} ${it.title.take(50)} · ${it.state}") }
            c.issues.forEach { events.add(it.updated to "◉ #${it.number} ${it.title.take(50)} · ${it.state}") }
            c.releases.forEach { events.add(it.time to "🏷 ${it.tag} released") }
            for ((t, s) in events.sortedByDescending { it.first }.take(40)) act.addView(Ui.text(h.ctx, "$s · ${VcsUi.ago(t / 1000)}", 12f, Ui.TEXT))
        }
    }

    fun contributionGrid(ctx: Context, grid: List<IntArray>): View {
        val row = Ui.horizontal(ctx); pad(row, 2, 6, 2, 6)
        val max = (grid.maxOfOrNull { it.maxOrNull() ?: 0 } ?: 0).coerceAtLeast(1)
        val size = Ui.dp(ctx, 14); val gap = Ui.dp(ctx, 3)
        for (week in grid) {
            val col = Ui.vertical(ctx)
            for (n in week) {
                val cell = View(ctx)
                val level = if (n == 0) 0 else 1 + (n * 3 / max).coerceAtMost(3)
                cell.background = Ui.rounded(when (level) { 0 -> Ui.SURFACE; 1 -> 0xFF0E4429.toInt(); 2 -> 0xFF006D32.toInt(); 3 -> 0xFF26A641.toInt(); else -> 0xFF39D353.toInt() }, 3, ctx, Ui.LINE)
                col.addView(cell, LinearLayout.LayoutParams(size, size).also { it.bottomMargin = gap })
            }
            row.addView(col, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.rightMargin = gap })
        }
        return android.widget.HorizontalScrollView(ctx).also { it.addView(row) }
    }

    // ---------------------------------------------------------------- across projects

    /** Search every project; tapping a hit opens it there. */
    fun searchAll(ctx: Context, open: (String, String, Int) -> Unit) {
        val box = Ui.vertical(ctx); pad(box, 20, 8, 20, 0)
        val q = EditText(ctx); q.hint = "Text to find in every project"; q.setSingleLine(); box.addView(q)
        val re = android.widget.CheckBox(ctx); re.text = "Regular expression"; re.setTextColor(Ui.TEXT); box.addView(re)
        AlertDialog.Builder(ctx).setTitle("Search all projects").setView(box).setPositiveButton("Search") { _, _ ->
            val query = q.text.toString(); if (query.isBlank()) return@setPositiveButton
            McpClient.background {
                val hits = CrossSearch.search(Projects.list(ctx).map { File(Projects.root(ctx), it) }, query, 300, re.isChecked)
                McpClient.onMain {
                    if (hits.isEmpty()) { toast(ctx, "Nothing found."); return@onMain }
                    val labels = hits.map { "${it.project} · ${it.path}:${it.line}\n${it.text}" }
                    AlertDialog.Builder(ctx).setTitle("${hits.size}${if (hits.size >= 300) "+" else ""} found").setItems(labels.toTypedArray()) { _, i -> open(hits[i].project, hits[i].path, hits[i].line) }
                        .setPositiveButton("Close", null).show()
                }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    /** Commits per day across every project with history. */
    fun activityAll(ctx: Context) {
        McpClient.background {
            val per = HashMap<String, Int>(); var total = 0; val busiest = ArrayList<Pair<String, Int>>()
            for (name in Projects.list(ctx)) {
                val v = Vcs(File(Projects.root(ctx), name)); if (!v.enabled()) continue
                val log = v.log(null, 2000); total += log.size; busiest.add(name to log.size)
                Insights.perDay(log).forEach { (d, n) -> per[d] = (per[d] ?: 0) + n }
            }
            McpClient.onMain {
                val col = Ui.vertical(ctx); pad(col, 16, 8, 16, 8)
                col.addView(Ui.text(ctx, "$total commits in ${busiest.size} project(s) with history", 13f, Ui.TEXT))
                col.addView(contributionGrid(ctx, Insights.grid(per, weeks = 20)))
                for ((n, c) in busiest.sortedByDescending { it.second }) col.addView(Ui.text(ctx, "$c  $n", 12f, Ui.MUTED, true))
                AlertDialog.Builder(ctx).setTitle("Activity").setView(scrollOf(col)).setPositiveButton("Close", null).show()
            }
        }
    }

    // ---------------------------------------------------------------- snippets

    fun snippetsFile(ctx: Context) = File(ctx.filesDir, "snippets.json")

    /** Pick a snippet to insert, or save [selection] as one. */
    fun snippets(ctx: Context, selection: String, lang: String, insert: (String) -> Unit) {
        val s = Snippets(snippetsFile(ctx)); val all = s.all().sortedByDescending { it.time }
        val labels = all.map { "${it.title}  ·  ${it.lang}" } + (if (selection.isNotBlank()) listOf("＋ Save the selection as a snippet") else emptyList())
        if (labels.isEmpty()) { toast(ctx, "No snippets yet: select some code, then ⋯ → Snippets → Save the selection."); return }
        AlertDialog.Builder(ctx).setTitle("Snippets").setItems(labels.toTypedArray()) { _, i ->
            if (i == all.size) {
                val e = EditText(ctx); e.hint = "Name"; e.setSingleLine(); val b = Ui.vertical(ctx); pad(b, 20, 8, 20, 0); b.addView(e)
                AlertDialog.Builder(ctx).setTitle("Save snippet").setView(b).setPositiveButton("Save") { _, _ -> s.add(e.text.toString(), lang, selection); toast(ctx, "Saved.") }.setNegativeButton("Cancel", null).show()
            } else {
                val sn = all[i]
                AlertDialog.Builder(ctx).setTitle(sn.title).setMessage(sn.code.take(3000)).setPositiveButton("Insert") { _, _ -> insert(sn.code) }
                    .setNeutralButton("Delete") { _, _ -> s.remove(sn.title) }.setNegativeButton("Close", null).show()
            }
        }.show()
    }
}
