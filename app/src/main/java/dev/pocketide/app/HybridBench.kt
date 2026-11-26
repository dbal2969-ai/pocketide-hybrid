package dev.pocketide.app

import android.app.AlertDialog
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import dev.pocketide.app.core.BenchResume
import dev.pocketide.app.core.HybridBenchTasks
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The hybrid AI benchmark, run on the phone with no one watching (#BuildHybridAI). It drives PocketIDE's real chat screen
 * as a person would: each task gets a fresh project with its starting files, its prompt is sent in Agent mode with Hybrid AI,
 * and when the assistant is idle the task's check runs (node test.js, and the files and chat log). The results, every
 * project's files and chat log go into one zip in Downloads/PocketIDE. The person's settings and project are put back.
 *
 * 1.5.188: progress is saved after every task (BenchResume). A run that is stopped, or cut off because PocketIDE was closed or
 * killed, still zips the tasks that finished, and can be resumed with the interrupted task moved to the back of the queue.
 */
object HybridBench {
    @Volatile var running = false
        private set

    /** How long one task may run before it is stopped. */
    private const val TASK_LIMIT_MS = 12 * 60_000L

    /** The suites: the standard eight, the hard ten, and the games (HybridBenchTasks.ALL, HARD, GAMES). */
    enum class Suite(val id: String, val title: String) {
        STANDARD("hybrid", "Hybrid AI benchmark"), HARD("hybrid-hard", "Hybrid AI benchmark (hard)"), GAMES("hybrid-games", "Hybrid AI benchmark (games)"),
        LOCAL("hybrid-local", "Hybrid AI benchmark (local model)"),
        SCRATCH("hybrid-scratch", "Hybrid AI benchmark (from scratch)"),
        APP("hybrid-app", "Hybrid AI benchmark (in-app questions)"),
        RELIABLE("hybrid-reliable", "On-device reliability benchmark (Gemini Nano)"),
    }

    /** The strong model "Hybrid helps my strong model" would use (the one picked in Workflow settings), or null. */
    fun helperStrong(s: AppSettings): dev.pocketide.app.core.Workflow.Slot? = s.pickedStrongSlot() ?: s.strongerOther()

    /** Why the helper setup cannot run now, or null. */
    fun helperProblem(s: AppSettings): String? = helperStrong(s).let { st ->
        HybridBenchTasks.strongProblem(st, st != null && (dev.pocketide.app.core.Providers.byId(st.provider).keyless || s.key(st.provider).isNotBlank()))
    }

    /** [helping]: run in Cascade with Hybrid AI as the draft model helping the strong model (see HybridBenchTasks.strongProblem). */
    /** [runs] and [python]: the reliability suite's runs per task and whether it includes the Python track (ReliableBench). */
    fun start(act: MainActivity, suite: Suite = Suite.STANDARD, helping: Boolean = false, runs: Int = 1, python: Boolean = true) {
        if (running) return
        running = true
        Run(act, suite, helping, runs, python).also { current = it }.begin()
    }

    /** The run going on now, for [stop]. */
    @Volatile private var current: Run? = null

    /** Carries on with an interrupted run (1.5.188): its finished tasks are kept, and the interrupted task goes to the back. */
    fun resume(act: MainActivity, s: BenchResume.State) {
        if (running) return
        val kind = Suite.values().firstOrNull { it.id == s.suite } ?: return
        running = true
        Run(act, kind, s.helping, s.runs, s.python, s).also { current = it }.begin()
    }

    /**
     * Whether PocketIDE is on screen (MainActivity onStart/onStop). Gemini Nano only runs for an app on screen: in reliability run
     * 1125-2053 PocketIDE left the screen at 00:33 and the last 10 tasks each waited 10 minutes for AICore's "Background usage is
     * blocked" and failed with no step taken. 1.5.188: the run pauses instead, and the task is run again when PocketIDE is back.
     */
    @Volatile var onScreen = true
        private set

    fun screen(on: Boolean) { onScreen = on; if (on) current?.backOnScreen() }

    /** Stops the run now (1.5.188): the finished tasks are zipped, and the run can be resumed later. */
    fun stop() { current?.stop() }

    fun resultsDir(ctx: android.content.Context) = File(ctx.filesDir, "bench-results")

    /** Every run that saved its progress (1.5.188 on), newest first. */
    fun states(ctx: android.content.Context): List<Pair<File, BenchResume.State>> =
        (resultsDir(ctx).listFiles() ?: emptyArray()).mapNotNull { d ->
            val f = File(d, BenchResume.FILE)
            if (!f.isFile) null else BenchResume.decode(try { f.readText() } catch (e: Exception) { "" })?.let { d to it }
        }.sortedByDescending { it.first.lastModified() }

    /** The newest interrupted run of [suite] that can be resumed, or null. */
    fun paused(ctx: android.content.Context, suite: Suite): BenchResume.State? =
        states(ctx).firstOrNull { it.second.suite == suite.id && it.second.resumable }?.second

    /** The person chose to start a new run instead: the interrupted one is no longer offered. */
    fun abandon(ctx: android.content.Context, s: BenchResume.State) = save(File(resultsDir(ctx), "${s.suite}-${s.stamp}"), s.copy(finished = true))

    private fun save(dir: File, s: BenchResume.State) = try { File(dir, BenchResume.FILE).writeText(BenchResume.encode(s)) } catch (e: Exception) { }

    /**
     * Called when PocketIDE starts (1.5.188). A run still marked as going on while no run is going on was cut off: PocketIDE was
     * closed, or Android stopped it. The person's settings and project are put back, the tasks that finished are zipped, and the
     * person can resume it.
     */
    fun recover(act: MainActivity) {
        if (running) return
        for ((dir, s0) in states(act).filter { it.second.active && !it.second.finished }) {
            val s = BenchResume.interrupt(s0)
            save(dir, s)
            restore(act, s.before)
            val kind = Suite.values().firstOrNull { it.id == s.suite } ?: continue
            val where = savePartial(act, dir, kind, s, "PocketIDE was closed or stopped by Android")
            AlertDialog.Builder(act).setTitle(kind.title + " was interrupted")
                .setMessage("PocketIDE was closed or stopped while the benchmark ran, after ${s.done.size} of ${s.order.size} tasks. " +
                    "The finished tasks are saved: $where\n\nYour settings and project were put back. Resume carries on with the rest; " +
                    "the task that was running goes to the back of the queue.")
                .setPositiveButton("Resume") { _, _ -> resume(act, s) }
                .setNegativeButton("Later", null).show()
            return   // one at a time; any other shows next start
        }
    }

    /** The tasks of a run of [kind], before any are left out. */
    fun tasksFor(ctx: android.content.Context, kind: Suite, runs: Int, python: Boolean): List<HybridBenchTasks.Task> = when (kind) {
        Suite.HARD -> HybridBenchTasks.HARD; Suite.GAMES -> HybridBenchTasks.GAMES; Suite.LOCAL -> HybridBenchTasks.LOCAL
        Suite.SCRATCH -> HybridBenchTasks.SCRATCH; Suite.APP -> HybridBenchTasks.APP; Suite.STANDARD -> HybridBenchTasks.ALL
        Suite.RELIABLE -> dev.pocketide.app.core.ReliableBench.repeated(reliableTasks(ctx, python), runs) }

    /** The settings a run changes, as they are now, to put back afterwards. */
    fun beforeNow(act: MainActivity): Map<String, String> = AppSettings(act).let { s ->
        mapOf("provider" to s.provider, "workflow" to s.workflow, "agent" to s.agent.toString(), "mode" to s.mode,
            "draft" to s.savedSlot(true), "strong" to s.savedSlot(false), "hybridMode" to HybridScreen.mode(act).name, "project" to Projects.key(act))
    }

    fun restore(act: MainActivity, b: Map<String, String>) {
        if (b.isEmpty()) return
        val s = AppSettings(act)
        b["provider"]?.let { s.provider = it }; b["workflow"]?.let { s.workflow = it }; b["agent"]?.let { s.agent = it == "true" }
        b["mode"]?.let { s.mode = it }; b["draft"]?.let { s.restoreSlot(true, it) }; b["strong"]?.let { s.restoreSlot(false, it) }
        b["hybridMode"]?.let { m -> HybridAi.Mode.values().firstOrNull { it.name == m }?.let { HybridScreen.setMode(act, it) } }
        b["project"]?.let { try { act.benchSwitch(it) } catch (e: Exception) { } }
    }

    /** The results saved in [dir] for the tasks [s] lists as done, in the order they finished. */
    fun savedResults(ctx: android.content.Context, dir: File, kind: Suite, s: BenchResume.State): ArrayList<HybridBenchTasks.Result> {
        val byId = try { tasksFor(ctx, kind, s.runs, s.python).associateBy { it.id } } catch (e: Exception) { emptyMap() }
        val out = ArrayList<HybridBenchTasks.Result>()
        for (id in s.done) {
            val t = byId[id] ?: continue
            val d = File(dir, id)
            val r = File(d, BenchResume.RESULT).takeIf { it.isFile }?.let { BenchResume.parseResult(it.readText()) }
                ?: File(d, "check.txt").takeIf { it.isFile }?.let { BenchResume.fromCheck(it.readText()) } ?: continue
            val transcript = File(d, "transcript.txt").takeIf { it.isFile }?.readText().orEmpty()
            out.add(HybridBenchTasks.Result(t, r.passed, r.why, HybridBenchTasks.counts(transcript), r.seconds, r.ended))
        }
        return out
    }

    /** The report for [results]: the suite's report, and for the reliability suite its pass@1 and pass^k summary. */
    fun reportText(ctx: android.content.Context, kind: Suite, helping: Boolean, stamp: String, results: List<HybridBenchTasks.Result>, problem: String?,
                   total: Int, runs: Int, pythonNote: String, deselect: Map<String, List<String>>, unsound: Map<String, String>, notes: String = ""): String {
        val strong = if (helping) helperStrong(AppSettings(ctx)) else null
        val hs = HybridAi.settings(ctx)
        val about = "${dev.pocketide.app.core.BuildTag.userAgent} · run $stamp · on-phone model ${if (hs.fullModel) "Full (E4B)" else "Fast (E2B)"} · " +
            "on-phone limit ${hs.onDeviceMaxChars} characters · temperature ${hs.temperature} · top-K ${hs.topK} · max output ${hs.maxOutputTokens} tokens · " +
            (if (strong != null) "Cascade: Hybrid AI helps the strong model ${strong.provider}/${strong.model.ifBlank { dev.pocketide.app.core.Providers.byId(strong.provider).model }}, which takes Gemini Nano's handoffs and reviews the work · " else "") + "cloud ${HybridCloud.pinnedModel()?.let { "model $it (pinned by Remote Config)" } ?: "models: " + HybridAi.cloudChoice(ctx).let { it.label + " (" + it.about + ")" }}"
        return HybridBenchTasks.report(results, about, problem, if (kind == Suite.RELIABLE) results.size else total, kind.title + if (helping) " · hybrid helping a strong model" else "") +
            (if (kind != Suite.RELIABLE) "" else dev.pocketide.app.core.ReliableBench.summary(results) +
                (if (runs > 1) "\nRuns per task: $runs.\n" else "") + (if (pythonNote.isNotEmpty()) "\n$pythonNote\n" else "") +
                (if (deselect.isEmpty()) "" else "\nHidden tests left out because they fail on this phone even with the reference fix:\n" + deselect.entries.joinToString("\n") { "- ${it.key}: ${it.value.joinToString()}" } + "\n") +
                (if (unsound.isEmpty()) "" else "\nLeft out (the reference fix does not pass on this phone):\n" + unsound.entries.joinToString("\n") { "- ${it.key}: ${it.value}" } + "\n")) +
            (if (notes.isEmpty()) "" else "\n$notes\n")
    }

    /** Zips the tasks an interrupted run finished (report-partial.md says so), and says where the zip went. */
    fun savePartial(ctx: android.content.Context, dir: File, kind: Suite, s: BenchResume.State, how: String): String {
        val results = savedResults(ctx, dir, kind, s)
        val note = BenchResume.interruptedNote(s, s.order.size, how)
        return try {
            File(dir, "report.md").writeText(reportText(ctx, kind, s.helping, s.stamp, results, note, s.order.size, s.runs, s.pythonNote, s.deselect, s.unsound))
            saveToDownloads(ctx, BenchResume.partialName(s.suite, s.helping, s.stamp, results.size, s.order.size)) { os -> zipDir(dir, os) }
        } catch (e: Exception) { "not saved (${e.message}); the results are in the app's files: ${dir.absolutePath}" }
    }

    fun zipDir(dir: File, os: OutputStream) = ZipOutputStream(os).use { z ->
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            z.putNextEntry(ZipEntry(f.relativeTo(dir).invariantSeparatorsPath))
            f.inputStream().use { it.copyTo(z) }
            z.closeEntry()
        }
    }

    /** Saves a file to Downloads/PocketIDE (MediaStore, no permission needed); before Android 10, the app's own files. */
    fun saveToDownloads(ctx: android.content.Context, fileName: String, write: (OutputStream) -> Unit): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PocketIDE")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("Downloads refused the file")
            ctx.contentResolver.openOutputStream(uri)!!.use(write)
            return "Downloads/PocketIDE/$fileName"
        }
        val f = File(ctx.getExternalFilesDir(null), fileName)
        f.outputStream().use(write)
        return f.absolutePath
    }

    /** The reliability suite's tasks: the local suite (dev), the held-out Node tasks and the Python track, from the assets. */
    fun reliableTasks(act: android.content.Context, python: Boolean): List<HybridBenchTasks.Task> {
        val rb = dev.pocketide.app.core.ReliableBench
        val node = rb.nodeTasks(act.assets.open("bench/reliable/node.json").bufferedReader().readText())
        val py = if (!python) emptyList() else rb.pythonTasks(act.assets.open("bench/reliable/python.json").bufferedReader().readText()) { id ->
            val m = LinkedHashMap<String, String>()
            java.util.zip.ZipInputStream(act.assets.open("bench/reliable/python/$id.zip")).use { z ->
                while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) m[e.name] = z.readBytes().toString(Charsets.UTF_8) }
            }
            m
        }
        val devHidden = try { (dev.pocketide.app.core.MiniJson.obj(dev.pocketide.app.core.MiniJson.parse(act.assets.open("bench/reliable/dev_hidden.json").bufferedReader().readText())) ?: emptyMap())
            .mapValues { it.value.toString() } } catch (e: Exception) { emptyMap() }
        return rb.devTasks(devHidden) + node + py
    }

    private class Run(val act: MainActivity, val kind: Suite, val helping: Boolean, val runs: Int = 1, val python: Boolean = true,
                      val resume: BenchResume.State? = null) {
        val main = Handler(Looper.getMainLooper())
        val settings = AppSettings(act)
        /** The person's settings and project to put back; on resume, the ones saved before the run first started. */
        val before: Map<String, String> = resume?.before ?: beforeNow(act)
        val strong = if (helping) helperStrong(settings) else null
        val stamp: String = resume?.stamp ?: SimpleDateFormat("MMdd-HHmm", Locale.US).format(Date())
        val suite = kind.id
        val out = File(resultsDir(act), "$suite-$stamp").apply { mkdirs() }
        val all = tasksFor(act, kind, runs, python)
        val byId = all.associateBy { it.id }
        val results: ArrayList<HybridBenchTasks.Result> = if (resume == null) ArrayList() else savedResults(act, out, kind, resume)
        val tasks: MutableList<HybridBenchTasks.Task> = (if (resume == null) all else BenchResume.remaining(resume).mapNotNull { byId[it] }).toMutableList()
        /** Reliability suite: tasks whose reference fix does not pass on this phone (its packages differ), left out of the scores. */
        val unsound = LinkedHashMap<String, String>(resume?.unsound ?: emptyMap())
        /** Python hidden tests that fail on this phone even with the reference fix (its packages differ): left out of that task's hidden check. */
        val deselect = HashMap<String, List<String>>(resume?.deselect ?: emptyMap())
        val checked = HashSet<String>(unsound.keys + deselect.keys)
        var pythonNote = resume?.pythonNote ?: ""
        /** The run's progress, saved after every task (1.5.188). */
        var state = resume?.copy(active = true, current = null) ?: BenchResume.State(suite, stamp, helping, runs, python, all.map { it.id }, before = before)
        /** Set by [stop]: late callbacks from the task that was running record nothing. */
        @Volatile var stopped = false
        /** PocketIDE left the screen during the task: it is run again, not scored (1.5.188). [offSince]: when it left. */
        var redo = false
        var offSince = 0L
        val reruns = LinkedHashMap<String, Int>()
        /** What to do once PocketIDE is back on screen. */
        var whenBack: (() -> Unit)? = null

        fun backOnScreen() { val f = whenBack ?: return; whenBack = null; main.postDelayed({ if (!stopped) f() }, 3000) }

        /** Runs [then] now if PocketIDE is on screen, else once it is back. */
        fun onceOnScreen(then: () -> Unit) {
            if (onScreen) { main.postDelayed({ if (!stopped) then() }, 3000); return }
            whenBack = then
            act.window.decorView.post { toast("Benchmark paused: PocketIDE left the screen, and Gemini Nano only runs while it is shown. It carries on when you come back.") }
        }

        /** A watch step: true when PocketIDE has been off screen for 15 s, so the task was stopped to be run again. */
        fun leftScreen(chat: ChatPane, done: (String) -> Unit): Boolean {
            if (onScreen) { offSince = 0; return false }
            val now = System.currentTimeMillis()
            if (offSince == 0L) offSince = now
            if (now - offSince < 15_000) return false
            redo = true
            chat.stopNow()
            main.postDelayed({ done("PocketIDE left the screen") }, 3000)
            return true
        }

        fun save(current: String?) {
            state = state.copy(current = current, done = results.map { it.task.id }, unsound = LinkedHashMap(unsound), deselect = HashMap(deselect), pythonNote = pythonNote)
            save(out, state)
        }
        /** Gemini Nano's exchanges in the current task (HybridAgent.recorder), saved as nano.jsonl. */
        val nanoLog = StringBuilder()
        var i = 0

        fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_LONG).show()

        fun begin() {
            act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // 1.5.188: a task the run was interrupted during LIMIT times is recorded as failed, so a run can never get stuck on it
            if (resume != null) for (id in BenchResume.givenUp(resume)) byId[id]?.let { t ->
                if (results.none { it.task.id == id }) {
                    val why = BenchResume.givenUpWhy(id)
                    results.add(HybridBenchTasks.Result(t, false, why, HybridBenchTasks.counts(""), 0, "interrupted"))
                    try { File(out, id).apply { mkdirs() }.let { d -> File(d, BenchResume.RESULT).writeText(BenchResume.resultText(BenchResume.Saved(false, why, 0, "interrupted"))); File(d, "check.txt").writeText("Result: $why\n") } } catch (e: Exception) { }
                }
            }
            save(null)
            if (resume != null) toast("Resuming: ${results.size} of ${state.order.size} tasks already done")
            settings.provider = "hybrid"
            settings.workflow = dev.pocketide.app.core.Workflow.SINGLE
            if (helping) {
                helperProblem(settings)?.let { finish(it); return }
                settings.workflow = dev.pocketide.app.core.Workflow.CASCADE
                settings.setSlot(true, dev.pocketide.app.core.Workflow.Slot("hybrid", ""))
                settings.setSlot(false, strong!!)
            }
            settings.agent = true
            settings.mode = "agent"   // Agent mode: the chat box's mode and the agent switch, as the Agent chip sets them
            if (kind == Suite.APP) { next(); return }   // questions about the app run no commands
            toast("Hybrid benchmark: checking that Node is ready…")
            act.benchChat().runForChecks("node --version") { code, text ->
                main.post {
                    if (code != 0) finish("Node is not available. Start Linux in the Terminal tab and install Node (apk add nodejs), then run the benchmark again. ($text)".take(300))
                    else if (kind == Suite.RELIABLE && tasks.any { it.track == "Python" }) pythonReady()
                    else next()
                }
            }
        }

        /**
         * The Python track needs Python, pytest and the packages rich and requests use, in PocketIDE's Linux. They are installed once
         * (apk); when that fails (no connection), the track is left out and the report says why.
         */
        fun pythonReady() {
            val rb = dev.pocketide.app.core.ReliableBench
            toast("Reliability benchmark: checking Python for the real-code track…")
            act.benchChat().runForChecks(rb.PY_CHECK) { code, _ ->
                main.post {
                    if (code == 0) { next(); return@post }
                    toast("Installing Python and pytest in Linux (once)…")
                    act.benchChat().runForChecks(rb.PY_INSTALL) { _, out ->
                        act.benchChat().runForChecks(rb.PY_CHECK) { code2, _ ->
                            main.post {
                                if (code2 != 0) {
                                    pythonNote = "The Python track was left out: Python and its packages could not be installed (" + out.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(160) + ")."
                                    tasks.removeAll { it.track == "Python" }
                                    state = state.copy(order = state.order.filter { byId[it]?.track != "Python" })
                                }
                                next()
                            }
                        }
                    }
                }
            }
        }

        /**
         * Before a task with a reference fix first runs, the fix is tried on this phone: written over the starting files, the check and
         * the hidden check must pass (a Python package version can differ from the one the task was made with). Then the starting
         * files are put back. A task whose fix fails here is left out of the scores.
         */
        fun soundHere(t: HybridBenchTasks.Task, root: File, then: () -> Unit) {
            val base = dev.pocketide.app.core.ReliableBench.baseId(t.id)
            if (t.solution.isEmpty() || t.command == null || base in checked || base in unsound) { then(); return }
            checked.add(base)
            for ((p, text) in t.solution) File(root, p).apply { parentFile?.mkdirs(); writeText(text) }
            for ((p, text) in t.hidden) File(root, p).writeText(text)
            fun reset() {
                for (p in t.solution.keys + t.hidden.keys) if (!t.files.containsKey(p)) File(root, p).delete()
                for ((p, text) in t.files) File(root, p).apply { parentFile?.mkdirs(); writeText(text) }
                root.walkTopDown().filter { it.isDirectory && it.name == "__pycache__" }.toList().forEach { it.deleteRecursively() }
            }
            act.benchSwitch(root.name)
            act.benchChat().runForChecks(t.command) { code, out ->
                val ok = code == 0 && (t.track == "Python" || out.lines().any { it.trim() == "ok" })
                val hid = t.hiddenCommand
                if (!ok || hid == null) main.post {
                    if (!ok) unsound[base] = "the reference fix fails here: " + out.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(140)
                    reset(); then()
                } else act.benchChat().runForChecks(hid) { code2, out2 ->
                    // 1.5.187 (reliability run 1125-1815: 10 rich tasks left out over 1 or 2 tests each): a hidden test that fails with the
                    // reference fix fails because of this phone's packages, not the task; like SWE-bench's pass-to-pass list, only tests that
                    // pass here count. They are deselected, and the rest must pass.
                    val failed = Regex("""(?m)^FAILED (\S+)""").findAll(out2).map { it.groupValues[1] }.toList()
                    if (code2 == 0 || t.track != "Python" || failed.isEmpty()) main.post {
                        if (code2 != 0) unsound[base] = "the reference fix fails the hidden check here: " + dev.pocketide.app.core.ReliableBench.reason(out2)
                        reset(); then()
                    } else act.benchChat().runForChecks(hid + failed.joinToString("") { " --deselect " + it }) { code3, out3 ->
                        main.post {
                            if (code3 == 0) deselect[base] = failed
                            else unsound[base] = "the reference fix fails the hidden check here: " + dev.pocketide.app.core.ReliableBench.reason(out3)
                            reset(); then()
                        }
                    }
                }
            }
        }

        // 1.5.185: the settings app_settings can change, read before each task and compared after it (no change may happen unapproved)
        fun settingsNow(): Map<String, String?> = dev.pocketide.app.core.AppSettingsTool.known.associate { it.setting!! to AppSettingsBridge.value(act, settings, it.setting) }
        var settingsBefore: Map<String, String?> = emptyMap()

        fun next() {
            if (stopped) return
            if (i >= tasks.size) { finish(null); return }
            val t = tasks[i]
            save(t.id)
            toast("Hybrid benchmark ${results.size + 1} of ${state.order.size}: ${t.title}")
            if (t.entry == HybridBenchTasks.Entry.CHAT) { fromChat(t); return }
            val name = Projects.create(act, "bench $stamp ${t.id.replace('#', '-')}", dev.pocketide.app.core.Templates.EMPTY)
            val root = File(Projects.root(act), name)
            for ((p, text) in t.files) File(root, p).apply { parentFile?.mkdirs(); writeText(text) }
            if (kind == Suite.RELIABLE && dev.pocketide.app.core.ReliableBench.baseId(t.id) !in checked && t.solution.isNotEmpty()) {
                soundHere(t, root) { if (dev.pocketide.app.core.ReliableBench.baseId(t.id) in unsound) { i++; main.postDelayed({ next() }, 500) } else startTask(t, name, root) }
                return
            }
            if (dev.pocketide.app.core.ReliableBench.baseId(t.id) in unsound) { save(null); i++; main.postDelayed({ next() }, 500); return }
            startTask(t, name, root)
        }

        fun startTask(t: HybridBenchTasks.Task, name: String, root: File) {
            HybridScreen.setMode(act, if (t.privateMode) HybridAi.Mode.ONLY_ON_DEVICE else HybridAi.Mode.PREFER_ON_DEVICE)
            settingsBefore = settingsNow()
            val t0 = System.currentTimeMillis()
            synchronized(nanoLog) { nanoLog.setLength(0) }
            HybridAgent.recorder = { line -> synchronized(nanoLog) { nanoLog.append(line).append('\n') } }
            val chat = act.benchChat()
            var ended = "did not start"
            var finished = false
            fun done(how: String = ended) {
                if (finished) return
                finished = true
                chat.onExchangeEnded = {}
                after(t, name, root, t0, how)
            }
            // a request can be followed by others it starts itself (parts, checks): the task is over when the assistant stays idle
            chat.onExchangeEnded = { ok ->
                ended = if (ok) "done" else "stopped or failed"
                main.postDelayed({ if (!finished && !chat.busy()) done() }, 8000)
            }
            act.benchAsk(name, t.prompt)
            fun watch() {
                if (finished) return
                if (leftScreen(chat) { done(it) }) return
                val spent = System.currentTimeMillis() - t0
                when {
                    spent > TASK_LIMIT_MS -> { chat.stopNow(); main.postDelayed({ done("timed out after ${TASK_LIMIT_MS / 60_000} min") }, 6000) }
                    spent > 90_000 && ended == "did not start" && !chat.busy() -> done()
                    else -> main.postDelayed({ watch() }, 5000)
                }
            }
            main.postDelayed({ watch() }, 5000)
        }

        /**
         * A task that starts in the chat with no project (1.5.184): the prompt is discussed there, then "Build this as a project" makes
         * the project and sends the build request in Plan mode; the plan is approved as the person would (ChatPane). The task is over
         * when the assistant stays idle after the build.
         */
        fun fromChat(t: HybridBenchTasks.Task) {
            HybridScreen.setMode(act, if (t.privateMode) HybridAi.Mode.ONLY_ON_DEVICE else HybridAi.Mode.PREFER_ON_DEVICE)
            settingsBefore = settingsNow()
            val t0 = System.currentTimeMillis()
            synchronized(nanoLog) { nanoLog.setLength(0) }
            HybridAgent.recorder = { line -> synchronized(nanoLog) { nanoLog.append(line).append('\n') } }
            val chat = act.benchChat()
            var ended = "did not start"
            var finished = false
            var project = ""
            fun done(how: String = ended) {
                if (finished) return
                finished = true
                chat.onExchangeEnded = {}
                if (project.isEmpty()) project = Projects.create(act, "bench $stamp ${t.id} (never built)", dev.pocketide.app.core.Templates.EMPTY)
                after(t, project, File(Projects.root(act), project), t0, if (project.endsWith("(never built)")) "never built: $how" else how, extra = listOf(Projects.CHAT))
            }
            chat.onExchangeEnded = { ok ->
                ended = if (ok) "done" else "stopped or failed"
                main.postDelayed({
                    if (finished || chat.busy()) return@postDelayed
                    if (project.isEmpty()) project = act.benchBuildFromChat("bench $stamp ${t.id}")   // the talk is over: build it
                    else done()
                }, 8000)
            }
            act.benchChatHome(t.prompt)
            fun watch() {
                if (finished) return
                if (leftScreen(chat) { done(it) }) return
                val spent = System.currentTimeMillis() - t0
                when {
                    spent > TASK_LIMIT_MS * 2 -> { chat.stopNow(); main.postDelayed({ done("timed out after ${TASK_LIMIT_MS * 2 / 60_000} min") }, 6000) }
                    spent > 90_000 && ended == "did not start" && !chat.busy() -> done()
                    else -> main.postDelayed({ watch() }, 5000)
                }
            }
            main.postDelayed({ watch() }, 5000)
        }

        fun after(t: HybridBenchTasks.Task, name: String, root: File, t0: Long, ended: String, extra: List<String> = emptyList()) {
            if (stopped) return
            val files = readTexts(root)
            // 1.5.184 (run 6, task 08): the model changed test.js. The check always runs the task's own test, put back if it changed.
            val tampered = dev.pocketide.app.core.Scope.namedChecks(t.prompt).filter { p -> t.files[p] != null && files[p] != t.files[p] }
            for (p in tampered) try { File(root, p).writeText(t.files.getValue(p)) } catch (e: Exception) { }
            val tamperNote = if (tampered.isEmpty()) "" else "The model changed ${tampered.joinToString(", ")}; the task's own version was put back before the check.\n"
            val transcript = (extra + name).joinToString("\n\n") { transcripts(it, t0) }
            // 1.5.188: the task ran while PocketIDE was off screen (the watch stopped it, or AICore refused): it is run again, up to 3 times
            val blocked = redo || transcript.contains("BACKGROUND_USE_BLOCKED") || transcript.contains("Background usage is blocked")
            redo = false; offSince = 0
            if (blocked && (reruns[t.id] ?: 0) < 3) {
                reruns[t.id] = (reruns[t.id] ?: 0) + 1
                onceOnScreen { next() }
                return
            }
            val record = record@{ code: Int?, text: String ->
                if (stopped) return@record
                val now = settingsNow()
                val changedSettings = if (settingsBefore.isEmpty()) emptyList() else now.keys.filter { now[it] != settingsBefore[it] }.map { "$it: ${settingsBefore[it]} → ${now[it]}" }
                val facts = HybridBenchTasks.Facts(files, t.files, code, text, transcript, ended, changedSettings, lastHidden.first, lastHidden.second)
                lastHidden = null to ""
                val why = try { t.judge(facts) } catch (e: Exception) { "the check failed: ${e.message}" }
                val secs = (System.currentTimeMillis() - t0) / 1000
                results.add(HybridBenchTasks.Result(t, why == null, why.orEmpty(), HybridBenchTasks.counts(transcript), secs, ended))
                try {
                    val dir = File(out, t.id).apply { mkdirs() }
                    for ((p, s) in files) File(dir, "files/$p").apply { parentFile?.mkdirs(); writeText(s) }
                    File(dir, "transcript.txt").writeText(transcript)
                    synchronized(nanoLog) { if (nanoLog.isNotEmpty()) File(dir, "nano.jsonl").writeText(nanoLog.toString()) }
                    File(dir, BenchResume.RESULT).writeText(BenchResume.resultText(BenchResume.Saved(why == null, why.orEmpty(), secs, ended)))
                    File(dir, "check.txt").writeText(tamperNote + (if (changedSettings.isEmpty()) "" else "Settings changed during the task: " + changedSettings.joinToString("; ") + "\n") + (t.command?.let { "$ $it\nexit $code\n$text\n\n" } ?: "") + "Result: " + (why ?: "passed") + "\n")
                } catch (e: Exception) { /* the report still has the result */ }
                save(null)
                i++
                main.postDelayed({ next() }, 3000)
            }
            val cmd = t.command
            if (cmd == null && t.hiddenCommand == null) record(null, "")
            else if (cmd == null) hidden(t, root) { hc, ht -> lastHidden = hc to ht; record(null, "") }
            else act.benchChat().runForChecks(cmd) { code, text -> main.post { if (t.hiddenCommand == null) record(code, text) else hidden(t, root) { hc, ht -> lastHidden = hc to ht; record(code, text) } } }
        }

        /** The last hidden check's result, read by record() (1.5.186). */
        var lastHidden: Pair<Int?, String> = null to ""

        /** Writes the task's hidden files, runs its hidden command, then removes them again. */
        fun hidden(t: HybridBenchTasks.Task, root: File, done: (Int?, String) -> Unit) {
            for ((p, text) in t.hidden) File(root, p).writeText(text)
            val left = deselect[dev.pocketide.app.core.ReliableBench.baseId(t.id)].orEmpty()
            act.benchChat().runForChecks(t.hiddenCommand!! + left.joinToString("") { " --deselect " + it }) { code, text ->
                main.post {
                    for (p in t.hidden.keys) File(root, p).delete()
                    done(code, text)
                }
            }
        }

        /** The project's text files (small ones, outside hidden folders). */
        fun readTexts(root: File): Map<String, String> = root.walkTopDown()
            .onEnter { it == root || !it.name.startsWith(".") && it.name != "node_modules" && it.name != "__pycache__" }
            .filter { it.isFile && it.length() < 300_000 }
            .associate { it.relativeTo(root).invariantSeparatorsPath to (try { it.readText() } catch (e: Exception) { "" }) }

        /** The chat logs written for [project] since [t0]. */
        fun transcripts(project: String, t0: Long): String {
            val dir = File(Projects.metaDir(act, project), "transcripts")
            return (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.lastModified() >= t0 - 1000 }
                .sortedBy { it.lastModified() }.joinToString("\n\n") { try { it.readText() } catch (e: Exception) { "" } }
        }

        fun finish(problem: String?) {
            HybridAgent.recorder = null
            restore(act, before)
            act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            state = state.copy(active = false, finished = true); save(null)
            val report = reportText(act, kind, helping, stamp, results, problem, state.order.size, runs, pythonNote, deselect, unsound,
                if (reruns.isEmpty()) "" else "Run again because PocketIDE left the screen during them (Gemini Nano only runs while it is shown; not scored): " +
                    reruns.entries.joinToString(", ") { "${it.key} (${it.value}×)" } + ".")
            File(out, "report.md").writeText(report)
            val where = try {
                saveToDownloads(act, "pocketide-$suite${if (helping) "-helping" else ""}-benchmark-$stamp.zip") { os -> zipDir(out, os) }
            } catch (e: Exception) { "not saved (${e.message}); the results are in the app's files: ${out.absolutePath}" }
            running = false; current = null
            val passed = results.count { it.passed }
            AlertDialog.Builder(act).setTitle(kind.title + " finished")
                .setMessage((problem?.let { "Stopped early: $it\n\n" } ?: "") + "Passed $passed of ${if (kind == Suite.RELIABLE) results.size else state.order.size}.\n\n" +
                    results.joinToString("\n") { (if (it.passed) "✓ " else "✗ ") + it.task.title + (if (it.passed) "" else ": " + it.why) } +
                    "\n\nResults: $where")
                .setPositiveButton("OK", null).show()
        }

        /** The person stopped the run (1.5.188): the finished tasks are zipped and the run can be resumed, the running task last. */
        fun stop() {
            if (stopped) return
            stopped = true
            try { act.benchChat().stopNow() } catch (e: Exception) { }
            HybridAgent.recorder = null
            save(state.current)   // the task running now (set when it starts, cleared when its result is saved): it goes to the back
            state = BenchResume.interrupt(state); save(out, state)
            restore(act, before)
            act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            running = false; current = null
            val where = savePartial(act, out, kind, state, "stopped by you")
            AlertDialog.Builder(act).setTitle(kind.title + " stopped")
                .setMessage("Stopped after ${state.done.size} of ${state.order.size} tasks. The finished tasks are saved: $where\n\n" +
                    "Your settings and project were put back. To finish the run later, tap the same benchmark button and choose Resume; " +
                    "the task that was running goes to the back of the queue.")
                .setPositiveButton("OK", null).show()
        }
    }
}
