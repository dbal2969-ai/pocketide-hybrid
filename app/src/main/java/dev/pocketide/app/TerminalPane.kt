package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pocketide.app.core.FileKinds
import java.io.File

/** The Terminal tab: runs one line at a time in Android's shell, in the project folder. */
class TerminalPane(private val ctx: Context, private val store: ProjectStore, private val home: File) : LinearLayout(ctx) {
    private val output = Ui.text(ctx, "", 12f, Ui.TEXT, true)
    private val scroll = ScrollView(ctx)
    private val input = EditText(ctx)
    private val runBtn: TextView
    private val history = ArrayList<String>()
    private var current: CommandCall? = null
    private var tail = StringBuilder()
    private val maxChars = 60_000

    /** Called after any command finishes, since it may have changed files. */
    var onFinished: (() -> Unit)? = null
    /** Save a copy of a project file to the phone (the host opens the system save dialog). */
    var onSaveCopy: ((String) -> Unit)? = null
    private val filesRow = Ui.horizontal(ctx)
    private val filesScroll = android.widget.HorizontalScrollView(ctx)

    /** After a command: a button for each binary file it created or changed (a build, a document, an archive). */
    /** When the app offers to install something a command could not find. Set by MainActivity. */
    var onInstallFix: ((String, dev.pocketide.app.core.MissingTool.Fix) -> Unit)? = null
    /** Tool ids already installed, for working out what to offer. */
    var installedToolIds: () -> Set<String> = { emptySet() }

    private fun showMissingTool(output: String) {
        val handler = onInstallFix ?: return
        val found = dev.pocketide.app.core.MissingTool.detect(output.takeLast(4000), dev.pocketide.app.core.ToolCatalog.all.filter { dev.pocketide.app.core.ToolCatalog.available(it, linuxDistroId) }.map { it.id }, installedToolIds(), linuxDistroId) ?: return
        val (name, fix) = found
        filesRow.addView(Ui.text(ctx, "'$name' is not installed.", 12f, Ui.BAD).also { it.setPadding(0, Ui.dp(ctx, 8), Ui.dp(ctx, 8), 0) })
        filesRow.addView(Ui.chip(ctx, fix.label) { handler(name, fix) }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
        filesScroll.visibility = VISIBLE
    }

    private fun showOutputFiles(before: Map<String, Long>) {
        val files = try { FileKinds.changed(before, store.snapshot()).filter { store.isBinary(it) && FileKinds.deliverable(it) }.take(10) } catch (e: Throwable) { emptyList() }
        filesRow.removeAllViews()
        if (files.isEmpty()) { filesScroll.visibility = GONE; return }
        if (files.isEmpty()) return
        filesRow.addView(Ui.text(ctx, "Files ready:", 12f, Ui.MUTED).also { it.setPadding(0, Ui.dp(ctx, 8), Ui.dp(ctx, 8), 0) })
        for (p in files) filesRow.addView(Ui.chip(ctx, "⬇ " + p.substringAfterLast('/')) { onSaveCopy?.invoke(p) }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
        filesScroll.visibility = VISIBLE
    }

    // ---- v0.4 W3: Problems panel and local-server links ----
    /** Errors and warnings from the last run (MainActivity wires onOpenAt / onAskAi / onChanged). */
    val problemsView = ProblemsView(ctx)
    /** Opens a local server address in the Preview tab. */
    var onOpenUrl: ((String) -> Unit)? = null
    private val urlRow = Ui.horizontal(ctx)
    private val urlChip = Ui.chip(ctx, "") { urlFound?.let { u -> onOpenUrl?.invoke(u) } }
    private var urlFound: String? = null
    private var urlScan = StringBuilder()
    private var problemCommand: String? = null
    private var forceProblems = false

    /** Shows problems found by something other than a terminal command (a server log, for example). */
    fun showProblems(items: List<dev.pocketide.app.core.Problem>, output: String, command: String?) {
        problemCommand = command
        problemsView.show(items, output, command)
    }

    /** Runs a command for the Run button or Check project: its problems always replace the list (an empty result clears it). */
    fun runForProblems(command: String, timeoutSeconds: Long, onDone: ((Int, String) -> Unit)?) {
        forceProblems = true
        exec(command, timeoutSeconds, onDone)
    }

    private fun scanForUrl(s: String) {
        urlScan.append(s)
        if (urlScan.length > 600) urlScan.delete(0, urlScan.length - 600)
        val u = dev.pocketide.app.core.ServerUrls.find(urlScan.toString()).lastOrNull() ?: return
        if (u == urlFound) return
        urlFound = u
        urlChip.text = "Open $u in Preview"
        urlRow.visibility = VISIBLE
    }

    // ---- GPL route: Linux environment (PRoot + Alpine) ----
    /** Set by MainActivity. When its environment is active, commands run inside Linux. */
    var linux: LinuxRuntime? = null
        set(v) { field = v; refreshHint() }
    /** Opens the full-screen interactive terminal. */
    var onOpenFull: (() -> Unit)? = null
    val linuxActive: Boolean get() = linux?.active == true
    /** "alpine" or "ubuntu": the base that is installed (or chosen, before installing). */
    val linuxDistroId: String get() = linux?.distro()?.id ?: "alpine"
    private val hint = Ui.text(ctx, "", 12f, Ui.MUTED)

    /** Updates the header line to say which shell commands run in. */
    fun refreshHint() {
        hint.text = if (linuxActive) (if (linuxDistroId == "ubuntu") "Ubuntu Linux in /work. One-tap installs: Tools button (or apt-get install …)." else "Alpine Linux in /work. One-tap installs: Tools button (or apk add …).")
        else "Android shell (basic tools only). Set up Linux from the Tools button."
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.BG)
        // GPL route: header with which shell is in use, and a link to the full terminal
        val head = Ui.horizontal(ctx)
        head.setBackgroundColor(Ui.SURFACE)
        head.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 2), Ui.dp(ctx, 4), Ui.dp(ctx, 2))
        hint.maxLines = 2
        head.addView(hint, Ui.lp(0, Ui.WRAP, 1f))
        val full = Ui.text(ctx, "Full terminal", 13f, Ui.ACCENT)
        full.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 10))
        full.setOnClickListener { onOpenFull?.invoke() }
        head.addView(full)
        addView(head, Ui.lp(Ui.MATCH, Ui.WRAP))
        refreshHint()

        output.setTextIsSelectable(true)
        output.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
        output.text = "Android shell, in the project folder. Python, Node and git are not installed here; use the tools Android provides (ls, cat, grep, sed, awk, find, cp, mv, rm).\n"
        scroll.addView(output)
        addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))

        // v0.4 W3: "Open http://127.0.0.1:3000 in Preview" when a command prints a local server address
        urlRow.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
        urlRow.addView(urlChip)
        urlRow.addView(Ui.chip(ctx, "✕") { urlRow.visibility = GONE }.also { Ui.describe(it, "✕", "Hide the server link") })
        urlRow.visibility = GONE
        addView(urlRow, Ui.lp(Ui.MATCH, Ui.WRAP))
        filesRow.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 2), Ui.dp(ctx, 8), Ui.dp(ctx, 2))
        filesScroll.isHorizontalScrollBarEnabled = false
        filesScroll.addView(filesRow)
        filesScroll.visibility = GONE
        addView(filesScroll, Ui.lp(Ui.MATCH, Ui.WRAP))
        addView(problemsView, Ui.lp(Ui.MATCH, Ui.WRAP))  // v0.4 W3

        val row = Ui.horizontal(ctx)
        row.setBackgroundColor(Ui.SURFACE)
        row.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
        val hist = Ui.text(ctx, "↺", 18f, Ui.ACCENT)
        hist.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8))
        hist.setOnClickListener { pickHistory() }
        Ui.tapTarget(hist, "Command history")
        row.addView(hist)
        input.hint = "command"
        input.setHintTextColor(Ui.MUTED)
        input.setTextColor(Ui.TEXT)
        input.textSize = 14f
        input.typeface = android.graphics.Typeface.MONOSPACE
        input.setSingleLine()
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.imeOptions = EditorInfo.IME_ACTION_GO
        input.setOnEditorActionListener { _, _, _ -> submit(); true }
        row.addView(input, Ui.lp(0, Ui.WRAP, 1f))
        runBtn = Ui.button(ctx, "Run", true) { if (current != null) stop() else submit() }
        row.addView(runBtn)
        addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun submit() {
        val c = input.text.toString().trim()
        if (c.isEmpty() || current != null) return
        input.setText("")
        exec(c, if (linuxActive) 1800 else 120, null)  // GPL route: package installs can take a while
    }

    private fun stop() { current?.cancel() }

    private fun pickHistory() {
        if (history.isEmpty()) return
        val items = history.asReversed().take(30).toTypedArray()
        AlertDialog.Builder(ctx).setTitle("Earlier commands").setItems(items) { _, i ->
            input.setText(items[i]); input.setSelection(items[i].length)
        }.show()
    }

    private fun append(s: String) {
        scanForUrl(s)  // v0.4 W3
        tail.append(s)
        if (tail.length > maxChars) tail.delete(0, tail.length - maxChars)
        output.text = tail
        scroll.post { scroll.fullScroll(FOCUS_DOWN) }
    }

    val busy: Boolean get() = current != null

    /** v0.4 W3: problems from a finished command. Any command that reports problems shows them; a clean
     *  re-run of the command that reported them (or any Run/Check) clears the list. */
    private fun updateProblems(command: String, code: Int, out: String, force: Boolean) {
        val found = dev.pocketide.app.core.Problems.parse(out, listOf(store.root.absolutePath))
        when {
            found.isNotEmpty() -> { problemCommand = command; problemsView.show(found, out, command) }
            force || (code == 0 && command == problemCommand) -> { problemCommand = null; problemsView.show(emptyList(), out, command) }
        }
    }

    /** The project's secrets (Project settings → Secrets): given to commands as environment variables and masked in their output. */
    private fun secrets(): Map<String, String> = try { dev.pocketide.app.core.Secrets(java.io.File(Projects.metaDir(ctx, store.root.name), "secrets.json")).all() } catch (e: Exception) { emptyMap() }

    /** Runs a command and shows it here. onDone gets the exit code and the output it produced. */
    /** Runs [command] in Linux without showing it in the terminal (1.5.188: the Python syntax check on save); 127 without Linux. */
    fun quietExec(command: String, timeoutSeconds: Long, onDone: (Int, String) -> Unit) {
        try {
            val viaLinux = linux?.takeIf { it.active }?.command(store.root, command) ?: run { onDone(127, ""); return }
            val out = StringBuilder()
            CommandRunner.run(store.root, home, command, timeoutSeconds, { s -> synchronized(out) { if (out.length < 20_000) out.append(s) } },
                { code -> onDone(code, synchronized(out) { out.toString() }) }, viaLinux.first, viaLinux.second)
        } catch (e: Throwable) { onDone(-1, e.message.orEmpty()) }
    }

    fun exec(command: String, timeoutSeconds: Long, onDone: ((Int, String) -> Unit)?) {
        val sec = secrets()
        if (current != null) { onDone?.invoke(-1, "Another command is still running."); return }
        if (history.lastOrNull() != command) history.add(command)
        if (tail.isEmpty()) tail = StringBuilder(output.text)
        append("\n$ $command\n")
        runBtn.text = "Stop"
        val produced = StringBuilder()
        val recent = StringBuilder()  // v0.4 W3: the end of the output, where errors usually are
        val force = forceProblems
        forceProblems = false
        val filesBefore = try { store.snapshot() } catch (e: Throwable) { emptyMap() }
        urlScan.setLength(0)
        try {
            val viaLinux = linux?.takeIf { it.active }?.command(store.root, command)  // GPL route
            current = CommandRunner.run(store.root, home, command, timeoutSeconds,
                { s0 ->
                    val s = if (sec.isEmpty()) s0 else dev.pocketide.app.core.Secrets.mask(s0, sec)
                    if (produced.length < 20_000) produced.append(s)
                    recent.append(s); if (recent.length > 40_000) recent.delete(0, recent.length - 30_000)
                    append(s)
                },
                { code ->
                    current = null
                    runBtn.text = "Run"
                    append(if (code == 0) "[done]\n" else "[exit $code]\n")
                    updateProblems(command, code, recent.toString(), force)  // v0.4 W3
                    showOutputFiles(filesBefore)
                    if (code != 0) showMissingTool(recent.toString())
                    onFinished?.invoke()
                    onDone?.invoke(code, produced.toString())
                }, viaLinux?.first, (viaLinux?.second ?: emptyMap()) + sec)
        } catch (e: Throwable) {
            // Report instead of closing the app.
            current = null
            runBtn.text = "Run"
            val msg = "Could not start the command: ${e.javaClass.simpleName}: ${e.message}\n"
            append(msg)
            onDone?.invoke(-1, msg)
        }
    }
}
