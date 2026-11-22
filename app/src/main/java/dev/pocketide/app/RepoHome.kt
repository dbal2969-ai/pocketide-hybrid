package dev.pocketide.app

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pocketide.app.core.MdBlock
import dev.pocketide.app.core.MdKind
import dev.pocketide.app.core.Markdown
import dev.pocketide.app.core.RepoBrowser
import dev.pocketide.app.core.RepoEntry
import dev.pocketide.app.core.Checkpoints
import dev.pocketide.app.core.Collab
import dev.pocketide.app.core.GitHubHttp
import dev.pocketide.app.core.RepoActivity
import dev.pocketide.app.core.RunState
import dev.pocketide.app.core.SelfFix
import java.io.File

/** The GitHub-style home of a project: name, actions, path, the files in the current folder, and the README under them. */
class RepoHome(private val ctx: Context, private val store: ProjectStore, private val settings: AppSettings) : LinearLayout(ctx) {
    var onOpenFile: (String) -> Unit = {}
    var onRun: () -> Unit = {}
    var onSearch: () -> Unit = {}
    var onGit: () -> Unit = {}
    var onAi: () -> Unit = {}
    /** Sends a request to the assistant (used by "Fix this build"). */
    var onFixBuild: (String) -> Unit = {}
    var onFiles: () -> Unit = {}
    var onAddFiles: (String) -> Unit = {}
    var onPreview: () -> Unit = {}
    var onFileMenu: (String) -> Unit = {}
    var onFolderMenu: (String) -> Unit = {}
    var onNewFile: (String) -> Unit = {}
    var onNewFolder: (String) -> Unit = {}

    /** Called with the files that were put back when a snapshot was restored. */
    var onRestored: (List<String>) -> Unit = {}

    /** The project's own history screens (VcsUi), with this page's callbacks. */
    var hostFactory: (() -> VcsUi.Host)? = null
    val vcsHost: VcsUi.Host get() = hostFactory?.invoke() ?: VcsUi.Host(ctx, store, settings, { onRestored(it) }, { onFixBuild(it) }, { onOpenFile(it) })
    private fun hasHistory(): Boolean = File(store.root, ".git/HEAD").isFile

    private var showHidden = false
    /** 0 Code, 1 History, 2 Pull requests, 3 Releases, 4 Builds, 5 Issues, 6 Board (shown in GitHub's order). */
    private var tab = 0
    private val tabNames = mapOf(0 to "Code", 5 to "Issues", 2 to "Pull requests", 6 to "Board", 1 to "History", 3 to "Releases", 8 to "Wiki", 7 to "Insights", 4 to "Builds", 9 to "Settings")
    /** The wiki page being read ("" for the list). */
    private var wikiPage = ""
    private fun lineFn(): (LinearLayout, Boolean, String, Int, String, String, () -> Unit) -> Unit = { p, f, l, c, t, s, click -> line(p, f, l, c, t, s, click) }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var loadId = 0

    /** The folder being shown, "" for the project root. */
    var dir = ""
        private set

    private val scroll = ScrollView(ctx)
    private val body = Ui.vertical(ctx)

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.BG)
        body.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 24))
        scroll.addView(body)
        addView(scroll, Ui.lp(Ui.MATCH, Ui.MATCH))
    }

    fun reset() { dir = ""; tab = 0 }

    /** Goes up one folder. False when already at the top. */
    fun goUp(): Boolean {
        if (dir.isEmpty()) return false
        dir = RepoBrowser.parent(dir)
        refresh()
        return true
    }

    private fun open(path: String) { dir = path; refresh(); scroll.post { scroll.scrollTo(0, 0) } }

    private fun pad(v: View, l: Int, t: Int, r: Int, b: Int) = v.setPadding(Ui.dp(ctx, l), Ui.dp(ctx, t), Ui.dp(ctx, r), Ui.dp(ctx, b))

    private var refreshGen = 0

    /** Reads the project off the main thread (a big project takes seconds to list) and then draws the overview. */
    fun refresh() {
        val gen = ++refreshGen
        McpClient.background {
            val files = try { store.list() } catch (e: Exception) { emptyList() }
            val dirs = try { store.listDirs() } catch (e: Exception) { emptyList() }
            val newest = files.maxOfOrNull { store.resolve(it)?.lastModified() ?: 0L } ?: 0L
            McpClient.onMain { if (gen == refreshGen) draw(files, dirs, newest) }
        }
    }

    private fun draw(files: List<String>, dirs: List<String>, newest: Long) {
        body.removeAllViews()
        if (dir.isNotEmpty() && dirs.none { it.trim('/') == dir.trim('/') } && files.none { it.startsWith(dir.trim('/') + "/") }) dir = ""
        val name = store.root.name

        // header
        val title = Ui.text(ctx, name, 20f, Ui.TEXT)
        title.typeface = Typeface.DEFAULT_BOLD
        body.addView(title)
        val repo = settings.ghRepo.trim()
        val ago = RepoBrowser.relativeTime(System.currentTimeMillis(), newest)
        val sub = (if (repo.isNotEmpty()) "GitHub: $repo" else "Not linked to GitHub") +
            "  ·  ${files.size} file${if (files.size == 1) "" else "s"}" + (if (ago.isNotEmpty()) "  ·  updated $ago" else "")
        body.addView(Ui.text(ctx, sub, 12f, Ui.MUTED))

        // actions
        val row = Ui.horizontal(ctx)
        val hs = HorizontalScrollView(ctx)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(row)
        fun act(label: String, primary: Boolean = false, f: () -> Unit) =
            row.addView(if (primary) Ui.button(ctx, label, true) { f() } else Ui.chip(ctx, label) { f() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 8) })
        act("▶ Run", true) { onRun() }
        act("Preview") { onPreview() }
        act("Ask AI") { onAi() }
        act("Search") { onSearch() }
        if (hasHistory()) {
            val br = dev.pocketide.app.core.VcsStore(store.root).headBranch() ?: "detached"
            act("⑂ $br ▾") { VcsUi.branchMenu(vcsHost) { refresh() } }
            act("Commit") { VcsUi.commitDialog(vcsHost) { refresh() } }
        }
        act("Git") { onGit() }
        act("＋ New") { newMenu() }
        body.addView(hs, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 12) })

        // tabs: Code, History, Pull requests, Releases, Builds
        val tabs = Ui.horizontal(ctx)
        for ((i, label) in tabNames) {
            val t = Ui.text(ctx, label, 14f, if (i == tab) Ui.TEXT else Ui.MUTED)
            if (i == tab) t.typeface = Typeface.DEFAULT_BOLD
            pad(t, 0, 10, 20, 10)
            t.setOnClickListener { if (tab != i) { tab = i; refresh() } }
            tabs.addView(t)
        }
        body.addView(HorizontalScrollView(ctx).also { it.isHorizontalScrollBarEnabled = false; it.addView(tabs) }, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
        body.addView(View(ctx).also { it.setBackgroundColor(Ui.LINE) }, Ui.lp(Ui.MATCH, 1))
        if (tab == 1) { historyTab(); return }
        if (tab == 2) { CollabUi.pullsTab(vcsHost, body, lineFn(), { card() }) { refresh() }; return }
        if (tab == 3) { CollabUi.releasesTab(vcsHost, body, lineFn(), { card() }) { refresh() }; return }
        if (tab == 4) { buildsTab(); return }
        if (tab == 5) { WorkUi.issuesTab(vcsHost, body, lineFn(), { card() }, { refresh() }, WorkUi.lastFilter); return }
        if (tab == 6) { WorkUi.boardTab(vcsHost, body, { card() }) { refresh() }; return }
        if (tab == 7) { ProjectUi.insightsTab(vcsHost, body) { card() }; return }
        if (tab == 8) { wikiTab(files); return }
        if (tab == 9) { ProjectUi.settingsTab(vcsHost, body, { card() }) { refresh() }; return }

        // path
        val crumbs = RepoBrowser.crumbs(name, dir)
        val path = Ui.horizontal(ctx)
        val ps = HorizontalScrollView(ctx)
        ps.isHorizontalScrollBarEnabled = false
        ps.addView(path)
        for ((i, c) in crumbs.withIndex()) {
            val last = i == crumbs.size - 1
            val t = Ui.text(ctx, c.label, 14f, if (last) Ui.TEXT else Ui.ACCENT)
            if (last) t.typeface = Typeface.DEFAULT_BOLD
            pad(t, 0, 10, 0, 10)
            if (!last) t.setOnClickListener { open(c.path) }
            path.addView(t)
            if (!last) path.addView(Ui.text(ctx, "  /  ", 14f, Ui.MUTED).also { pad(it, 0, 10, 0, 10) })
        }
        body.addView(ps, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 8) })

        // files
        val all = RepoBrowser.entries(files, dirs, dir) { p -> store.resolve(p)?.let { it.length() to it.lastModified() } ?: (0L to 0L) }
        val hidden = all.filter { RepoBrowser.isNoise(it) }
        val entries = if (showHidden) all else all.filter { !RepoBrowser.isNoise(it) }
        val card = Ui.vertical(ctx)
        card.background = Ui.rounded(Ui.SURFACE, 10, ctx, Ui.LINE)
        if (dir.isNotEmpty()) card.addView(entryRow("📁", "..", "", true) { goUp() })
        if (entries.isEmpty()) card.addView(Ui.text(ctx, if (dir.isEmpty()) "This project is empty. Ask the AI to build something, or tap Add files." else "This folder is empty.", 13f, Ui.MUTED).also { pad(it, 14, 14, 14, 14) })
        val now = System.currentTimeMillis()
        for ((i, e) in entries.withIndex()) {
            if (i > 0 || dir.isNotEmpty()) card.addView(divider())
            val er = entryRow(if (e.isDir) "📁" else icon(e), e.name, RepoBrowser.relativeTime(now, e.modified), false) { if (e.isDir) open(e.path) else onOpenFile(e.path) }
            er.setOnLongClickListener { if (e.isDir) onFolderMenu(e.path) else onFileMenu(e.path); true }
            card.addView(er)
        }
        body.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
        val left = store.omittedCount()
        if (left > 0) body.addView(Ui.text(ctx, "$left file${if (left == 1) "" else "s"} not shown here: the project is too big to list in full. Search covers text files; binary files such as images and PDFs are listed only while the project is small enough.", 12f, Ui.MUTED).also { pad(it, 2, 10, 2, 0) })
        val hint = Ui.text(ctx, "Tap to open  ·  long-press for rename, move, delete", 11f, Ui.MUTED)
        pad(hint, 2, 6, 2, 0)
        body.addView(hint)
        if (hidden.isNotEmpty()) {
            val t = Ui.text(ctx, if (showHidden) "Hide ${hidden.joinToString(", ") { it.name }}" else "${hidden.size} hidden folder${if (hidden.size == 1) "" else "s"} (${hidden.joinToString(", ") { it.name }}) · Show", 12f, Ui.ACCENT)
            pad(t, 2, 8, 2, 8)
            t.setOnClickListener { showHidden = !showHidden; refresh() }
            body.addView(t)
        }

        // README
        val readme = RepoBrowser.findReadme(files, dir)
        if (readme != null) readmeCard(readme)
        else if (dir.isEmpty() && files.isNotEmpty()) {
            body.addView(Ui.text(ctx, "No README yet. Ask the AI to write one if you want it shown here.", 12f, Ui.MUTED).also { pad(it, 2, 16, 2, 0) })
        }
    }

    private fun card(): LinearLayout {
        val c = Ui.vertical(ctx)
        c.background = Ui.rounded(Ui.SURFACE, 10, ctx, Ui.LINE)
        body.addView(c, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 8) })
        return c
    }

    private fun sectionTitle(t: String) {
        val h = Ui.text(ctx, t.uppercase(), 12f, Ui.MUTED)
        h.typeface = Typeface.DEFAULT_BOLD
        pad(h, 2, 18, 2, 0)
        body.addView(h)
    }

    private fun openUrl(url: String) {
        try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { /* no browser */ }
    }

    private fun line(parent: LinearLayout, first: Boolean, left: String, leftColor: Int, title: String, sub: String, onClick: (() -> Unit)?, vararg chips: TextView) {
        if (!first) parent.addView(divider())
        val r = Ui.horizontal(ctx)
        r.gravity = Gravity.CENTER_VERTICAL
        pad(r, 14, 10, 10, 10)
        r.addView(Ui.text(ctx, left, 15f, leftColor).also { it.setPadding(0, 0, Ui.dp(ctx, 10), 0); it.typeface = Typeface.DEFAULT_BOLD })
        val col = Ui.vertical(ctx)
        col.addView(Ui.text(ctx, title, 14f, Ui.TEXT).also { it.maxLines = 2; it.ellipsize = android.text.TextUtils.TruncateAt.END })
        col.addView(Ui.text(ctx, sub, 11f, Ui.MUTED))
        r.addView(col, Ui.lp(0, Ui.WRAP, 1f))
        for (c in chips) r.addView(c, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 6) })
        if (onClick != null) r.setOnClickListener { onClick() }
        parent.addView(r)
    }

    /** History: the assistant's snapshots on this phone, and the GitHub commits when the project is linked. */
    private fun historyTab() {
        localHistory()
        sectionTitle("Before each assistant change")
        val cps = try { Checkpoints(File(Projects.metaDir(ctx), "checkpoints")).list() } catch (e: Exception) { emptyList() }
        val cardA = card()
        if (cps.isEmpty()) cardA.addView(Ui.text(ctx, "Nothing yet. Each time the assistant changes files, the way they were is kept here so you can go back.", 13f, Ui.MUTED).also { pad(it, 14, 14, 14, 14) })
        val now = System.currentTimeMillis()
        for ((i, cp) in cps.take(30).withIndex()) {
            val files = Ui.chip(ctx, "Files") {
                android.app.AlertDialog.Builder(ctx).setTitle(cp.label.take(60)).setItems(cp.paths.toTypedArray(), null).setPositiveButton("Close", null).show()
            }
            val back = Ui.chip(ctx, "Go back") { confirmRestore(cp.id, cp.label) }
            line(cardA, i == 0, "↶", Ui.ACCENT, cp.label.ifBlank { "Assistant change" }, RepoActivity.files(cp.paths.size) + "  ·  " + RepoBrowser.relativeTime(now, cp.time), null, files, back)
        }
        sectionTitle("Commits on GitHub")
        val repo = settings.ghRepo.trim()
        val why = RepoActivity.notLinked(repo, settings.ghToken)
        val cardB = card()
        if (why != null) { cardB.addView(Ui.text(ctx, why, 13f, Ui.MUTED).also { pad(it, 14, 14, 14, 14) }); cardB.addView(Ui.chip(ctx, "Open Git") { onGit() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 14); it.bottomMargin = Ui.dp(ctx, 12) }); return }
        val status = Ui.text(ctx, "Loading from GitHub…", 13f, Ui.MUTED)
        pad(status, 14, 14, 14, 14)
        cardB.addView(status)
        val id = ++loadId
        val slug = (dev.pocketide.app.core.GitRepo.parse(repo)?.identity ?: repo)
        Thread {
            var rows: List<dev.pocketide.app.core.CommitRow> = emptyList()
            var err: String? = null
            try {
                val r = GitHubHttp(settings.ghToken).call("GET", RepoActivity.commitsPath(slug, settings.ghBranch))
                if (r.code in 200..299) rows = RepoActivity.parseCommits(r.text) else err = RepoActivity.apiError(r.code)
            } catch (e: java.net.UnknownHostException) { err = "Could not reach GitHub. Check your internet connection." }
            catch (e: Exception) { err = e.message ?: "Could not load the commits." }
            main.post {
                if (id != loadId || tab != 1) return@post
                cardB.removeAllViews()
                if (err != null) { cardB.addView(Ui.text(ctx, err, 13f, Ui.BAD).also { pad(it, 14, 14, 14, 14) }); return@post }
                if (rows.isEmpty()) cardB.addView(Ui.text(ctx, "No commits found on ${settings.ghBranch}.", 13f, Ui.MUTED).also { pad(it, 14, 14, 14, 14) })
                for ((i, c) in rows.withIndex()) line(cardB, i == 0, "●", Ui.MUTED, c.message, c.short + "  ·  " + c.author + "  ·  " + RepoBrowser.relativeTime(System.currentTimeMillis(), c.time), { openUrl(RepoActivity.commitUrl(slug, c.sha)) })
            }
        }.start()
    }

    /** The project's own commits (GitHub-style history on the phone), or the offer to start it. */
    private fun localHistory() {
        sectionTitle("Version history")
        val c = card()
        if (!hasHistory()) {
            c.addView(Ui.text(ctx, "Keep this project's history like a GitHub repository: commits, branches, merges, tags, and who changed each line. Stored on this phone in real git format.", 13f, Ui.MUTED).also { pad(it, 14, 14, 14, 8) })
            c.addView(Ui.button(ctx, "Start version history", true) { VcsUi.startDialog(vcsHost) { refresh() } }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 14); it.bottomMargin = Ui.dp(ctx, 12) })
            return
        }
        val status = Ui.text(ctx, "Loading…", 13f, Ui.MUTED); pad(status, 14, 14, 14, 14); c.addView(status)
        val id = ++loadId
        VcsUi.bg(ctx, { val v = dev.pocketide.app.core.Vcs(store.root); Triple(v.status(), v.log(null, 60), Triple(v.branch(), v.tags(), v.merging() || v.unresolved().isNotEmpty())) }) { (changes, commits, extra) ->
            if (id != loadId || tab != 1) return@bg
            val (branch, tags, conflicted) = extra
            c.removeAllViews()
            val byCommit = tags.entries.groupBy({ dev.pocketide.app.core.VcsStore(store.root).readCommit(it.value)?.id ?: it.value }, { it.key })
            var first = true
            if (conflicted) { line(c, first, "⚠", Ui.BAD, "Conflicts to resolve", "Lines changed on both sides", { VcsUi.conflictsDialog(vcsHost) { refresh() } }); first = false }
            if (changes.isNotEmpty()) {
                line(c, first, "●", Ui.ACCENT, "${changes.size} uncommitted change${if (changes.size == 1) "" else "s"}", changes.take(4).joinToString(", ") { it.path } + if (changes.size > 4) "…" else "",
                    { VcsUi.commitDialog(vcsHost) { refresh() } }, Ui.chip(ctx, "Commit") { VcsUi.commitDialog(vcsHost) { refresh() } })
                first = false
            }
            for (cm in commits) {
                val labels = byCommit[cm.id].orEmpty()
                val who = if (cm.author.email == dev.pocketide.app.core.Vcs.ASSISTANT.email) "assistant" else cm.author.name
                line(c, first, if (cm.parents.size > 1) "⑃" else "○", if (who == "assistant") Ui.ACCENT else Ui.MUTED, cm.title,
                    cm.short + "  ·  " + who + "  ·  " + VcsUi.ago(cm.author.time) + (if (labels.isNotEmpty()) "  ·  🏷 " + labels.joinToString(", ") else ""),
                    { VcsUi.commitDetail(vcsHost, cm.id) { refresh() } })
                first = false
            }
            val more = Ui.horizontal(ctx); pad(more, 14, 8, 14, 12)
            more.addView(Ui.chip(ctx, "⑂ ${branch ?: "branches"}") { VcsUi.branchMenu(vcsHost) { refresh() } })
            more.addView(Ui.chip(ctx, "Stashes") { VcsUi.stashes(vcsHost) { refresh() } }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 6) })
            more.addView(Ui.chip(ctx, "Tags") { VcsUi.tags(vcsHost) { refresh() } }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 6) })
            c.addView(more)
        }
    }

    private fun confirmRestore(id: String, label: String) {
        android.app.AlertDialog.Builder(ctx).setTitle("Go back to before this change?")
            .setMessage("\"${label.take(80)}\"\n\nFiles it changed (and anything the assistant changed after it) are put back the way they were.")
            .setPositiveButton("Go back") { _, _ ->
                val touched = try { Checkpoints(File(Projects.metaDir(ctx), "checkpoints")).restore(id, { p, b -> store.writeBytes(p, b) }, { p -> store.delete(p) }) } catch (e: Exception) { emptyList() }
                android.widget.Toast.makeText(ctx, if (touched.isEmpty()) "Nothing to restore" else "Restored ${touched.size} file${if (touched.size == 1) "" else "s"}", android.widget.Toast.LENGTH_SHORT).show()
                onRestored(touched)
                refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    /** Builds: the repository's GitHub Actions runs, newest first. Tap one to open it on GitHub. */
    private var watched: Map<Long, RunState> = emptyMap()

    private fun buildsTab() {
        val repo = settings.ghRepo.trim()
        val why = RepoActivity.notLinked(repo, settings.ghToken)
        val c = card()
        if (why != null) { c.addView(Ui.text(ctx, why, 13f, Ui.MUTED).also { pad(it, 14, 14, 14, 14) }); c.addView(Ui.chip(ctx, "Open Git") { onGit() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 14); it.bottomMargin = Ui.dp(ctx, 12) }); return }
        val status = Ui.text(ctx, "Loading builds from GitHub…", 13f, Ui.MUTED)
        pad(status, 14, 14, 14, 14)
        c.addView(status)
        val id = ++loadId
        val slug = (dev.pocketide.app.core.GitRepo.parse(repo)?.identity ?: repo)
        Thread {
            var runs: List<SelfFix.Run> = emptyList()
            var err: String? = null
            try {
                val r = GitHubHttp(settings.ghToken).call("GET", SelfFix.runsPath(slug, 25))
                if (r.code in 200..299) runs = SelfFix.parseRuns(r.text) else err = RepoActivity.apiError(r.code)
            } catch (e: java.net.UnknownHostException) { err = "Could not reach GitHub. Check your internet connection." }
            catch (e: Exception) { err = e.message ?: "Could not load the builds." }
            main.post {
                if (id != loadId || tab != 4) return@post
                c.removeAllViews()
                if (err != null) { c.addView(Ui.text(ctx, err, 13f, Ui.BAD).also { pad(it, 14, 14, 14, 14) }); return@post }
                if (runs.isEmpty()) c.addView(Ui.text(ctx, "No builds yet. Pushing a project with a workflow starts one.", 13f, Ui.MUTED).also { pad(it, 14, 14, 14, 14) })
                val states = runs.map { it to RepoActivity.state(it) }
                for ((r, st) in dev.pocketide.app.core.RunWatch.finished(watched, states)) {
                    Notifier.buildFinished(ctx, r.id, dev.pocketide.app.core.RunWatch.message(r, st))
                    android.widget.Toast.makeText(ctx, dev.pocketide.app.core.RunWatch.message(r, st), android.widget.Toast.LENGTH_LONG).show()
                }
                watched = dev.pocketide.app.core.RunWatch.snapshot(states)
                if (dev.pocketide.app.core.RunWatch.anyRunning(states)) main.postDelayed({ if (id == loadId && tab == 4 && isAttachedToWindow) refresh() }, 30000)
                for ((i, r) in runs.withIndex()) {
                    val st = RepoActivity.state(r)
                    val color = when (st) { RunState.PASSED -> Ui.GOOD; RunState.FAILED -> Ui.BAD; RunState.RUNNING -> Ui.ACCENT; else -> Ui.MUTED }
                    val ago = RepoBrowser.relativeTime(System.currentTimeMillis(), RepoActivity.parseTime(r.finished))
                    line(c, i == 0, st.mark, color, r.title.ifBlank { r.name }, r.name + "  ·  " + st.word + (if (r.head.isNotEmpty()) "  ·  " + r.head else "") + (if (ago.isNotEmpty()) "  ·  $ago" else ""),
                        { if (st == RunState.FAILED) failedMenu(slug, r) else openUrl(RepoActivity.runUrl(slug, r.id)) })
                }
            }
        }.start()
        body.addView(Ui.chip(ctx, "Refresh") { refresh() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 10) })
    }

    /** A failed build: see why, or hand the error to the assistant. */
    private fun failedMenu(slug: String, r: SelfFix.Run) {
        android.app.AlertDialog.Builder(ctx).setTitle(r.title.ifBlank { r.name } + " failed")
            .setItems(arrayOf("Ask the assistant to fix it", "Show the error", "Open on GitHub")) { _, which ->
                if (which == 2) { openUrl(RepoActivity.runUrl(slug, r.id)); return@setItems }
                android.widget.Toast.makeText(ctx, "Loading the build log…", android.widget.Toast.LENGTH_SHORT).show()
                Thread {
                    var job: dev.pocketide.app.core.CiFailure.FailedJob? = null
                    var excerpt = ""
                    var err: String? = null
                    try {
                        val jr = GitHubHttp(settings.ghToken).call("GET", dev.pocketide.app.core.CiFailure.jobsPath(slug, r.id))
                        if (jr.code in 200..299) job = dev.pocketide.app.core.CiFailure.failedJobs(jr.text).firstOrNull() else err = RepoActivity.apiError(jr.code)
                        job?.let { j -> excerpt = dev.pocketide.app.core.CiFailure.excerpt(fetchLog(dev.pocketide.app.core.CiFailure.logPath(slug, j.id)) ?: "") }
                    } catch (e: Exception) { err = e.message ?: "Could not load the build log." }
                    main.post {
                        if (which == 0) onFixBuild(dev.pocketide.app.core.CiFailure.prompt(r.title, r.name, job, excerpt))
                        else {
                            val t = Ui.text(ctx, err ?: (job?.let { "Job: ${it.name}" + (if (it.step.isNotEmpty()) "\nStep: ${it.step}" else "") + "\n\n" } ?: "") + excerpt.ifBlank { "No log found." }, 11f, Ui.TEXT, true)
                            t.setTextIsSelectable(true)
                            t.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 8), Ui.dp(ctx, 14), Ui.dp(ctx, 8))
                            val sv = android.widget.ScrollView(ctx); sv.addView(t)
                            android.app.AlertDialog.Builder(ctx).setTitle("Why it failed").setView(sv)
                                .setPositiveButton("Ask the assistant") { _, _ -> onFixBuild(dev.pocketide.app.core.CiFailure.prompt(r.title, r.name, job, excerpt)) }
                                .setNegativeButton("Close", null).show()
                        }
                    }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    /** Job logs redirect to a signed storage address that must not get the GitHub token, so the redirect is followed by hand. */
    private fun fetchLog(path: String): String? {
        val first = java.net.URI("https://api.github.com$path").toURL().openConnection() as java.net.HttpURLConnection
        try {
            first.instanceFollowRedirects = false
            first.connectTimeout = 20_000; first.readTimeout = 60_000
            first.setRequestProperty("Accept", "application/vnd.github+json")
            first.setRequestProperty("User-Agent", "PocketIDE")
            if (settings.ghToken.isNotEmpty()) first.setRequestProperty("Authorization", "Bearer " + settings.ghToken)
            val code = first.responseCode
            if (code in 200..299) return first.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            val where = first.getHeaderField("Location") ?: return null
            if (!where.startsWith("https://")) return null
            val second = java.net.URI(where).toURL().openConnection() as java.net.HttpURLConnection
            try {
                second.connectTimeout = 20_000; second.readTimeout = 60_000
                if (second.responseCode !in 200..299) return null
                val bytes = second.inputStream.use { it.readBytes() }
                return String(if (bytes.size > 3_000_000) bytes.copyOfRange(bytes.size - 3_000_000, bytes.size) else bytes, Charsets.UTF_8)
            } finally { second.disconnect() }
        } finally { first.disconnect() }
    }

    private fun newMenu() {
        val where = if (dir.isEmpty()) "this project" else dir
        android.app.AlertDialog.Builder(ctx).setTitle("Add to $where")
            .setItems(arrayOf("New file", "New folder", "Add files from phone…")) { _, i ->
                when (i) { 0 -> onNewFile(dir); 1 -> onNewFolder(dir); else -> onAddFiles(dir) }
            }.show()
    }

    private fun icon(e: RepoEntry): String = when (e.name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "🌐"
        "png", "jpg", "jpeg", "gif", "webp", "svg" -> "🖼"
        "md", "txt", "rst" -> "📝"
        else -> "📄"
    }

    private fun divider(): View = View(ctx).also { it.setBackgroundColor(Ui.LINE); it.layoutParams = Ui.lp(Ui.MATCH, 1) }

    private fun entryRow(icon: String, name: String, time: String, up: Boolean, onClick: () -> Unit): View {
        val r = Ui.horizontal(ctx)
        r.gravity = Gravity.CENTER_VERTICAL
        pad(r, 14, 12, 14, 12)
        r.addView(Ui.text(ctx, icon, 16f, Ui.TEXT).also { it.setPadding(0, 0, Ui.dp(ctx, 12), 0) })
        val n = Ui.text(ctx, name, 15f, if (up) Ui.ACCENT else Ui.TEXT)
        n.maxLines = 1
        n.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        r.addView(n, Ui.lp(0, Ui.WRAP, 1f))
        if (time.isNotEmpty()) r.addView(Ui.text(ctx, time, 12f, Ui.MUTED).also { it.setPadding(Ui.dp(ctx, 10), 0, 0, 0) })
        r.setOnClickListener { onClick() }
        return r
    }

    /** The wiki: Markdown pages in wiki/ (part of the project, so they have history too), read here like GitHub's wiki. */
    private fun wikiTab(files: List<String>) {
        val pages = files.filter { it.startsWith("wiki/") && it.endsWith(".md", true) }.sortedWith(compareBy({ !it.equals("wiki/Home.md", true) }, { it.lowercase() }))
        val top = Ui.horizontal(ctx); pad(top, 0, 10, 0, 0)
        top.addView(Ui.button(ctx, "New page", true) {
            val e = android.widget.EditText(ctx); e.hint = "Page title"; e.setSingleLine(); val b = Ui.vertical(ctx); pad(b, 20, 8, 20, 0); b.addView(e)
            android.app.AlertDialog.Builder(ctx).setTitle("New wiki page").setView(b).setPositiveButton("Create") { _, _ ->
                val t = e.text.toString().trim().ifEmpty { "Home" }; val p = "wiki/" + t.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '-') + ".md"
                if (!store.exists(p)) store.write(p, "# $t\n\n")
                onRestored(listOf(p)); onOpenFile(p)
            }.setNegativeButton("Cancel", null).show()
        })
        if (wikiPage.isNotEmpty()) top.addView(Ui.chip(ctx, "All pages") { wikiPage = ""; refresh() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 6) })
        body.addView(top)
        if (wikiPage.isNotEmpty() && wikiPage in pages) { readmeCard(wikiPage); return }
        val c = card()
        if (pages.isEmpty()) c.addView(Ui.text(ctx, "No wiki yet. Pages are Markdown files in wiki/ (start with Home): notes, how-tos, design decisions. They are part of the project, so they have history.", 13f, Ui.MUTED).also { pad(it, 14, 14, 14, 14) })
        for ((i, p) in pages.withIndex()) line(c, i == 0, "📄", Ui.ACCENT, p.removePrefix("wiki/").removeSuffix(".md").replace('-', ' '), p, { wikiPage = p; refresh() })
    }

    private fun readmeCard(path: String) {
        val text = store.read(path) ?: return
        val head = Ui.text(ctx, "📝  " + path.substringAfterLast('/'), 13f, Ui.MUTED)
        pad(head, 2, 18, 2, 6)
        body.addView(head)
        val card = Ui.vertical(ctx)
        card.background = Ui.rounded(Ui.SURFACE, 10, ctx, Ui.LINE)
        pad(card, 14, 12, 14, 14)
        val blocks = Markdown.blocks(text.take(40_000), 120)
        if (blocks.isEmpty()) card.addView(Ui.text(ctx, "The README is empty.", 13f, Ui.MUTED))
        for (b in blocks) card.addView(block(b, path), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, if (b.kind == MdKind.H1 || b.kind == MdKind.H2) 12 else 6) })
        card.addView(Ui.chip(ctx, "Open " + path.substringAfterLast('/')) { onOpenFile(path) }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 12) })
        body.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun block(b: MdBlock, path: String): View = when (b.kind) {
        MdKind.TASK -> android.widget.CheckBox(ctx).also { cb ->
            cb.text = b.text; cb.setTextColor(Ui.TEXT); cb.isChecked = b.checked
            cb.setOnCheckedChangeListener { _, on -> store.read(path)?.let { t -> store.write(path, Markdown.toggleTask(t, b.line, on)); onRestored(listOf(path)) } }
        }
        MdKind.DIAGRAM -> Ui.vertical(ctx).also { col ->
            col.addView(Ui.text(ctx, b.text, 11f, Ui.MUTED, true).also { it.maxLines = 6 })
            col.addView(Ui.chip(ctx, "View diagram") { WorkUi.showDiagram(ctx, b.text) })
        }
        MdKind.H1 -> Ui.text(ctx, b.text, 20f, Ui.TEXT).also { it.typeface = Typeface.DEFAULT_BOLD }
        MdKind.H2 -> Ui.text(ctx, b.text, 17f, Ui.TEXT).also { it.typeface = Typeface.DEFAULT_BOLD }
        MdKind.H3 -> Ui.text(ctx, b.text, 15f, Ui.TEXT).also { it.typeface = Typeface.DEFAULT_BOLD }
        MdKind.BULLET -> Ui.text(ctx, "•  " + b.text, 14f, Ui.TEXT).also { pad(it, 8, 0, 0, 0) }
        MdKind.NUMBERED -> Ui.text(ctx, b.text, 14f, Ui.TEXT).also { pad(it, 8, 0, 0, 0) }
        MdKind.QUOTE -> Ui.text(ctx, b.text, 14f, Ui.MUTED).also { pad(it, 10, 0, 0, 0); it.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC) }
        MdKind.CODE -> {
            val t = Ui.text(ctx, b.text, 12f, Ui.TEXT, true)
            t.background = Ui.rounded(Ui.BG, 8, ctx, Ui.LINE)
            pad(t, 10, 8, 10, 8)
            t.setHorizontallyScrolling(true)
            val h = HorizontalScrollView(ctx)
            h.addView(t)
            h
        }
        MdKind.RULE -> View(ctx).also { it.setBackgroundColor(Ui.LINE); it.layoutParams = Ui.lp(Ui.MATCH, 1) }
        MdKind.PARA -> Ui.text(ctx, b.text, 14f, Ui.TEXT).also { if (hasHistory() || Collab.refs(b.text).isNotEmpty()) WorkUi.linkRefs(vcsHost, it) { refresh() } }
    }
}
