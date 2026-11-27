package dev.pocketide.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.pocketide.app.core.HybridSettings
import java.util.concurrent.Executors

/**
 * Hybrid AI (#BuildHybridAI 2026): Gemini Nano on this phone with a cloud Gemini fallback, through Firebase AI Logic.
 * The screen shows whether Nano is ready (and downloads it), lets the person pick the routing mode and try a prompt,
 * and labels every reply with where it ran and how long it took.
 */
object HybridScreen {
    private const val PREFS = "hybrid_ai"

    fun mode(ctx: Context): HybridAi.Mode =
        HybridAi.Mode.entries.firstOrNull { it.name == ctx.getSharedPreferences(PREFS, 0).getString("mode", null) } ?: HybridCloud.remoteMode()

    fun setMode(ctx: Context, m: HybridAi.Mode) = ctx.getSharedPreferences(PREFS, 0).edit().putString("mode", m.name).apply()

    fun show(activity: Activity) {
        val ctx: Context = activity
        val fs = FullScreen(activity, "Hybrid AI (Gemini Nano + cloud)")
        val main = Handler(Looper.getMainLooper())
        val io = Executors.newSingleThreadExecutor()
        var closed = false
        fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

        fs.body.addView(Ui.text(ctx, "Quick coding help runs on this phone with Gemini Nano: offline, private and free. Long requests, " +
            "phones without Nano, and the cloud-first mode use a cloud Gemini model through Firebase AI Logic. Every reply says where it ran. " +
            "In the editor: ⋮ → Quick assist.", 13f, Ui.MUTED))
        fs.buttonRow(fs.body, Ui.button(ctx, "How it works") { intro(activity, always = true) })

        if (!HybridAi.configured(ctx)) {
            fs.heading("Not set up in this build")
            fs.body.addView(Ui.text(ctx, "This APK was built without a Firebase config (app/google-services.json), so hybrid AI is off. " +
                "Everything else in PocketIDE works as before. See HACKATHON.md in the pocketide-hybrid repository.", 13f, Ui.TEXT))
            fs.show(); return
        }

        // ---- Gemini Nano on this phone ----
        fs.heading("Gemini Nano on this phone")
        val nanoCard = fs.card()
        val nanoLine = Ui.text(ctx, "Checking…", 13f, Ui.TEXT)
        nanoCard.addView(nanoLine)
        lateinit var refresh: () -> Unit
        val downloadBtn = Ui.button(ctx, "Download Gemini Nano", true) {
            nanoLine.text = "Starting the download (Android AICore)…"
            io.execute {
                val err = HybridAi.downloadNano(ctx) { done, total ->
                    main.post { if (!closed) nanoLine.text = if (total > 0) "Downloading: ${done / 1_000_000} of ${total / 1_000_000} MB" else "Downloading: ${done / 1_000_000} MB" }
                }
                main.post { if (!closed) { if (err != null) toast(err) else toast("Gemini Nano is ready on this phone."); refresh() } }
            }
        }
        fs.buttonRow(nanoCard, downloadBtn, Ui.button(ctx, "Check again") { refresh() })
        refresh = {
            nanoLine.text = "Checking…"
            io.execute {
                val s = HybridAi.nanoStatus(ctx)
                main.post {
                    if (closed) return@post
                    nanoLine.text = when {
                        s == "AVAILABLE" -> "Ready. Quick assist runs on this phone, even in airplane mode."
                        s == "DOWNLOADABLE" -> "This phone supports Gemini Nano. Download it once (through Android AICore) to work offline."
                        s == "DOWNLOADING" -> "Downloading through Android AICore…"
                        s.startsWith("UNAVAILABLE") -> "Gemini Nano is not available on this phone, so requests go to the cloud model.\n$s"
                        else -> s
                    }
                    downloadBtn.visibility = if (s == "DOWNLOADABLE") TextView.VISIBLE else TextView.GONE
                }
            }
        }

        // ---- Routing ----
        fs.heading("Where requests run")
        val modeCard = fs.card()
        val modeLine = Ui.text(ctx, "", 13f, Ui.TEXT)
        fun paintMode() { modeLine.text = mode(ctx).label + "\nRequests over ${HybridAi.settings(ctx).onDeviceMaxChars} characters go to the cloud in the on-phone-first mode." }
        paintMode()
        modeCard.addView(modeLine)
        // Private mode: the plainest way to say "my code never leaves this phone"
        val privateBox = CheckBox(ctx).apply {
            text = "Private: keep my code on this phone (Gemini Nano only, never the cloud)"
            isChecked = mode(ctx) == HybridAi.Mode.ONLY_ON_DEVICE; setTextColor(Ui.TEXT)
            setOnCheckedChangeListener { _, on ->
                setMode(ctx, if (on) HybridAi.Mode.ONLY_ON_DEVICE else HybridAi.Mode.PREFER_ON_DEVICE); paintMode()
            }
        }
        fs.buttonRow(modeCard, Ui.button(ctx, "Change") {
            val all = HybridAi.Mode.entries
            AlertDialog.Builder(ctx).setTitle("Where requests run")
                .setSingleChoiceItems(all.map { it.label }.toTypedArray(), all.indexOf(mode(ctx))) { d, i -> setMode(ctx, all[i]); paintMode(); privateBox.isChecked = all[i] == HybridAi.Mode.ONLY_ON_DEVICE; d.dismiss() }
                .show()
        })
        modeCard.addView(privateBox)

        // ---- Model settings ----
        fs.heading("Model settings")
        val setCard = fs.card()
        val cur = HybridAi.settings(ctx)
        fun field(hint: String, value: String, decimal: Boolean = false): EditText = EditText(ctx).apply {
            this.hint = hint; setText(value); textSize = 14f; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
            inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
        }
        val tempBox = field("Temperature (0 to 2)", cur.temperature.toString(), true)
        val topKBox = field("Top-K (1 to 100)", cur.topK.toString())
        val outBox = field("Max output tokens (64 to 8192)", cur.maxOutputTokens.toString())
        val charsBox = field("On-phone limit, characters (1000 to 30000)", cur.onDeviceMaxChars.toString())
        val warn = Ui.text(ctx, HybridSettings.warning(cur) ?: "", 12f, Ui.BAD)
        val fullBox = CheckBox(ctx).apply {
            text = "Use the Full on-phone model (Gemma 4 E4B: slower, stronger) instead of Fast (E2B). It may need its own download."
            isChecked = cur.fullModel; setTextColor(Ui.TEXT)
        }
        setCard.addView(Ui.text(ctx, "Temperature: lower is more predictable. Top-K: how many likely words the model picks from. " +
            "Max output tokens: the longest reply. On-phone limit: longer requests go to the cloud in the on-phone-first mode. " +
            "Changes apply to the next request.", 12f, Ui.MUTED))
        for (box in listOf(tempBox, topKBox, outBox, charsBox)) setCard.addView(box, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        setCard.addView(fullBox)
        // 1.5.184: the cloud model tier (CloudTier). Auto starts on Balanced and moves a hard request up to Best, and says so in the chat.
        setCard.addView(Ui.text(ctx, "Cloud model (used when Gemini Nano passes a request on)", 14f, Ui.TEXT))
        val tierGroup = android.widget.RadioGroup(ctx)
        val choiceNow = HybridAi.cloudChoice(ctx)
        for (c in dev.pocketide.app.core.CloudTier.Choice.values()) tierGroup.addView(android.widget.RadioButton(ctx).apply {
            id = View.generateViewId(); tag = c
            text = c.label + ": " + c.about; setTextColor(Ui.TEXT); isChecked = c == choiceNow
        })
        tierGroup.setOnCheckedChangeListener { g, id ->
            val c = g.findViewById<View>(id)?.tag as? dev.pocketide.app.core.CloudTier.Choice ?: return@setOnCheckedChangeListener
            HybridAi.saveCloudChoice(ctx, c); refresh()
            toast("Cloud model: ${c.label}. The next request uses it.")
        }
        setCard.addView(tierGroup)
        HybridCloud.pinnedModel()?.let { setCard.addView(Ui.text(ctx, "Remote Config pins the cloud model to $it, so this choice does not apply until that key is cleared in the console.", 12f, Ui.BAD)) }
        setCard.addView(warn)
        fun fill(s: HybridSettings) {
            tempBox.setText(s.temperature.toString()); topKBox.setText(s.topK.toString())
            outBox.setText(s.maxOutputTokens.toString()); charsBox.setText(s.onDeviceMaxChars.toString())
            warn.text = HybridSettings.warning(s) ?: ""
            fullBox.isChecked = s.fullModel
        }
        fs.buttonRow(setCard, Ui.button(ctx, "Save settings", true) {
            val s = HybridSettings.parse(HybridAi.settings(ctx), tempBox.text.toString(), topKBox.text.toString(),
                outBox.text.toString(), charsBox.text.toString()).copy(fullModel = fullBox.isChecked)
            HybridAi.saveSettings(ctx, s)
            fill(s); paintMode(); refresh()
            toast("Saved. The next request uses these settings.")
        }, Ui.button(ctx, "Reset to defaults") {
            HybridAi.saveSettings(ctx, HybridSettings.DEFAULT)
            fill(HybridSettings.DEFAULT); paintMode(); refresh()
            toast("Back to the defaults.")
        })

        // ---- Try it ----
        fs.heading("Try it")
        val tryCard = fs.card()
        val input = EditText(ctx).apply {
            hint = "Ask something short, e.g. \"What does Kotlin's ?: do?\""
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2; gravity = Gravity.TOP; textSize = 14f; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
        }
        tryCard.addView(input, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        val answer = Ui.text(ctx, "", 14f, Ui.TEXT)
        val where = Ui.text(ctx, "", 12f, Ui.MUTED)
        fs.buttonRow(tryCard, Ui.button(ctx, "Ask", true) {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return@button
            answer.text = "Thinking…"; where.text = ""
            io.execute {
                val r = try { Result.success(HybridAi.ask(ctx, q, mode(ctx))) } catch (e: Exception) { Result.failure(e) }
                main.post {
                    if (closed) return@post
                    r.onSuccess { answer.text = it.text.ifBlank { "(empty reply)" }; where.text = label(it)
                        HybridCloud.log(if (it.onDevice) "phone" else "cloud", it.seconds, "", "try") }
                        .onFailure { answer.text = HybridAi.explain(it); where.text = "" }
                }
            }
        })
        tryCard.addView(answer); tryCard.addView(where)

        // ---- Benchmark (HybridBench): eight tasks with the real agent and Gemini Nano, no one watching ----
        fs.heading("Benchmark")
        val benchCard = fs.card()
        benchCard.addView(Ui.text(ctx, "Runs ${dev.pocketide.app.core.HybridBenchTasks.ALL.size} coding tasks with the real agent and Gemini Nano, checks each one, " +
            "and saves a results zip to Downloads/PocketIDE. About 30 to 60 minutes: keep the phone plugged in with PocketIDE open. " +
            "Needs Linux with Node (Terminal tab). Your settings and project are put back afterwards.", 13f, Ui.MUTED))
        // 1.5.184: run any suite with Hybrid AI helping the person's strong model (Cascade) instead of on its own
        val helpBox = android.widget.CheckBox(ctx).apply {
            val st = HybridBench.helperStrong(dev.pocketide.app.AppSettings(ctx))
            text = "Hybrid helps my strong model: Gemini Nano does the cheap steps and passes the rest to " +
                (st?.let { (it.model.ifBlank { dev.pocketide.app.core.Providers.byId(it.provider).model }).ifBlank { dev.pocketide.app.core.Providers.byId(it.provider).name } } ?: "the strong model in Settings → Workflow") +
                ", which also reviews the work (Cascade). Not Cloudflare."
            setTextColor(Ui.TEXT)
        }
        benchCard.addView(helpBox)
        // 1.5.188: while a run goes on, a benchmark button offers to stop it (the finished tasks are zipped); an interrupted run of
        // the same suite is offered for resuming before a new one starts
        fun offerStop() {
            AlertDialog.Builder(ctx).setTitle("Stop the benchmark?")
                .setMessage("The tasks that finished are saved in a zip in Downloads/PocketIDE, and your settings are put back. " +
                    "You can resume the run later from the same button; the task that is running now goes to the back of the queue.")
                .setPositiveButton("Stop and save") { _, _ -> fs.dialog.dismiss(); HybridBench.stop() }
                .setNegativeButton("Keep running", null).show()
        }
        fun orResume(suite: HybridBench.Suite, fresh: () -> Unit) {
            val m = activity as? MainActivity
            val p = HybridBench.paused(ctx, suite)
            if (m == null || p == null) { fresh(); return }
            AlertDialog.Builder(ctx).setTitle("Resume the interrupted run?")
                .setMessage("The run from ${p.stamp} stopped after ${p.done.size} of ${p.order.size} tasks" +
                    (if (suite.reliable) " (${p.runs} ${if (p.runs == 1) "run" else "runs"} per task, Python ${if (p.python) "included" else "left out"})" else "") +
                    ". Resume keeps its finished tasks and runs the rest, with the task it stopped during moved to the back. The final zip has every task.")
                .setPositiveButton("Resume") { _, _ -> fs.dialog.dismiss(); HybridBench.resume(m, p) }
                .setNeutralButton("Start a new run") { _, _ -> HybridBench.abandon(ctx, p); fresh() }
                .setNegativeButton("Cancel", null).show()
        }
        fun startBenchNew(suite: HybridBench.Suite) {
            if (helpBox.isChecked) HybridBench.helperProblem(dev.pocketide.app.AppSettings(ctx))?.let { toast(it); return }
            val hard = suite != HybridBench.Suite.STANDARD
            val m = activity as? MainActivity
            val n = when (suite) { HybridBench.Suite.HARD -> dev.pocketide.app.core.HybridBenchTasks.HARD.size; HybridBench.Suite.GAMES -> dev.pocketide.app.core.HybridBenchTasks.GAMES.size
                HybridBench.Suite.LOCAL -> dev.pocketide.app.core.HybridBenchTasks.LOCAL.size; HybridBench.Suite.SCRATCH -> dev.pocketide.app.core.HybridBenchTasks.SCRATCH.size; HybridBench.Suite.APP -> dev.pocketide.app.core.HybridBenchTasks.APP.size
                else -> dev.pocketide.app.core.HybridBenchTasks.ALL.size }
            when {
                m == null -> toast("Open this from PocketIDE's main screen.")
                HybridBench.running -> toast("The benchmark is already running.")
                else -> AlertDialog.Builder(ctx).setTitle("Run the ${suite.title.removePrefix("Hybrid AI ")}?")
                    .setMessage("PocketIDE will make $n projects named \"bench …\", send each task to the agent " +
                        "with Hybrid AI (Single model, On this phone first; Private where a task says so), and check the results. The screen stays on. " +
                        "Do not use the app or leave it while it runs: Android pauses Gemini Nano when PocketIDE is not on screen. " +
                        "The results zip goes to Downloads/PocketIDE." + if (hard) " These tasks take longer: about 30 to 90 minutes." else "")
                    .setPositiveButton("Start") { _, _ -> fs.dialog.dismiss(); HybridBench.start(m, suite, helpBox.isChecked) }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        fun startBench(suite: HybridBench.Suite) {
            if (HybridBench.running) { offerStop(); return }
            if (HybridBench.paused(ctx, suite) != null && !helpBox.isChecked) { orResume(suite) { startBenchNew(suite) }; return }
            startBenchNew(suite)
        }
        fs.buttonRow(benchCard, Ui.button(ctx, if (HybridBench.running) "Benchmark running… (tap to stop)" else "Run the hybrid benchmark", true) { startBench(HybridBench.Suite.STANDARD) })
        benchCard.addView(Ui.text(ctx, "The hard benchmark has ${dev.pocketide.app.core.HybridBenchTasks.HARD.size} tasks that need several files, " +
            "a spec, async code, a refactor, a very long file, the web, or Private mode with several rules.", 13f, Ui.MUTED))
        fs.buttonRow(benchCard, Ui.button(ctx, "Run the hard benchmark") { startBench(HybridBench.Suite.HARD) })
        benchCard.addView(Ui.text(ctx, "The game benchmark has ${dev.pocketide.app.core.HybridBenchTasks.GAMES.size} browser games checked by play tests: " +
            "keys that must work without clicking first, a game from a spec, new features, an opponent, and a restart that stacks loops.", 13f, Ui.MUTED))
        fs.buttonRow(benchCard, Ui.button(ctx, "Run the game benchmark") { startBench(HybridBench.Suite.GAMES) })
        benchCard.addView(Ui.text(ctx, "The local model benchmark has ${dev.pocketide.app.core.HybridBenchTasks.LOCAL.size} tiny tasks for Gemini Nano alone " +
            "(Private mode), grouped by skill, and saves every Nano prompt and reply, to tune how PocketIDE drives it. About 20 to 40 minutes; no internet needed.", 13f, Ui.MUTED))
        fs.buttonRow(benchCard, Ui.button(ctx, "Run the local model benchmark") { startBench(HybridBench.Suite.LOCAL) })
        benchCard.addView(Ui.text(ctx, "The from-scratch benchmark asks for ${dev.pocketide.app.core.HybridBenchTasks.SCRATCH.size / 2} apps and games with no code to start " +
            "from, each twice: in an empty project, and in the chat with no project, then Build this as a project (its plan is approved for you). About 60 to 120 minutes.", 13f, Ui.MUTED))
        fs.buttonRow(benchCard, Ui.button(ctx, "Run the from-scratch benchmark") { startBench(HybridBench.Suite.SCRATCH) })
        benchCard.addView(Ui.text(ctx, "The in-app questions benchmark asks ${dev.pocketide.app.core.HybridBenchTasks.APP.size} questions about PocketIDE itself: " +
            "how-tos, troubleshooting, features that do not exist, and settings changes, which must come as a card to approve (the benchmark declines them all). About 15 to 30 minutes.", 13f, Ui.MUTED))
        fs.buttonRow(benchCard, Ui.button(ctx, "Run the in-app questions benchmark") { startBench(HybridBench.Suite.APP) })
        // 1.5.186: the on-device reliability benchmark (ReliableBench): Gemini Nano alone, so it needs no cloud quota
        benchCard.addView(Ui.text(ctx, "The on-device reliability benchmark runs Gemini Nano alone (Private mode, no cloud quota): the local suite's 20 tasks " +
            "it is tuned on, 24 held-out tasks it never was, and real Python fixes from the rich and requests libraries, each checked by a test " +
            "and a hidden check. Quick runs each task once (about an hour); Full runs each 3 times to measure how reliable it is (an evening).", 13f, Ui.MUTED))
        fun startReliableNew(runs: Int, suite: HybridBench.Suite) {
            val m = activity as? MainActivity
            val cloud = suite == HybridBench.Suite.RELIABLE_CLOUD || suite == HybridBench.Suite.RELIABLE_BOTH
            val both = suite == HybridBench.Suite.RELIABLE_BOTH
            val pyHybrid = suite == HybridBench.Suite.RELIABLE_PY_HYBRID
            val pyOnly = suite == HybridBench.Suite.RELIABLE_PY || pyHybrid
            when {
                m == null -> toast("Open this from PocketIDE's main screen.")
                HybridBench.running -> toast("The benchmark is already running.")
                else -> {
                    val py = CheckBox(ctx).also { it.text = "Include the Python track (installs Python and pytest in Linux once)"; it.isChecked = true }
                    val helper = CheckBox(ctx).also { it.text = "Python helper for Gemini Nano on (compare a run with it on and one with it off)"; it.isChecked = pyOnly }
                    val boxes = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; if (!pyOnly) addView(py); if (suite != HybridBench.Suite.RELIABLE_CLOUD) addView(helper) }
                    val n = 57 * runs   // cloud runs (the side-by-side suite also runs each on Nano)
                    AlertDialog.Builder(ctx).setTitle("Run the ${if (pyOnly) "Python" else if (both) "side-by-side" else if (cloud) "cloud" else "on-device"} reliability benchmark (${if (runs == 1) "quick" else "full, $runs runs per task"})?")
                        .setView(benchSheet(ctx, (if (pyHybrid) "The 13 real Python bug fixes (from rich and requests) with the cloud allowed: Gemini Nano works first, " +
                            "and with the Python helper on, the Python fix path asks the cloud for a plan and then for the fixed function when Nano's own rewrites " +
                            "fail, with the test deciding each time. This uses some of the free cloud quota (a few requests a task). " else if (pyOnly) "Only the 13 real Python bug fixes (from rich and requests), on Gemini Nano alone in Private mode, so no cloud quota is used. " +
                            "Run it once with the Python helper on and once with it off to compare. " else if (both) "Every task runs twice in a row: on Gemini Nano alone (Private mode), then with every step on Gemini in the cloud " +
                            "(${HybridAi.cloudChoice(ctx).label}). The report has both scores and a side-by-side table. The cloud half uses the project's free " +
                            "quota: about ${n * 6} to ${n * 10} requests. If every cloud model runs out, the run stops and can be resumed after the quota resets " +
                            "(midnight Pacific time). A full run takes most of a day. "
                            else if (cloud) "Every step of every task runs on Gemini in the cloud (${HybridAi.cloudChoice(ctx).label}); Gemini Nano is never used, so the " +
                            "scores compare directly with Nano's runs of the same tasks. This uses the project's free cloud quota: about ${n * 6} to ${n * 10} " +
                            "requests. If every cloud model runs out, the run stops without scoring that task and can be resumed after the quota resets " +
                            "(midnight Pacific time). "
                            else "Gemini Nano runs every task alone in Private mode, so no cloud quota is used. ") +
                            "Each task's reference fix is first checked on this phone. The screen stays on; do not use the app or leave it while it runs. " +
                            "The results zip goes to Downloads/PocketIDE.", boxes))
                        .setPositiveButton("Start") { _, _ -> fs.dialog.dismiss(); HybridBench.start(m, suite, false, runs, py.isChecked, helper.isChecked) }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        }
        fun startReliable(runs: Int, suite: HybridBench.Suite = HybridBench.Suite.RELIABLE) {
            if (HybridBench.running) { offerStop(); return }
            if (HybridBench.paused(ctx, suite) != null) { orResume(suite) { startReliableNew(runs, suite) }; return }
            startReliableNew(runs, suite)
        }
        fs.buttonRow(benchCard, Ui.button(ctx, "Reliability: quick") { startReliable(1) }, Ui.button(ctx, "Reliability: full (3 runs)") { startReliable(dev.pocketide.app.core.ReliableBench.RUNS) })
        // 1.5.190: the same tasks with every step on Gemini in the cloud, to compare with Nano's scores
        benchCard.addView(Ui.text(ctx, "The cloud reliability benchmark runs the same tasks and checks with every step on Gemini in the cloud " +
            "(Cloud first; Nano never runs), so the two scores compare directly. It uses the free cloud quota: start with the quick run.", 13f, Ui.MUTED))
        fs.buttonRow(benchCard, Ui.button(ctx, "Cloud only: quick") { startReliable(1, HybridBench.Suite.RELIABLE_CLOUD) }, Ui.button(ctx, "Cloud only: full (3 runs)") { startReliable(dev.pocketide.app.core.ReliableBench.RUNS, HybridBench.Suite.RELIABLE_CLOUD) })
        benchCard.addView(Ui.text(ctx, "Side by side runs each task on Gemini Nano and then in the cloud, and the report compares the two.", 13f, Ui.MUTED))
        benchCard.addView(Ui.text(ctx, "Python only runs just the 13 real Python bug fixes on Gemini Nano, to try the Python helper quickly.", 13f, Ui.MUTED))
        fs.buttonRow(benchCard, Ui.button(ctx, "Python only: quick") { startReliable(1, HybridBench.Suite.RELIABLE_PY) }, Ui.button(ctx, "Python only: full (3 runs)") { startReliable(dev.pocketide.app.core.ReliableBench.RUNS, HybridBench.Suite.RELIABLE_PY) })
        benchCard.addView(Ui.text(ctx, "Python with the cloud runs the same 13 Python fixes with the cloud allowed, Gemini Nano first, so the Python fix path can ask the cloud when Nano's own rewrites fail.", 13f, Ui.MUTED))
        fs.buttonRow(benchCard, Ui.button(ctx, "Python, Nano + cloud: quick") { startReliable(1, HybridBench.Suite.RELIABLE_PY_HYBRID) }, Ui.button(ctx, "Python, Nano + cloud: full (3 runs)") { startReliable(dev.pocketide.app.core.ReliableBench.RUNS, HybridBench.Suite.RELIABLE_PY_HYBRID) })
        fs.buttonRow(benchCard, Ui.button(ctx, "Nano + cloud: quick") { startReliable(1, HybridBench.Suite.RELIABLE_BOTH) }, Ui.button(ctx, "Nano + cloud: full (3 runs)") { startReliable(dev.pocketide.app.core.ReliableBench.RUNS, HybridBench.Suite.RELIABLE_BOTH) })
        // 1.5.192: the total benchmark, every chosen suite in one run and one zip, with a summary of them all
        fun startTotalNew() {
            val m = activity as? MainActivity ?: run { toast("Open this from PocketIDE's main screen."); return }
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), 0) }
            val picks = HybridBench.TOTAL_SUITES.map { k -> k to CheckBox(ctx).also { it.text = k.title.removePrefix("Hybrid AI benchmark").trim().trim('(', ')').ifEmpty { "Standard" }.replaceFirstChar { c -> c.uppercase() }; it.isChecked = true; box.addView(it) } }
            box.addView(Ui.text(ctx, "Reliability suite:", 14f, Ui.MUTED))
            val group = android.widget.RadioGroup(ctx)
            val engines = listOf("Gemini Nano and the cloud, side by side" to HybridBench.Suite.RELIABLE_BOTH, "Gemini Nano only (no quota)" to HybridBench.Suite.RELIABLE,
                "Cloud only" to HybridBench.Suite.RELIABLE_CLOUD, "Leave it out" to null)
            val radios = engines.map { (label, _) -> android.widget.RadioButton(ctx).also { it.text = label; it.id = android.view.View.generateViewId(); group.addView(it) } }
            radios[0].isChecked = true
            box.addView(group)
            val full = CheckBox(ctx).also { it.text = "Full reliability (3 runs per task); otherwise quick (1 run)"; box.addView(it) }
            val py = CheckBox(ctx).also { it.text = "Include the Python track"; it.isChecked = true; box.addView(it) }
            val pyHelp = CheckBox(ctx).also { it.text = "Python helper for Gemini Nano on (and its Python fix path)"; it.isChecked = true; box.addView(it) }
            AlertDialog.Builder(ctx).setTitle("Run the total benchmark?")
                .setView(benchSheet(ctx, "Every suite you pick runs one after another in one run, each in its own folder with its own report, and the zip has a summary of " +
                    "them all. The hybrid suites and the reliability cloud runs use the free cloud quota: if it runs out, the hybrid suites carry on with " +
                    "Gemini Nano alone and a cloud reliability run stops (resume it after midnight Pacific time). A full total run takes most of a day; " +
                    "keep PocketIDE on screen and plugged in. Stop and save works at any time, and Resume carries on where it stopped.", box))
                .setPositiveButton("Start") { _, _ ->
                    val rel = engines[radios.indexOfFirst { it.isChecked }.coerceAtLeast(0)].second
                    val suites = picks.filter { it.second.isChecked }.map { it.first } + listOfNotNull(rel)
                    if (suites.isEmpty()) { toast("Pick at least one suite."); return@setPositiveButton }
                    fs.dialog.dismiss()
                    HybridBench.startTotal(m, suites, if (full.isChecked) dev.pocketide.app.core.ReliableBench.RUNS else 1, py.isChecked, pyHelp.isChecked)
                }
                .setNegativeButton("Cancel", null).show()
        }
        fun startTotal() {
            if (HybridBench.running) { offerStop(); return }
            val m = activity as? MainActivity
            val p = HybridBench.pausedTotal(ctx)
            if (m == null || p == null) { startTotalNew(); return }
            AlertDialog.Builder(ctx).setTitle("Resume the interrupted total benchmark?")
                .setMessage("The total run from ${p.stamp} stopped during suite ${p.index + 1} of ${p.suites.size}. Resume keeps everything finished and carries on " +
                    "with that suite where it stopped. The final zip has every suite.")
                .setPositiveButton("Resume") { _, _ -> fs.dialog.dismiss(); HybridBench.resumeTotal(m, p) }
                .setNeutralButton("Start a new run") { _, _ -> HybridBench.abandonTotal(ctx, p); startTotalNew() }
                .setNegativeButton("Cancel", null).show()
        }
        benchCard.addView(Ui.text(ctx, "The total benchmark runs every suite you pick, one after another, into one zip with a summary.", 13f, Ui.MUTED))
        fs.buttonRow(benchCard, Ui.button(ctx, "Total benchmark (every suite)") { startTotal() })

        // ---- Usage (Firebase Authentication + Firestore) and Remote Config ----
        fs.heading("Your hybrid usage")
        val useCard = fs.card()
        val useLine = Ui.text(ctx, "Loading…", 13f, Ui.TEXT)
        val cfgLine = Ui.text(ctx, "", 12f, Ui.MUTED)
        useCard.addView(useLine); useCard.addView(cfgLine)
        fun loadUsage() {
            useLine.text = "Loading…"
            Thread {
                val s = HybridCloud.summary()
                main.post {
                    if (closed) return@post
                    useLine.text = s
                    cfgLine.text = HybridCloud.authNote + "\n" + HybridCloud.configNote +
                        "\nStored in Firestore: where each request ran, its time and why it was passed on. Never prompts or code."
                }
            }.start()
        }
        fs.buttonRow(useCard, Ui.button(ctx, "Refresh") { loadUsage() })
        loadUsage()

        // ---- App Check (debug builds) ----
        if (AppCheckSetup.DEBUG) {
            fs.heading("App Check (debug build)")
            val ac = fs.card()
            ac.addView(Ui.text(ctx, "Firebase requires App Check. This debug build uses a debug token: after your first request, copy it and add it in the " +
                "Firebase console (App Check → Apps → Manage debug tokens).", 13f, Ui.MUTED))
            fs.buttonRow(ac, Ui.button(ctx, "Copy App Check debug token") {
                val t = HybridAi.appCheckDebugSecret(ctx)
                if (t == null) toast("No token yet. Send one request first (Try it), then copy it.") else {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("App Check debug token", t))
                    toast("Copied. Add it in the Firebase console → App Check → Manage debug tokens.")
                }
            })
        }

        fs.dialog.setOnDismissListener { closed = true; io.shutdown() }
        fs.show()
        refresh()
        intro(activity)
    }

    /** "How hybrid AI works", shown the first time the screen opens (and from the ⓘ line under the heading afterwards). */
    fun intro(activity: Activity, always: Boolean = false) {
        val p = activity.getSharedPreferences(PREFS, 0)
        if (!always && p.getBoolean("intro_seen", false)) return
        AlertDialog.Builder(activity).setTitle("How hybrid AI works")
            .setMessage("• Gemini Nano answers on this phone first: free, private, and it works offline.\n\n" +
                "• When a request needs the web, is too long, or Nano cannot do it, Nano says so and passes it to the cloud model " +
                "(Firebase AI Logic), or to your strong model in the Cascade and Critique workflows.\n\n" +
                "• Every reply says where it ran and how long it took. \"Your hybrid usage\" adds it up.\n\n" +
                "• Private mode keeps everything on the phone.\n\n" +
                "• In the agent, Nano's steps are real tool calls, and PocketIDE checks every edit: a broken edit is put back.")
            .setPositiveButton("Got it") { _, _ -> p.edit().putBoolean("intro_seen", true).apply() }
            .setOnCancelListener { p.edit().putBoolean("intro_seen", true).apply() }
            .show()
    }

    /** "On this phone (Gemini Nano) · 1.2 s" plus the routing note, if any. */
    fun label(r: HybridAi.Reply): String =
        (if (r.onDevice && r.model == HybridAgent.APP_STEP) "⚡ PocketIDE did this step itself (no model)" else if (r.onDevice) "⚡ Ran on this phone (Gemini Nano)" else "☁ Ran in the cloud (${r.model.ifBlank { HybridCloud.cloudModel() }})") +
            " · ${"%.1f".format(r.seconds)} s" + (if (r.note.isNotEmpty()) "\n" + r.note else "")

    /** Editor quick assist: one action on the selected lines, shown in a dialog with where it ran. */
    fun quickAssist(activity: Activity, lang: String, code: String, onInsert: ((String) -> Unit)?, onEscalate: ((String) -> Unit)?) {
        val ctx: Context = activity
        if (!HybridAi.configured(ctx)) { Toast.makeText(ctx, "Hybrid AI is not set up in this build (no Firebase config).", Toast.LENGTH_LONG).show(); return }
        val actions = HybridAi.Action.entries
        AlertDialog.Builder(ctx).setTitle("Quick assist (Gemini Nano first)").setItems(actions.map { it.label }.toTypedArray()) { _, i ->
            val a = actions[i]
            val prompt = a.prompt(lang, code)
            val dlg = AlertDialog.Builder(ctx).setTitle(a.label).setMessage("Working…").setPositiveButton("Close", null).create()
            dlg.show()
            val main = Handler(Looper.getMainLooper())
            Thread {
                val r = try { Result.success(HybridAi.ask(ctx, prompt, mode(ctx))) } catch (e: Exception) { Result.failure(e) }
                main.post {
                    if (!dlg.isShowing) return@post
                    dlg.dismiss()
                    val b = AlertDialog.Builder(ctx).setTitle(a.label).setPositiveButton("Close", null)
                    r.onSuccess { rep ->
                        HybridCloud.log(if (rep.onDevice) "phone" else "cloud", rep.seconds, "", "quick")
                        b.setMessage(rep.text.ifBlank { "(empty reply)" } + "\n\n" + label(rep))
                        if (onEscalate != null) b.setNegativeButton("Hand to the agent") { _, _ -> onEscalate(prompt) }
                        b.setNeutralButton(if (a == HybridAi.Action.DOC && onInsert != null) "Insert above" else "Copy") { _, _ ->
                            if (a == HybridAi.Action.DOC && onInsert != null) onInsert(rep.text.removePrefix("```").removeSuffix("```").trim() + "\n")
                            else (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("reply", rep.text))
                        }
                    }.onFailure { b.setMessage(HybridAi.explain(it)) }
                    b.show()
                }
            }.start()
        }.show()
    }
}

/**
 * 1.5.203: a benchmark dialog's text and options in one scrolling view. With a long message set apart (setMessage) and the options
 * below it, a phone screen had no room left for the buttons: the total benchmark showed no Start button.
 */
internal fun benchSheet(ctx: android.content.Context, message: String, options: android.view.View): android.view.View {
    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 12), Ui.dp(ctx, 20), 0) }
    col.addView(Ui.text(ctx, message, 14f, Ui.TEXT))
    (options.parent as? android.view.ViewGroup)?.removeView(options)
    col.addView(options)
    return android.widget.ScrollView(ctx).also { it.addView(col) }
}
