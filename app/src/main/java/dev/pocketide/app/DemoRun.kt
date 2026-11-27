package dev.pocketide.app

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.pocketide.app.core.DemoScript
import java.io.File

/**
 * 1.5.213: one tap records-ready demo of Hybrid AI for the #BuildHybridAI video. It does the tapping and typing a person would, in a
 * set order of scenes (DemoScript.SCENES); every answer is live, from Gemini Nano on the phone or Gemini in the cloud, and nothing is
 * replayed. A bar at the top names the scene, with ↺ (retake this scene), ▸ (next scene) and ■ (stop). The demo works in projects
 * named "Demo …", shows the welcome screen as a new user sees it without resetting anything, and puts the person's settings back at the
 * end. A scene log (what ran where, and when) is saved to Downloads/PocketIDE for the captions.
 */
object DemoRun {
    @Volatile var running = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private lateinit var act: MainActivity
    private lateinit var log: DemoScript.Log
    private var stamp = ""
    private var before: Map<String, String> = emptyMap()
    private var at = 0
    /** Bumped by retake, next and stop, so the callbacks of a scene that was left do nothing. */
    private var token = 0
    private var pythonReady = false
    private val dialogs = ArrayList<Dialog>()
    private var bar: LinearLayout? = null
    private var barText: TextView? = null
    private var tryScreen: HybridScreen.DemoTry? = null

    private fun toast(s: String) = Toast.makeText(act.applicationContext, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = Ui.dp(act, v)

    /** Runs [f] after [ms] if the scene that asked for it is still the one playing. */
    private fun later(ms: Long, f: () -> Unit) { val my = token; main.postDelayed({ if (running && my == token) f() }, ms) }

    // ---------------- start: the checklist ----------------

    fun start(a: MainActivity) {
        act = a
        if (running) { toast("The demo is already running."); return }
        if (HybridBench.running) { toast("A benchmark is running. Stop it first."); return }
        if (!HybridAi.configured(a)) { toast("This build has no Firebase config, so Hybrid AI (and the demo) is off."); return }
        val battery = try { (a.getSystemService(android.content.Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (e: Exception) { -1 }
        val online = HybridAgent.online(a)
        val msg = TextView(a).apply { textSize = 14f; setTextColor(Ui.TEXT); setPadding(dp(20), dp(8), dp(20), dp(8)) }
        var pyLine = "checking…"
        fun paint() {
            msg.text = "Every answer in the demo is live, from Gemini Nano on this phone or Gemini in the cloud. The demo only taps and types " +
                "as you would. If a scene goes wrong, tap ↺ in the top bar to do it again.\n\n" +
                "Before you record:\n" +
                "• Battery: " + (if (battery >= 0) "$battery%" else "unknown") + (if (battery in 0..29) ". Plug the phone in." else "") + "\n" +
                "• Internet: " + (if (online) "connected" else "OFF. Connect first (the cloud scenes need it).") + "\n" +
                "• Turn on Do Not Disturb, so notifications stay out of the video.\n" +
                "• Python for the bug-fix scene: $pyLine\n" +
                "• Scene 3 asks you to turn airplane mode on, then off again.\n\n" +
                "The demo makes projects named \"Demo …\" and puts your settings and project back at the end. About 10 to 15 minutes.\n\n" +
                "Start your screen recorder, then tap Go."
        }
        paint()
        val dlg = AlertDialog.Builder(a).setTitle("Record a demo of Hybrid AI").setView(android.widget.ScrollView(a).also { it.addView(msg) })
            .setPositiveButton("Go") { _, _ -> begin() }.setNegativeButton("Cancel", null).show()
        pythonReady = false
        if (!a.demoLinuxActive()) { pyLine = "Linux is not set up, so that scene is skipped (set it up from the Terminal tab first)."; paint(); return }
        val rb = dev.pocketide.app.core.ReliableBench
        a.benchChat().runForChecks(rb.PY_CHECK) { code, _ ->
            if (code == 0) main.post { pythonReady = true; pyLine = "ready"; if (dlg.isShowing) paint() }
            else {
                main.post { pyLine = "installing Python and pytest in Linux (once)…"; if (dlg.isShowing) paint() }
                a.benchChat().runForChecks(rb.PY_INSTALL) { _, _ ->
                    a.benchChat().runForChecks(rb.PY_CHECK) { code2, _ ->
                        main.post { pythonReady = code2 == 0; pyLine = if (pythonReady) "ready" else "could not be installed, so that scene is skipped."; if (dlg.isShowing) paint() }
                    }
                }
            }
        }
    }

    private fun begin() {
        running = true
        token++
        at = 0
        stamp = java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        before = HybridBench.beforeNow(act)
        try { act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (e: Exception) { }
        showBar()
        var n = 3
        fun count() {
            if (!running) return
            if (n == 0) { log = DemoScript.Log(); HybridCloud.tap = { w, s, r, k -> log.add(DemoScript.route(w, s, r, k)) }; play(); return }
            say("Starting in $n…"); n--; main.postDelayed({ count() }, 1000)
        }
        count()
    }

    // ---------------- the bar and the taps ----------------

    private fun showBar() {
        val b = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(0xE0141A26.toInt()); cornerRadius = dp(10).toFloat() }
            setPadding(dp(12), dp(4), dp(4), dp(4))
        }
        val t = TextView(act).apply { textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); maxLines = 3 }
        b.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fun icon(s: String, f: () -> Unit) = TextView(act).apply {
            text = s; textSize = 17f; setTextColor(0xFF9DB0FF.toInt()); setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { f() }
        }.also { b.addView(it) }
        icon("↺") { retake() }
        icon("▸") { skip() }
        icon("■") { stop() }
        bar = b; barText = t
        addBar()
    }

    private fun barParams() = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP; y = dp(36); horizontalMargin = 0.03f }

    private fun addBar() { val b = bar ?: return; try { act.windowManager.addView(b, barParams()) } catch (e: Exception) { } }

    /** Puts the bar back on top after a dialog opened over it. */
    private fun raise() { val b = bar ?: return; try { act.windowManager.removeViewImmediate(b) } catch (e: Exception) { }; addBar() }

    private fun removeBar() { val b = bar ?: return; bar = null; try { act.windowManager.removeViewImmediate(b) } catch (e: Exception) { } }

    private fun say(s: String) { barText?.text = s }

    /** A circle where the demo taps, then the tap itself, so viewers see what was pressed. */
    private fun tap(v: View, then: () -> Unit = {}) {
        val my = token
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val size = dp(46)
        val dot = View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x667C93FF); setStroke(dp(2), 0xFFFFFFFF.toInt()) } }
        val p = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = loc[0] + v.width / 2 - size / 2; y = loc[1] + v.height / 2 - size / 2 }
        try { act.windowManager.addView(dot, p) } catch (e: Exception) { }
        main.postDelayed({
            try { act.windowManager.removeViewImmediate(dot) } catch (e: Exception) { }
            if (running && my == token) { v.performClick(); then() }
        }, 650)
    }

    /** Types [text] into [input] a few letters at a time, as a person would. */
    private fun type(input: EditText, text: String, then: () -> Unit) {
        input.setText("")
        var i = 0
        fun step() {
            if (i >= text.length) { later(500) { then() }; return }
            i = minOf(text.length, i + 2)
            input.setText(text.substring(0, i)); input.setSelection(i)
            later(45) { step() }
        }
        step()
    }

    private fun closeDialogs() { for (d in dialogs) try { if (d.isShowing) d.dismiss() } catch (e: Exception) { }; dialogs.clear(); tryScreen = null }

    // ---------------- scenes ----------------

    private fun play() {
        if (at >= DemoScript.SCENES.size) { finish(false); return }
        val s = DemoScript.SCENES[at]
        log.scene(s)
        say("${at + 1}/${DemoScript.SCENES.size} · ${s.title}")
        when (s.id) {
            "welcome" -> welcome()
            "try" -> tryIt()
            "offline" -> offline()
            "build" -> agent("Demo snake game", DemoScript.SNAKE, emptyMap()) { log.add("Opened Preview to play the game."); act.benchSelect(1); say("${at + 1}/${DemoScript.SCENES.size} · Play it: arrow keys or swipe. ▸ when done."); later(60_000) { next() } }
            "python" -> python()
            "aiweb" -> aiWeb()
            "cloud" -> agent("Demo research", DemoScript.CLOUD, emptyMap()) {
                val notes = File(Projects.root(act), Projects.key(act) + "/notes.md")
                log.add(if (notes.isFile) "notes.md written:\n" + notes.readText().take(600) else "notes.md was not written.")
                later(8000) { next() }
            }
            "results" -> results()
        }
    }

    private fun next() { token++; act.benchChat().onExchangeEnded = {}; closeDialogs(); at++; play() }

    private fun skip() {
        if (!running) return
        log.add("▸ skipped to the next scene by the person recording.")
        if (act.benchChat().busy()) act.benchChat().stopNow()
        next()
    }

    private fun retake() {
        if (!running) return
        log.add("↺ retake of this scene.")
        token++
        if (act.benchChat().busy()) act.benchChat().stopNow()
        act.benchChat().onExchangeEnded = {}
        closeDialogs()
        later(1500) { play() }
    }

    private fun welcome() {
        later(1200) {
            val card = act.demoWelcome { act.settingsForDemo().provider = "hybrid"; log.add("Chose Hybrid AI: Gemini Nano + cloud. No account or key."); later(1200) { next() } }
            later(300) { raise() }
            if (card == null) { log.add("This build has no Hybrid AI choice."); later(2000) { next() } }
            else later(4500) { tap(card) }
        }
    }

    private fun openTry(then: (HybridScreen.DemoTry) -> Unit) {
        HybridScreen.setMode(act, HybridAi.Mode.PREFER_ON_DEVICE)
        HybridScreen.demoOpened = { h ->
            HybridScreen.demoOpened = null
            tryScreen = h; dialogs.add(h.dialog)
            later(400) { raise() }
            later(2500) { h.scroll.smoothScrollTo(0, maxOf(0, h.card.top - dp(80))); later(1500) { then(h) } }
        }
        HybridScreen.show(act)
    }

    private fun ask(h: HybridScreen.DemoTry, q: String, answered: (HybridAi.Reply?) -> Unit) {
        val my = token
        HybridScreen.demoAnswered = { r ->
            HybridScreen.demoAnswered = null
            if (running && my == token) {
                log.add(if (r == null) "No answer: " + h.answer.text.toString().take(200) else "Answer ${r.where}, %.1f s: ".format(r.seconds) + r.text.trim().take(300))
                answered(r)
            }
        }
        log.add("Typed in Try it: \"$q\"")
        type(h.input, q) { tap(h.ask) }
    }

    /** Try it; the offline scene follows on the same screen, so the answer stays in view. */
    private fun tryIt() = openTry { h -> ask(h, DemoScript.TRY_QUESTION) { later(7000) { at++; log.scene(DemoScript.SCENES[at]); offline() } } }

    private fun offline() {
        val h = tryScreen
        if (h == null || !h.dialog.isShowing) { openTry { offlineOn(it) }; return }
        offlineOn(h)
    }

    private fun offlineOn(h: HybridScreen.DemoTry) {
        say("${at + 1}/${DemoScript.SCENES.size} · Turn on airplane mode now. The demo waits.")
        fun waitOff() {
            if (!HybridAgent.online(act)) {
                log.add("Airplane mode on: no connection.")
                say("${at + 1}/${DemoScript.SCENES.size} · Offline")
                later(2500) {
                    ask(h, DemoScript.OFFLINE_QUESTION) {
                        later(6000) {
                            say("${at + 1}/${DemoScript.SCENES.size} · Turn airplane mode off again. The demo waits.")
                            fun waitOn() { if (HybridAgent.online(act)) { log.add("Back online."); later(5000) { next() } } else later(1000) { waitOn() } }
                            waitOn()
                        }
                    }
                }
            } else later(1000) { waitOff() }
        }
        waitOff()
    }

    /**
     * Sends [request] to the agent in a new project, in Agent mode with Hybrid AI (phone first), and calls [after] when the agent has
     * stayed idle, as the benchmark does.
     */
    private fun agent(name: String, request: String, files: Map<String, String>, pythonFix: Boolean = false,
                      template: dev.pocketide.app.core.Template = dev.pocketide.app.core.Templates.EMPTY, after: () -> Unit) {
        val s = act.settingsForDemo()
        s.provider = "hybrid"; s.workflow = dev.pocketide.app.core.Workflow.SINGLE; s.agent = true; s.mode = "agent"; s.pythonHelper = pythonFix
        HybridScreen.setMode(act, HybridAi.Mode.PREFER_ON_DEVICE)
        val project = Projects.create(act, name, template)
        val root = File(Projects.root(act), project)
        for ((p, text) in files) File(root, p).apply { parentFile?.mkdirs(); writeText(text) }
        log.add("New project \"$project\", Agent mode, Hybrid AI (phone first). Sent: \"${request.take(200)}\"")
        val chat = act.benchChat()
        val my = token
        val t0 = System.currentTimeMillis()
        var ended = ""
        var finished = false
        fun done(how: String) {
            if (finished || my != token) return
            finished = true
            chat.onExchangeEnded = {}
            log.add("The agent finished ($how) after ${(System.currentTimeMillis() - t0) / 1000} s.")
            after()
        }
        chat.onExchangeEnded = { ok -> ended = if (ok) "done" else "stopped or failed"; later(8000) { if (!chat.busy()) done(ended) } }
        later(800) { act.benchAsk(project, request) }
        fun watch() {
            if (finished) return
            val spent = System.currentTimeMillis() - t0
            when {
                spent > DemoScript.AGENT_LIMIT_MS -> { chat.stopNow(); later(5000) { done("stopped at the demo's time limit") } }
                spent > 90_000 && ended.isEmpty() && !chat.busy() -> done("idle")
                else -> later(5000) { watch() }
            }
        }
        later(5000) { watch() }
    }

    private fun python() {
        val t = if (pythonReady) try { HybridBench.reliableTasks(act, true).firstOrNull { it.id == DemoScript.PYTHON_TASK } } catch (e: Exception) { null } else null
        if (t == null) { log.add("Skipped: Python is not set up in Linux on this phone."); later(1500) { next() }; return }
        agent("Demo Python fix (requests)", t.prompt, t.files, pythonFix = true) {
            val cmd = t.command ?: run { later(6000) { next() }; return@agent }
            act.benchChat().runForChecks(cmd) { code, out ->
                main.post {
                    log.add("The test afterwards (`$cmd`): " + (if (code == 0) "passes. " else "fails. ") + out.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(160))
                    later(8000) { next() }
                }
            }
        }
    }

    /** The agent changes the Hybrid AI web app starter; then the page is used in Preview, where it asks PocketIDE's Gemini Nano. */
    private fun aiWeb() {
        agent("Demo recipe helper", DemoScript.AIWEB, emptyMap(), template = dev.pocketide.app.core.AiWebTemplate.TEMPLATE) {
            log.add("Opened Preview; typing \"${DemoScript.AIWEB_INPUT}\" into the page.")
            act.benchSelect(1)
            later(4000) {
                act.demoPreviewScript(DemoScript.typeIntoPage(DemoScript.AIWEB_INPUT)) { r -> log.add("The page: " + r.trim('"')) }
                var tries = 0
                fun read() {
                    act.demoPreviewScript(DemoScript.READ_PAGE) { raw ->
                        val o = try { dev.pocketide.app.core.MiniJson.obj(dev.pocketide.app.core.MiniJson.parse(dev.pocketide.app.core.MiniJson.parse(raw) as String)) } catch (e: Exception) { null }
                        val where = o?.get("where")?.toString().orEmpty()
                        if (where.isNotBlank() || tries++ > 40) {
                            log.add("The page's answer (" + where.ifBlank { "no where-it-ran line" } + "): " + o?.get("answer")?.toString().orEmpty().take(400))
                            later(12_000) { next() }
                        } else later(1500) { read() }
                    }
                }
                later(3000) { read() }
            }
        }
    }

    private fun results() {
        closeDialogs()
        val fs = FullScreen(act, "PocketIDE Hybrid")
        fs.heading("Measured on this phone", 8)
        val card = fs.card()
        for (r in DemoScript.RESULTS) card.addView(Ui.text(act, "• $r", 16f, Ui.TEXT).also { it.setPadding(0, dp(6), 0, dp(6)) })
        fs.body.addView(Ui.text(act, DemoScript.RESULTS_SOURCE, 13f, Ui.MUTED).also { it.setPadding(0, dp(10), 0, 0) })
        fs.heading("A coding agent in your pocket")
        fs.body.addView(Ui.text(act, "On the phone first, the cloud when it matters. Built with Firebase AI Logic.", 15f, Ui.TEXT))
        fs.body.addView(Ui.text(act, DemoScript.REPO, 15f, Ui.ACCENT).also { it.setPadding(0, dp(12), 0, 0) })
        fs.show(); dialogs.add(fs.dialog)
        log.add("End card: measured results and the repository link.")
        later(400) { raise(); say("Done. Stop your screen recorder, then tap ■.") }
        later(20_000) { finish(false) }
    }

    // ---------------- the end ----------------

    fun stop() { if (running) finish(true) }

    private fun finish(stopped: Boolean) {
        if (!running) return
        if (stopped) log.add("■ stopped by the person recording.")
        token++
        running = false
        HybridCloud.tap = null
        HybridScreen.demoOpened = null; HybridScreen.demoAnswered = null
        try { if (act.benchChat().busy()) act.benchChat().stopNow(); act.benchChat().onExchangeEnded = {} } catch (e: Exception) { }
        try { act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (e: Exception) { }
        removeBar()
        if (stopped) closeDialogs()
        HybridBench.restore(act, before)
        val text = if (::log.isInitialized) log.text(stamp, Build.MANUFACTURER + " " + Build.MODEL) else ""
        if (text.isNotEmpty()) try {
            val where = HybridBench.saveToDownloads(act, "PocketIDE-demo-log-$stamp.txt", "text/plain") { it.write(text.toByteArray()) }
            toast("Demo ended. Your settings are back. Scene log: $where (send it with the recording for the captions).")
        } catch (e: Exception) { toast("Demo ended. The scene log could not be saved: ${e.message}") }
    }
}
