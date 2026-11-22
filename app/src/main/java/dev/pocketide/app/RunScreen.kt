package dev.pocketide.app


import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.pocketide.app.core.Problems
import dev.pocketide.app.core.RunKind
import dev.pocketide.app.core.RunTarget
import dev.pocketide.app.core.RunTargets
import dev.pocketide.app.core.ServerUrls
import dev.pocketide.app.core.ToolCatalog
import java.io.File

/**
 * v0.4 W3: what the Run button needs from the edition. The GPL edition implements it with its Linux
 * environment (LinuxRunHost); the MIT edition uses NoLinuxRunHost, where only the HTML preview and
 * shell commands run. RunController and RunTargets are edition-neutral.
 */
interface RunHost {
    /** This build can run Linux tools at all. */
    val canUseLinux: Boolean
    /** Linux is installed and switched on. */
    val linuxReady: Boolean
    /** ToolCatalog ids that are installed, or null when unknown (then everything is assumed present). */
    fun installedTools(): Set<String>?
    /** Installs a tool (shows progress in the Terminal tab). onDone(true) on success. */
    fun installTool(toolId: String, onDone: (Boolean) -> Unit)
    /** Opens the screen where Linux is set up. */
    fun openTools()
    /** Starts a long-running server in the background in the current project. Returns an error or null. */
    fun startServer(name: String, title: String, command: String): String?
    fun stopServer(name: String)
    fun isServerRunning(name: String): Boolean
    fun serverLog(name: String, chars: Int = 4000): String
    /** Background servers this build is running: name to title. */
    fun runningServers(): List<Pair<String, String>>
}

/** For builds without a Linux environment (the MIT edition). */
class NoLinuxRunHost(private val act: Activity) : RunHost {
    override val canUseLinux = false
    override val linuxReady = false
    override fun installedTools(): Set<String>? = emptySet()
    override fun installTool(toolId: String, onDone: (Boolean) -> Unit) { onDone(false) }
    override fun openTools() {
        AlertDialog.Builder(act).setTitle("Needs Linux")
            .setMessage("Running Python, Node, Go and other languages needs PocketIDE's Linux environment: ⋮ → Tools & Linux → Install Linux " +
                "(the MIT edition gets the small Linux add-on first). The HTML preview and shell commands work without it.")
            .setPositiveButton("OK", null).show()
    }
    override fun startServer(name: String, title: String, command: String): String? = "Background servers need the Linux environment (⋮ → Tools & Linux)."
    override fun stopServer(name: String) {}
    override fun isServerRunning(name: String) = false
    override fun serverLog(name: String, chars: Int) = ""
    override fun runningServers(): List<Pair<String, String>> = emptyList()
}

/**
 * v0.4 W3: the ▶ button. Detects run targets, remembers the last choice and custom commands per project,
 * offers to install a missing tool, runs commands in the Terminal tab (feeding Problems) and servers in
 * the background (opening them in Preview once they answer).
 */
class RunController(
    private val act: Activity,
    private val store: ProjectStore,
    private val host: RunHost,
    private val terminal: TerminalPane,
    private val preview: PreviewPane
) {
    private val main = Handler(Looper.getMainLooper())

    /** Switches tabs: 1 = Preview, 2 = Terminal. */
    var onShowTab: (Int) -> Unit = {}
    /** The file open in the editor ("" for none). */
    var currentFile: () -> String = { "" }
    /** Saves the editor before running. */
    var beforeRun: () -> Unit = {}

    private fun configFile(): File = File(Projects.metaDir(act), "run.json")  // per project, outside the project folder

    private fun config(): RunTargets.Config = try {
        val f = configFile(); RunTargets.decode(if (f.isFile) f.readText() else null)
    } catch (e: Exception) { RunTargets.Config() }

    private fun save(c: RunTargets.Config) {
        try { configFile().parentFile?.mkdirs(); configFile().writeText(RunTargets.encode(c)) } catch (e: Exception) { }
    }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()

    fun targets(): List<RunTarget> {
        val open = currentFile().ifEmpty { null }
        val detected = RunTargets.detect(store.list(), open, { store.read(it) }, { File(store.root, it).isDirectory })
        return RunTargets.merge(detected, config().custom)
    }

    /** ▶ tap. */
    fun runDefault() {
        beforeRun()
        val all = targets()
        val t = RunTargets.pickDefault(all, currentFile().ifEmpty { null }, config().last, host.canUseLinux)
        if (t == null) {
            if (all.isEmpty()) toast("Nothing to run yet: add an index.html, main.py, package.json, main.go, … or a custom command (long-press ▶).")
            else showMenu()
            return
        }
        run(t)
    }

    fun run(t: RunTarget) {
        beforeRun()
        val why = RunTargets.unavailableReason(t, host.canUseLinux, host.linuxReady)
        if (why != null) {
            if (host.canUseLinux) AlertDialog.Builder(act).setTitle(t.label).setMessage(why)
                .setPositiveButton("Set up Linux") { _, _ -> host.openTools() }.setNegativeButton("Cancel", null).show()
            else host.openTools()
            return
        }
        save(config().copy(last = t.id))
        dev.pocketide.app.core.AddOns.beforeRun(t.id, { store.read(it) }, { p, text -> store.write(p, text) })
            .takeIf { it.isNotEmpty() }?.let { toast("Updated ${it.joinToString(", ")} to this version of PocketIDE") }
        val tool = t.toolId?.let { ToolCatalog.byId(it) }
        val have = host.installedTools()
        if (tool != null && have != null && tool.id !in have && host.canUseLinux) {
            AlertDialog.Builder(act).setTitle("${tool.name} isn't installed")
                .setMessage("\"${t.label}\" needs ${tool.name} (${ToolCatalog.sizeLabel(tool.approxMb)}). Install it now? Progress shows in the Terminal tab.")
                .setPositiveButton("Install and run") { _, _ ->
                    onShowTab(2)
                    host.installTool(tool.id) { ok -> if (ok) run(t) else toast("Installing ${tool.name} failed; see the Terminal tab.") }
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        when (t.kind) {
            RunKind.PREVIEW -> { preview.showFiles(); onShowTab(1) }
            RunKind.COMMAND -> {
                if (terminal.busy) { toast("A command is still running in the Terminal tab."); onShowTab(2); return }
                onShowTab(2)
                terminal.runForProblems(t.command, t.timeoutSec?.toLong() ?: if (host.linuxReady) 1800 else 300, null)
            }
            RunKind.SERVER -> startServer(t)
        }
    }

    private fun serverName(t: RunTarget) = "run-" + t.id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)

    private fun startServer(t: RunTarget) {
        val name = serverName(t)
        if (host.isServerRunning(name)) {
            AlertDialog.Builder(act).setTitle(t.label).setMessage("It's already running.")
                .setPositiveButton(if (t.opensApps.isEmpty()) "Open in Preview" else "Open the game") { _, _ -> openWhenReady(t, name, 1) }
                .setNeutralButton("Restart") { _, _ ->
                    host.stopServer(name)
                    main.postDelayed({ launch(t, name) }, 1200)
                }
                .setNegativeButton("Stop") { _, _ -> host.stopServer(name); toast("Stopped.") }
                .show()
            return
        }
        launch(t, name)
    }

    private fun launch(t: RunTarget, name: String) {
        val err = host.startServer(name, t.label, t.command)
        if (err != null) {
            AlertDialog.Builder(act).setTitle("Could not start").setMessage(err).setPositiveButton("OK", null).show()
            return
        }
        toast("Starting ${t.label}… (it keeps running in the background; long-press ▶ to stop)")
        openWhenReady(t, name, t.readySeconds)
    }

    /** Waits until the server answers (address from its log, or the expected port), then opens it in Preview. */
    private fun openWhenReady(t: RunTarget, name: String, seconds: Int) {
        val until = System.currentTimeMillis() + seconds * 1000L
        Thread {
            var url: String? = null
            while (System.currentTimeMillis() < until) {
                val log = host.serverLog(name, 6000)
                val fromLog = if (t.opensApps.isEmpty()) ServerUrls.find(log).lastOrNull() else null   // a game server's log may name other addresses
                val candidate = fromLog ?: t.url
                if (candidate != null && answers(candidate)) { url = candidate; break }
                if (!host.isServerRunning(name)) break
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
            }
            val running = host.isServerRunning(name)
            val log = host.serverLog(name, 8000)
            main.post {
                when {
                    url != null && t.opensApps.isNotEmpty() -> offerApp(t)
                    url != null -> { preview.showServer(url); onShowTab(1) }
                    !running -> {
                        val found = Problems.parse(log, listOf(store.root.absolutePath))
                        terminal.showProblems(found, log, t.command)
                        showLog("${t.label} stopped", log.ifBlank { "(no output)" }, found.isNotEmpty())
                    }
                    t.opensApps.isNotEmpty() -> AlertDialog.Builder(act).setTitle("${t.label} is still loading")
                        .setMessage("It is running but not answering at ${t.url} yet. Check its log; tap ▶ again later to check.")
                        .setPositiveButton("Log") { _, _ -> showLog("${t.label} log", log, false) }
                        .setNegativeButton("Close", null).show()
                    else -> AlertDialog.Builder(act).setTitle("${t.label} is running")
                        .setMessage("It hasn't answered at ${t.url ?: "a known address"} yet. If it printed an address, open it in Preview.")
                        .setPositiveButton("Open address…") { _, _ -> preview.showServer(ServerUrls.find(log).lastOrNull() ?: t.url ?: "http://127.0.0.1:3000"); onShowTab(1) }
                        .setNeutralButton("Log") { _, _ -> showLog("${t.label} log", log, false) }
                        .setNegativeButton("Close", null).show()
                }
            }
        }.start()
    }

    /** A game server is ready: open its game app (the first one installed), or say which app to install. */
    private fun offerApp(t: RunTarget) {
        val launch = t.opensApps.firstNotNullOfOrNull { act.packageManager.getLaunchIntentForPackage(it) }
        val b = AlertDialog.Builder(act).setTitle("${t.label}: ready")
        if (launch != null) b.setMessage("The server is answering at ${t.url}. Open the game app and log in with any name.")
            .setPositiveButton("Open the game") { _, _ -> try { act.startActivity(launch) } catch (e: Exception) { toast("Could not open it: ${e.message}") } }
            .setNegativeButton("Close", null).show()
        else b.setMessage("The server is answering at ${t.url}, but the game app is not installed. See README.md for the app and how to point it at this phone.")
            .setPositiveButton("OK", null).show()
    }

    private fun answers(url: String): Boolean {
        val port = ServerUrls.port(url) ?: 80
        return try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 400) }
            true
        } catch (e: Exception) { false }
    }

    private fun showLog(title: String, text: String, hasProblems: Boolean) {
        val tv = Ui.text(act, text.takeLast(8000), 11f, Ui.TEXT, true)
        tv.setTextIsSelectable(true)
        tv.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 8))
        val sv = ScrollView(act); sv.addView(tv)
        val b = AlertDialog.Builder(act).setTitle(title).setView(sv).setPositiveButton("Close", null)
        if (hasProblems) b.setNeutralButton("Problems") { _, _ -> onShowTab(2) }
        b.show()
    }

    /** "Check project": fast syntax checks with the installed tools; results go to Problems. */
    fun checkProject() {
        beforeRun()
        if (!host.linuxReady) {
            // without Linux only shell scripts can be checked
            val sh = store.list().filter { it.endsWith(".sh") && !RunTargets.ignored(it) }
            if (sh.isEmpty()) { host.openTools(); return }
        }
        val tools = if (host.linuxReady) host.installedTools() else emptySet()
        val cmd = RunTargets.checkCommand(store.list(), tools)
        if (cmd == null) { toast("Nothing to check with the tools that are installed."); return }
        if (terminal.busy) { toast("A command is still running in the Terminal tab."); onShowTab(2); return }
        onShowTab(2)
        terminal.runForProblems(cmd, 600) { _, _ ->
            val n = terminal.problemsView.problems.size
            toast(if (n == 0) "No problems found." else "$n problem${if (n == 1) "" else "s"} found.")
        }
    }

    /** Long-press ▶: choose a target, edit commands, add your own, check the project, stop servers. */
    fun showMenu() {
        beforeRun()
        val pad = Ui.dp(act, 16)
        val box = Ui.vertical(act)
        box.setPadding(pad, Ui.dp(act, 8), pad, Ui.dp(act, 8))
        val dlg = AlertDialog.Builder(act).setTitle("Run").setView(ScrollView(act).also { it.addView(box) })
            .setPositiveButton("Close", null).create()
        val cfg = config()
        val all = targets()
        val def = RunTargets.pickDefault(all, currentFile().ifEmpty { null }, cfg.last, host.canUseLinux)

        fun heading(s: String) {
            val t = Ui.text(act, s, 12f, Ui.MUTED)
            t.typeface = android.graphics.Typeface.DEFAULT_BOLD
            t.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 4))
            box.addView(t)
        }
        fun link(label: String, onClick: () -> Unit): TextView {
            val t = Ui.text(act, label, 13f, Ui.ACCENT)
            t.setPadding(Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 10), Ui.dp(act, 8))
            t.setOnClickListener { onClick() }
            return t
        }

        heading("RUN")
        if (all.isEmpty()) box.addView(Ui.text(act, "Nothing detected. Add a custom command below.", 13f, Ui.MUTED))
        for (t in all) {
            val why = RunTargets.unavailableReason(t, host.canUseLinux, host.linuxReady)
            val row = Ui.vertical(act)
            row.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 6))
            val line = Ui.horizontal(act)
            val name = Ui.text(act, (if (t.id == def?.id) "▶ " else "") + t.label + when (t.kind) { RunKind.SERVER -> "  · server"; else -> "" }, 14f,
                if (why == null) Ui.TEXT else Ui.MUTED)
            line.addView(name, Ui.lp(0, Ui.WRAP, 1f))
            line.addView(link("Run") { dlg.dismiss(); run(t) })
            row.addView(line)
            val sub = if (t.kind == RunKind.PREVIEW) "Opens the Preview tab" else t.command
            row.addView(Ui.text(act, sub, 11f, Ui.MUTED, true).also { it.maxLines = 2 })
            if (why != null) row.addView(Ui.text(act, why, 11f, Ui.MUTED))
            if (t.kind != RunKind.PREVIEW) {
                val actions = Ui.horizontal(act)
                actions.addView(link("Edit command") { dlg.dismiss(); editCommand(t) })
                if (!t.custom && cfg.custom.any { it.id == t.id }) actions.addView(link("Reset") {
                    save(cfg.copy(custom = cfg.custom.filter { it.id != t.id })); dlg.dismiss(); showMenu()
                })
                if (t.custom) actions.addView(link("Delete") {
                    save(cfg.copy(custom = cfg.custom.filter { it.id != t.id }, last = if (cfg.last == t.id) null else cfg.last))
                    dlg.dismiss(); showMenu()
                })
                if (t.id != def?.id && why == null) actions.addView(link("Make default") { save(cfg.copy(last = t.id)); dlg.dismiss(); showMenu() })
                row.addView(actions)
            }
            box.addView(row)
            box.addView(View(act).also { it.setBackgroundColor(Ui.LINE) }, Ui.lp(Ui.MATCH, 1))
        }
        box.addView(link("+ Add a custom command") { dlg.dismiss(); editCommand(null) })
        box.addView(link("Check project for errors") { dlg.dismiss(); checkProject() })

        val servers = host.runningServers()
        if (servers.isNotEmpty()) {
            heading("RUNNING IN THE BACKGROUND")
            for ((n, title) in servers) {
                val line = Ui.horizontal(act)
                line.addView(Ui.text(act, "● $title", 13f, Ui.GOOD), Ui.lp(0, Ui.WRAP, 1f))
                line.addView(link("Log") { showLog("$title log", host.serverLog(n, 8000).ifBlank { "(empty)" }, false) })
                line.addView(link("Stop") { host.stopServer(n); dlg.dismiss(); toast("Stopping $title…") })
                box.addView(line)
            }
        }
        if (!host.canUseLinux) box.addView(Ui.text(act, "Without Linux, the HTML preview and shell commands run. Other languages need the Linux environment (⋮ → Tools & Linux).", 11f, Ui.MUTED).also { it.setPadding(0, Ui.dp(act, 12), 0, 0) })
        else if (!host.linuxReady) box.addView(link("Set up Linux to run Python, Node, Go…") { dlg.dismiss(); host.openTools() })
        dlg.show()
    }

    /** Edit a detected target's command (saved as an override) or add a new custom one (t == null). */
    private fun editCommand(t: RunTarget?) {
        val pad = Ui.dp(act, 20)
        val box = Ui.vertical(act)
        box.setPadding(pad, Ui.dp(act, 8), pad, 0)
        val label = EditText(act)
        label.hint = "Name (optional)"
        label.setSingleLine()
        val cmd = EditText(act)
        cmd.hint = "Command, e.g. python3 main.py --debug"
        cmd.typeface = android.graphics.Typeface.MONOSPACE
        cmd.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        if (t != null) cmd.setText(t.command) else box.addView(label)
        box.addView(cmd)
        box.addView(Ui.text(act, "Runs in the project folder. Servers (npm run dev, flask run, …) run in the background and open in Preview.", 11f, Ui.MUTED))
        AlertDialog.Builder(act).setTitle(if (t == null) "Custom command" else "Edit: ${t.label}").setView(box)
            .setPositiveButton("Save and run") { _, _ ->
                val c = cmd.text.toString().trim()
                if (c.isEmpty()) return@setPositiveButton
                val cfg = config()
                val id = t?.id ?: ("custom-" + System.currentTimeMillis().toString(36))
                val entry = RunTargets.custom(id, label.text.toString().trim().ifEmpty { t?.label ?: c }, c)
                save(cfg.copy(custom = cfg.custom.filter { it.id != id } + entry, last = id))
                targets().firstOrNull { it.id == id }?.let { run(it) }
            }
            .setNegativeButton("Cancel", null).show()
    }
}
