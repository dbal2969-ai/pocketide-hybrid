package dev.pocketide.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.pocketide.app.core.FileKinds
import dev.pocketide.app.core.Providers

class MainActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var store: ProjectStore
    private lateinit var editor: EditorPane
    private lateinit var preview: PreviewPane
    private lateinit var terminal: TerminalPane
    private lateinit var chat: ChatPane
    private lateinit var linux: LinuxRuntime  // GPL route
    private lateinit var serverHooks: LinuxServerHooks  // GPL route: runs Ollama for the Models screen
    private lateinit var git: GitPanel
    private lateinit var runner: RunController  // v0.4 W3: the ▶ button
    // v0.4 W1: projects, file tree drawer
    private lateinit var tree: FileTreePanel
    private lateinit var drawer: Drawer
    private lateinit var projectLabel: TextView
    private val projectListener: (String) -> Unit = { onProjectSwitched() }
    /** Set by editions that can run a local model server themselves (the GPL edition's Linux). */
    private val tabs = ArrayList<Pair<TextView, View>>()
    private var current = 0
    // GitHub-style layout (Settings > New layout): the Code tab opens on the repository page, files open in the editor
    private var newLayout = false
    private lateinit var repoHome: RepoHome
    private lateinit var homeView: HomeScreen
    /** The tab to go back to when Home was opened from inside the app; -1 when it is the page the app opened on. */
    private var homeReturn = -1
    private lateinit var codeHost: LinearLayout
    private lateinit var backBar: TextView
    private lateinit var editorFrame: FrameLayout
    private var viewingFile = false

    private val reqExport = 41
    private val reqImport = 42
    private val reqImage = 43  // v0.4 W2: image for the chat
    private val reqAddFiles = 44
    private val reqSaveCopy = 45
    private val reqSaveZip = 46
    private val reqChatFile = 47
    private val reqVoice = 48
    private var addFilesDir = ""
    private var savePath = ""
    private var exportSmall = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1.5.178: every connection says it is PocketIDE, not "Dalvik/2.1.0" (Ollama Cloud's edge answers that with 403)
        System.setProperty("http.agent", dev.pocketide.app.core.BuildTag.userAgent)
        dev.pocketide.app.HybridAi.appContext = applicationContext
        dev.pocketide.app.HybridCloud.start(applicationContext)   // Remote Config and anonymous sign-in, in the background
        CrashReport.install(this)  // GPL route
        dev.pocketide.app.core.AppLog.attach(java.io.File(filesDir, "applog.txt"))
        dev.pocketide.app.core.BuildTag.app = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION") val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            dev.pocketide.app.core.BuildTag.app(pi.versionName, code)
        } catch (e: Exception) { "" }
        ExitLog.note(this)
        dev.pocketide.app.core.TreeSitter.load()
        settings = AppSettings(this)
        // the light theme is read before any view is made (Ui colours), and the system dialogs follow it
        Ui.light = settings.appLight
        if (Ui.light) {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        }
        // add-ons: registered here (the core never refers to their code), off when the person switched them off.
        // 1.5.207: the judges' build registers none. The RuneScape game-cache add-on (addon/osrs) is not part of the
        // #BuildHybridAI entry (no third-party game names in the app), so there it shows nowhere: no Settings box, no tools.
        // 1.5.217: the owner's own build (no judges' token) has it again, as before 1.5.207.
        if (!resources.getBoolean(R.bool.judges_build)) dev.pocketide.app.core.AddOns.register(dev.pocketide.app.addon.osrs.OsrsAddOn)
        dev.pocketide.app.core.AddOns.disabled = settings.disabledAddOns
        ModelClient.workspaceId = settings.anthropicWorkspace
        dev.pocketide.app.core.Tools.webEnabled = settings.webResearch
        McpClient.refresh(settings)
        newLayout = settings.newLayout
        store = ProjectStore(Projects.current(this), seed = false)  // v0.4 W1: one folder per project
        val home = java.io.File(filesDir, "home").also { it.mkdirs() }

        editor = EditorPane(this, store, settings)
        preview = PreviewPane(this, store)
        preview.beforeReload = { if (::editor.isInitialized) editor.save() }
        terminal = TerminalPane(this, store, home)
        chat = ChatPane(this, store, settings, preview, terminal)

        chat.currentFile = { editor.path }
        chat.flush = { editor.save() }
        chat.onNeedSettings = { settingsDialog() }
        chat.onShowChat = { select(3) }
        chat.onHelp = { id -> showHelp(id) }
        // v0.4 W2: chat hooks (captureScreen is set to preview.capture() once the Preview tab provides it)
        chat.onOpenFile = { p, line -> select(0); showEditor(); editor.openAt(p, maxOf(1, line)) }
        chat.captureScreen = { preview.capture() }
        chat.currentSelection = { editor.selectedText() }
        chat.changesSummary = { git.changesSummary() }
        chat.onPickImage = {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), reqImage)
        }
        chat.onPickFile = {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), reqChatFile)
        }
        chat.onVoice = {
            try {
                startActivityForResult(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say what you want built or changed"), reqVoice)
            } catch (e: Exception) { toast("This phone has no speech recognizer. Use the microphone key on your keyboard instead.") }
        }
        chat.onSaveCopy = { p -> saveCopy(p) }
        editor.onSaveCopy = { p -> saveCopy(p) }
        terminal.onSaveCopy = { p -> saveCopy(p) }
        terminal.installedToolIds = { installedToolIds() }
        terminal.onInstallFix = { name, fix -> offerInstall(name, fix) }
        chat.onOpenGit = { git.open() }
        chat.onFilesChanged = { changedPaths ->
            terminal.problemsView.dropForFiles(changedPaths)
            editor.reload()
            if (newLayout && !viewingFile) repoHome.refresh()
            if (current == 1) preview.reload()
        }
        terminal.onFinished = { editor.reload() }

        // GPL route: Linux environment (PRoot + Alpine) and the full terminal
        linux = LinuxRuntime(this, settings)
        serverHooks = LinuxServerHooks(this, linux, { store.root }, home)
        terminal.linux = linux
        terminal.onSetUpLinux = { openTools(setUp = true) }
        terminal.onOpenFull = { editor.save(); startActivity(android.content.Intent(this, TerminalActivity::class.java)) }

        // v0.4 W3: Run button, Problems panel and local-server preview
        runner = RunController(this, store, LinuxRunHost(this, linux, store, terminal) { openTools(setUp = true) }, terminal, preview)
        dev.pocketide.app.core.Templates.extra = dev.pocketide.app.core.AddOns.templates()   // GPL: projects that need Linux (the 2009scape server, from the RuneScape add-on)
        runner.currentFile = { editor.path }
        runner.beforeRun = { editor.save() }
        runner.onShowTab = { i -> select(i) }
        terminal.problemsView.onOpenAt = { path, line, col -> select(0); showEditor(); editor.openAt(path, line, col) }
        terminal.problemsView.onAskAi = { text -> select(3); chat.ask(text) }
        terminal.problemsView.onChanged = { n ->
            tabs.getOrNull(2)?.first?.text = if (n > 0) "Terminal ⚠$n" else "Terminal"
            chat.reportProblems(terminal.problemsView.problems.map { it.where() + ": " + it.message })  // for /fix
        }
        terminal.onOpenUrl = { url -> preview.showServer(url); select(1) }
        preview.onAskAi = { question -> select(3); chat.ask(question) }
        git = GitPanel(this, store, settings, { editor.save() }) {
            editor.reload()
            if (current == 1) preview.reload()
        }

        // v0.4 W1: file tree drawer and project switching
        tree = FileTreePanel(this, store)
        drawer = Drawer(this, tree)
        tree.onClose = { drawer.close() }
        tree.onRefreshed = { if (newLayout && ::repoHome.isInitialized && !viewingFile) repoHome.refresh() }
        tree.beforeChange = { editor.save() }
        tree.onOpen = { p -> editor.open(p); drawer.close(); select(0); showEditor() }
        tree.onCreated = { _ -> if (current == 1) preview.reload() }
        tree.onDeleted = { p -> editor.fileGone(p); if (current == 1) preview.reload() }
        tree.onMoved = { a, b -> editor.fileMoved(a, b); if (current == 1) preview.reload() }
        tree.onSaveCopy = { p -> saveCopy(p) }
        tree.onHistory = { p, blame -> val h = vcsHost(); if (blame) VcsUi.blame(h, p) else VcsUi.fileHistory(h, p) }
        tree.onSaveFolder = { d -> saveFolderZip(d) }
        tree.onAddFiles = { d -> addFiles(d) }
        editor.onSearch = { openSearch() }
        editor.onAskLines = { t -> select(3); chat.ask(t) }
        editor.onLinkLines = { t -> select(3); chat.insertInput(t) }
        editor.onHistory = { p, blame -> if (!java.io.File(store.root, ".git/HEAD").isFile) android.widget.Toast.makeText(this, "Start version history first (project page → History).", android.widget.Toast.LENGTH_LONG).show()
            else { val h = vcsHost(); if (blame) VcsUi.blame(h, p) else VcsUi.fileHistory(h, p) } }
        editor.onShowFiles = { openFiles() }
        Projects.listeners.add(projectListener)

        val root = Ui.vertical(this)
        root.setBackgroundColor(Ui.BG)

        val top = Ui.horizontal(this)
        top.setBackgroundColor(Ui.SURFACE)
        top.setPadding(Ui.dp(this, 2), Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2))
        // v0.4 W1: ☰ opens the file tree; the project name opens the project picker
        val menuButton = Ui.text(this, "☰", 20f, Ui.TEXT)
        menuButton.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8))
        menuButton.setOnClickListener { openFiles() }
        if (!settings.newLayout) top.addView(menuButton)  // the repository page replaces the file drawer
        projectLabel = Ui.text(this, Projects.label(store.root.name) + "  ▾", 15f, Ui.TEXT)
        projectLabel.typeface = android.graphics.Typeface.DEFAULT_BOLD
        projectLabel.maxLines = 1
        projectLabel.ellipsize = android.text.TextUtils.TruncateAt.END
        projectLabel.setPadding(0, Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10))
        projectLabel.setOnClickListener { showHome() }
        top.addView(projectLabel, Ui.lp(0, Ui.WRAP, 1f))
        // v0.4 W3: ▶ runs the project; long-press for targets, custom commands and checks
        val play = Ui.text(this, "▶", 18f, Ui.GOOD)
        play.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8))
        play.setOnClickListener { runner.runDefault() }
        play.setOnLongClickListener { runner.showMenu(); true }
        Ui.tapTarget(play, "Run (long-press for options)")
        top.addView(play)
        for ((label, desc, action) in listOf<Triple<String, String, () -> Unit>>(
            Triple("⚙", "Settings", { settingsDialog() }),
            Triple("⋮", "Menu", { moreDialog() })
        )) {
            val t = Ui.text(this, label, 19f, Ui.ACCENT)
            t.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8))
            Ui.tapTarget(t, desc)
            t.setOnClickListener { action() }
            top.addView(t)
        }
        root.addView(top, Ui.lp(Ui.MATCH, Ui.WRAP))

        val content = FrameLayout(this)
        val codeView: View = if (newLayout) buildCodeHost() else editor
        for (v in listOf<View>(codeView, preview, terminal, chat)) content.addView(v, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        if (newLayout) {
            homeView = HomeScreen(this)
            homeView.onNew = { ProjectsScreen.newProject(this) { name -> editor.save(); Projects.switchTo(this, name); select(0) } }
            homeView.onClone = { ProjectsScreen.clone(this) { name -> editor.save(); Projects.switchTo(this, name); select(0) } }
            homeView.onOpen = { name -> openFromHome(name, 0) }
            homeView.onAsk = { name -> openFromHome(name, 3) }
            homeView.onOpenAt = { name, path, line -> openFromHome(name, 0); window.decorView.postDelayed({ editor.openAt(path, line); showEditor() }, 300) }
            homeView.onChanged = { projectLabel.text = Projects.label(store.root.name) + "  ▾" }
            homeView.hasAi = { val p = settings.provider; dev.pocketide.app.core.Starter.connected(p, settings.key(p).isNotBlank()) }
            ModelClient.engineReady = { model ->
                val m = dev.pocketide.app.core.Engine.byId(model)
                if (m == null) "Pick a model for the built-in engine: Menu ⋮ → Models (local AI) → Built-in engine." else EngineHost.ensure(applicationContext, m)
            }
            homeView.aiNote = { val p = settings.provider; dev.pocketide.app.core.Starter.keylessNote(p, settings.key(p).isNotBlank()) }
            homeView.hybrid = { settings.provider == "hybrid" }
            homeView.used = { settings.usageJson.isNotBlank() }
            homeView.onConnect = { showHelp(dev.pocketide.app.core.HelpContent.KEYS) }
            homeView.onTry = { prompt -> select(3); chat.ask(prompt) }
            homeView.onChat = { openChatHome() }
            homeView.onSearchChats = { ChatSearchScreen.show(this) { p, id -> openChatIn(p, id) } }
            homeView.visibility = View.GONE
            content.addView(homeView, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        }
        content.addView(drawer, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))  // v0.4 W1
        root.addView(content, Ui.lp(Ui.MATCH, 0, 1f))

        val bar = Ui.horizontal(this)
        bar.setBackgroundColor(Ui.SURFACE)
        val names = listOf("Code", "Preview", "Terminal", "AI")
        val views = listOf<View>(codeView, preview, terminal, chat)
        for (i in names.indices) {
            val t = Ui.text(this, names[i], 13f, Ui.MUTED)
            t.gravity = Gravity.CENTER
            t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14))
            t.setOnClickListener { if (i == 0 && current == 0 && newLayout && viewingFile && homeView.visibility != View.VISIBLE) showRepo() else select(i) }
            bar.addView(t, Ui.lp(0, Ui.WRAP, 1f))
            tabs.add(t to views[i])
        }
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))
        setContentView(root)
        // 1.5.188: a benchmark run cut off when PocketIDE was closed or killed: put the settings back, zip its finished tasks, offer to resume
        root.post { try { HybridBench.recover(this) } catch (e: Exception) { dev.pocketide.app.core.AppLog.add("bench", "recovering an interrupted run failed: ${e.message}") } }

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                WindowInsets.CONSUMED
            }
        }

        editor.projectChanged()  // v0.4 W1: restores this project's tabs
        select(if (Projects.isChat(this)) 3 else 0)   // an app last left in the Chat home opens on its chat (1.5.127)
        if (newLayout) { showRepo(); showHome(launch = true) }  // the app opens on Home, not on the code
        CrashReport.showIfAny(this)  // GPL route
        Welcome.showIfFirstRun(this, settings, { showHelp(dev.pocketide.app.core.HelpContent.KEYS) }, { showModels() }, welcomeHybrid())
    }

    private fun select(i: Int) {
        if (current == 0) editor.save()
        // 1.5.127: leaving a tab with the keyboard open left it covering the next one (the emulator test: half the Preview hidden)
        if (i != current) currentFocus?.let { f -> (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken, 0) }
        // 1.5.127: the Chat home has no files, so Code, Preview and Terminal showed its hidden ".chat" folder as an empty project
        // (emulator test). They open Home instead, where the person picks a project.
        if (i != 3 && Projects.isChat(this)) { showHome(); return }
        if (newLayout) homeView.visibility = View.GONE
        current = i
        // wide screens: Code and AI sit together, and Preview shows the code next to the page (1.5.113)
        val pair: Pair<Int, Int>? = if (!wideScreen()) null else when (i) { 0, 3 -> 0 to 3; 1 -> 0 to 1; else -> null }
        for ((k, p) in tabs.withIndex()) {
            val on = k == i || (pair != null && (k == pair.first || k == pair.second))
            p.first.setTextColor(if (k == i) Ui.ACCENT else Ui.MUTED)
            p.second.visibility = if (on) View.VISIBLE else View.GONE
        }
        layoutPanes(pair)
        if (i == 1) preview.reload()
        if (i == 3) chat.refresh()
        if (i == 0 && newLayout && !viewingFile) repoHome.refresh()
    }

    /** Tablets and phones turned sideways with room to spare: the code and the assistant sit next to each other. */
    private fun wideScreen(): Boolean = dev.pocketide.app.core.Layout.sideBySide(resources.configuration.screenWidthDp)

    private fun layoutPanes(pair: Pair<Int, Int>?) {
        if (tabs.size < 4) return
        val widthPx = resources.displayMetrics.widthPixels
        for ((k, p) in tabs.withIndex()) if (pair == null || (k != pair.first && k != pair.second)) p.second.layoutParams = FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH)
        if (pair == null) return
        val leftW = (widthPx * dev.pocketide.app.core.Layout.CODE_SHARE).toInt()
        tabs[pair.first].second.layoutParams = FrameLayout.LayoutParams(leftW, Ui.MATCH).also { it.gravity = android.view.Gravity.START }
        tabs[pair.second].second.layoutParams = FrameLayout.LayoutParams(widthPx - leftW, Ui.MATCH).also { it.gravity = android.view.Gravity.END }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::homeView.isInitialized && homeView.visibility == View.VISIBLE) return
        select(current)
    }

    // ---- GitHub-style layout ----

    /** The Home page: the repositories, with the current one on top. The classic layout keeps its project dialog. */
    private fun showHome(launch: Boolean = false) {
        if (!newLayout) { ProjectsScreen.show(this, onChat = { openChatHome() }, onSearchChats = { ChatSearchScreen.show(this) { p, id -> openChatIn(p, id) } }) { editor.save() }; return }
        editor.save()
        if (homeView.visibility != View.VISIBLE) homeReturn = if (launch) -1 else current
        homeView.refresh()
        homeView.visibility = View.VISIBLE
        for (t in tabs) t.first.setTextColor(Ui.MUTED)
    }

    /** The Chat home (1.5.125): a chat with no project, on the AI tab. */
    private fun openChatHome() {
        if (Projects.key(this) != Projects.CHAT) { editor.save(); Projects.openChat(this) }
        select(3)
    }

    /** Opens a chat found by "Search all chats" in its own project (1.5.125). */
    private fun openChatIn(project: String, id: String) {
        if (project != Projects.key(this)) { editor.save(); if (project == Projects.CHAT) Projects.openChat(this) else Projects.switchTo(this, project) }
        select(3)
        chat.openChatById(id)
    }

    /** Opens a repository from Home and goes to a tab (0 Code, 3 AI). */
    private fun openFromHome(name: String, tab: Int) {
        if (name != Projects.key(this)) { editor.save(); Projects.switchTo(this, name) }
        select(tab)
    }

    private fun buildCodeHost(): View {
        repoHome = RepoHome(this, store, settings)
        repoHome.onOpenFile = { p -> editor.open(p); showEditor() }
        repoHome.onRun = { runner.runDefault() }
        repoHome.onPreview = { select(1) }
        repoHome.onFixBuild = { t -> repoHome.onAi(); chat.ask(t) }
        repoHome.onAi = { select(3) }
        repoHome.onSearch = { openSearch() }
        repoHome.onGit = { git.open() }
        repoHome.onRestored = { paths -> chat.onFilesChanged(paths); chat.refresh() }
        repoHome.hostFactory = { vcsHost() }
        chat.onCommitted = { id -> CollabUi.afterCommit(vcsHost(), id) }
        repoHome.onFileMenu = { p -> tree.fileActions(p) }
        repoHome.onFolderMenu = { p -> tree.folderActions(p) }
        repoHome.onNewFile = { d -> tree.newFile(d) }
        repoHome.onNewFolder = { d -> tree.newFolder(d) }
        repoHome.onAddFiles = { d -> addFiles(d) }
        backBar = Ui.text(this, "", 13f, Ui.ACCENT)
        backBar.setBackgroundColor(Ui.SURFACE)
        backBar.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10))
        backBar.maxLines = 1
        backBar.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        backBar.setOnClickListener { showRepo() }
        editorFrame = FrameLayout(this)
        editorFrame.addView(editor, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        editorFrame.addView(repoHome, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        codeHost = Ui.vertical(this)
        codeHost.addView(backBar, Ui.lp(Ui.MATCH, Ui.WRAP))
        codeHost.addView(editorFrame, Ui.lp(Ui.MATCH, 0, 1f))
        return codeHost
    }

    /** Shows the repository page (new layout only). */
    private fun showRepo() {
        if (!newLayout) return
        editor.save()
        viewingFile = false
        editor.visibility = View.GONE
        repoHome.visibility = View.VISIBLE
        backBar.visibility = View.GONE
        repoHome.refresh()
    }

    /** Shows the editor (new layout only); the old layout always shows it. */
    private fun showEditor() {
        if (!newLayout) return
        viewingFile = true
        repoHome.visibility = View.GONE
        editor.visibility = View.VISIBLE
        backBar.text = "‹  " + store.root.name + (if (editor.path.isNotEmpty()) "  /  " + editor.path else "")
        backBar.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        terminal.refreshHint()  // GPL route
        editor.reload()         // the full terminal may have changed files
        if (newLayout && !viewingFile && current == 0) repoHome.refresh()
    }

    override fun onStart() {
        super.onStart()
        HybridBench.screen(this, true)   // 1.5.188: a benchmark paused while PocketIDE was off screen carries on
    }

    override fun onStop() {
        HybridBench.screen(this, false)
        // leaving the chat screen during a request can stop it; the record says so
        dev.pocketide.app.core.ToolTrace.target?.let { dev.pocketide.app.core.ToolTrace.write(dev.pocketide.app.core.ToolTrace.event("the app left the screen during a request (it may stop)")) }
        super.onStop()
    }

    override fun onPause() {
        editor.save()
        super.onPause()
    }

    override fun onDestroy() {
        HybridBench.gone(this)
        Projects.listeners.remove(projectListener)  // v0.4 W1
        preview.destroy()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---- v0.4 W1: projects, file tree, search ----

    private fun openFiles() {
        editor.save()
        if (newLayout) { select(0); showRepo(); return }  // the repository page is the file browser
        tree.select(editor.path)
        tree.refresh()
        drawer.open()
    }

    private fun openSearch() {
        editor.save()
        SearchScreen.show(this, store, { p, line, col -> select(0); showEditor(); editor.openAt(p, line, col) }) { changedFiles ->
            editor.reload()
            if (current == 1) preview.reload()
            if (changedFiles.isNotEmpty() && drawer.isOpen) tree.refresh()
        }
    }

    /** Runs after Projects.switchTo (and after renaming or deleting the open project). */
    /**
     * A report about PocketIDE itself goes to its own project ("PocketIDE reports", made when first needed) and a new chat
     * there, so it never lands in the middle of the person's own work.
     */
    // ---- the hybrid AI benchmark (HybridBench) drives the real chat screen through these ----
    fun benchChat(): ChatPane = chat

    /** Opens [project] and sends [prompt] in a new chat there, as a person would. */
    fun benchAsk(project: String, prompt: String) {
        editor.save()
        if (Projects.key(this) != project) Projects.switchTo(this, project)
        select(3)
        chat.askInNewChat(prompt)
    }

    /** The from-scratch benchmark: a new conversation in the chat with no project. */
    fun benchChatHome(prompt: String) {
        editor.save()
        if (Projects.key(this) != Projects.CHAT) Projects.switchTo(this, Projects.CHAT)
        select(3)
        chat.askInNewChat(prompt)
    }

    /** The from-scratch benchmark: "Build this as a project" on the current chat, sending the build request. Returns the project. */
    fun benchBuildFromChat(name: String): String = chat.buildAs(name, send = true)

    /** 1.5.215, the demo recording: runs [js] in the visible Preview page and gives back its result (JSON text, as WebView gives it). */
    fun demoPreviewScript(js: String, result: (String) -> Unit) = preview.runScript(js, result)

    /** 1.5.213, the demo recording: the settings it switches to Hybrid AI and Agent mode (HybridBench.restore puts them back). */
    fun settingsForDemo(): AppSettings = settings

    /** 1.5.213, the demo recording: whether Linux is running (the Python scene needs it). */
    fun demoLinuxActive(): Boolean = linux.active

    /** 1.5.213, the demo recording: shows a tab (0 Code, 1 Preview, 2 Terminal, 3 AI). */
    fun benchSelect(i: Int) = select(i)

    /** 1.5.213, the demo recording: the welcome screen as a new user sees it; [useHybrid] runs when its Hybrid AI card is chosen. */
    fun demoWelcome(useHybrid: () -> Unit): View? = Welcome.show(this, {}, {}, useHybrid)

    fun benchSwitch(project: String) {
        editor.save()
        if (Projects.key(this) != project) Projects.switchTo(this, project)
    }

    private fun reportInOwnChat(report: String) {
        val wanted = "PocketIDE reports"
        val name = Projects.list(this).firstOrNull { it == wanted } ?: Projects.create(this, wanted, dev.pocketide.app.core.Templates.EMPTY)
        editor.save()
        if (Projects.key(this) != name) Projects.switchTo(this, name)
        select(3)
        chat.askInNewChat(report)
    }

    private fun onProjectSwitched() {
        store.switchTo(Projects.current(this))
        projectLabel.text = Projects.label(store.root.name) + "  ▾"
        drawer.close()
        tree.reset()
        editor.projectChanged()
        preview.showFiles()  // v0.4 W3: back to the project's own files
        if (newLayout) { repoHome.reset(); showRepo() }
        terminal.showProblems(emptyList(), "", null)  // problems belonged to the old project
        chat.projectChanged()
        select(if (Projects.isChat(this)) 3 else 0)   // the Chat home lives on the AI tab (1.5.127)
        // a setup file that is new or changed: offer to run it (GPL: needs the Linux environment)
        if (linux.active && store.exists(dev.pocketide.app.core.ProjectSetup.FILE)) window.decorView.postDelayed({ ProjectUi.offerSetup(vcsHost()) }, 800)
    }

    @Deprecated("Framework back handling; this app avoids AndroidX")
    override fun onBackPressed() {
        if (drawer.isOpen) { drawer.close(); return }
        if (newLayout && homeView.visibility == View.VISIBLE && homeReturn >= 0) { select(homeReturn); return }
        if (newLayout && current == 0) {
            if (viewingFile) { showRepo(); return }
            if (repoHome.goUp()) return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---- ⋮ menu, help and models ----

    /** The ⋮ menu, grouped so related things sit together. */
    private fun moreSections(): List<Pair<String, List<Pair<String, () -> Unit>>>> = listOf(
        "Project" to listOf(
            "Home: all repositories" to { showHome() },
            "Git: commit, push, pull" to { git.open() },
            "Search in project" to { openSearch() },
            "Find a definition (functions, classes, ids)" to { SymbolsScreen.show(this, store) { p, line -> select(0); showEditor(); editor.openAt(p, line, 0) } },
            "Add files from phone…" to { addFiles("") },
            "New starter project…" to { startersDialog() },
            "Export or import zip" to { zipDialog() }
        ),
        "Run and check" to listOf(
            "Run…" to { runner.showMenu() },
            "Check project for errors" to { runner.checkProject() },
            "Full terminal" to { editor.save(); startActivity(android.content.Intent(this, TerminalActivity::class.java)) },
            "Errors from the published site (Cloudflare)" to { select(3); chat.showSiteErrors() },
            "API tester (send requests to a server)" to { ApiScreen.show(this, Projects.key(this)) },
            "Database viewer (SQLite, Supabase)" to { DbScreen.show(this, store, Projects.key(this)) },
            "Remote machine (SSH: run builds and commands on a server)" to { RemoteScreen.show(this, settings, store, Projects.key(this)) { paths -> chat.onFilesChanged(paths) } },
            "Tools & Linux (one-tap installs)" to { openTools() }
        ),
        "AI" to listOf(
            "Settings (provider, key, behaviour)" to { settingsDialog() },
            "Models (local AI)" to { showModels() },
            "Remote server setup (bigger models)" to {
                AlertDialog.Builder(this).setTitle(dev.pocketide.app.core.RemoteGuide.TITLE)
                    .setView(ScrollView(this).also { sv -> sv.addView(Ui.text(this, dev.pocketide.app.core.RemoteGuide.steps(), 13f, Ui.TEXT, false).also { it.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8)); it.setTextIsSelectable(true) }) })
                    .setPositiveButton("Open settings") { _, _ -> settingsDialog("remote") }.setNegativeButton("Close", null).show()
            },
            "Usage & cost" to { UsageScreen.show(this, settings) },
            "Outside tool servers (MCP)" to { McpScreen.show(this, settings) },
            "Agent profile (reviewer, debugger, planner…)" to { AgentProfileScreen.show(this, settings) { chat.refresh() } },
            "Multi-model workflow and advisor" to { WorkflowScreen.show(this, settings) },
            "Record a demo video (Hybrid AI, one tap)" to { DemoRun.start(this) },
            "Sync chats & backups (Supabase)" to { SyncScreen.show(this, settings, store) { chat.refresh() } }
        ),
        "App" to listOf(
            "Fix the app itself (crash, newest build)" to { SelfFixScreen.show(this, settings) { report -> reportInOwnChat(report) } },
            "Diagnostics (check everything)" to { DiagnosticsScreen.show(this, settings, store) },
            "Help & FAQ" to { showHelp(null) },
            "Welcome screen" to { Welcome.show(this, { showHelp(dev.pocketide.app.core.HelpContent.KEYS) }, { showModels() }, welcomeHybrid()) },
            "Licenses" to { LinuxScreen.licenses(this) }
        )
    )

    private fun moreDialog() {
        val col = Ui.vertical(this)
        col.setPadding(0, 0, 0, Ui.dp(this, 8))
        lateinit var dlg: AlertDialog
        for ((title, items) in moreSections()) {
            col.addView(sectionHeading(title))
            for ((label, action) in items) {
                val t = Ui.text(this, label, 15f, Ui.TEXT)
                t.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12))
                t.setOnClickListener { dlg.dismiss(); action() }
                col.addView(t)
            }
        }
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { null }
        if (version != null) col.addView(Ui.text(this, "About: PocketIDE $version", 12f, Ui.MUTED).also { it.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 4)) })
        val sv = ScrollView(this)
        sv.addView(col)
        dlg = AlertDialog.Builder(this).setTitle("Menu").setView(sv).setNegativeButton("Close", null).create()
        dlg.show()
    }

    /** A small section title used in the menu and in Settings. */
    private fun sectionHeading(title: String): TextView {
        val h = Ui.text(this, title.uppercase(), 12f, Ui.ACCENT)
        h.typeface = android.graphics.Typeface.DEFAULT_BOLD
        h.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 4))
        return h
    }

    // GPL route: shared by the ⋮ menu and the Run button
    /** [setUp]: start the whole Linux setup at once (add-on, Linux, Python/Node/git), as from "Set up Linux" (1.5.211). */
    private fun openTools(setUp: Boolean = false) {
        ToolsScreen.show(this, linux, settings, { terminal.refreshHint(); chat.refresh() }, autoSetup = setUp && !linux.active)
    }

    private fun showHelp(section: String?) {
        HelpScreen.show(this, section, openSettingsFor = { id -> settingsDialog(id) }, openModels = { showModels() })
    }

    private fun showModels() {
        ModelsScreen.show(this, settings, serverHooks) { chat.refresh() }
    }

    /** The project's history screens (VcsUi): unsaved editor text is written first, and rewritten files reload everywhere. */
    fun vcsHost(): VcsUi.Host {
        editor.save()
        return VcsUi.Host(this, store, settings,
            { paths -> chat.onFilesChanged(paths); tree.refresh(); chat.refresh() },
            { t -> select(3); chat.ask(t) },
            { p -> editor.open(p); showEditor() },
            { cmd, done -> chat.runForChecks(cmd, done) },
            { f -> saveExternal(f) })
    }

    /** Asks where to save a file kept outside the project (a release's file) on the phone. */
    fun saveExternal(f: java.io.File) {
        if (!f.isFile) { toast("That file is gone"); return }
        savePath = "file:" + f.absolutePath
        val it = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(dev.pocketide.app.core.FileKinds.mime(f.name)).putExtra(Intent.EXTRA_TITLE, f.name)
        startActivityForResult(it, reqSaveCopy)
    }

    // ---- zip ----

    private fun zipDialog() {
        AlertDialog.Builder(this).setTitle("Project zip").setItems(arrayOf("Export project as zip", "Export without large files (over 20 MB)", "Import zip into project")) { _, i ->
            if (i == 0 || i == 1) {
                editor.save(); exportSmall = i == 1
                val it = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, store.root.name.replace(' ', '-') + ".zip")  // v0.4 W1
                startActivityForResult(it, reqExport)
            } else {
                val it = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                startActivityForResult(it, reqImport)
            }
        }.show()
    }

    @Deprecated("Framework result API; this app avoids AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == reqAddFiles) { importFiles(pickedUris(data), addFilesDir); return }
        if (requestCode == reqChatFile) { chat.onFilesPicked(pickedUris(data)); return }
        if (requestCode == reqVoice) { chat.onVoiceText(data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()); return }
        val uri: Uri = data.data ?: return
        if (requestCode == reqImage) { chat.onImagePicked(uri); return }  // v0.4 W2
        try {
            if (requestCode == reqSaveCopy) {
                val src = if (savePath.startsWith("file:")) java.io.File(savePath.removePrefix("file:")).takeIf { it.isFile }?.inputStream() else store.openStream(savePath)
                if (src == null) toast("That file no longer exists")
                else {
                    src.use { s -> contentResolver.openOutputStream(uri)?.use { s.copyTo(it) } }
                    toast("Saved ${savePath.substringAfterLast('/')}")
                }
            } else if (requestCode == reqSaveZip) {
                toast("Saving…")
                val dir = savePath
                Thread {
                    val ok = try { contentResolver.openOutputStream(uri)?.use { store.exportZip(it, dir) }; true } catch (e: Exception) { false }
                    runOnUiThread { toast(if (ok) "Saved ${dir.substringAfterLast('/')}.zip" else "Could not save the zip") }
                }.start()
            } else if (requestCode == reqExport) {
                toast("Exporting…")
                val small = exportSmall
                Thread {
                    val limit = if (small) 20L * 1024 * 1024 else 0L
                    var skipped: List<Pair<String, Long>> = emptyList()
                    val ok = try { contentResolver.openOutputStream(uri)?.use { skipped = store.exportZip(it, "", limit) }; true } catch (e: Exception) { false }
                    runOnUiThread {
                        when {
                            !ok -> toast("Could not export the zip")
                            skipped.isEmpty() -> toast("Exported" + (if (small) ": no file was over 20 MB, so nothing was left out" else ""))
                            else -> AlertDialog.Builder(this).setTitle("Exported, ${skipped.size} file${if (skipped.size == 1) "" else "s"} left out")
                                .setMessage(ProjectStore.leftOutNote(skipped, limit) + "\nThe same list is inside the zip as ${ProjectStore.LEFT_OUT}.")
                                .setPositiveButton("OK", null).show()
                        }
                    }
                }.start()
            } else if (requestCode == reqImport) {
                // a big zip takes a while to unpack, so it is done off the main thread
                toast("Importing…")
                // the zip's name becomes the folder it unpacks into (a single top folder inside the zip is still removed)
                val named = displayName(uri).removeSuffix(".zip").removeSuffix(".ZIP").replace(Regex("""[/\\:*?"<>|]"""), "_").trim().trimStart('.').ifEmpty { "imported" }
                val folder = if (store.list().none { !it.startsWith(".") }) "" else named
                Thread {
                    val n = try { contentResolver.openInputStream(uri)?.use { store.importZip(it, folder) } ?: 0 } catch (e: Exception) { -3 }
                    runOnUiThread {
                        val lim = dev.pocketide.app.core.UploadLimits
                        when {
                            n == -1 -> toast("That zip is too large (${lim.mb(lim.ZIP_TOTAL)} limit once unpacked)")
                            n == -2 -> toast("That zip has too many files (${lim.ZIP_ENTRIES} limit)")
                            n < 0 -> toast("Could not read that zip")
                            else -> {
                                toast("Imported $n file${if (n == 1) "" else "s"}" + if (folder.isEmpty()) "" else " into $folder/")
                                val keep = editor.path
                                editor.open(if (keep.isNotEmpty() && store.exists(keep)) keep else (if (store.exists("index.html")) "index.html" else store.list().firstOrNull() ?: ""))
                                if (drawer.isOpen) tree.refresh()  // v0.4 W1
                            }
                        }
                    }
                }.start()
            }
        } catch (e: Exception) {
            toast("Failed: ${e.message}")
        }
    }

    // ---- any file in and out ----

    /** Asks where to save a copy of a project file on the phone (any type: documents, builds, archives). */
    fun saveCopy(path: String) {
        editor.save()
        if (!store.exists(path)) { toast("That file no longer exists"); return }
        savePath = path
        val it = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(FileKinds.mime(path)).putExtra(Intent.EXTRA_TITLE, path.substringAfterLast('/'))
        startActivityForResult(it, reqSaveCopy)
    }

    private fun saveFolderZip(dir: String) {
        editor.save()
        savePath = dir
        val it = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip").putExtra(Intent.EXTRA_TITLE, dir.substringAfterLast('/') + ".zip")
        startActivityForResult(it, reqSaveZip)
    }

    /** Opens the phone's file picker (several files allowed) and copies the choices into a project folder ("" for the root). */
    private fun addFiles(dir: String) {
        addFilesDir = dir
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), reqAddFiles)
    }

    private fun pickedUris(data: Intent): List<Uri> {
        val out = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) { for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) } }
        else data.data?.let { out.add(it) }
        return out
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) { val n = c.getString(0); if (!n.isNullOrBlank()) return n }
            }
        } catch (e: Exception) { /* fall through */ }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun importFiles(uris: List<Uri>, dir: String) {
        if (uris.isEmpty()) return
        editor.save()
        toast("Adding ${uris.size} file${if (uris.size == 1) "" else "s"}…")
        Thread {
            val added = ArrayList<String>()
            var failed = 0
            for (u in uris) {
                val path = store.uniquePath(dir, displayName(u))
                val ok = try { contentResolver.openInputStream(u)?.use { store.writeStream(path, it) } ?: false } catch (e: Exception) { false }
                if (ok) added.add(path) else failed++
            }
            runOnUiThread {
                if (added.isEmpty()) { toast("Could not add the file${if (uris.size == 1) "" else "s"} (${dev.pocketide.app.core.UploadLimits.mb(dev.pocketide.app.core.UploadLimits.FILE)} limit per file)"); return@runOnUiThread }
                toast("Added " + added.joinToString(", ") { it.substringAfterLast('/') } + if (failed > 0) " ($failed failed)" else "")
                if (drawer.isOpen) tree.refresh()
                if (newLayout && ::repoHome.isInitialized && !viewingFile && current == 0) repoHome.refresh()
                if (current == 1) preview.reload()
                if (added.size == 1) { editor.open(added[0]); select(0) }
                chat.refresh()
            }
        }.start()
    }

    // ---- settings ----

    /** select: open with this provider shown (from Help's "Paste key in Settings"), pasting a copied key if the field is empty. */
    private fun settingsDialog(select: String? = null) {
        val pad = Ui.dp(this, 20)
        val box = Ui.vertical(this)
        box.setPadding(pad, Ui.dp(this, 8), pad, 0)
        val providers = Providers.all
        val spin = Spinner(this)
        spin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers.map { it.name })
        val note = Ui.text(this, "", 12f, Ui.MUTED)
        val key = EditText(this)
        key.hint = "API key"
        key.setSingleLine()
        key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val model = EditText(this)
        model.hint = "Model"
        model.setSingleLine()
        model.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        val base = EditText(this)
        base.hint = "Server address, e.g. http://127.0.0.1:11434/v1"
        base.setSingleLine()
        base.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val all = CheckBox(this).also { it.text = "Send all project files as context"; it.isChecked = settings.everything }
        val agent = CheckBox(this).also { it.text = "Agent mode (apply changes and fix errors automatically)"; it.isChecked = settings.agent }
        val autoAgent = CheckBox(this).also { it.text = "Choose agent mode for each request (questions stay in review; building or fixing applies changes)"; it.isChecked = settings.autoMode }
        val autoCredit = CheckBox(this).also { it.text = "When a provider runs out of credit, try the next provider that has a key"; it.isChecked = settings.autoSwitchCredit }
        val auto = CheckBox(this).also { it.text = "Run commands from the assistant without asking"; it.isChecked = settings.autorun }
        // v0.4 W2
        val tools = CheckBox(this).also { it.text = "Native tool calling when the provider supports it (Groq, Gemini, OpenRouter, OpenAI, Anthropic…)"; it.isChecked = settings.nativeTools }
        val reqBox = CheckBox(this).also { it.text = "Big requests: list the requirements first, then check the result against them"; it.isChecked = settings.requirementsFirst }
        val partsBox = CheckBox(this).also { it.text = "Long lists: build in parts (each part its own pass, then check they fit together)"; it.isChecked = settings.splitParts }
        val webBox = CheckBox(this).also { it.text = "Web research: the assistant may search the web and read pages (asks before reading a site)"; it.isChecked = settings.webResearch }
        val queueBox = CheckBox(this).also { it.text = "Verified steps: check each plan step against its order before the next one starts (one short extra review per step)"; it.isChecked = settings.verifySteps }
        val parallelBox = CheckBox(this).also { it.text = "Build in parts: build parts that share no files at the same time, on every provider that has a key"; it.isChecked = settings.parallelParts }
        val partsAlwaysBox = CheckBox(this).also { it.text = "Build in parts for any list of 3 or more requirements, not only long lists"; it.isChecked = settings.partsAlways }
        val addOnBoxes = dev.pocketide.app.core.AddOns.all().map { a ->
            a.id to CheckBox(this).also { it.text = a.title + ": " + a.summary + " Only for projects that hold its files."; it.isChecked = a.id !in settings.disabledAddOns }
        }
        val partsSameBox = CheckBox(this).also { it.text = "Without a team below, build parts at the same time with 3 copies of the main model"; it.isChecked = settings.partsSameModel }
        val partsPickBox = CheckBox(this).also { it.text = "Let the assistant choose which team model builds each part (otherwise they take turns)"; it.isChecked = settings.partsPick }
        val partsTeamBox = EditText(this).also {
            it.hint = "Parts team, one model per line: provider model (e.g. anthropic claude-sonnet-5-5, or kilo nvidia/nemotron-3-ultra-550b-a55b:free). The same model may be listed twice. Empty: every provider with a key."
            it.setText(settings.partsTeam.joinToString("\n") { s -> s.provider + " " + s.model }.trim())
            it.minLines = 2
        }
        val fastBox = CheckBox(this).also { it.text = "Fast mode: no second-opinion model calls (advisor, reviewer, explore helper, cascade, critique)"; it.isChecked = settings.fastMode }
        val routeBox = CheckBox(this).also { it.text = "Phase routing: reading steps use the cheaper draft model; edits and answers use the main model"; it.isChecked = settings.phaseRouting }
        val tipsBox = CheckBox(this).also { it.text = "Show model tips in the chat (suggestions to try another model)"; it.isChecked = settings.modelTips }
        val pyBox = CheckBox(this).also { it.text = "Python helper for Gemini Nano: a Python note, edits fixed to the right indentation, outlines of long files, and where a failing test points (experimental)"; it.isChecked = settings.pythonHelper }
        val slowBox = CheckBox(this).also { it.text = "When a reply shows no sign of life for a minute, offer a retry or another provider"; it.isChecked = settings.slowOffer }
        val embedBox = EditText(this).also { it.hint = "Search by meaning (optional): embeddings model as provider:model, e.g. cloudflare:@cf/baai/bge-base-en-v1.5. Sends your project's text to that provider (secret files left out, keys masked)"; it.setSingleLine(true); it.setText(settings.embedModel) }
        val braveBox = EditText(this).also { it.hint = "Brave Search API key (optional, gives better search results)"; it.setSingleLine(true); it.setText(settings.braveKey) }
        val planFirst = CheckBox(this).also { it.text = "Plan big requests first (approve a step-by-step plan)"; it.isChecked = settings.planFirst }
        // v0.4 W1: editor
        val edLight = CheckBox(this).also { it.text = "Light editor theme"; it.isChecked = settings.editorTheme == "light" }
        val appLightBox = CheckBox(this).also { it.text = "Light theme for the whole app (the screen restarts)"; it.isChecked = settings.appLight }
        val edWrap = CheckBox(this).also { it.text = "Word wrap in the editor"; it.isChecked = settings.editorWrap }
        val announceBox = CheckBox(this).also { it.text = "Screen reader: say what the assistant is doing (\"Reading\", \"Running\") while it works"; it.isChecked = settings.workAnnounce }
        val edAuto = CheckBox(this).also { it.text = "AI suggestions while typing (uses your provider)"; it.isChecked = settings.autoComplete }
        // 1.5.125: saved only when changed, so an untouched box keeps following the network (off on mobile data)
        val nextEditShown = settings.nextEditOn(dev.pocketide.app.core.NetWatch.metered(this))
        val edNext = CheckBox(this).also { it.text = "Next-edit suggestions: after you pause, the suggestion model proposes the next change elsewhere in the file (off on mobile data unless you tick it)"; it.isChecked = nextEditShown }
        val edModel = EditText(this).also { it.hint = "Faster model for suggestions (optional, e.g. a small one)"; it.setSingleLine(true); it.setText(settings.completionModel(settings.provider)) }
        val workspace = EditText(this).also { it.hint = "Anthropic workspace ID (only if you get a 'workspace' error)"; it.setSingleLine(true); it.setText(settings.anthropicWorkspace) }
        val test = Ui.button(this, "Test connection") {}
        val testOut = Ui.text(this, "", 12f, Ui.MUTED)
        // hybrid AI: its settings live on the Hybrid AI screen, opened from here when that provider is chosen
        val hybridBtn = Ui.button(this, "Hybrid AI settings (temperature, limits)") { HybridScreen.show(this) }
        // under the key: how to get a free one, and paste from the clipboard
        val keyHelp = Ui.horizontal(this)
        val howTo = Ui.text(this, "How do I get a free key?", 13f, Ui.ACCENT)
        howTo.setPadding(0, Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6))
        keyHelp.addView(howTo, Ui.lp(0, Ui.WRAP, 1f))
        val pasteKey = Ui.chip(this, "Paste") {}
        keyHelp.addView(pasteKey)
        val cacheBox = CheckBox(this).also { it.text = "Reuse the start of requests to cut cost (prompt caching)"; it.isChecked = settings.promptCache }
        val cacheLongBox = CheckBox(this).also { it.text = "Keep the saved start of requests for an hour (Anthropic; costs a little more to save, pays off if you pause more than 5 minutes between requests)"; it.isChecked = settings.cacheTtl == "1h" }
        val verifyBox = CheckBox(this).also { it.text = "Run tests or checks after the assistant changes code (Agent on)"; it.isChecked = settings.verify }
        val causeBox = CheckBox(this).also { it.text = "Problem reports: name the cause before the first change, and question changed data files (experimental)"; it.isChecked = settings.causeFirst }
        val askBox = CheckBox(this).also { it.text = "When a request asks for several things, check each one before finishing"; it.isChecked = settings.askCheck }
        val scopeBox = CheckBox(this).also { it.text = "Keep to what was asked: say what the project lacks instead of adding it, and question extra edits (experimental)"; it.isChecked = settings.scopeCheck }
        val exploreBox = CheckBox(this).also { it.text = "Explore helper: a second model call reads the project for the assistant (uses more tokens; off by default)"; it.isChecked = settings.exploreHelper }
        val premiseBox = CheckBox(this).also { it.text = "When a request says something disappeared, search for it first, and report it if it was never there (experimental)"; it.isChecked = settings.premiseCheck }
        val freshBox = CheckBox(this).also { it.text = "Before finishing, have a fresh reviewer compare the request with the changes (one extra model call; experimental)"; it.isChecked = settings.verifyFresh }
        val varietyBox = CheckBox(this).also { it.text = "Code that reads files or web pages: try it on other shapes of input before finishing (experimental)"; it.isChecked = settings.varietyProbe }
        val layoutBox = CheckBox(this).also { it.text = "GitHub-style repository page (turn off for the classic editor and file drawer; restarts the screen)"; it.isChecked = settings.newLayout }
        val tapBox = CheckBox(this).also { it.text = "Tap a few harmless buttons when checking a page (skips Delete, Send, Buy…)"; it.isChecked = settings.pageTap }
        val secondBox = CheckBox(this).also { it.text = "Second try: when a request ends unfinished, undo it and try once more from the start"; it.isChecked = settings.secondTry }
        val escalateBox = CheckBox(this).also { it.text = "When a request ends unfinished, carry on with the strong model (Workflow) at once, instead of offering a button. Not yet measured: the button is the tested way"; it.isChecked = settings.autoEscalate }
        val roundsLabel = Ui.text(this, "Tool rounds per request. This also sets how long the text format may look around and fix, and how many rounds each part of a parts run gets. Each run has a time limit that grows with this setting. Autonomous removes the round cap but still stops after 90 minutes or if it gets stuck. Long runs use a lot of your provider's quota.", 12f, Ui.MUTED, false)
        val rounds = Spinner(this)
        // 1.5.99: how hard thinking models think before each step (ThinkEffort); the default leaves each model as it is
        val effortLabel = Ui.text(this, "Thinking before each step (models that think, such as Kimi K2.6 and GPT-OSS). Lower is faster; the model's own setting is the default. A provider that does not take it is asked without it.", 12f, Ui.MUTED, false)
        val effort = Spinner(this).also { sp ->
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dev.pocketide.app.core.ThinkEffort.LEVELS.map { it.second })
            sp.setSelection(maxOf(0, dev.pocketide.app.core.ThinkEffort.LEVELS.indexOfFirst { it.first == settings.thinkEffort }))
        }
        val runLabel = Ui.text(this, "Limit per request. When one request reaches it, the assistant pauses and asks before spending more. Every step counts: fixes, reviews and the advisor. 0 = no limit.", 12f, Ui.MUTED, false)
        val runUsd = EditText(this).also { it.hint = "Dollars, e.g. 1.00"; it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; it.setText(if (settings.runUsd > 0) String.format(java.util.Locale.US, "%.2f", settings.runUsd) else "0") }
        val runTok = EditText(this).also { it.hint = "Tokens, e.g. 3M or 500k"; it.setSingleLine(); it.setText(if (settings.runTokens > 0) dev.pocketide.app.core.RunBudget.count(settings.runTokens) else "0") }
        var chosenRounds = settings.maxRounds
        val roundNames = ArrayList<String>()
        val roundValues = ArrayList<Int>()
        var shownPos = -1  // setting a new adapter makes the spinner "select" the shown row again; that must not reopen the dialog
        fun fillRounds() {
            roundNames.clear(); roundValues.clear()
            for (c in dev.pocketide.app.core.Rounds.CHOICES) { roundNames.add(dev.pocketide.app.core.Rounds.label(c)); roundValues.add(c) }
            val custom = chosenRounds !in dev.pocketide.app.core.Rounds.CHOICES && !dev.pocketide.app.core.Rounds.isAutonomous(chosenRounds)
            roundNames.add(if (custom) "Custom: $chosenRounds rounds" else "Custom number…"); roundValues.add(-1)
            roundNames.add(dev.pocketide.app.core.Rounds.label(dev.pocketide.app.core.Rounds.AUTONOMOUS)); roundValues.add(dev.pocketide.app.core.Rounds.AUTONOMOUS)
            val cur0 = if (dev.pocketide.app.core.Rounds.isAutonomous(chosenRounds)) roundValues.size - 1 else if (custom) roundValues.size - 2 else roundValues.indexOf(chosenRounds)
            shownPos = cur0.coerceAtLeast(0)
            rounds.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, roundNames)
            rounds.setSelection(shownPos, false)
        }
        fillRounds()
        rounds.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == shownPos) return
                val v = roundValues[position]
                if (v >= 0) { if (v != chosenRounds) { chosenRounds = v; fillRounds() }; return }
                val et = EditText(this@MainActivity)
                et.inputType = InputType.TYPE_CLASS_NUMBER
                et.hint = "${dev.pocketide.app.core.Rounds.MIN} to ${dev.pocketide.app.core.Rounds.MAX}"
                if (chosenRounds > 0) et.setText(chosenRounds.toString())
                AlertDialog.Builder(this@MainActivity).setTitle("Rounds per request").setMessage("How many tool rounds the assistant may use. Each round is one model call plus its tool calls.")
                    .setView(et)
                    .setPositiveButton("Set") { _, _ ->
                        val n = dev.pocketide.app.core.Rounds.parseCustom(et.text.toString())
                        if (n == null) toast("Enter a number from ${dev.pocketide.app.core.Rounds.MIN} to ${dev.pocketide.app.core.Rounds.MAX}") else chosenRounds = n
                        fillRounds()
                    }
                    .setNegativeButton("Cancel") { _, _ -> fillRounds() }
                    .setOnCancelListener { fillRounds() }.show()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        fun group(title: String, vararg views: View) {
            box.addView(sectionHeading(title).also { it.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 4)) })
            for (v in views) box.addView(v)
        }
        val cfAccount = EditText(this)
        cfAccount.hint = "Cloudflare account ID (32 characters, from the Cloudflare dashboard)"
        cfAccount.setSingleLine()
        cfAccount.inputType = InputType.TYPE_CLASS_TEXT
        // OpenRouter: sign in instead of pasting a key, see the key's spend, fallbacks and routing
        val orBox = Ui.vertical(this)
        val orOut = Ui.text(this, "", 12f, Ui.MUTED)
        val orRow = Ui.horizontal(this)
        orRow.addView(Ui.button(this, "Sign in with OpenRouter") {
            OpenRouterLogin.start(this, { k -> key.setText(k); settings.setKey(dev.pocketide.app.core.OpenRouter.ID, k); orOut.text = "Signed in: a new key was made for PocketIDE and saved." }) { st -> orOut.text = st }
        })
        orRow.addView(Ui.button(this, "Paste a code instead") {
            OpenRouterLogin.pasteCode(this, { k -> key.setText(k); settings.setKey(dev.pocketide.app.core.OpenRouter.ID, k); orOut.text = "Signed in: a new key was made for PocketIDE and saved." }) { st -> orOut.text = st }
        }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(this, 8) })
        val orSpendRow = Ui.horizontal(this)
        orSpendRow.addView(Ui.button(this, "Check spend") { orOut.text = "Checking…"; OpenRouterLogin.checkSpend(dev.pocketide.app.core.KeyClean.clean(key.text.toString())) { t -> orOut.text = t } })
        val orFallbacks = EditText(this).also { it.hint = "Fallback models, tried in order when the main one fails (comma separated, up to 3)"; it.setText(settings.openRouterFallbacks) }
        val orRoutes = dev.pocketide.app.core.OpenRouter.Route.values()
        val orRoute = Spinner(this).also { sp -> sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, orRoutes.map { "Providers: " + it.label }); sp.setSelection(orRoutes.indexOf(settings.openRouterRoute)) }
        for (v in listOf<View>(orRow, orSpendRow, orOut, orFallbacks, orRoute)) orBox.addView(v, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(this, 4) })
        for (row in listOf(orRow, orSpendRow)) (row.layoutParams as? LinearLayout.LayoutParams)?.width = Ui.WRAP
        group("AI provider", spin, note, key, keyHelp, orBox, model, base, cfAccount, workspace, hybridBtn, test, testOut)
        group("Assistant", agent, autoAgent, autoCredit, auto, tools, fastBox, routeBox, slowBox, tipsBox, pyBox, reqBox, partsBox, parallelBox, partsAlwaysBox, partsSameBox, partsPickBox, partsTeamBox, queueBox, planFirst, verifyBox, askBox, causeBox, scopeBox, varietyBox, premiseBox, freshBox, exploreBox, tapBox, secondBox, escalateBox, webBox, braveBox, embedBox)
        group("How long it may work", roundsLabel, rounds, effortLabel, effort, runLabel, Ui.text(this, "Dollars per request (uses list prices for Claude models when you have not set a price)", 12f, Ui.TEXT, false), runUsd, Ui.text(this, "Tokens per request (covers free and unpriced models)", 12f, Ui.TEXT, false), runTok)
        group("Cost and context", all, cacheBox, cacheLongBox)
        if (addOnBoxes.isNotEmpty()) group("Add-ons", *addOnBoxes.map { it.second }.toTypedArray())
        group("Editor and layout", layoutBox, appLightBox, edLight, edWrap, edAuto, edNext, edModel, announceBox)
        (test.layoutParams as? LinearLayout.LayoutParams)?.let { it.width = Ui.WRAP; it.topMargin = Ui.dp(this, 6) }

        // keep per-provider edits until Save
        val keys = HashMap<String, String>()
        val models = HashMap<String, String>()
        val bases = HashMap<String, String>()
        var shown = providers.indexOfFirst { it.id == (select ?: settings.provider) }.coerceAtLeast(0)
        fun stash() {
            val id = providers[shown].id
            keys[id] = key.text.toString()
            models[id] = model.text.toString()
            if (id == "cloudflare") {
                dev.pocketide.app.core.Providers.cloudflareBase(cfAccount.text.toString())?.let { bases[id] = it }
            } else if (providers[shown].custom) bases[id] = base.text.toString()
        }
        fun load(i: Int) {
            shown = i
            val p = providers[i]
            key.setText(keys[p.id] ?: settings.key(p.id))
            model.setText(models[p.id] ?: settings.model(p.id))
            model.hint = p.model.ifEmpty { if (p.modelOptional) "Model (optional)" else "Model" }
            base.setText(bases[p.id] ?: settings.baseFor(p.id))
            base.hint = p.defaultBase.ifEmpty { "Server address, e.g. http://127.0.0.1:11434/v1" }
            val keyLink = if (p.keyless) null else dev.pocketide.app.core.Providers.keyUrls[p.id]
            note.text = p.note + (if (keyLink != null) "\n\nGet a key: $keyLink" else "")
            note.autoLinkMask = android.text.util.Linkify.WEB_URLS   // the key link is tappable
            note.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            testOut.text = ""
            test.visibility = if (p.kind == dev.pocketide.app.core.ProviderKind.DEMO || p.kind == dev.pocketide.app.core.ProviderKind.HYBRID) View.GONE else View.VISIBLE
            key.visibility = if (p.keyless && !p.custom) View.GONE else View.VISIBLE
            keyHelp.visibility = if (p.keyless) View.GONE else View.VISIBLE
            workspace.visibility = if (p.kind == dev.pocketide.app.core.ProviderKind.ANTHROPIC) View.VISIBLE else View.GONE
            howTo.visibility = if (p.id in dev.pocketide.app.core.HelpContent.keyProviders) View.VISIBLE else View.INVISIBLE
            model.visibility = if (p.kind == dev.pocketide.app.core.ProviderKind.DEMO || p.kind == dev.pocketide.app.core.ProviderKind.HYBRID) View.GONE else View.VISIBLE
            hybridBtn.visibility = if (p.kind == dev.pocketide.app.core.ProviderKind.HYBRID) View.VISIBLE else View.GONE
            base.visibility = if (p.custom) View.VISIBLE else View.GONE
            orBox.visibility = if (p.id == dev.pocketide.app.core.OpenRouter.ID) View.VISIBLE else View.GONE
            if (p.id == "cloudflare") {
                base.visibility = View.GONE
                cfAccount.visibility = View.VISIBLE
                cfAccount.setText(dev.pocketide.app.core.Providers.cloudflareAccount(bases[p.id] ?: settings.baseFor(p.id)))
            } else cfAccount.visibility = View.GONE
        }
        test.setOnClickListener {
            stash()
            ModelClient.workspaceId = workspace.text.toString().trim()
            val p = providers[shown]
            val m = models[p.id].orEmpty().ifBlank { settings.model(p.id) }.ifBlank { p.model }
            testModels(p, dev.pocketide.app.core.KeyClean.clean(keys[p.id] ?: ""), bases[p.id] ?: base.text.toString(), testOut, m) { picked -> model.setText(picked) }
        }
        howTo.setOnClickListener { HelpScreen.show(this, providers[shown].id, openSettingsFor = { _ -> }) }
        pasteKey.setOnClickListener {
            val c = clipboardText()
            if (c == null) toast("Nothing to paste. Copy the key first.") else key.setText(c)
        }
        spin.setSelection(shown)
        load(shown)
        if (select != null && key.text.isEmpty() && key.visibility == View.VISIBLE) {
            clipboardText()?.takeIf { it.length >= 16 && !it.contains(' ') }?.let {
                key.setText(it)
                toast("Pasted the key you copied. Tap Save.")
            }
        }
        spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != shown) { stash(); load(position) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val scroll = ScrollView(this)
        scroll.addView(box)
        AlertDialog.Builder(this).setTitle("Settings").setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                stash()
                for (p in providers) {
                    keys[p.id]?.let { settings.setKey(p.id, it) }
                    models[p.id]?.let { settings.setModel(p.id, it) }
                    bases[p.id]?.let { settings.setBaseFor(p.id, it) }
                }
                settings.provider = providers[shown].id
                settings.anthropicWorkspace = workspace.text.toString()
                settings.everything = all.isChecked
                settings.agent = agent.isChecked
                settings.autoMode = autoAgent.isChecked
                settings.autoSwitchCredit = autoCredit.isChecked
                settings.autorun = auto.isChecked
                settings.nativeTools = tools.isChecked  // v0.4 W2
                settings.planFirst = planFirst.isChecked
                settings.webResearch = webBox.isChecked
                settings.parallelParts = parallelBox.isChecked
                settings.partsAlways = partsAlwaysBox.isChecked
                settings.partsSameModel = partsSameBox.isChecked
                settings.disabledAddOns = addOnBoxes.filter { !it.second.isChecked }.map { it.first }.toSet()
                dev.pocketide.app.core.AddOns.disabled = settings.disabledAddOns
                settings.partsPick = partsPickBox.isChecked
                settings.partsTeam = dev.pocketide.app.core.Parts.parseTeam(partsTeamBox.text.toString(), dev.pocketide.app.core.Providers.all.map { it.id to it.name })
                settings.verifySteps = queueBox.isChecked
                settings.fastMode = fastBox.isChecked
                settings.phaseRouting = routeBox.isChecked
                settings.slowOffer = slowBox.isChecked
                settings.braveKey = braveBox.text.toString()
                settings.embedModel = embedBox.text.toString()
                settings.requirementsFirst = reqBox.isChecked
                settings.splitParts = partsBox.isChecked
                settings.verify = verifyBox.isChecked
                settings.askCheck = askBox.isChecked
                settings.causeFirst = causeBox.isChecked
                settings.varietyProbe = varietyBox.isChecked
                settings.scopeCheck = scopeBox.isChecked
                settings.verifyFresh = freshBox.isChecked
                settings.exploreHelper = exploreBox.isChecked
                settings.premiseCheck = premiseBox.isChecked
                settings.pageTap = tapBox.isChecked
                settings.secondTry = secondBox.isChecked
                settings.autoEscalate = escalateBox.isChecked
                val layoutChanged = settings.newLayout != layoutBox.isChecked
                settings.newLayout = layoutBox.isChecked
                settings.promptCache = cacheBox.isChecked
                settings.thinkEffort = dev.pocketide.app.core.ThinkEffort.LEVELS.getOrNull(effort.selectedItemPosition)?.first ?: ""
                settings.openRouterFallbacks = orFallbacks.text.toString()
                settings.openRouterRoute = orRoutes[orRoute.selectedItemPosition.coerceAtLeast(0)]
                settings.cacheTtl = if (cacheLongBox.isChecked) "1h" else "5m"
                settings.maxRounds = dev.pocketide.app.core.Rounds.clamp(chosenRounds)
                dev.pocketide.app.core.RunBudget.parseUsd(runUsd.text.toString())?.let { settings.runUsd = it } ?: toast("The dollar limit was not changed: enter a number such as 1.50")
                dev.pocketide.app.core.RunBudget.parseTokens(runTok.text.toString())?.let { settings.runTokens = it } ?: toast("The token limit was not changed: enter a number such as 3M or 500k")
                settings.editorTheme = if (edLight.isChecked) "light" else "dark"  // v0.4 W1
                if (appLightBox.isChecked != settings.appLight) { settings.appLight = appLightBox.isChecked; editor.save(); window.decorView.post { recreate() } }
                settings.modelTips = tipsBox.isChecked
                if (pyBox.isChecked != settings.pythonHelper) settings.pythonHelper = pyBox.isChecked   // 1.5.197: unchanged stays Remote Config's default
                settings.editorWrap = edWrap.isChecked
                settings.workAnnounce = announceBox.isChecked
                settings.autoComplete = edAuto.isChecked
                if (edNext.isChecked != nextEditShown) settings.setNextEdit(edNext.isChecked)
                settings.setCompletionModel(settings.provider, edModel.text.toString())
                editor.applyEditorSettings()
                chat.refresh()
                if (layoutChanged) { editor.save(); recreate() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Lists the models a provider offers (GET <base>/models), so a working setup can be confirmed and a model picked. */
    /**
     * Test connection: reads the model list, then sends one tiny chat request with the chosen model, which is the real test
     * (a list can be public and say "connected" with a wrong key, or be missing while chat works). Warns when the chosen
     * model is no longer in the list, with a suggestion, and opens the list to pick from.
     */
    private fun testModels(p: dev.pocketide.app.core.Provider, key: String, base: String, out: TextView, model: String, pick: (String) -> Unit) {
        out.text = "Contacting ${p.name}…"
        out.setTextColor(Ui.MUTED)
        ModelPicker.fetch(p, key, base) { models, err ->
            val list = models.orEmpty()
            val chatModel = model.ifBlank { p.model }.ifBlank { list.firstOrNull().orEmpty() }
            out.text = "Checking chat with ${chatModel.ifBlank { "the default model" }}…"
            ModelPicker.ping(p, key, base, chatModel) { ok, msg ->
                val sb = StringBuilder(msg)
                if (models == null) sb.append(dev.pocketide.app.core.ConnectionCheck.listNote(p, err))
                if (list.isNotEmpty() && chatModel.isNotBlank() && chatModel !in list) {
                    val facts = dev.pocketide.app.core.ModelFacts.all().filter { it.provider == p.id && it.id in list }
                    val best = dev.pocketide.app.core.ModelRecommend.best(dev.pocketide.app.core.ModelRecommend.Task.BUILD, facts, setOf(p.id), 1).firstOrNull()?.facts?.id
                    sb.append("\n").append(dev.pocketide.app.core.ConnectionCheck.notListed(p, chatModel, best, answered = ok))
                }
                if (!ok && p.id == "remote") sb.append(" ").append(dev.pocketide.app.core.RemoteGuide.explain(msg, base))
                out.text = sb.toString()
                out.setTextColor(if (ok) Ui.GOOD else Ui.BAD)
                if (list.isNotEmpty()) ModelPicker.show(this, "Models from ${p.name}", list, chatModel,
                    dev.pocketide.app.core.HelpContent.MODEL_ADVICE, pick)
            }
        }
    }


    /** Tool ids whose packages are already installed (GPL route). */
    private fun installedToolIds(): Set<String> {
        val pkgs = try { ToolsScreen.installedPackages(linux) } catch (e: Throwable) { return emptySet() }
        return dev.pocketide.app.core.ToolCatalog.all.filter { dev.pocketide.app.core.ToolCatalog.isInstalled(it, pkgs, linux.distro().id) }.map { it.id }.toSet()
    }

    /** Offers to install what a failed command could not find. */
    private fun offerInstall(name: String, fix: dev.pocketide.app.core.MissingTool.Fix) {
        AlertDialog.Builder(this).setTitle("Install $name?")
            .setMessage(fix.note + (if (fix.command != null) "\n\nIt will run:\n${fix.command}" else "\n\nThis opens the Tools screen, where you can tap Install."))
            .setPositiveButton(if (fix.command != null) "Install" else "Open Tools") { _, _ ->
                if (!linux.active) { toast("Install Linux first (⋮ → Tools & Linux)."); openTools(); return@setPositiveButton }
                val cmd = fix.command
                if (cmd != null) { select(2); terminal.exec(cmd, 900) { code, _ -> toast(if (code == 0) "Installed $name." else "Could not install $name.") } }
                else openTools()
            }.setNegativeButton("Not now", null).show()
    }

    /** Writes a small ready-made project into the current project folder. */
    private fun startersDialog() {
        val all = dev.pocketide.app.core.Starters.all
        val installed = installedToolIds()
        val labels = all.map { st ->
            val missing = dev.pocketide.app.core.Starters.missingTools(st, installed)
            st.name + (if (missing.isEmpty()) "" else "  (needs " + missing.joinToString(", ") + ")")
        }
        AlertDialog.Builder(this).setTitle("New starter project").setItems(labels.toTypedArray()) { _, i ->
            val st = all[i]
            val clashes = dev.pocketide.app.core.Starters.clashes(st, store.list())
            val write = {
                editor.save()
                for ((p, t) in st.files) store.write(p, t)
                tree.refresh(); editor.reload()
                st.files.keys.firstOrNull()?.let { select(0); editor.openAt(it, 1, 0) }
                val missing = dev.pocketide.app.core.Starters.missingTools(st, installed)
                if (missing.isNotEmpty()) toast("Created ${st.name}. It needs: " + missing.joinToString(", "))
                else toast("Created ${st.name}. " + (if (st.run.isEmpty()) "Open the Preview tab." else "Run: " + st.run))
            }
            if (clashes.isEmpty()) write()
            else AlertDialog.Builder(this).setTitle("Replace existing files?")
                .setMessage("This would overwrite: " + clashes.joinToString(", "))
                .setPositiveButton("Replace") { _, _ -> write() }.setNegativeButton("Cancel", null).show()
        }.setNegativeButton("Close", null).show()
    }

    private fun clipboardText(): String? {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()?.let { dev.pocketide.app.core.KeyClean.clean(it) }?.takeIf { it.isNotEmpty() && it.length < 400 }
    }

    /** 1.5.209: the welcome screen's Hybrid AI choice: use it, refresh the home card, and open its screen (status of Gemini Nano, Try it). */
    private fun welcomeHybrid(): (() -> Unit)? = if (!HybridAi.configured(this)) null else ({
        settings.provider = "hybrid"
        try { homeView.refresh() } catch (e: Exception) { }
        HybridScreen.show(this)
    })

}
