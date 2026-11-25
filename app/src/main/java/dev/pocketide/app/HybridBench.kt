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
        Run(act, suite, helping, runs, python).begin()
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
        return rb.devTasks() + node + py
    }

    private class Run(val act: MainActivity, val kind: Suite, val helping: Boolean, val runs: Int = 1, val python: Boolean = true) {
        val main = Handler(Looper.getMainLooper())
        val settings = AppSettings(act)
        val before = Triple(settings.provider, settings.workflow, settings.agent)
        val chatModeBefore = settings.mode
        val draftBefore = settings.savedSlot(true)
        val strongBefore = settings.savedSlot(false)
        val strong = if (helping) helperStrong(settings) else null
        val modeBefore = HybridScreen.mode(act)
        val projectBefore = Projects.key(act)
        val stamp: String = SimpleDateFormat("MMdd-HHmm", Locale.US).format(Date())
        val suite = kind.id
        val out = File(act.filesDir, "bench-results/$suite-$stamp").apply { mkdirs() }
        val results = ArrayList<HybridBenchTasks.Result>()
        val tasks: MutableList<HybridBenchTasks.Task> = when (kind) { Suite.HARD -> HybridBenchTasks.HARD; Suite.GAMES -> HybridBenchTasks.GAMES; Suite.LOCAL -> HybridBenchTasks.LOCAL
            Suite.SCRATCH -> HybridBenchTasks.SCRATCH; Suite.APP -> HybridBenchTasks.APP; Suite.STANDARD -> HybridBenchTasks.ALL
            Suite.RELIABLE -> dev.pocketide.app.core.ReliableBench.repeated(reliableTasks(act, python), runs) }.toMutableList()
        /** Reliability suite: tasks whose reference fix does not pass on this phone (its packages differ), left out of the scores. */
        val unsound = LinkedHashMap<String, String>()
        val checked = HashSet<String>()
        var pythonNote = ""
        /** Gemini Nano's exchanges in the current task (HybridAgent.recorder), saved as nano.jsonl. */
        val nanoLog = StringBuilder()
        var i = 0

        fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_LONG).show()

        fun begin() {
            act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                    main.post {
                        if (code2 != 0) unsound[base] = "the reference fix fails the hidden check here: " + out2.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(140)
                        reset(); then()
                    }
                }
            }
        }

        // 1.5.185: the settings app_settings can change, read before each task and compared after it (no change may happen unapproved)
        fun settingsNow(): Map<String, String?> = dev.pocketide.app.core.AppSettingsTool.known.associate { it.setting!! to AppSettingsBridge.value(act, settings, it.setting) }
        var settingsBefore: Map<String, String?> = emptyMap()

        fun next() {
            if (i >= tasks.size) { finish(null); return }
            val t = tasks[i]
            toast("Hybrid benchmark ${i + 1} of ${tasks.size}: ${t.title}")
            if (t.entry == HybridBenchTasks.Entry.CHAT) { fromChat(t); return }
            val name = Projects.create(act, "bench $stamp ${t.id.replace('#', '-')}", dev.pocketide.app.core.Templates.EMPTY)
            val root = File(Projects.root(act), name)
            for ((p, text) in t.files) File(root, p).apply { parentFile?.mkdirs(); writeText(text) }
            if (kind == Suite.RELIABLE && dev.pocketide.app.core.ReliableBench.baseId(t.id) !in checked && t.solution.isNotEmpty()) {
                soundHere(t, root) { if (dev.pocketide.app.core.ReliableBench.baseId(t.id) in unsound) { i++; main.postDelayed({ next() }, 500) } else startTask(t, name, root) }
                return
            }
            if (dev.pocketide.app.core.ReliableBench.baseId(t.id) in unsound) { i++; main.postDelayed({ next() }, 500); return }
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
            val files = readTexts(root)
            // 1.5.184 (run 6, task 08): the model changed test.js. The check always runs the task's own test, put back if it changed.
            val tampered = dev.pocketide.app.core.Scope.namedChecks(t.prompt).filter { p -> t.files[p] != null && files[p] != t.files[p] }
            for (p in tampered) try { File(root, p).writeText(t.files.getValue(p)) } catch (e: Exception) { }
            val tamperNote = if (tampered.isEmpty()) "" else "The model changed ${tampered.joinToString(", ")}; the task's own version was put back before the check.\n"
            val transcript = (extra + name).joinToString("\n\n") { transcripts(it, t0) }
            val record = { code: Int?, text: String ->
                val now = settingsNow()
                val changedSettings = if (settingsBefore.isEmpty()) emptyList() else now.keys.filter { now[it] != settingsBefore[it] }.map { "$it: ${settingsBefore[it]} → ${now[it]}" }
                val facts = HybridBenchTasks.Facts(files, t.files, code, text, transcript, ended, changedSettings, lastHidden.first, lastHidden.second)
                lastHidden = null to ""
                val why = try { t.judge(facts) } catch (e: Exception) { "the check failed: ${e.message}" }
                results.add(HybridBenchTasks.Result(t, why == null, why.orEmpty(), HybridBenchTasks.counts(transcript),
                    (System.currentTimeMillis() - t0) / 1000, ended))
                try {
                    val dir = File(out, t.id).apply { mkdirs() }
                    for ((p, s) in files) File(dir, "files/$p").apply { parentFile?.mkdirs(); writeText(s) }
                    File(dir, "transcript.txt").writeText(transcript)
                    synchronized(nanoLog) { if (nanoLog.isNotEmpty()) File(dir, "nano.jsonl").writeText(nanoLog.toString()) }
                    File(dir, "check.txt").writeText(tamperNote + (if (changedSettings.isEmpty()) "" else "Settings changed during the task: " + changedSettings.joinToString("; ") + "\n") + (t.command?.let { "$ $it\nexit $code\n$text\n\n" } ?: "") + "Result: " + (why ?: "passed") + "\n")
                } catch (e: Exception) { /* the report still has the result */ }
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
            act.benchChat().runForChecks(t.hiddenCommand!!) { code, text ->
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
            settings.provider = before.first; settings.workflow = before.second; settings.agent = before.third; settings.mode = chatModeBefore
            settings.restoreSlot(true, draftBefore); settings.restoreSlot(false, strongBefore)
            HybridScreen.setMode(act, modeBefore)
            act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val hs = HybridAi.settings(act)
            val about = "${dev.pocketide.app.core.BuildTag.userAgent} · run $stamp · on-phone model ${if (hs.fullModel) "Full (E4B)" else "Fast (E2B)"} · " +
                "on-phone limit ${hs.onDeviceMaxChars} characters · temperature ${hs.temperature} · top-K ${hs.topK} · max output ${hs.maxOutputTokens} tokens · " +
                (if (strong != null) "Cascade: Hybrid AI helps the strong model ${strong.provider}/${strong.model.ifBlank { dev.pocketide.app.core.Providers.byId(strong.provider).model }}, which takes Gemini Nano's handoffs and reviews the work · " else "") + "cloud ${HybridCloud.pinnedModel()?.let { "model $it (pinned by Remote Config)" } ?: "models: " + HybridAi.cloudChoice(act).let { it.label + " (" + it.about + ")" }}"
            val report = HybridBenchTasks.report(results, about, problem, tasks.size, kind.title + if (helping) " · hybrid helping a strong model" else "") +
                (if (kind != Suite.RELIABLE) "" else dev.pocketide.app.core.ReliableBench.summary(results) +
                    (if (runs > 1) "\nRuns per task: $runs.\n" else "") + (if (pythonNote.isNotEmpty()) "\n$pythonNote\n" else "") +
                    (if (unsound.isEmpty()) "" else "\nLeft out (the reference fix does not pass on this phone):\n" + unsound.entries.joinToString("\n") { "- ${it.key}: ${it.value}" } + "\n"))
            File(out, "report.md").writeText(report)
            val where = try {
                saveToDownloads("pocketide-$suite${if (helping) "-helping" else ""}-benchmark-$stamp.zip") { os -> zipDir(out, os) }
            } catch (e: Exception) { "not saved (${e.message}); the results are in the app's files: ${out.absolutePath}" }
            act.benchSwitch(projectBefore)
            running = false
            val passed = results.count { it.passed }
            AlertDialog.Builder(act).setTitle(kind.title + " finished")
                .setMessage((problem?.let { "Stopped early: $it\n\n" } ?: "") + "Passed $passed of ${tasks.size}.\n\n" +
                    results.joinToString("\n") { (if (it.passed) "✓ " else "✗ ") + it.task.title + (if (it.passed) "" else ": " + it.why) } +
                    "\n\nResults: $where")
                .setPositiveButton("OK", null).show()
        }

        fun zipDir(dir: File, os: OutputStream) = ZipOutputStream(os).use { z ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(ZipEntry(f.relativeTo(dir).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }

        /** Saves a file to Downloads/PocketIDE (MediaStore, no permission needed); before Android 10, the app's own files. */
        fun saveToDownloads(fileName: String, write: (OutputStream) -> Unit): String {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PocketIDE")
                }
                val uri = act.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("Downloads refused the file")
                act.contentResolver.openOutputStream(uri)!!.use(write)
                return "Downloads/PocketIDE/$fileName"
            }
            val f = File(act.getExternalFilesDir(null), fileName)
            f.outputStream().use(write)
            return f.absolutePath
        }
    }
}
