package dev.pocketide.app.bench

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.pocketide.app.AppSettings
import dev.pocketide.app.ModelCall
import dev.pocketide.app.ModelGateway
import dev.pocketide.app.NativeAgent
import dev.pocketide.app.NativeListener
import dev.pocketide.app.ProjectStore
import dev.pocketide.app.core.AgentPrompt
import dev.pocketide.app.core.FileEdit
import dev.pocketide.app.core.Habits
import dev.pocketide.app.core.MiniJson
import dev.pocketide.app.core.Prompts
import dev.pocketide.app.core.Scope
import dev.pocketide.app.core.ToolCall
import dev.pocketide.app.core.ToolTurn
import dev.pocketide.app.core.Tools
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs the app's real agent loop (NativeAgent) on the JVM, with Robolectric standing in for Android (1.5.85).
 *
 * The model is a [ModelGateway]: a [ScriptedModel] for regression tests (each round's reply is written in the test), or the
 * network one for benchmark runs with a real provider (see [LiveBench]). Commands run in a real shell in the project folder.
 * There is no WebView here, so look_at_preview is not offered; tasks are checked with commands instead.
 */
class AgentHarness(
    /** Project files to start from, path to text. */
    private val files: Map<String, String>,
    /** The person's message, exactly as typed. */
    val request: String,
    private val model: ModelGateway,
    /** Agent mode (edits applied); false = Agent: suggest (edits staged). */
    private val agent: Boolean = true,
    private val askOnly: Boolean = false,
    private val rounds: Int = 24,
    private val provider: String = "openrouter",
    private val modelName: String = "scripted",
    private val apiKey: String = "",
    /** The provider's address, when it is not the default (Cloudflare's includes the account id). */
    private val base: String = "",
    private val timeoutSeconds: Long = 120,
    /** Thinking effort sent to the model ("" = the model's own; low, medium, high), ThinkEffort (1.5.99). */
    private val effort: String = System.getenv("POCKETIDE_BENCH_EFFORT").orEmpty().trim(),
    /** false = pieces of files are served as asked, as before 1.5.99 (POCKETIDE_BENCH_WHOLE_READS=0), to measure ReadPlan on the same code. */
    private val readWhole: Boolean = System.getenv("POCKETIDE_BENCH_WHOLE_READS")?.trim() != "0",
    /**
     * The tools the app offers by default (1.5.106): the read-only explore helper in agent mode, and apply_patch for GPT-family
     * models (ChatPane sets both before every request). Before, the benchmark offered neither, so it measured a smaller tool set
     * than the phone has. LiveBench turns this on (POCKETIDE_BENCH_APP_TOOLS=0 turns it off); scripted tests leave it off.
     */
    private val appTools: Boolean = false,
    /** The app's verify plan (tests or syntax checks) as on the phone; off by default so scripted tests stay quick (1.5.184). */
    private val verify: Boolean = false,
    /** A second model that plans and reviews ("provider:model", POCKETIDE_BENCH_ADVISOR), as Settings → Advisor does; "" = none. */
    private val advisor: String = "",
    private val advisorMode: String = dev.pocketide.app.core.Advisor.AUTO,
    /** Called before each model request with the tool calls so far and the conversation sent, so a caller can save a run while it is still going. */
    private val onProgress: ((calls: List<Pair<ToolCall, String>>, requests: Int, messages: List<Map<String, Any?>>) -> Unit)? = null,
    /** Added to the person's message (not to the request the loop reminds the model of): a second try's note (SecondTry, 1.5.112). */
    private val extra: String = "",
    /**
     * A conversation to carry on (1.5.163): when set, [extra] is sent as the next user message after it instead of being appended to
     * the request. Aider's second try continues the same chat with the test output; ours used to start a new one.
     */
    private val history: List<Map<String, Any?>> = emptyList(),
    /** A repair turn (1.5.164, RepairTurn): only reading and editing tools, and finishing without an edit is sent back once. */
    private val repair: Boolean = false,
    /** The Chat home (ChatHome, 1.5.125): no project, read-only, and the Chat home's note in the instructions. */
    private val chatHome: Boolean = false,
    /**
     * A second, cheaper model for the helper jobs ("provider:model", POCKETIDE_BENCH_HELPER, 1.5.138): the explorer and the history
     * summary use it, as the "draft" model in the phone's workflow screen does; "" = they use the main model. Its key comes from
     * POCKETIDE_BENCH_KEY_<PROVIDER>.
     */
    private val helper: String = System.getenv("POCKETIDE_BENCH_HELPER").orEmpty().trim(),
    /**
     * Search by meaning for find_code (1.5.141): an embeddings model "provider:model" (POCKETIDE_BENCH_EMBED, e.g.
     * cloudflare:@cf/baai/bge-base-en-v1.5), indexed in memory for the run; tests pass their own [meaning] instead.
     */
    private val embed: String = System.getenv("POCKETIDE_BENCH_EMBED").orEmpty().trim(),
    /** The ask checklist (1.5.138): as the app, off unless POCKETIDE_BENCH_ASK_CHECK=1 (off by default since 1.5.145). */
    private val askCheck: Boolean = System.getenv("POCKETIDE_BENCH_ASK_CHECK")?.trim() == "1",
    /** Cause before change for problem reports (CauseGate, 1.5.147): POCKETIDE_BENCH_CAUSE_FIRST=1, off as in the app. */
    private val causeFirst: Boolean = System.getenv("POCKETIDE_BENCH_CAUSE_FIRST")?.trim() == "1",
    /** Try code that reads outside input on other shapes (InputVariety, 1.5.154): POCKETIDE_BENCH_VARIETY=1, off as in the app. */
    private val variety: Boolean = System.getenv("POCKETIDE_BENCH_VARIETY")?.trim() == "1",
    /** Keep to what was asked (ScopeGate, 1.5.156): POCKETIDE_BENCH_SCOPE=1, off as in the app. */
    private val scope: Boolean = System.getenv("POCKETIDE_BENCH_SCOPE")?.trim() == "1",
    /** A second look in a fresh context before finishing (FreshVerify, 1.5.161): POCKETIDE_BENCH_VERIFY=1, off as in the app. */
    /** The explore helper (1.5.162: off by default in the app, as here): POCKETIDE_BENCH_EXPLORE=1 offers it when app tools are on. */
    private val explore: Boolean = System.getenv("POCKETIDE_BENCH_EXPLORE")?.trim() == "1",
    /** The premise check (PremiseCheck, 1.5.164): POCKETIDE_BENCH_PREMISE=1, off as in the app. */
    private val premise: Boolean = System.getenv("POCKETIDE_BENCH_PREMISE")?.trim() == "1",
    private val verifyFresh: Boolean = System.getenv("POCKETIDE_BENCH_VERIFY")?.trim() == "1",
    /** look_at_preview in headless Chromium (HarnessLook, 1.5.147): POCKETIDE_BENCH_LOOK=1, when node can load Playwright. */
    private val look: Boolean = System.getenv("POCKETIDE_BENCH_LOOK")?.trim() == "1",
    private val meaning: ((String, Map<String, String>) -> List<dev.pocketide.app.core.RankedSearch.Hit>)? = null,
    /** Reading rounds go to the helper model too (Settings → phase routing, POCKETIDE_BENCH_ROUTE=1). */
    private val route: Boolean = System.getenv("POCKETIDE_BENCH_ROUTE")?.trim() == "1",
    /** A scripted person (1.5.170): this message is typed while the run works, read before model request number first (from 1). */
    private val steer: Pair<Int, String>? = null,
    /** The person's answer to a held test change (1.5.171): true allows, false keeps; null asks after the run, as the benchmark does. */
    private val testAnswer: Boolean? = null,
) {
    /** One model request: which job it was for, how long it took, and the tokens it used (1.5.138). */
    class Request(val role: String, val millis: Long, var usage: dev.pocketide.app.core.Usage = dev.pocketide.app.core.Usage())
    class Outcome(
        val result: NativeAgent.Result?,
        /** Every tool call in order, with the result the loop sent back. */
        val calls: List<Pair<ToolCall, String>>,
        /** Model requests made (rounds plus re-prompts). */
        val requests: Int,
        val files: Map<String, String>,
        val root: File,
        val millis: Long,
        val timedOut: Boolean,
        /** The conversation of the last request: the model's words between calls and what the app told it. */
        val messages: List<Map<String, Any?>> = emptyList(),
        /** Every model request in order, with its job, time and tokens (1.5.138). */
        val log: List<Request> = emptyList(),
        /** Lines about search by meaning (1.5.142): each use and any failure, for the transcript. */
        val meaningLog: List<String> = emptyList(),
        /** Tool calls that could not run as sent (1.5.164): unknown names, calls written as text, calls cut off. */
        val badCalls: Int = 0,
    ) {
        val names: List<String> get() = calls.map { it.first.name }
        fun resultsOf(name: String): List<String> = calls.filter { it.first.name == name }.map { it.second }
        fun file(path: String): String? = files[path]
        val finishedOk: Boolean get() = result?.ok == true
    }

    private val meaningLog = ArrayList<String>()
    private var steerGiven = false

    /** The app's search by meaning for [embed], with an in-memory index, the key and address from the bench secrets. */
    private fun embedSearch(settings: AppSettings): ((String, Map<String, String>) -> List<dev.pocketide.app.core.RankedSearch.Hit>)? {
        val slot = dev.pocketide.app.core.EmbedClient.slot(embed) ?: return null
        val env = slot.provider.uppercase().replace('-', '_')
        val key = dev.pocketide.app.core.KeyClean.clean(System.getenv("POCKETIDE_BENCH_KEY_$env").orEmpty())
        val base = System.getenv("POCKETIDE_BENCH_BASE_$env").orEmpty().ifBlank { settings.baseFor(slot.provider) }
        val url = dev.pocketide.app.core.EmbedClient.url(dev.pocketide.app.core.Providers.byId(slot.provider), base) ?: return null
        val idx = dev.pocketide.app.core.SemanticIndex()
        return { q, files ->
            try {
                val e = { t: List<String> -> dev.pocketide.app.core.EmbedClient.embed(url, key, slot.model, t) }
                val n = idx.update(files, embed, e)
                val hits = idx.query(e(listOf(dev.pocketide.app.core.SecretMask.mask(q))).first(), files)
                synchronized(meaningLog) { meaningLog.add("\"${q.take(60)}\": ${hits.size} hits (index ${idx.size} chunks, $n embedded now)") }
                hits
            } catch (t: Throwable) {
                // the app falls back to keyword search; the job log says so, so a run never measures a silent fallback
                synchronized(meaningLog) { meaningLog.add("FAILED: ${t.message?.take(200)}") }
                throw t
            }
        }
    }

    fun run(): Outcome {
        val ctx = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("pocketide-bench").toFile()
        for ((p, t) in files) File(root, p).also { it.parentFile?.mkdirs() }.writeText(t)
        val store = ProjectStore(root, seed = false)
        val settings = AppSettings(ctx)
        settings.provider = provider
        settings.setModel(provider, modelName)
        if (apiKey.isNotEmpty()) settings.setKey(provider, apiKey)
        if (base.isNotEmpty()) settings.setBaseFor(provider, base)
        settings.agent = agent
        settings.mode = if (askOnly || chatHome) "ask" else "agent"
        settings.autorun = true
        settings.maxRounds = rounds
        settings.verify = false
        settings.slowOffer = false
        settings.phaseRouting = false
        settings.webResearch = false
        settings.thinkEffort = effort
        settings.readWhole = readWhole
        val helperSlot = helper.takeIf { it.contains(':') }?.let { h ->
            val hp = h.substringBefore(':').trim(); val hm = h.substringAfter(':').trim()
            val env = hp.uppercase().replace('-', '_')
            System.getenv("POCKETIDE_BENCH_KEY_$env")?.takeIf { it.isNotBlank() }?.let { settings.setKey(hp, dev.pocketide.app.core.KeyClean.clean(it)) }
            System.getenv("POCKETIDE_BENCH_BASE_$env")?.takeIf { it.isNotBlank() }?.let { settings.setBaseFor(hp, it) }
            dev.pocketide.app.core.Workflow.Slot(hp, hm).also { settings.setSlot(true, it) }
        }
        settings.phaseRouting = route && helperSlot != null
        settings.askCheck = askCheck
        settings.causeFirst = causeFirst
        settings.varietyProbe = variety
        settings.scopeCheck = scope
        settings.verifyFresh = verifyFresh
        settings.premiseCheck = premise
        val advisorPlan = advisor.takeIf { it.contains(':') }?.let { a ->
            val ap = a.substringBefore(':').trim(); val am = a.substringAfter(':').trim()
            val env = ap.uppercase().replace('-', '_')
            System.getenv("POCKETIDE_BENCH_KEY_$env")?.takeIf { it.isNotBlank() }?.let { settings.setKey(ap, it) }
            System.getenv("POCKETIDE_BENCH_BASE_$env")?.takeIf { it.isNotBlank() }?.let { settings.setBaseFor(ap, it) }
            dev.pocketide.app.core.Advisor.Plan(advisorMode, dev.pocketide.app.core.Workflow.Slot(ap, am), 3)
        }

        val main = Handler(Looper.getMainLooper())
        val calls = ArrayList<Pair<ToolCall, String>>()
        var requests = 0
        var latest: List<Map<String, Any?>> = emptyList()
        val log = java.util.Collections.synchronizedList(ArrayList<Request>())
        // which request the provider's token report belongs to: requests in the loop run one at a time, and each reports its usage
        // as its stream ends, before the next one starts (ModelClient), so the newest request is the one being reported
        var open: Request? = null
        var openStarted = 0L
        val counting = object : ModelGateway {
            override fun startNative(settings: AppSettings, system: String, messages: List<Map<String, Any?>>, tools: Boolean, listener: NativeListener,
                                     docs: Boolean, loaded: Set<String>?): ModelCall {
                requests++
                // the main conversation (also when a reading round is routed to the helper); the explorer's own conversation is not the run's
                if (role(system, settings).let { it == "main" || it == "route" }) { collect(messages, calls); latest = messages.toList() }
                onProgress?.let { cb -> try { cb(calls.toList(), requests, latest) } catch (e: Exception) { /* saving must not stop the run */ } }
                val entry = Request(role(system, settings), 0)
                openStarted = System.currentTimeMillis(); open = entry
                fun end() { if (open === entry) { log.add(Request(entry.role, System.currentTimeMillis() - openStarted, entry.usage)); open = null } }
                val timed = object : NativeListener {
                    override fun onStatus(text: String) = listener.onStatus(text)
                    override fun onDelta(acc: String) = listener.onDelta(acc)
                    override fun onTurn(turn: ToolTurn) { end(); listener.onTurn(turn) }
                    override fun onStopped(partial: String) { end(); listener.onStopped(partial) }
                    override fun onError(message: String, needsSettings: Boolean, toolsRejected: Boolean) { end(); listener.onError(message, needsSettings, toolsRejected) }
                }
                return model.startNative(settings, system, messages, tools, timed, docs, loaded)
            }
        }
        val meter0 = dev.pocketide.app.core.UsageMeter.sink
        dev.pocketide.app.core.UsageMeter.sink = { _, _, u -> open?.let { it.usage = it.usage + u } ?: log.lastOrNull()?.let { it.usage = it.usage + u } }
        var done: NativeAgent.Result? = null
        val box = LinearLayout(ctx)
        val body = TextView(ctx)
        val readOnlyMode = askOnly || chatHome
        val system = AgentPrompt.core(request, agent, Prompts.ENV_NOTE, false, false, readOnlyMode, false, scope = scope, premise = premise && agent) + (if (chatHome) dev.pocketide.app.core.ChatHome.NOTE else "") +
            dev.pocketide.app.core.LibDocs.note(dev.pocketide.app.core.LibDocs.used(files.keys) { files[it] }) + AgentPrompt.pageNotes(request, agent, false, false)
        val started = System.currentTimeMillis()
        val saved = ModelGateway.current
        ModelGateway.current = counting
        val tools0 = Triple(Tools.exploreEnabled, Tools.patchEnabled, Tools.advisorEnabled)
        val look0 = Tools.lookEnabled
        val looks = look && HarnessLook.available
        Tools.lookEnabled = looks && dev.pocketide.app.core.LookOffer.offer(store.findPage() != null, files.isEmpty(), request)
        Tools.exploreEnabled = appTools && agent && explore
        val only0 = Tools.only
        Tools.only = if (repair) dev.pocketide.app.core.RepairTurn.TOOLS else null
        val report0 = Tools.reportEnabled
        Tools.reportEnabled = premise && agent
        Tools.patchEnabled = appTools && dev.pocketide.app.core.ApplyPatch.suits(provider, modelName)
        Tools.advisorEnabled = advisorPlan != null
        var agentRef: NativeAgent? = null
        try {
            agentRef = NativeAgent(ctx, store, settings, agent, box, body,
                write = { e: FileEdit -> store.write(e.path, e.content) },
                remove = { p -> store.delete(p) },
                exec = { cmd, cb -> Thread { val (code, out) = shell(root, cmd); main.post { cb(code, out) } }.start() },
                askRun = { cmd, onRun, _ -> Thread { val (code, out) = shell(root, cmd); main.post { onRun(code, out) } }.start(); View(ctx) },
                check = { cb -> cb(emptyList()) },
                setCall = { },
                onPlan = { },
                scrollDown = { },
                docs = false,
                docEdit = { _, _, _ -> "Documents are not available in the harness." },
                verifyPlan = { if (verify) dev.pocketide.app.core.Verify.plan(store.list(), { f -> store.read(f) }, null) else null },
                // 1.5.139: as the app passes it (ChatPane); 1.5.138 left it out, so POCKETIDE_BENCH_HELPER reached only phase routing
                explorerSlot = { settings.explorerSlot() },
                meaning = meaning ?: embedSearch(settings),
                // the harness cuts a run off at timeoutSeconds; the app wraps up before that, as it does at its own limits on a phone
                hardLimitMs = TimeUnit.SECONDS.toMillis(timeoutSeconds),
                // 1.5.179: the app checks every written script, page script and JSON file (Acorn in a hidden browser) and puts a
                // working file back when an edit breaks it; the harness passed no checker, so broken edits stayed (the local models'
                // "SyntaxError: Unexpected token '}'" failures). node --check and a JSON parse stand in for it.
                syntax = { p, t, cb -> Thread { val e = harnessSyntax(p, t); main.post { cb(e) } }.start() },
                look = if (!looks) null else { steps, page, trace, cb ->
                    // as ChatPane: the named .html page, else the project's page; the browser runs off the main thread
                    val entry = page.trim().removePrefix("./").removePrefix("/").takeIf { it.endsWith(".html", true) && store.exists(it) } ?: store.findPage()
                    if (entry == null) cb(dev.pocketide.app.core.LookOffer.NO_PAGE + " Write an .html file first.", null)
                    else Thread { val (t, img) = HarnessLook.look(root, entry, steps, trace); Handler(Looper.getMainLooper()).post { cb(t, img) } }.start()
                },
                onDone = { r -> done = r },
                request = request,
                advisor = advisorPlan,
                readOnly = { askOnly },
                // the undefined-name check the phone runs in its hidden browser, run here with Node (1.5.103)
                mustEdit = repair,
                names = if (NodeNames.available) { p, t, cb -> Thread { val r = NodeNames.run(p, t); main.post { cb(r) } }.start() } else null,
                question = Habits.isQuestion(request),
                // the scripted person: typed after request first-1 ended, read by the loop before the next one (as ChatPane's steer box)
                steerTake = { if (steer != null && !steerGiven && requests >= steer.first - 1) { steerGiven = true; steer.second } else null },
                steerPending = { steer != null && !steerGiven && requests >= steer.first - 1 },
                reportOnly = !Habits.isQuestion(request) && Scope.reportOnly(request),
                askTest = testAnswer?.let { yes -> { _, _, allow, keep -> main.post { if (yes) allow() else keep() }; android.widget.TextView(ctx) } },
            )
            agentRef.run(system, if (history.isEmpty()) listOf(Tools.userText(false, request + extra + (if (premise && agent) dev.pocketide.app.core.PremiseCheck.probe(request, files.keys.toList()) { files[it] }.orEmpty() else ""))) else history + listOf(Tools.userText(false, extra.trim())))
            val deadline = started + TimeUnit.SECONDS.toMillis(timeoutSeconds)
            while (done == null && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                if (done == null) Thread.sleep(5)
            }
        } finally {
            dev.pocketide.app.core.UsageMeter.sink = meter0
            ModelGateway.current = saved
            Tools.exploreEnabled = tools0.first; Tools.patchEnabled = tools0.second; Tools.advisorEnabled = tools0.third; Tools.lookEnabled = look0
            Tools.only = only0; Tools.reportEnabled = report0
        }
        val r = done
        val out = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to (try { it.readText() } catch (e: Exception) { "" }) }
        return Outcome(r, calls.toList(), requests, out, root, System.currentTimeMillis() - started, r == null, latest, log.toList(), synchronized(meaningLog) { meaningLog.toList() }, agentRef?.badCalls ?: 0)
    }

    /**
     * The job a request was for, from its instructions: the explorer, the history summary and the advisor have their own; a
     * main-loop request sent to another model is a routed reading round (1.5.138).
     */
    private fun role(system: String, settings: AppSettings): String = when {
        system.startsWith(Prompts.EXPLORER) -> "explore"
        system.startsWith(dev.pocketide.app.core.WorkSummary.PROMPT) -> "summary"
        system.startsWith(dev.pocketide.app.core.Advisor.SYSTEM) -> "advisor"
        system.startsWith(dev.pocketide.app.core.AskLedger.COUNT_PROMPT) -> "askcount"
        // 1.5.146: the reviewer runs on the helper with the override set, and was taken for a routed round of the main conversation,
        // so its conversation replaced the run's tool calls in the transcript and the read/search columns
        system.startsWith(dev.pocketide.app.core.ReviewPass.PROMPT) -> "review"
        system.startsWith(dev.pocketide.app.core.FreshVerify.PROMPT) -> "verify"
        settings.override.let { o -> o != null && (o.provider != provider || o.model.ifBlank { modelName } != modelName) } -> "route"
        else -> "main"
    }

    // The tool calls and their results, read from the conversation the loop sends to the model (the last request holds them all).
    private fun collect(messages: List<Map<String, Any?>>, into: MutableList<Pair<ToolCall, String>>) {
        into.clear(); into.addAll(pairs(messages))
    }

    companion object {
        fun pairs(messages: List<Map<String, Any?>>): List<Pair<ToolCall, String>> {
            val byId = LinkedHashMap<String, ToolCall>()
            val out = ArrayList<Pair<ToolCall, String>>()
            for (m in messages) {
                val calls = m["tool_calls"] as? List<*>
                if (m["role"] == "assistant" && calls != null) for (c in calls) {
                    val cm = c as? Map<*, *> ?: continue
                    val fn = cm["function"] as? Map<*, *> ?: continue
                    val id = cm["id"] as? String ?: continue
                    byId[id] = ToolCall(id, fn["name"] as? String ?: "", fn["arguments"] as? String ?: "{}")
                }
                if (m["role"] == "tool") {
                    val id = m["tool_call_id"] as? String ?: continue
                    val c = byId[id] ?: continue
                    out.add(c to (m["content"] as? String ?: ""))
                }
            }
            return out
        }

        fun shell(dir: File, cmd: String, isolate: Boolean = System.getenv("POCKETIDE_BENCH_MODELS").orEmpty().isNotBlank(),
                  timeoutSeconds: Long = 60): Pair<Int, String> = try {
            val path = System.getenv("PATH").orEmpty()
            val node = path.split(File.pathSeparator).map { File(it, "node") }.firstOrNull { it.isFile && it.canExecute() }?.canonicalFile
            // 1.5.179: a throwaway machine whose container forbids user namespaces (a Kaggle notebook) runs task commands without
            // bubblewrap, still with a clean environment and the time limit; only where POCKETIDE_BENCH_NO_SANDBOX=1 is set on purpose
            val args = if (isolate && System.getenv("POCKETIDE_BENCH_NO_SANDBOX") == "1") {
                require(node != null) { "Node is required for the live benchmark." }
                listOf("/bin/bash", "-c", cmd)
            } else if (isolate) {
                require(node != null) { "Node is required for the live benchmark." }
                val sandbox = mutableListOf("bwrap", "--unshare-all", "--die-with-parent", "--ro-bind", "/usr", "/usr",
                    "--ro-bind", "/lib", "/lib", "--symlink", "usr/bin", "/bin",
                    "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp")
                // 1.5.179: ARM machines (an Oracle Ampere server) have no /lib64, and bwrap stops on a missing source
                if (File("/lib64").exists()) sandbox.addAll(1, listOf("--ro-bind", "/lib64", "/lib64"))
                val runtime = node.parentFile.parentFile
                if (!runtime.path.startsWith("/usr/")) sandbox.addAll(listOf("--ro-bind", runtime.path, runtime.path))
                // 1.5.125: the JavaScript polyglot exercises' hidden tests need jest, installed once per job outside the project; read-only
                // like the Node runtime, and only when the workflow installed it
                PolyglotTasks.jestDir()?.let { sandbox.addAll(listOf("--ro-bind", it.path, it.path)) }
                sandbox + listOf("--bind", dir.canonicalPath, dir.canonicalPath, "--chdir", dir.canonicalPath, "--", "/bin/bash", "-c", cmd)
            } else listOf("bash", "-c", cmd)
            // GNU timeout runs the command in its own process group and kills the whole group, so a stray child cannot hold the output open
            val builder = ProcessBuilder(listOf("timeout", "-k", "5", "${timeoutSeconds}s") + args).directory(dir).redirectErrorStream(true)
            builder.environment().apply {
                clear()
                put("PATH", if (isolate) "${node!!.parent}:/usr/local/bin:/usr/bin:/bin" else path)
                put("HOME", if (isolate) "/tmp" else dir.canonicalPath)
                for (name in listOf("LANG", "LC_ALL", "TZ")) System.getenv(name)?.let { put(name, it) }
            }
            val p = builder.start()
            val out = StringBuffer()
            val reader = Thread { p.inputStream.bufferedReader().useLines { lines -> lines.forEach { out.append(it).append('\n') } } }
            reader.isDaemon = true
            reader.start()
            if (!p.waitFor(timeoutSeconds + 10, TimeUnit.SECONDS)) { p.destroyForcibly(); p.waitFor(5, TimeUnit.SECONDS) }
            reader.join(1000)
            val code = if (p.isAlive) 124 else p.exitValue()
            if (code == 124 || code == 137) 124 to out.toString() + "\n(timed out)" else code to out.toString()
        } catch (e: Exception) { 127 to (e.message ?: "could not run") }
    }
}

/**
 * A model whose replies are written in the test. Each step sees the conversation so far (to read a tool result) and returns the
 * reply: tool calls, or plain text to finish. When the steps run out, it answers "Done." with no calls.
 */
class ScriptedModel(private val steps: List<(List<Map<String, Any?>>) -> ToolTurn>) : ModelGateway {
    var calls = 0; private set
    private var n = 0
    override fun startNative(settings: AppSettings, system: String, messages: List<Map<String, Any?>>, tools: Boolean, listener: NativeListener,
                             docs: Boolean, loaded: Set<String>?): ModelCall {
        calls++
        val step = steps.getOrNull(n++)
        val turn = step?.invoke(messages) ?: ToolTurn("Done.", emptyList(), "stop")
        Handler(Looper.getMainLooper()).post { listener.onTurn(turn) }
        return ModelCall()
    }

    companion object {
        private var id = 0
        fun call(name: String, vararg args: Pair<String, Any?>): ToolCall = ToolCall("c" + (++id), name, MiniJson.write(mapOf(*args)))
        fun tools(vararg calls: ToolCall, text: String = ""): (List<Map<String, Any?>>) -> ToolTurn = { ToolTurn(text, calls.toList(), "tool_calls") }
        fun say(text: String): (List<Map<String, Any?>>) -> ToolTurn = { ToolTurn(text, emptyList(), "stop") }
    }
}

/** The harness's stand-in for the app's syntax check (SyntaxCheck + Acorn): an error description, or null when the file parses. */
internal fun harnessSyntax(path: String, text: String): String? {
    if (text.length > dev.pocketide.app.core.SyntaxCheck.MAX_CHARS) return null
    return when (dev.pocketide.app.core.SyntaxCheck.kind(path, text)) {
        dev.pocketide.app.core.SyntaxCheck.Kind.JSON ->
            try { dev.pocketide.app.core.MiniJson.parse(text); null } catch (e: Exception) { e.message ?: "invalid JSON" }
        dev.pocketide.app.core.SyntaxCheck.Kind.SCRIPT, dev.pocketide.app.core.SyntaxCheck.Kind.MODULE -> {
            val module = dev.pocketide.app.core.SyntaxCheck.kind(path, text) == dev.pocketide.app.core.SyntaxCheck.Kind.MODULE
            val f = java.io.File.createTempFile("pocketide-syntax", if (module) ".mjs" else ".cjs")
            try {
                f.writeText(text)
                val pr = ProcessBuilder("node", "--check", f.path).redirectErrorStream(true).start()
                val out = pr.inputStream.bufferedReader().readText()
                if (!pr.waitFor(20, TimeUnit.SECONDS)) { pr.destroyForcibly(); null }
                else if (pr.exitValue() == 0) null
                else out.lines().map { it.trim() }.firstOrNull { it.startsWith("SyntaxError") }
                    ?.let { m -> (Regex(""":(\d+)""").find(out.substringBefore("SyntaxError"))?.groupValues?.get(1)?.let { "line $it: " } ?: "") + m }
                    ?: out.trim().lines().lastOrNull()?.take(200)
            } catch (e: Exception) { null } finally { f.delete() }
        }
        else -> null
    }
}
