package dev.pocketide.app

import android.app.Activity
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pocketide.app.core.RepoBrowser
import dev.pocketide.app.core.RepoCard
import dev.pocketide.app.core.RepoList

/**
 * The page the app opens on: your repositories, with the one you were last in on top.
 * It is not the coding screen: tapping a repository opens it, and the tabs below take you into the work.
 */
class HomeScreen(private val act: Activity) : LinearLayout(act) {
    var onNew: () -> Unit = {}
    var onClone: () -> Unit = {}
    var onOpen: (String) -> Unit = {}
    var onAsk: (String) -> Unit = {}
    var onChanged: () -> Unit = {}
    var onTry: (String) -> Unit = {}
    var onConnect: () -> Unit = {}
    /** The Chat home, a chat with no project (1.5.125). */
    var onChat: () -> Unit = {}
    /** Search the chats of every project (1.5.125). */
    var onSearchChats: () -> Unit = {}
    /** A search hit in another project: project, file, line. */
    var onOpenAt: (String, String, Int) -> Unit = { _, _, _ -> }
    /** Whether an AI is set up, and whether it has answered at least once; both decide the first-run card. */
    var hasAi: () -> Boolean = { true }
    /** The note shown while a no-key provider is in use (Starter.keylessNote), else null. */
    var aiNote: () -> String? = { null }
    /** 1.5.209: Hybrid AI is the provider (the card says it is ready, with no key). */
    var hybrid: () -> Boolean = { false }
    var used: () -> Boolean = { true }

    private val scroll = ScrollView(act)
    private val body = Ui.vertical(act)

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.BG)
        body.setPadding(Ui.dp(act, 16), Ui.dp(act, 14), Ui.dp(act, 16), Ui.dp(act, 24))
        scroll.addView(body)
        addView(scroll, Ui.lp(Ui.MATCH, Ui.MATCH))
        isClickable = true  // an opaque page: taps never reach the screens underneath
    }

    private fun pad(v: android.view.View, l: Int, t: Int, r: Int, b: Int) = v.setPadding(Ui.dp(act, l), Ui.dp(act, t), Ui.dp(act, r), Ui.dp(act, b))

    private fun card(): LinearLayout {
        val c = Ui.vertical(act)
        c.background = Ui.rounded(Ui.SURFACE, 12, act, Ui.LINE)
        pad(c, 14, 12, 14, 12)
        body.addView(c, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(act, 10) })
        return c
    }

    private fun heading(s: String) {
        val t = Ui.text(act, s.uppercase(), 12f, Ui.MUTED)
        t.typeface = Typeface.DEFAULT_BOLD
        t.letterSpacing = 0.06f
        body.addView(t, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(act, 22) })
    }

    private fun meta(name: String, now: Long): String {
        val n = Projects.fileCount(act, name)
        val ago = RepoBrowser.relativeTime(now, Projects.modified(act, name))
        val repo = Projects.gitConfig(act, name).getProperty("repo", "")
        return (if (n >= 999) "999+ files" else "$n file${if (n == 1) "" else "s"}") +
            (if (ago.isNotEmpty()) "  ·  updated $ago" else "") + (if (repo.isNotEmpty()) "  ·  GitHub: $repo" else "")
    }

    fun refresh() {
        body.removeAllViews()
        val title = Ui.text(act, "PocketIDE", 24f, Ui.TEXT)
        title.typeface = Typeface.DEFAULT_BOLD
        body.addView(title)
        body.addView(Ui.text(act, "Pick a repository to work in, or start a new one.", 13f, Ui.MUTED))

        if (dev.pocketide.app.core.Starter.show(used())) {
            val c = card()
            c.background = Ui.rounded(Ui.SELECTED, 12, act, Ui.ACCENT)
            c.addView(Ui.text(act, "Your first project in two minutes", 16f, Ui.TEXT).also { it.typeface = Typeface.DEFAULT_BOLD })
            val note = aiNote()
            for (st in dev.pocketide.app.core.Starter.steps(hasAi(), used(), note != null, hybrid())) c.addView(Ui.text(act, (if (st.done) "✓  " else "○  ") + st.text, 13f, if (st.done) Ui.GOOD else Ui.TEXT).also { pad(it, 0, 6, 0, 0) })
            if (!hasAi()) c.addView(Ui.button(act, "Connect an AI", true) { onConnect() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(act, 10) })
            else if (note != null) {
                c.addView(Ui.text(act, note, 12f, Ui.MUTED).also { pad(it, 0, 8, 0, 0) })
                c.addView(Ui.button(act, "Use my own key") { onConnect() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(act, 6) })
            }
            val ideas = Ui.horizontal(act)
            for (i in dev.pocketide.app.core.Starter.IDEAS) ideas.addView(Ui.chip(act, i.title) { onTry(i.prompt) }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(act, 6) })
            val hs = android.widget.HorizontalScrollView(act); hs.addView(ideas)
            c.addView(hs, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(act, 10) })
        }

        val row = Ui.horizontal(act)
        row.addView(Ui.button(act, "New project", true) { onNew() })
        row.addView(Ui.button(act, "Clone from GitHub") { onClone() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(act, 8) })
        body.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(act, 14) })
        val row2 = Ui.horizontal(act)
        row2.addView(Ui.chip(act, "🔍 Search all projects") { ProjectUi.searchAll(act) { p, f, l -> onOpenAt(p, f, l) } })
        row2.addView(Ui.chip(act, "Activity") { ProjectUi.activityAll(act) }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(act, 8) })
        body.addView(row2, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(act, 8) })
        // 1.5.125: talk to the assistant without a project, and find any past conversation
        val row3 = Ui.horizontal(act)
        row3.addView(Ui.chip(act, "💬 Just chat") { onChat() })
        row3.addView(Ui.chip(act, "🔍 Search all chats") { onSearchChats() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(act, 8) })
        body.addView(row3, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(act, 8) })

        val current = Projects.key(act)
        val now = System.currentTimeMillis()
        val cards = RepoList.order(Projects.list(act).map { nm ->
            RepoCard(nm, Projects.fileCount(act, nm), Projects.modified(act, nm), Projects.description(act, nm),
                Projects.gitConfig(act, nm).getProperty("repo", ""), nm == current)
        })

        val cur = cards.firstOrNull { it.name == current }
        if (cur != null) {
            heading("Continue")
            val c = card()
            c.background = Ui.rounded(Ui.SURFACE, 12, act, Ui.ACCENT)
            c.addView(Ui.text(act, cur.name, 17f, Ui.TEXT).also { it.typeface = Typeface.DEFAULT_BOLD })
            Projects.description(act, cur.name).takeIf { it.isNotEmpty() }?.let { c.addView(Ui.text(act, it, 13f, Ui.TEXT).also { t -> pad(t, 0, 2, 0, 0) }) }
            c.addView(Ui.text(act, meta(cur.name, now), 12f, Ui.MUTED).also { pad(it, 0, 4, 0, 0) })
            val r = Ui.horizontal(act)
            r.addView(Ui.button(act, "Open code", true) { onOpen(cur.name) })
            r.addView(Ui.button(act, "Ask AI") { onAsk(cur.name) }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(act, 8) })
            c.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(act, 10) })
        }

        fun flag(n: String, f: String) = java.io.File(java.io.File(java.io.File(act.filesDir, "meta"), n), f).isFile
        // pinned first, archived last (GitHub's pins and archive)
        val others = cards.filter { it.name != current }.sortedWith(compareBy({ !flag(it.name, "pinned") }, { flag(it.name, "archived") }))
        heading("Your repositories")
        if (others.isEmpty()) {
            body.addView(Ui.text(act, if (cur == null) "No repositories yet. Tap New project to start." else "This is your only repository. Tap New project to add another; each one keeps its own files and chats.", 13f, Ui.MUTED).also { pad(it, 0, 8, 0, 0) })
        }
        for (r in others) {
            val c = card()
            val archived = flag(r.name, "archived")
            c.addView(Ui.text(act, (if (flag(r.name, "pinned")) "📌 " else "") + r.name + (if (archived) "  · archived" else ""), 16f, if (archived) Ui.MUTED else Ui.TEXT).also { it.typeface = Typeface.DEFAULT_BOLD })
            if (r.description.isNotEmpty()) c.addView(Ui.text(act, r.description, 13f, Ui.TEXT).also { pad(it, 0, 2, 0, 0) })
            c.addView(Ui.text(act, meta(r.name, now), 12f, Ui.MUTED).also { pad(it, 0, 4, 0, 0) })
            c.setOnClickListener { onOpen(r.name) }
            c.setOnLongClickListener { ProjectsScreen.more(act, r.name, { }) { refresh(); onChanged() }; true }
        }
        if (others.isNotEmpty()) body.addView(Ui.text(act, "Tap to open  ·  long-press to rename, duplicate or delete", 11f, Ui.MUTED).also { pad(it, 2, 8, 2, 0) })
        val version = try { act.packageManager.getPackageInfo(act.packageName, 0).versionName } catch (e: Exception) { null }
        if (version != null) body.addView(Ui.text(act, "PocketIDE $version", 11f, Ui.MUTED).also { pad(it, 2, 28, 2, 0) })
    }
}
