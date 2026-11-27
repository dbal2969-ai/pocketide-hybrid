package dev.pocketide.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream

/** Each project is served from its own address, so one project cannot read another's saved data. */
private fun hostOf(store: ProjectStore): String = dev.pocketide.app.core.ProjectIsolation.previewHost(store.root.name)

/** Serves project files to a WebView as https://<project>.pocketide.local/<path>, and records console errors. */
private fun serve(store: ProjectStore, req: WebResourceRequest, errors: MutableList<String>?, keepCanvas: Boolean = false, recordTaps: Boolean = false,
                  trace: dev.pocketide.app.core.Trace.Plan? = null, placed: MutableMap<Int, Int?>? = null,
                  debug: dev.pocketide.app.core.StepDebug.Plan? = null): WebResourceResponse? {
    val u = req.url
    if (u.host != hostOf(store)) return null
    var path = (u.path ?: "/").removePrefix("/")
    if (path.isEmpty()) path = "index.html"
    var bytes = store.readBytes(path)
    if (bytes != null && keepCanvas && (path.endsWith(".html", true) || path.endsWith(".htm", true)))
        bytes = dev.pocketide.app.core.PageLook.inject(String(bytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
    if (bytes != null && recordTaps && (path.endsWith(".html", true) || path.endsWith(".htm", true)))
        bytes = dev.pocketide.app.core.PageProbe.recordTaps(String(bytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
    // log points (Trace, 1.5.112): the recorder goes into every page, and each traced file gets its calls, in this copy only
    if (bytes != null && trace != null && trace.points.isNotEmpty()) {
        val mine = trace.points.filter { it.path.equals(path, true) }
        var text: String? = null
        if (mine.isNotEmpty()) { val (s, used) = dev.pocketide.app.core.Trace.instrument(String(bytes, Charsets.UTF_8), mine); text = s; synchronized(placed ?: Any()) { placed?.putAll(used) } }
        if (path.endsWith(".html", true) || path.endsWith(".htm", true)) text = dev.pocketide.app.core.Trace.withRecorder(text ?: String(bytes, Charsets.UTF_8))
        if (text != null) bytes = text.toByteArray(Charsets.UTF_8)
    }
    // pause-and-step (StepDebug, 1.5.125): every page gets the small debugger runtime, and each file with a breakpoint gets a check
    // in front of each statement line, in this copy only. Line numbers stay the same, so errors still point at the real lines.
    if (bytes != null && debug != null) {
        val isHtml = path.endsWith(".html", true) || path.endsWith(".htm", true)
        val mine = debug.breakpoints.filter { it.path.equals(path, true) }
        var text: String? = null
        if (mine.isNotEmpty()) {
            val src = String(bytes, Charsets.UTF_8)
            val r = when {
                isHtml -> dev.pocketide.app.core.StepDebug.instrumentHtml(src, path, mine, debug.watches)
                path.endsWith(".js", true) || path.endsWith(".mjs", true) -> dev.pocketide.app.core.StepDebug.instrument(src, path, mine, debug.watches)
                else -> null
            }
            if (r != null) { text = r.text; synchronized(placed ?: Any()) { placed?.putAll(r.placed) } }
        }
        if (isHtml) text = dev.pocketide.app.core.StepDebug.withRuntime(text ?: String(bytes, Charsets.UTF_8))
        if (text != null) bytes = text.toByteArray(Charsets.UTF_8)
    }
    if (bytes == null) {
        // Browsers ask for /favicon.ico on their own. A missing one is not a problem, so answer "no content" and do not report it.
        if (path == "favicon.ico") return WebResourceResponse("image/x-icon", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        errors?.let { if (it.size < 20) it.add("404 Not found: $path") }
        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream("Not found".toByteArray()))
    }
    val ext = path.substringAfterLast('.', "").lowercase()
    val mime = when (ext) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "webmanifest" -> "application/json"
        "svg" -> "image/svg+xml"
        "wasm" -> "application/wasm"
        "txt", "md" -> "text/plain"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
    val enc = if (mime.startsWith("text/") || mime == "application/json" || mime == "image/svg+xml") "utf-8" else null
    return WebResourceResponse(mime, enc, 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(bytes))
}

/** The Preview tab. */
class PreviewPane(private val ctx: Context, private val store: ProjectStore) : LinearLayout(ctx) {
    /** The visible page; replaced by a new one if its renderer dies (1.5.133). */
    private var web = WebView(ctx)
    private val shown = ArrayList<String>()
    private val restarts = dev.pocketide.app.core.RendererCrash.Restarts()
    private val label = Ui.text(ctx, "", 12f, Ui.MUTED, true)
    private val log = Ui.text(ctx, "", 11f, Ui.BAD, true)
    private var entry = ""

    // ---- console and device sizes (v0.9.1) ----
    /** Every console message and page error of the current page, newest last. */
    private val console = ArrayList<String>()
    /** Asks the assistant about what the page printed. Set by MainActivity. */
    var onAskAi: ((String) -> Unit)? = null
    private val consoleChip = Ui.text(ctx, "Console", 13f, Ui.ACCENT)
    /** Width in device-independent pixels, or 0 for "fill the screen". */
    private var deviceWidth = 0
    private val sizeChip = Ui.text(ctx, "Size", 13f, Ui.ACCENT)

    private fun record(level: String, message: String, source: String, line: Int) {
        val where = if (source.isEmpty()) "" else " (" + source.substringAfterLast('/') + ":" + line + ")"
        console.add("[" + level.lowercase() + "] " + message + where)
        if (console.size > 300) console.removeAt(0)
        consoleChip.text = if (console.isEmpty()) "Console" else "Console (" + console.size + ")"
    }

    private fun consoleReport(): String =
        if (console.isEmpty()) "The page printed nothing." else console.takeLast(60).joinToString("\n")

    private fun showConsole() {
        val tv = Ui.text(ctx, consoleReport(), 11f, Ui.TEXT, true)
        tv.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 8))
        tv.setTextIsSelectable(true)
        val sv = android.widget.ScrollView(ctx); sv.addView(tv)
        val b = android.app.AlertDialog.Builder(ctx).setTitle("Console (" + console.size + " messages)").setView(sv)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ -> console.clear(); consoleChip.text = "Console" }
        if (onAskAi != null && console.isNotEmpty()) b.setNegativeButton("Ask the assistant") { _, _ ->
            onAskAi?.invoke("The preview of " + (serverUrl ?: entry.ifEmpty { "the page" }) + " printed this in the browser console. Find the cause and fix it.\n\n" + consoleReport())
        }
        b.show()
    }

    private fun pickSize() {
        val sizes = listOf(0 to "Fill the screen", 360 to "Small phone (360dp)", 412 to "Large phone (412dp)", 768 to "Tablet (768dp)", 1024 to "Desktop (1024dp)")
        android.app.AlertDialog.Builder(ctx).setTitle("Preview width")
            .setItems(sizes.map { (w, n) -> (if (w == deviceWidth) "● " else "") + n }.toTypedArray()) { _, i ->
                deviceWidth = sizes[i].first
                applySize()
                reload()
            }.setNegativeButton("Close", null).show()
    }

    private fun applySize() {
        sizeChip.text = if (deviceWidth == 0) "Size" else deviceWidth.toString() + "dp"
        val lp = web.layoutParams as? LayoutParams ?: return
        lp.width = if (deviceWidth == 0) Ui.MATCH else Ui.dp(ctx, deviceWidth)
        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
        web.layoutParams = lp
        web.requestLayout()
    }

    // ---- pause-and-step debugger (StepDebug, 1.5.125) ----
    /** One debugging run: its breakpoints, the wait the page is held in, and where each breakpoint landed. */
    private class DebugRun(val plan: dev.pocketide.app.core.StepDebug.Plan, val gate: dev.pocketide.app.core.StepDebug.Gate) {
        val placed: MutableMap<Int, Int?> = java.util.Collections.synchronizedMap(LinkedHashMap<Int, Int?>())
    }
    @Volatile private var debugRun: DebugRun? = null
    private var pauseNo = 0
    private val main = Handler(Looper.getMainLooper())
    private val dbgPanel = Ui.vertical(ctx)
    private val dbgWhere = Ui.text(ctx, "", 13f, Ui.ACCENT)
    private val dbgCode = Ui.text(ctx, "", 11f, Ui.TEXT, true)
    private val dbgValues = Ui.text(ctx, "", 11f, Ui.TEXT, true)
    private val dbgScroll = android.widget.ScrollView(ctx)
    private val dbgContinue = Ui.button(ctx, "Continue", true) { answer(dev.pocketide.app.core.StepDebug.CONTINUE) }
    private val dbgStep = Ui.button(ctx, "Step") { answer(dev.pocketide.app.core.StepDebug.STEP) }
    private val dbgStop = Ui.button(ctx, "Stop debugging") { endDebug() }

    /**
     * Handed to the page as window.PocketDebug while debugging. WebView calls it on its own background thread and the page's
     * JavaScript waits for the answer, so waiting here is the pause; the screen's thread is never held (the gate refuses it).
     */
    /** 1.5.214: the page's address, kept for the AI bridge (its calls arrive off the main thread, where the WebView cannot be read). */
    @Volatile private var pageUrl: String? = null

    /**
     * 1.5.214: PocketIDEAI, PocketIDE's Hybrid AI for the project's own pages (PreviewAi): Gemini Nano on this phone first, through
     * PocketIDE's Firebase AI Logic; the cloud at most Tuning.previewCloudPerHour times an hour, then Gemini Nano only.
     */
    private inner class AiBridge {
        @android.webkit.JavascriptInterface
        fun ask(id: String, prompt: String) {
            val p = dev.pocketide.app.core.PreviewAi
            val url = pageUrl
            fun back(json: String) = main.post { if (pageUrl == url) try { web.evaluateJavascript(p.callback(id, json), null) } catch (e: Exception) { } }
            p.refusal(url, prompt)?.let { back(p.error(it)); return }
            if (!HybridAi.configured(ctx)) { back(p.error("This PocketIDE build has no Firebase config, so Hybrid AI is off.")); return }
            previewAiIo.execute {
                val chosen = HybridScreen.mode(ctx)
                val cloudOk = previewAllowance.cloudLeft(dev.pocketide.app.core.Tuning.previewCloudPerHour) > 0
                val mode = if (cloudOk || chosen == HybridAi.Mode.ONLY_ON_DEVICE) chosen else HybridAi.Mode.ONLY_ON_DEVICE
                val json = try {
                    val r = HybridAi.ask(ctx, prompt, mode)
                    if (!r.onDevice) previewAllowance.usedCloud()
                    HybridCloud.log(if (r.onDevice) "phone" else "cloud", r.seconds, "", "preview")
                    p.reply(r.text, r.onDevice, r.seconds, r.model)
                } catch (e: Exception) {
                    p.error(if (!cloudOk && mode != chosen) p.CLOUD_USED_UP else HybridAi.explain(e))
                }
                back(json)
            }
        }
    }
    private val previewAiIo = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val previewAllowance = dev.pocketide.app.core.PreviewAi.Allowance()

    private inner class DebugBridge(private val run: DebugRun) {
        @android.webkit.JavascriptInterface
        fun pause(file: String, line: Int, values: String): String {
            val c = run.gate.pause { no -> main.post { if (debugRun === run) showPaused(no, file, line, values) } }
            main.post { if (debugRun === run) showRunning(run) }
            return c
        }
    }

    private fun answer(command: String) {
        val run = debugRun ?: return
        if (run.gate.answer(pauseNo, command)) { dbgContinue.isEnabled = false; dbgStep.isEnabled = false }
    }

    private fun showPaused(no: Int, file: String, line: Int, values: String) {
        val run = debugRun ?: return
        pauseNo = no
        dbgWhere.text = "Paused at $file:$line"
        dbgCode.text = dev.pocketide.app.core.StepDebug.codeAround(store.readBytes(file)?.let { String(it, Charsets.UTF_8) }, line)
        Ui.show(dbgCode)
        dbgValues.text = dev.pocketide.app.core.StepDebug.describe(values, run.plan.watches)
        Ui.show(dbgScroll)
        dbgContinue.isEnabled = true; dbgStep.isEnabled = true
        Ui.show(dbgContinue); Ui.show(dbgStep)
        dbgPanel.visibility = VISIBLE
    }

    private fun showRunning(run: DebugRun) {
        val where = run.plan.breakpoints.joinToString("; ") { b ->
            "${b.path}:${b.line}" + when {
                !run.placed.containsKey(b.id) -> " (file not loaded yet)"
                run.placed[b.id] == null -> " (no statement starts near it)"
                run.placed[b.id] != b.line -> " (at line ${run.placed[b.id]})"
                else -> ""
            }
        }
        val late = if (run.gate.timedOut > 0) "\nA pause was left for ${dev.pocketide.app.core.StepDebug.PAUSE_TIMEOUT_MS / 60_000} minutes, so the page went on by itself." else ""
        dbgWhere.text = "Debugging: running. Stops at $where$late"
        Ui.gone(dbgCode); Ui.gone(dbgScroll); Ui.gone(dbgContinue); Ui.gone(dbgStep)
        dbgPanel.visibility = VISIBLE
    }

    /** Starts a debugging run on the visible preview; returns why not, or null. */
    private fun startDebug(spec: String): String? {
        if (serverUrl != null) return "Pausing works on the project's own files, not on a local server: switch the preview to Files first."
        val plan = dev.pocketide.app.core.StepDebug.parse(spec)
        if (plan.error != null) return plan.error
        endDebug()
        val run = DebugRun(plan, dev.pocketide.app.core.StepDebug.Gate(dev.pocketide.app.core.StepDebug.PAUSE_TIMEOUT_MS) { Looper.myLooper() == Looper.getMainLooper() })
        // the bridge object exists only during a run, and is only seen by pages loaded after this
        web.addJavascriptInterface(DebugBridge(run), "PocketDebug")
        debugRun = run
        showRunning(run)
        load()
        return null
    }

    /**
     * Stop debugging: a paused page is let go at once and its checks turn themselves off, so it carries on as it was; the next
     * reload loads the plain files. Also called when the preview is reloaded, hidden or closed, so a page is never left held.
     */
    private fun endDebug() {
        val run = debugRun ?: return
        debugRun = null
        run.gate.close()
        try { web.removeJavascriptInterface("PocketDebug") } catch (e: Exception) { }
        dbgPanel.visibility = GONE
    }

    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE && debugRun != null) endDebug()
    }

    override fun onDetachedFromWindow() {
        endDebug()
        super.onDetachedFromWindow()
    }

    // ---- v0.4 W3: preview a local server (http://127.0.0.1:PORT) as well as the project files ----
    private val prefs = ctx.getSharedPreferences("preview", Context.MODE_PRIVATE)
    /** null = project files; otherwise the local server address being shown. */
    var serverUrl: String? = null
        private set
    private val mode = Ui.text(ctx, "Files ▾", 13f, Ui.ACCENT)

    /** Shows a local server in the preview (switches to "Local server" mode). */
    fun showServer(url: String) {
        serverUrl = url
        remember(url)
        reload()
    }

    /** Back to previewing the project's own files. */
    fun showFiles() {
        serverUrl = null
        reload()
    }

    private fun recent(): List<String> = (prefs.getString("recent", "") ?: "").split("\n").filter { it.isNotBlank() }

    private fun remember(url: String) {
        val l = (listOf(url) + recent().filter { it != url }).take(8)
        prefs.edit().putString("recent", l.joinToString("\n")).apply()
    }

    private fun pickMode() {
        val items = ArrayList<String>()
        items.add("Project files (index.html)")
        items.add("Local server address…")
        val rec = recent()
        rec.forEach { items.add(it) }
        android.app.AlertDialog.Builder(ctx).setTitle("Preview").setItems(items.toTypedArray()) { _, i ->
            when (i) {
                0 -> showFiles()
                1 -> askAddress()
                else -> showServer(rec[i - 2])
            }
        }.show()
    }

    private fun askAddress() {
        val et = android.widget.EditText(ctx)
        et.setSingleLine()
        et.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        et.setText(serverUrl ?: recent().firstOrNull() ?: "http://127.0.0.1:3000")
        et.setSelection(et.text.length)
        val box = android.widget.FrameLayout(ctx)
        box.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), 0)
        box.addView(et)
        android.app.AlertDialog.Builder(ctx).setTitle("Local server address")
            .setMessage("A server started from ▶ or the terminal, e.g. http://127.0.0.1:3000")
            .setView(box)
            .setPositiveButton("Open") { _, _ ->
                var u = et.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                if (Regex("""^\d{2,5}$""").matches(u)) u = "http://127.0.0.1:$u"
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
                showServer(u)
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** A picture of what the preview shows, at most 1280 px wide (for sending to the AI), or null if nothing is drawn. */
    fun capture(): android.graphics.Bitmap? {
        val w = web.width
        val h = web.height
        if (w <= 0 || h <= 0) return null
        return try {
            val full = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(full)
            c.drawColor(0xFFFFFFFF.toInt())
            web.draw(c)
            if (w <= 1280) full else {
                val scaled = android.graphics.Bitmap.createScaledBitmap(full, 1280, (h * 1280.0 / w).toInt().coerceAtLeast(1), true)
                full.recycle()
                scaled
            }
        } catch (e: Throwable) { null }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.BG)
        val bar = Ui.horizontal(ctx)
        bar.setBackgroundColor(Ui.SURFACE)
        bar.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
        label.maxLines = 1
        // v0.4 W3: switch between project files and a local server
        mode.setPadding(0, Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
        mode.setOnClickListener { pickMode() }
        bar.addView(mode)
        label.setOnClickListener { if (serverUrl != null) askAddress() else pickMode() }
        bar.addView(label, Ui.lp(0, Ui.WRAP, 1f))
        val reload = Ui.text(ctx, "Reload", 13f, Ui.ACCENT)
        reload.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
        reload.setOnClickListener { reload() }
        consoleChip.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8))
        consoleChip.setOnClickListener { showConsole() }
        bar.addView(consoleChip)
        val debugChip = Ui.text(ctx, "Debug", 13f, Ui.ACCENT)
        debugChip.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8))
        debugChip.setOnClickListener { debugDialog() }
        bar.addView(debugChip)
        sizeChip.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8))
        sizeChip.setOnClickListener { pickSize() }
        bar.addView(sizeChip)
        bar.addView(reload)
        addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))

        log.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 4), Ui.dp(ctx, 12), Ui.dp(ctx, 4))
        log.maxLines = 4
        log.visibility = GONE
        addView(log, Ui.lp(Ui.MATCH, Ui.WRAP))

        // the debugger's panel (1.5.125): where the page stopped, the code there, the values, and the buttons
        dbgPanel.setBackgroundColor(Ui.SURFACE)
        dbgPanel.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 6), Ui.dp(ctx, 12), Ui.dp(ctx, 6))
        dbgPanel.addView(dbgWhere, Ui.lp(Ui.MATCH, Ui.WRAP))
        dbgCode.setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4))
        dbgPanel.addView(dbgCode, Ui.lp(Ui.MATCH, Ui.WRAP))
        dbgValues.setTextIsSelectable(true)
        dbgScroll.addView(dbgValues)
        dbgPanel.addView(dbgScroll, Ui.lp(Ui.MATCH, Ui.dp(ctx, 140)))
        val dbgRow = Ui.horizontal(ctx)
        for (b in listOf(dbgContinue, dbgStep, dbgStop)) dbgRow.addView(b, Ui.lp(0, Ui.WRAP, 1f).also { it.setMargins(0, Ui.dp(ctx, 4), Ui.dp(ctx, 6), 0) })
        dbgPanel.addView(dbgRow, Ui.lp(Ui.MATCH, Ui.WRAP))
        dbgPanel.visibility = GONE
        addView(dbgPanel, Ui.lp(Ui.MATCH, Ui.WRAP))

        setUpWeb(web)
        addView(web, Ui.lp(Ui.MATCH, 0, 1f))
        this.tag = shown
    }

    /** Settings, clients and colours of the visible page's browser. */
    private fun setUpWeb(w: WebView) {
        configure(w)
        w.addJavascriptInterface(AiBridge(), dev.pocketide.app.core.PreviewAi.NAME)
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val run = debugRun
                return serve(store, request, null, placed = run?.placed, debug = run?.plan)
            }

            // 1.5.133 (analysis C2): without this, a page that crashes or exhausts the browser's renderer takes the whole app down,
            // and any assistant run with it. The dead view is replaced and the page loaded again (unless it keeps crashing).
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                if (view === web) post { restartWeb() } else view.destroy()
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { if (view === web) pageUrl = url }

            override fun onPageFinished(view: WebView, url: String) {
                if (view === web) pageUrl = url
                debugRun?.let { showRunning(it) }   // breakpoints that landed on another line are named once the files have loaded
            }

            // v0.4 W3: a local server that is not running yet gives a readable page instead of Chrome's error
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                val url = serverUrl ?: return
                if (!request.isForMainFrame) return
                val safe = android.text.TextUtils.htmlEncode(url)
                view.loadDataWithBaseURL(null, "<body style='font-family:sans-serif;color:#444;padding:24px;line-height:1.5'>" +
                    "<h3>Nothing is answering at $safe</h3><p>Start the server with ▶ (or in the Terminal tab), then tap Reload.</p>" +
                    "<p style='color:#888'>${android.text.TextUtils.htmlEncode(error.description?.toString() ?: "")}</p></body>",
                    "text/html", "utf-8", null)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                record(m.messageLevel().name, m.message(), m.sourceId() ?: "", m.lineNumber())
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    shown.add(m.message() + " (" + m.sourceId().substringAfterLast('/') + ":" + m.lineNumber() + ")")
                    log.text = shown.takeLast(4).joinToString("\n")
                    log.visibility = VISIBLE
                }
                return true
            }
        }
        w.setBackgroundColor(0xFFFFFFFF.toInt())
    }

    private fun restartWeb() {
        val i = indexOfChild(web)
        val old = web
        endDebug()
        removeView(old)
        try { old.destroy() } catch (e: Throwable) { }
        web = WebView(ctx)
        setUpWeb(web)
        addView(web, if (i < 0) childCount else i, Ui.lp(Ui.MATCH, 0, 1f))
        applySize()
        val again = restarts.reload(System.currentTimeMillis())
        log.text = if (again) dev.pocketide.app.core.RendererCrash.RESTARTED else dev.pocketide.app.core.RendererCrash.STOPPED
        log.visibility = VISIBLE
        dev.pocketide.app.core.AppLog.add("preview", "renderer gone" + if (again) ", reloaded" else ", not reloaded (crashed twice in a minute)")
        if (again) load()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(w: WebView) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.cacheMode = WebSettings.LOAD_NO_CACHE
    }

    /** The page to preview: index.html, else the first .html file. */
    /** The page to open; found on disk, not from the file list, which leaves files out of very big projects. */
    fun pickEntry(): String? = store.findPage()

    @Suppress("UNCHECKED_CAST")
    /** Called before the page is loaded again: the editor saves, so code shown next to the preview is what runs (1.5.113). */
    var beforeReload: () -> Unit = {}

    fun reload() {
        endDebug()   // a reload by hand loads the plain files again (1.5.125)
        load()
    }

    private fun load() {
        try { beforeReload() } catch (e: Exception) { }
        (tag as? MutableList<String>)?.clear()
        console.clear()
        consoleChip.text = "Console"
        log.visibility = GONE
        // v0.4 W3: local server mode
        val server = serverUrl
        mode.text = if (server != null) "Server ▾" else "Files ▾"
        if (server != null) {
            entry = ""
            label.text = server
            web.loadUrl(server)
            return
        }
        val e = pickEntry()
        if (e == null) {
            entry = ""
            label.text = "No .html file in this project"
            web.loadData("<body style='font-family:sans-serif;color:#666;padding:24px'>Add an index.html to preview a page.</body>", "text/html", "utf-8")
            return
        }
        entry = e
        label.text = e
        web.loadUrl("https://${hostOf(store)}/$e")
    }

    fun destroy() {
        endDebug()   // let a paused page go before the browser is torn down
        web.stopLoading()
        web.destroy()
    }

    /** Tap a few harmless buttons during the check (set from the settings before each check). */
    var tapCheck = true

    /**
     * Loads the page in an invisible WebView and reports what went wrong: console errors, missing files, a page that
     * shows nothing, values like NaN on screen, an empty canvas, and errors raised by tapping a few harmless buttons.
     * The callback receives an empty list when the page ran cleanly, and arrives on the main thread.
     */
    fun check(onResult: (List<String>) -> Unit) {
        val e = pickEntry()
        if (e == null) { onResult(emptyList()); return }
        val errors = ArrayList<String>()
        val main = Handler(Looper.getMainLooper())
        val probe = WebView(ctx)
        configure(probe)
        // an unattached WebView has no size, so every element measured 0x0 and a working canvas game looked empty
        run {
            val dm = ctx.resources.displayMetrics
            probe.measure(android.view.View.MeasureSpec.makeMeasureSpec(dm.widthPixels, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(dm.heightPixels, android.view.View.MeasureSpec.EXACTLY))
            probe.layout(0, 0, dm.widthPixels, dm.heightPixels)
        }
        var finished = false
        var tapping = false
        val texts = HashMap<String, String>()
        for (p in store.list().filter { dev.pocketide.app.core.TouchCheck.isWebFile(it) && !dev.pocketide.app.core.GameTemplates.isEngineFile(it) }.take(40)) {
            val b = store.readBytes(p) ?: continue
            texts[p] = String(b, Charsets.UTF_8).take(200_000)
        }
        var before: Map<String, Any?>? = null
        var after: Map<String, Any?>? = null
        fun finish() {
            if (finished) return
            finished = true
            probe.stopLoading()
            probe.destroy()
            val seen = dev.pocketide.app.core.PageProbe.problems(before, after, dev.pocketide.app.core.PageProbe.hasDrawingLoop(texts))
            onResult((errors + seen + dev.pocketide.app.core.TouchCheck.problems(texts)).distinct().take(8))
        }
        probe.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                main.post { errors.add(dev.pocketide.app.core.RendererCrash.IN_CHECK); finish() }
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // the check must never post anything to a real server
                if (!request.method.equals("GET", true)) return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                return serve(store, request, errors, recordTaps = tapCheck)
            }
            override fun onPageFinished(view: WebView, url: String) {
                // scripts often act on load or after a short timer, so wait a little before judging
                main.postDelayed({
                    if (finished) return@postDelayed
                    view.evaluateJavascript(dev.pocketide.app.core.PageProbe.LOOK) { r1 ->
                        before = dev.pocketide.app.core.PageProbe.parse(r1)
                        if (!tapCheck) { after = before; finish(); return@evaluateJavascript }
                        tapping = true
                        view.evaluateJavascript(dev.pocketide.app.core.PageProbe.tapScript()) { _ ->
                            main.postDelayed({
                                view.evaluateJavascript(dev.pocketide.app.core.PageProbe.LOOK) { r2 ->
                                    after = dev.pocketide.app.core.PageProbe.parse(r2)
                                    finish()
                                }
                            }, 1700)
                        }
                    }
                }, 1800)
            }
        }
        probe.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR && errors.size < 20) {
                    errors.add((if (tapping) "After tapping a control: " else "") + m.message() + " (" + m.sourceId().substringAfterLast('/') + ":" + m.lineNumber() + ")")
                }
                return true
            }
            // dialogs would block the check; answer them silently
            override fun onJsAlert(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean { result.confirm(); return true }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean { result.cancel(); return true }
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: android.webkit.JsPromptResult): Boolean { result.cancel(); return true }
        }
        main.postDelayed({ finish() }, 12000)
        probe.loadUrl("https://${hostOf(store)}/$e")
    }

    /**
     * For the look_at_preview tool: loads the page in a hidden browser placed under the app's own screen (so it is really
     * drawn), then returns a text report and, when it worked, a JPEG data URL of what the screen shows. WebGL canvases are
     * read from inside the page and painted over the screenshot, because the screenshot path cannot capture them.
     * The callback arrives on the main thread.
     */
    /**
     * The Debug button (Trace, 1.5.112): log points and optional steps; the page runs in the hidden browser with them and the values
     * seen at each point come back, with errors and their stacks. The files are not changed.
     */
    private fun debugDialog() {
        val prefs = ctx.getSharedPreferences("pocketide", Context.MODE_PRIVATE)
        val key = "debug.points." + store.root.name
        val box = Ui.vertical(ctx).also { it.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 4), Ui.dp(ctx, 16), 0) }
        box.addView(Ui.text(ctx, "Log points: one per line, file:line then the values to record. Run: the page runs once with them in a hidden browser. " +
            "Pause: the preview reloads and stops at these lines, showing the values and the names there; then Continue, Step or Stop debugging. Your files are not changed.", 12f, Ui.MUTED, false))
        val points = android.widget.EditText(ctx).apply { hint = "game/main.js:248 sid, inv.slots"; typeface = android.graphics.Typeface.MONOSPACE; textSize = 13f; minLines = 2; setText(prefs.getString(key, "")) }
        box.addView(points, Ui.lp(Ui.MATCH, Ui.WRAP))
        val steps = android.widget.EditText(ctx).apply { hint = "Optional steps first: tap #start; wait 500; hold 120 300 700"; textSize = 13f; minLines = 1 }
        box.addView(steps, Ui.lp(Ui.MATCH, Ui.WRAP))
        val out = Ui.text(ctx, "", 12f, Ui.TEXT, false).also { it.typeface = android.graphics.Typeface.MONOSPACE; it.setTextIsSelectable(true) }
        box.addView(out, Ui.lp(Ui.MATCH, Ui.WRAP))
        val dlg = android.app.AlertDialog.Builder(ctx).setTitle("Debug the page").setView(android.widget.ScrollView(ctx).also { it.addView(box) })
            .setPositiveButton("Run", null).setNeutralButton("Pause", null).setNegativeButton("Close", null).create()
        dlg.setOnShowListener {
            // pause-and-step (1.5.125): the same lines become breakpoints in the visible preview
            dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val spec = points.text.toString()
                prefs.edit().putString(key, spec).apply()
                val why = startDebug(spec)
                if (why != null) out.text = "Not paused: $why" else dlg.dismiss()
            }
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val spec = points.text.toString()
                prefs.edit().putString(key, spec).apply()
                out.text = "Running the page…"
                look(steps.text.toString(), { report, _ -> out.text = report.substring(report.indexOf("Log points:").takeIf { it >= 0 } ?: 0) + (if (report.contains("Log points:")) "\n\n" + report.substringBefore("Log points:").trim() else "") }, "", spec.ifBlank { " " })
            }
        }
        dlg.show()
    }

    fun look(steps: String, onResult: (report: String, image: String?) -> Unit, page: String = "", traceSpec: String = "", target: String? = null) {
        val e = page.trim().removePrefix("./").removePrefix("/").takeIf { it.endsWith(".html", true) && store.exists(it) } ?: pickEntry()
        if (e == null) { onResult("This project has no .html page to look at.", null); return }
        val plan = dev.pocketide.app.core.PageSteps.parse(steps)
        if (plan.error != null) { onResult("Steps not played: ${plan.error}. Nothing was loaded; fix the steps and call look_at_preview again.", null); return }
        val trace = if (traceSpec.isBlank()) null else dev.pocketide.app.core.Trace.parse(traceSpec)
        if (trace?.error != null) { onResult("Log points not set: ${trace.error}. Nothing was loaded.", null); return }
        val placed = java.util.Collections.synchronizedMap(LinkedHashMap<Int, Int?>())
        val errors = ArrayList<String>()
        val main = Handler(Looper.getMainLooper())
        val probe = WebView(ctx)
        configure(probe)
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        val host = (ctx as? android.app.Activity)?.window?.decorView as? android.view.ViewGroup
        // 1.5.98: sized by hand as well. With the app in the background Android lays nothing out, so the hidden browser stayed 0x0:
        // the page saw a 0x0 screen, every element was "outside the screen" and the canvas "drew nothing", and the model believed it.
        fun size() {
            if (probe.width == w && probe.height == h) return
            probe.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
            probe.layout(0, 0, w, h)
        }
        if (host != null) host.addView(probe, 0, android.widget.FrameLayout.LayoutParams(w, h))
        size()
        var finished = false
        fun finish(report: String, image: String?) {
            if (finished) return
            finished = true
            probe.stopLoading()
            host?.removeView(probe)
            probe.destroy()
            onResult(report, image)
        }
        fun shoot(look: Map<String, Any?>?): Pair<String?, String> {
            return try {
                val full = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(full)
                c.drawColor(android.graphics.Color.WHITE)
                probe.draw(c)
                val scale = (look?.get("dpr") as? Number)?.toFloat() ?: dm.density
                for (cv in dev.pocketide.app.core.PageLook.canvases(look)) {
                    val raw = android.util.Base64.decode(cv.dataUrl.substringAfter("base64,"), android.util.Base64.DEFAULT)
                    val bm = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: continue
                    val dst = android.graphics.RectF(cv.x * scale, cv.y * scale, (cv.x + cv.w) * scale, (cv.y + cv.h) * scale)
                    c.drawBitmap(bm, null, dst, null)
                    bm.recycle()
                }
                val outW = minOf(480, w)
                val small = android.graphics.Bitmap.createScaledBitmap(full, outW, (h.toFloat() * outW / w).toInt().coerceAtLeast(1), true)
                if (small != full) full.recycle()
                // screenshot to code (DesignMatch, 1.5.112): the page against the design picture the person attached
                val match = target?.let { tg -> try {
                    val raw = android.util.Base64.decode(tg.substringAfter("base64,"), android.util.Base64.DEFAULT)
                    val bm = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    if (bm == null) null else {
                        fun cells(b: android.graphics.Bitmap): IntArray { val s = android.graphics.Bitmap.createScaledBitmap(b, 90, 160, true); val a = IntArray(90 * 160); s.getPixels(a, 0, 90, 0, 0, 90, 160); if (s != b) s.recycle(); return dev.pocketide.app.core.DesignMatch.grid(a, 90, 160) }
                        val r = dev.pocketide.app.core.DesignMatch.compare(cells(bm), cells(small)); bm.recycle()
                        "\n" + dev.pocketide.app.core.DesignMatch.report(r)
                    }
                } catch (e: Throwable) { null } }.orEmpty()
                val px = IntArray(small.width * small.height / 64 + 1)
                var k = 0
                for (y in 0 until small.height step 8) for (x in 0 until small.width step 8) if (k < px.size) px[k++] = small.getPixel(x, y)
                if (dev.pocketide.app.core.PageLook.flat(px.copyOf(k))) { small.recycle(); return null to "Screenshot: it came out as one flat colour, so it is not attached; rely on the report above.$match" }
                val bos = java.io.ByteArrayOutputStream()
                small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bos)
                small.recycle()
                ("data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)) to "Screenshot: attached (${outW}px wide).$match"
            } catch (t: Throwable) {
                null to "Screenshot: could not be taken (${t.javaClass.simpleName})."
            }
        }
        probe.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                main.post { finish(dev.pocketide.app.core.RendererCrash.lookReport(errors), null) }
                return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!request.method.equals("GET", true)) return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                return serve(store, request, errors, keepCanvas = true, trace = trace, placed = placed)
            }
            override fun onPageFinished(view: WebView, url: String) {
                // games build their first screen after load; give them a moment
                main.postDelayed({
                    if (finished) return@postDelayed
                    fun report(prefix: String, checks: List<dev.pocketide.app.core.PageSteps.Check> = emptyList()) {
                        size()
                        view.evaluateJavascript(dev.pocketide.app.core.PageLook.REPORT) { r ->
                            val look = dev.pocketide.app.core.PageProbe.parse(r)
                            val (img, shotNote) = shoot(look)
                            val text = dev.pocketide.app.core.PageLook.summary(look, errors, shotNote, checks).let { s -> val nl = s.indexOf('\n'); if (prefix.isEmpty() || nl < 0) s else s.substring(0, nl + 1) + prefix + s.substring(nl + 1) }
                            if (trace == null) finish(text, img)
                            else view.evaluateJavascript(dev.pocketide.app.core.Trace.RESULTS) { raw ->
                                finish(text + "\n\n" + dev.pocketide.app.core.Trace.summary(dev.pocketide.app.core.PageProbe.parse(raw), trace.points, HashMap(placed)), img)
                            }
                        }
                    }
                    size()
                    if (plan.steps.isEmpty()) { report(""); return@postDelayed }
                    view.evaluateJavascript(dev.pocketide.app.core.PageSteps.STATE) { before ->
                        val was = dev.pocketide.app.core.PageSteps.text(before)
                        val started = System.currentTimeMillis()
                        // the steps run inside the page one after another (until waits as long as it must); ask every 250 ms whether they are done
                        fun poll() {
                            if (finished) return
                            view.evaluateJavascript(dev.pocketide.app.core.PageSteps.RESULTS) { raw ->
                                val checks = dev.pocketide.app.core.PageSteps.results(raw)
                                val late = System.currentTimeMillis() - started > plan.totalMs + 4000L
                                if (checks == null && !late) { main.postDelayed({ poll() }, 250); return@evaluateJavascript }
                                main.postDelayed({
                                    if (!finished) report("Played ${plan.steps.size} step${if (plan.steps.size == 1) "" else "s"}" + (if (checks == null) " (they did not finish in time)" else "") + "; the report below is after them." +
                                        (if (was.isNotEmpty()) "\nGame state before the steps: $was" else "") + "\n", checks ?: listOf(dev.pocketide.app.core.PageSteps.Check("steps", false, "did not finish within ${plan.totalMs + 4000} ms")))
                                }, 300L)
                            }
                        }
                        view.evaluateJavascript(dev.pocketide.app.core.PageSteps.script(plan.steps)) { _ -> main.postDelayed({ poll() }, 200) }
                    }
                }, 2500)
            }
        }
        probe.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR && errors.size < 20) errors.add(m.message() + " (" + m.sourceId().substringAfterLast('/') + ":" + m.lineNumber() + ")")
                return true
            }
            override fun onJsAlert(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean { result.confirm(); return true }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean { result.cancel(); return true }
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: android.webkit.JsPromptResult): Boolean { result.cancel(); return true }
        }
        main.postDelayed({ finish(dev.pocketide.app.core.PageLook.summary(null, errors, ""), null) }, 15000L + plan.totalMs)
        probe.loadUrl("https://${hostOf(store)}/$e")
    }
}
