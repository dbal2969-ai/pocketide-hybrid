@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package dev.pocketide.app

import android.content.Context
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.Part
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.TextPart
import dev.pocketide.app.core.MiniJson
import dev.pocketide.app.core.SmallLocal
import dev.pocketide.app.core.ToolCall
import dev.pocketide.app.core.ToolTurn
import dev.pocketide.app.core.Tools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Hybrid AI in the agent (#BuildHybridAI). Every reply reaches the agent as real tool calls, so PocketIDE's harness (plans,
 * checks, every tool) works the same as with any tool-calling model:
 *
 * - On the phone: Gemini Nano has no tool calling yet, so it gets small local model mode (SmallLocal: one JSON action per
 *   reply, seven core tools), sized to the on-phone limit. Its action becomes a tool call. A reply that is not one action,
 *   names an unknown tool, or says it changed files when nothing was written goes to the cloud instead.
 * - In the cloud: Gemini through Firebase AI Logic with native function calling and the full tool list.
 */
object HybridAgent {
    class Result(val turn: ToolTurn, val onDevice: Boolean, val seconds: Double, val note: String, val model: String = "")

    /**
     * What the phone did with one agent turn: [result] when it is answered here, else null and [reason], why it leaves
     * the phone (empty in the cloud-first mode, where nothing is said). The caller picks where it goes: the strong model
     * when one is set (Workflow), else the cloud model ([cloud]).
     */
    class Phone(val result: Result?, val reason: String, val stay: Boolean = false, val strongFailed: Boolean = false)

    /** Tests only (HybridAgentLoopTest): stand-ins for Gemini Nano (prompt → reply) and the cloud model (system, messages, tools → turn). */
    @Volatile var testPhone: ((String) -> String)? = null
    @Volatile var testCloud: ((String, List<Map<String, Any?>>, List<*>?) -> ToolTurn)? = null

    private const val HANDOFF_MARK = "⚡ Gemini Nano (on this phone): I'm passing this to"
    private const val FALLBACK_MARK = " instead. Why: "

    /** The model name of a step PocketIDE took itself, with no model call ([appFirst]); HybridScreen.label names it. */
    const val APP_STEP = "PocketIDE itself"

    /** The person's own words in a user message (PromptCache.withContext puts the project context first). */
    fun requestOf(text: String): String = text.substringAfterLast("My request:\n")

    /**
     * 1.5.185 (in-app run 1: 1 of 9 Nano tasks): asked about PocketIDE, Gemini Nano answered from what it thinks it knows ("I do not
     * have access to PocketIDE's documentation") instead of calling app_help, and the cloud model once read app.js instead. For a
     * question about the app, or a request to change one of its settings, PocketIDE now takes the first step itself, before any
     * model: it looks the question up (app_help), or proposes the change (app_settings, a card the person approves). The model
     * answers from that result. Null when the request is not about the app or a step was already taken.
     */
    fun appFirst(messages: List<Map<String, Any?>>): ToolCall? {
        if (messages.any { it["role"] == "tool" || it["role"] == "assistant" && (it["tool_calls"] as? List<*>).orEmpty().isNotEmpty() }) return null
        val ask = requestOf(messages.lastOrNull { it["role"] == "user" }?.let { textOf(it["content"]) }.orEmpty()).trim()
        if (ask.isEmpty()) return null
        val app = dev.pocketide.app.core.AppHelp
        app.settingRequest(ask)?.let { item ->
            app.settingValue(ask)?.let { v -> return ToolCall("app-first", dev.pocketide.app.core.AppSettingsTool.NAME,
                MiniJson.write(mapOf("action" to "propose", "key" to item.setting, "value" to v, "why" to "You asked: " + ask.take(120)))) }
        }
        // a question about the app, or any request about an API key or token (app11: "Set my OpenAI API key…" got advice to edit config files)
        if (app.aboutApp(ask) && (dev.pocketide.app.core.Intent.of(ask) == dev.pocketide.app.core.Intent.Kind.QUESTION || Regex("""(?i)\b(api key|token)\b""").containsMatchIn(ask)))
            return ToolCall("app-first", "app_help", MiniJson.write(mapOf("query" to ask.take(200))))
        return null
    }

    fun phone(ctx: Context, messages: List<Map<String, Any?>>, docs: Boolean): Phone {
        appFirst(messages)?.let { c -> return Phone(Result(ToolTurn("", listOf(c), "tool_calls"), true, 0.0,
            if (c.name == "app_help") "PocketIDE looked up its own help and settings first." else "PocketIDE proposed the setting change first.", APP_STEP), "") }
        val p = phoneStep(ctx, messages, docs)
        val r = p.result ?: return p
        if (HybridScreen.mode(ctx) == HybridAi.Mode.ONLY_ON_DEVICE || online(ctx)) return p
        return Phone(Result(r.turn, r.onDevice, r.seconds, listOf(r.note, "No internet connection, so this stayed on the phone.").filter { it.isNotBlank() }.joinToString(" "), r.model), p.reason, p.stay, p.strongFailed)
    }

    private fun phoneStep(ctx: Context, messages: List<Map<String, Any?>>, docs: Boolean): Phone {
        val mode = HybridScreen.mode(ctx)
        if (mode == HybridAi.Mode.PREFER_IN_CLOUD) return Phone(null, "")
        // 1.5.184 (the owner's touch-controls run): with no connection, Nano handed off after two failed edits and the request died on
        // "android_getaddrinfo failed". Offline, nothing can be passed on, so Nano carries on as in Private mode.
        val offline = !online(ctx)
        // once a step of this request was passed on, the rest of it stays with that model (Nano would only start over)
        val said = messages.filter { it["role"] == "assistant" }.map { (it["content"] as? String).orEmpty() }
        if (mode == HybridAi.Mode.PREFER_ON_DEVICE && !offline && said.any { it.contains(HANDOFF_MARK) })
            return Phone(null, "", stay = true, strongFailed = said.any { it.contains(FALLBACK_MARK) })
        val limit = HybridAi.settings(ctx).onDeviceMaxChars
        val only = mode == HybridAi.Mode.ONLY_ON_DEVICE || offline
        // Nano tends to repeat a failing step (a benchmark run had six refused edits in a row): after two, the cloud takes over
        val streak = failStreak(messages)
        if (streak >= FAIL_LIMIT && !only) return Phone(null, "my last $streak tool calls failed (the last: ${lastFailure(messages)})")
        // a request that needs the web skips the phone in the on-phone-first mode (Nano's tools cannot reach it)
        val ask = messages.lastOrNull { it["role"] == "user" }?.let { textOf(it["content"]) }.orEmpty()
        val web = dev.pocketide.app.core.HybridRoute.needsCloud(ask)
        if (web != null && !only) return Phone(null, web)
        // 1.5.184 (benchmark run 3, task 07): Nano saw 2,000 characters of a 22,000-character file, never reached total(), and
        // read it four more times. The newest result now gets most of the limit; one longer than that goes to the cloud.
        val newest = messages.lastOrNull()?.takeIf { it["role"] == "tool" }?.let { textOf(it["content"]) }.orEmpty()
        if (newest.length > resultRoom(docs, messages, limit) && !only)
            return Phone(null, "what I just read is longer than my on-phone limit (${newest.length} characters; the limit is $limit)")
        val prompt = phonePrompt(docs, messages, limit)
        if (prompt == null) {
            if (only) return Phone(Result(ToolTurn("This request is longer than the on-phone limit ($limit characters), and this mode never uses the cloud. " +
                "Raise the limit or change where requests run (Models → Hybrid AI).", emptyList(), "stop"), true, 0.0, ""), "")
            return Phone(null, "the conversation is longer than my on-phone limit ($limit characters)")
        }
        val t0 = System.nanoTime()
        val reply = try { testPhone?.invoke(prompt) ?: HybridAi.onDeviceText(ctx, prompt) } catch (e: Exception) {
            // 1.5.184 (run 3, task 08): Android does not let Gemini Nano run while the app is in the background. Private mode waits for
            // PocketIDE to be on screen again and tries once more; the other modes say why and pass the step on.
            val away = testPhone == null && !foreground(ctx)
            if (away && only && waitForeground(ctx, 10 * 60_000L)) {
                try { HybridAi.onDeviceText(ctx, prompt) } catch (e2: Exception) { throw e2 }
            } else {
                if (only) throw e
                return Phone(null, if (away) "PocketIDE was in the background, where Android does not let Gemini Nano run"
                    else "I could not run on this phone (${(e.message ?: e.javaClass.simpleName).take(80)})")
            }
        }
        val secs = (System.nanoTime() - t0) / 1e9
        var (turn, why) = phoneTurn(reply, messages)
        recorder?.let { rec ->
            val c = turn?.calls?.firstOrNull()
            val previous = messages.lastOrNull()?.takeIf { it["role"] == "tool" }?.let { textOf(it["content"]) }.orEmpty()
            try {
                rec(MiniJson.write(mapOf("seconds" to "%.1f".format(secs), "prompt_chars" to prompt.length, "previous_result" to previous.take(600),
                    "prompt" to prompt, "reply" to reply, "tool" to (c?.name ?: if (turn != null) "answer" else ""), "args" to c?.arguments.orEmpty(),
                    "unusable" to why)))
            } catch (e: Exception) { /* the record is best-effort */ }
        }
        val again = turn?.calls?.firstOrNull()?.takeIf { repeatsFailure(it, messages) }
        if (again != null && !only) return Phone(null, "Gemini Nano repeated a step that already failed (${again.name})")
        // 1.5.184 (run 4, task 08): in Private mode there is no one to pass to, and Nano sent the same refused edit five times.
        // It is asked once more, told what failed; a repeat after that goes ahead so the loop's own limits still apply.
        if (again != null) {
            val note = "\nNOTE: that exact action already failed (${lastFailure(messages)}). Do something different: read the file again, " +
                "change fewer lines, or write the whole small file.\nYour reply, one JSON object:"
            val retry = try { testPhone?.invoke(prompt + note) ?: HybridAi.onDeviceText(ctx, prompt + note) } catch (e: Exception) { null }
            if (retry != null) { val t2 = phoneTurn(retry, messages); turn = t2.first; why = t2.second }
        }
        // 1.5.184 (runs 4 and 7, task 01): with the note in its prompt, Nano still swapped its passing fix back. An edit that undoes an
        // earlier edit right after a passing check is not run; the request is reported done.
        if (turn != null && passedNote(messages).isNotEmpty() && turn.calls.firstOrNull()?.let { undoes(it, messages) } == true)
            return Phone(Result(ToolTurn("Done: the check passed after the change, so I left the files as they are.", emptyList(), "stop"), true, secs,
                "Gemini Nano tried to undo a change that had just passed the check; it was not run."), "")
        if (turn != null) return Phone(Result(turn, true, secs, ""), "")
        if (turn == null && only && why.startsWith("answered without doing")) {
            val note = "\nNOTE: nothing has been done yet. Take the first step with a tool: write_file to create a file, or read_file or search to look.\nYour reply, one JSON object:"
            val retry = try { testPhone?.invoke(prompt + note) ?: HybridAi.onDeviceText(ctx, prompt + note) } catch (e: Exception) { null }
            if (retry != null) { val t2 = phoneTurn(retry, messages); turn = t2.first; why = t2.second; if (turn != null) return Phone(Result(turn, true, secs, ""), "") }
        }
        // 1.5.185 (in-app run 1, app01/app04): a refusal written as an answer action was shown as raw JSON; its text is shown instead
        val shown = (if (SmallLocal.isAction(reply)) SmallLocal.parse(reply) { Tools.paramTypes(it) }.text else "").ifBlank { reply.trim() }
        if (only) return Phone(Result(ToolTurn(shown.trim(), emptyList(), "stop"), true, secs, "Gemini Nano $why; this mode never uses the cloud."), "")
        return Phone(null, "Gemini Nano $why")
    }

    /** How long a busy model is left before it is asked again, when no other tier can answer. */
    private const val BUSY_WAIT_MS = 8_000L

    /** The turn on the cloud model through Firebase AI Logic, with every tool as a function. Blocking. */
    fun cloud(ctx: Context, system: String, messages: List<Map<String, Any?>>, tools: List<*>?): Result {
        val pick = tierFor(ctx, messages)
        testCloud?.let { f -> return withEscalation(Result(f(system, messages, tools), false, 0.5, "", pick.tier.model), pick) }
        val t0 = System.nanoTime()
        var tier = pick.tier
        val decl = declarations(tools)
        var lastError: Exception? = null
        var fellBack = ""
        // a tier this project cannot use is skipped for the session and the next one down answers; a refused thinking setting is dropped
        val tried = HashSet<dev.pocketide.app.core.CloudTier.Tier>()
        var waited = false
        repeat(6) {
            tried.add(tier)
            val model = HybridAi.cloudToolModel(ctx, system, decl, tier)
            val ask = { HybridCloud.traced("hybrid_cloud") { runBlocking { model.generateContent(contents(messages)) } } }
            try {
                // 1.5.184 (hard benchmark run 1, h07): one cloud call failed with the SDK's "Something unexpected happened." and the whole
                // task ended before a step ran. A failure that may pass is tried once more after a short wait.
                val r = try { ask() } catch (e: Exception) {
                    if (!transient(e)) throw e
                    Thread.sleep(RETRY_MS)
                    ask()
                }
                val note = if (tier != pick.tier) fellBack else ""
                return withEscalation(Result(turn(r), false, (System.nanoTime() - t0) / 1e9, note, HybridAi.modelFor(tier)), pick.copy(tier = tier))
            } catch (e: Exception) {
                lastError = e
                val msg = HybridAi.explain(e)
                when {
                    dev.pocketide.app.core.CloudTier.cutOff(msg) && !HybridAi.bigOutput -> HybridAi.bigOutput = true
                    HybridCloud.pinnedModel() != null -> throw e
                    dev.pocketide.app.core.CloudTier.thinkingRefused(msg) && tier !in HybridAi.noThinking -> HybridAi.noThinking.add(tier)
                    // 1.5.184 (hard run 5): the free quota ran out ("check your plan and billing details"); it refills, so a cool-down
                    dev.pocketide.app.core.CloudTier.quota(msg) -> {
                        HybridAi.coolUntil[tier] = System.currentTimeMillis() + dev.pocketide.app.core.CloudTier.QUOTA_COOLDOWN_MS
                        val down = dev.pocketide.app.core.CloudTier.fallback(tier, tried, HybridAi.skipNow()) ?: throw e
                        fellBack = "${tier.label} (${tier.model}) used up its free request quota for now, so ${down.label} answers for the next " +
                            "${dev.pocketide.app.core.CloudTier.QUOTA_COOLDOWN_MS / 60_000} minutes (${msg.take(100)})."
                        tier = down
                    }
                    // 1.5.184 (hard run 4): "high demand" is Google's servers, not the project; the next tier down answers this step only
                    dev.pocketide.app.core.CloudTier.busy(msg) -> {
                        // with every other tier tried, a busy model is asked once more after a pause (demand spikes pass)
                        val down = dev.pocketide.app.core.CloudTier.fallback(tier, tried, HybridAi.skipNow())
                            ?: if (!waited) { waited = true; Thread.sleep(BUSY_WAIT_MS); tried.clear(); tier } else throw e
                        fellBack = if (down == tier) "${tier.label} (${tier.model}) was busy; it was asked again after a pause (${msg.take(120)})."
                            else "${tier.label} (${tier.model}) was busy, so ${down.label} answered this step (${msg.take(120)})."
                        tier = down
                    }
                    dev.pocketide.app.core.CloudTier.unavailable(msg) -> {
                        HybridAi.skipTiers.add(tier)
                        val down = dev.pocketide.app.core.CloudTier.fallback(tier, tried, HybridAi.skipNow()) ?: throw e
                        fellBack = "${tier.label} (${tier.model}) is not available to this project, so ${down.label} answers from now on (${msg.take(160)})."
                        tier = down
                    }
                    else -> throw e
                }
            }
        }
        throw lastError ?: IllegalStateException("No cloud model answered.")
    }

    /** The cloud tier for this step of the request (CloudTier.pick), from what has happened in it so far. */
    fun tierFor(ctx: Context, messages: List<Map<String, Any?>>): dev.pocketide.app.core.CloudTier.Pick {
        val since = messages.indexOfFirst { it["role"] == "user" }.coerceAtLeast(0)
        val mine = messages.drop(since)
        val said = mine.filter { it["role"] == "assistant" }.map { (it["content"] as? String).orEmpty() }   // raw: textOf drops the escalation line
        val results = mine.filter { it["role"] == "tool" }.map { textOf(it["content"]) }
        val notes = mine.filter { it["role"] == "user" }.drop(1).map { textOf(it["content"]) }
        return dev.pocketide.app.core.CloudTier.pick(HybridAi.cloudChoice(ctx), said, results, notes, HybridAi.skipNow())
    }

    /** When this step moved up to Best, the turn starts with the line that says so (kept in the chat, so the rest stays on Best). */
    private fun withEscalation(r: Result, pick: dev.pocketide.app.core.CloudTier.Pick): Result {
        val why = pick.why ?: return r
        if (pick.tier != dev.pocketide.app.core.CloudTier.Tier.BEST) return r
        val line = dev.pocketide.app.core.CloudTier.escalation(r.model.ifBlank { pick.tier.model }, why)
        return Result(r.turn.copy(text = listOf(line, r.turn.text).filter { it.isNotBlank() }.joinToString("\n\n")), r.onDevice, r.seconds, r.note, r.model)
    }

    /** One agent turn, phone first, then the cloud model. Blocking. [tools] is the OpenAI-style tool list (Tools.body). */
    fun run(ctx: Context, system: String, messages: List<Map<String, Any?>>, docs: Boolean, tools: List<*>?): Result {
        val p = phone(ctx, messages, docs)
        p.result?.let { return it }
        val c = cloud(ctx, system, messages, tools)
        return if (p.reason.isEmpty()) c else Result(c.turn, false, c.seconds, "Sent to the cloud: ${p.reason}.", c.model)
    }

    /** What Gemini Nano says when it passes a request on: shown at once, and kept above the reply. */
    fun handoff(reason: String, target: String): String =
        "$HANDOFF_MARK $target. Why: $reason."

    /** What Gemini Nano says when the strong model it passed a request to fails, and the cloud model answers instead. */
    fun fallback(why: String, cloudModel: String): String =
        "$HANDOFF_MARK the cloud model ($cloudModel) instead. Why: $why."

    /** A reply without the app's where-it-ran and handoff lines, for sending back to a model. */
    fun clean(text: String): String = LABEL.replace(text, "").trim()

    // ---------------- phone: small local model mode ----------------

    const val FAIL_LIMIT = 2

    /**
     * Set by the benchmark (1.5.184): receives one JSON line per Gemini Nano exchange: its prompt and reply, the tool it chose (or why
     * the reply was unusable), the time, and the result of its previous step. The local suite saves them as nano.jsonl per task, to
     * see where Nano goes wrong and to judge prompt changes.
     */
    @Volatile var recorder: ((String) -> Unit)? = null

    /** For tests: whether the phone is online (null = ask Android). */
    @Volatile var testOnline: Boolean? = null

    /** True when the phone has a working internet connection (when unsure, true: the cloud call then says what is wrong). */
    fun online(ctx: Context): Boolean {
        testOnline?.let { return it }
        if (testPhone != null) return true
        return try {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) { true }
    }

    /**
     * A tool Gemini Nano named in another language or its own words ("membaca_file", "讀取文件", "readFile"): the PocketIDE tool
     * its arguments fit, or null (1.5.184; benchmark runs had both of those names, each costing a handoff).
     */
    fun toolFromArgs(name: String, args: Set<String>): String? {
        val n = name.lowercase().replace(Regex("[^a-z]"), "")
        val byName = mapOf("readfile" to "read_file", "writefile" to "write_file", "editfile" to "edit_file", "editlines" to "edit_lines",
            "runcommand" to "run_command", "listfiles" to "list_files", "search" to "search", "grep" to "search")
        byName[n]?.let { return it }
        return when {
            "command" in args -> "run_command"
            "path" in args && "search" in args && "replace" in args -> "edit_file"
            "path" in args && "start" in args && ("text" in args || "end" in args) -> "edit_lines"
            "path" in args && "content" in args -> "write_file"
            "pattern" in args || "query" in args -> "search"
            args == setOf("path") -> "read_file"
            else -> null
        }
    }
    @Volatile var RETRY_MS = 2500L

    private val LASTING = Regex("""(?i)App Check|attestation|PERMISSION_DENIED|API has not been used|SERVICE_DISABLED|INVALID_ARGUMENT|valid role|API key|billing|not supported|unsupported|cancel""")

    /** True when a cloud failure may pass if tried again (a dropped connection, a server error, the SDK's unknown error). */
    fun transient(e: Throwable): Boolean {
        val all = generateSequence(e) { it.cause }.take(6).joinToString(" | ") { it.javaClass.simpleName + ": " + it.message.orEmpty() }
        if (e is InterruptedException || LASTING.containsMatchIn(all)) return false
        return true
    }

    /** Room for the newest tool result in Nano's prompt: what [limit] leaves after the instructions, the request and a little more. */
    fun resultRoom(docs: Boolean, messages: List<Map<String, Any?>>, limit: Int): Int {
        val first = messages.firstNotNullOfOrNull { line(it) }.orEmpty()
        return maxOf(2000, limit - head(docs, messages).length - TAIL.length - first.length - 1500)
    }

    /**
     * 1.5.185 (from-scratch run 1125-1218, s05): the app set up a starter kit before the request and said so in the system prompt,
     * which Nano's own prompt replaces; Nano listed the files as if the project were empty. The note now reaches Nano too.
     */
    private fun kitNote(messages: List<Map<String, Any?>>): String {
        val sys = messages.firstOrNull { it["role"] == "system" }?.let { textOf(it["content"]) }.orEmpty()
        val i = sys.indexOf(dev.pocketide.app.core.Design.KIT_MARK)
        if (i < 0) return ""
        return "\n" + sys.substring(i).substringBefore("\n\n").take(900)
    }

    /** 1.5.185: the app tools are offered when the person's latest request is about PocketIDE itself (SmallLocal.forApp). */
    private fun head(docs: Boolean, messages: List<Map<String, Any?>>): String {
        // 1.5.185: the person's own words, not the project context before them (its file names made every request look like a project's)
        val asked = requestOf(messages.lastOrNull { it["role"] == "user" && textOf(it["content"]).isNotEmpty() }?.let { textOf(it["content"]) }.orEmpty())
        // 1.5.185: the Chat home (no project) is known from its note in the system prompt, which Nano's own prompt replaces
        val chatHome = messages.any { it["role"] == "system" && textOf(it["content"]).contains(dev.pocketide.app.core.ChatHome.LABEL_NOTE) }
        return SmallLocal.system(Tools.smallCatalogue(docs, SmallLocal.forApp(asked), chatHome)) +
            (if (chatHome) "\n" + dev.pocketide.app.core.ChatHome.SMALL_NOTE else "") + kitNote(messages) + "\n\nThe conversation so far, oldest first:\n"
    }
    private const val TAIL = "\nYour reply, one JSON object:"

    private val PASSED = Regex("""(?i)^\s*exit code 0\b""")

    /**
     * 1.5.184 (run 4, task 01): Nano ran the test, it passed, and Nano then edited the fix back out. When the newest result is a
     * passing command after a change, the prompt says so and asks for the answer.
     */
    fun passedNote(messages: List<Map<String, Any?>>): String {
        val last = messages.lastOrNull()?.takeIf { it["role"] == "tool" } ?: return ""
        if (!PASSED.containsMatchIn(textOf(last["content"]))) return ""
        val calls = messages.filter { it["role"] == "assistant" }.flatMap { (it["tool_calls"] as? List<*>).orEmpty().mapNotNull { tc -> callOf(tc) } }
        if (calls.lastOrNull()?.name != "run_command" || calls.none { it.name in WRITES }) return ""
        return "NOTE: the check passed after your change. If the request is done, reply with the answer action now and do not change the files again.\n"
    }

    /** True when [call] is an edit_file that swaps back an earlier edit of the same file in this request (its search and replace reversed). */
    fun undoes(call: ToolCall, messages: List<Map<String, Any?>>): Boolean {
        if (call.name != "edit_file") return false
        val a = args(call.arguments)
        val (path, search, replace) = Triple(a["path"]?.toString(), a["search"]?.toString(), a["replace"]?.toString())
        if (path == null || search == null || replace == null) return false
        return messages.filter { it["role"] == "assistant" }.flatMap { (it["tool_calls"] as? List<*>).orEmpty().mapNotNull { tc -> callOf(tc) } }
            .filter { it.name == "edit_file" }.map { args(it.args) }
            .any { it["path"]?.toString() == path && it["search"]?.toString() == replace && it["replace"]?.toString() == search }
    }

    /** True while PocketIDE is the app on screen. */
    fun foreground(ctx: Context): Boolean = try {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    } catch (e: Exception) { true }

    /** Waits (blocking) until PocketIDE is on screen again, up to [maxMs]. */
    fun waitForeground(ctx: Context, maxMs: Long): Boolean {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) { if (foreground(ctx)) return true; Thread.sleep(1000) }
        return foreground(ctx)
    }
    private val FAILED = Regex("""(?i)^\s*(NOT SAVED|Not saved|Not written|Edit not applied|Error\b|Could not|Failed|No such file|Unknown tool|Give something|You already read this file)""")

    /**
     * How many of the last tool results in a row failed (refused or not applied writes, errors). The agent loop's own notes in
     * between (user messages such as "From now on you have a few plain tools") do not break the run; tool results only exist
     * inside the current request, since earlier requests reach the model as text.
     */
    fun failStreak(messages: List<Map<String, Any?>>): Int {
        var n = 0
        for (m in messages.asReversed()) {
            if (m["role"] == "tool") { if (FAILED.containsMatchIn(textOf(m["content"]))) n++ else return n }
        }
        return n
    }

    /** True when [call] is the same tool with the same arguments as an earlier call in this request whose result was a failure. */
    fun repeatsFailure(call: ToolCall, messages: List<Map<String, Any?>>): Boolean {
        val failed = messages.filter { it["role"] == "tool" && FAILED.containsMatchIn(textOf(it["content"])) }.mapNotNull { it["tool_call_id"] as? String }.toSet()
        if (failed.isEmpty()) return false
        val want = args(call.arguments)
        return messages.filter { it["role"] == "assistant" }.flatMap { (it["tool_calls"] as? List<*>).orEmpty().mapNotNull { tc -> callOf(tc) } }
            .any { it.id in failed && it.name == call.name && args(it.args) == want }
    }

    private fun lastFailure(messages: List<Map<String, Any?>>): String =
        messages.lastOrNull { it["role"] == "tool" }?.let { textOf(it["content"]).lineSequence().first().take(100) }.orEmpty()

    private val WRITES = setOf("write_file", "edit_file", "edit_lines", "multi_edit", "apply_patch", "delete_file")
    /** "I created / have updated / fixed …", or "… has been added": a claim that files changed. */
    private val CLAIM = Regex("""(?i)\b(I\s+(have\s+|'ve\s+)?|(has|have)\s+been\s+)(created|updated|wrote|written|modified|added|saved|edited|fixed|implemented|changed|built|made)\b""")

    /** The conversation as small local model mode expects it; null when it does not fit in [limit] characters. */
    fun phonePrompt(docs: Boolean, messages: List<Map<String, Any?>>, limit: Int): String? {
        val head = head(docs, messages)
        val tail = TAIL
        val lastTool = if (messages.lastOrNull()?.get("role") == "tool") messages.size - 1 else -1
        val room = resultRoom(docs, messages, limit)
        val lines = messages.mapIndexedNotNull { i, m -> line(m, if (i == lastTool) room else 2000) }
        if (lines.isEmpty()) return null
        val first = lines.first()
        var budget = limit - head.length - tail.length - first.length - 40
        if (budget < 0) return null
        val recent = ArrayList<String>()
        for (l in lines.drop(1).asReversed()) {
            if (l.length > budget) break
            recent.add(0, l); budget -= l.length
        }
        val skipped = lines.size - 1 - recent.size
        return head + first + (if (skipped > 0) "[$skipped earlier steps left out]\n" else "") + recent.joinToString("") + passedNote(messages) + tail
    }

    private fun line(m: Map<String, Any?>, room: Int = 2000): String? {
        val text = textOf(m["content"])
        return when (m["role"]) {
            "user" -> if (text.isEmpty()) null else "PERSON: ${clip(text, 3000)}\n"
            "assistant" -> {
                val calls = (m["tool_calls"] as? List<*>).orEmpty().mapNotNull { callOf(it) }
                val acts = calls.joinToString("") { c -> "YOU: {\"tool\": ${MiniJson.write(c.name)}, \"tool_args\": ${clip(c.args, 1500)}}\n" }
                val said = if (text.isNotEmpty() && calls.isEmpty())
                    "YOU: {\"tool\": \"answer\", \"tool_args\": {\"text\": ${MiniJson.write(clip(text, 1500))}}}\n" else ""
                (said + acts).ifEmpty { null }
            }
            "tool" -> "RESULT: ${clip(text, room)}\n"
            else -> null   // the system prompt is replaced by small local model mode's own
        }
    }

    /** Nano's reply as a turn, or null and why it cannot be used. */
    fun phoneTurn(reply: String, messages: List<Map<String, Any?>>): Pair<ToolTurn?, String> {
        if (!SmallLocal.isAction(reply)) return null to "did not reply with one JSON action"
        val p = SmallLocal.parse(reply) { Tools.paramTypes(it) }
        if (p.calls.isNotEmpty()) {
            val c = p.calls[0]
            // insert_lines (Nano's own tool) is edit_lines inserting after a line: start = after + 1, end = after
            if (c.name == "insert_lines") {
                val a = MiniJson.obj(MiniJson.parse(c.arguments)) ?: emptyMap<String, Any?>()
                val after = (a["after"] as? Number)?.toInt() ?: a["after"]?.toString()?.toIntOrNull() ?: return null to "gave insert_lines no line number"
                val args = MiniJson.write(mapOf("path" to a["path"], "start" to after + 1, "end" to after, "text" to (a["text"] ?: "")))
                return ToolTurn(p.text, listOf(ToolCall(c.id, "edit_lines", args)), "tool_calls") to ""
            }
            if (Tools.paramTypes(c.name) == null) {
                val keys = (MiniJson.obj(MiniJson.parse(c.arguments)) ?: emptyMap<String, Any?>()).keys
                val known = toolFromArgs(c.name, keys) ?: return null to "asked for a tool that does not exist (${c.name})"
                return ToolTurn(p.text, listOf(ToolCall(c.id, known, c.arguments)), "tool_calls") to ""
            }
            return ToolTurn(p.text, p.calls, "tool_calls") to ""
        }
        if (dev.pocketide.app.core.HybridRoute.refused(p.text)) return null to "said it could not do it"
        val wrote = messages.any { m -> (m["tool_calls"] as? List<*>).orEmpty().any { callOf(it)?.name in WRITES } }
        if (!wrote && CLAIM.containsMatchIn(p.text)) return null to "said it changed files, but no file was written"
        if (p.text.isBlank()) return null to "gave an empty answer"
        if (answersATaskUndone(messages)) return null to "answered without doing anything the request asked for"
        return ToolTurn(p.text, emptyList(), "stop") to ""
    }

    /**
     * 1.5.185 (from-scratch run 1125-1218, s10): after Build this as a project, "Build what we discussed above." in an empty project got
     * Nano's "I have provided the JavaScript function… save it in a file" and the request ended with nothing built. A request to do
     * something (not a question or a report) that Nano answers before any tool call in it is passed on (in Private mode, asked again).
     * Not in the Chat home, where answering is the job.
     */
    fun answersATaskUndone(messages: List<Map<String, Any?>>): Boolean {
        if (messages.any { it["role"] == "system" && textOf(it["content"]).contains(dev.pocketide.app.core.ChatHome.LABEL_NOTE) }) return false
        // the person's message (the app's later notes are user messages too, without "My request:")
        val start = messages.indexOfLast { it["role"] == "user" && textOf(it["content"]).contains("My request:") }
            .let { if (it >= 0) it else messages.indexOfLast { m -> m["role"] == "user" } }
        if (start < 0) return false
        if (messages.drop(start + 1).any { (it["tool_calls"] as? List<*>).orEmpty().isNotEmpty() || it["role"] == "tool" }) return false
        val ask = requestOf(textOf(messages[start]["content"])).trim()
        return dev.pocketide.app.core.Intent.of(ask) == dev.pocketide.app.core.Intent.Kind.TASK && !dev.pocketide.app.core.AppHelp.aboutApp(ask)
    }


    // ---------------- cloud: Firebase AI Logic function calling ----------------

    /** PocketIDE's tools (OpenAI-style, from Tools.body) as Firebase function declarations. */
    fun declarations(tools: List<*>?): List<FunctionDeclaration> = tools.orEmpty().mapNotNull { t ->
        val f = (t as? Map<*, *>)?.get("function") as? Map<*, *> ?: return@mapNotNull null
        val name = f["name"] as? String ?: return@mapNotNull null
        val params = f["parameters"] as? Map<*, *>
        val props = (params?.get("properties") as? Map<*, *>).orEmpty()
        val required = (params?.get("required") as? List<*>).orEmpty().map { it.toString() }.toSet()
        val schemas = LinkedHashMap<String, Schema>()
        for ((k, v) in props) schemas[k.toString()] = schema((v as? Map<*, *>) ?: emptyMap<String, Any?>())
        FunctionDeclaration(name, (f["description"] as? String).orEmpty().ifBlank { name }, schemas, schemas.keys.filter { it !in required })
    }

    /** A JSON schema (as the tool list writes it) as a Firebase schema. Free-form objects are passed as JSON text. */
    fun schema(m: Map<*, *>): Schema {
        val d = (m["description"] as? String).orEmpty()
        val type = when (val t = m["type"]) {
            is String -> t
            is List<*> -> t.firstOrNull { it != "null" }?.toString()
            else -> null
        }
        val values = (m["enum"] as? List<*>)?.map { it.toString() }
        return when (type) {
            "string" -> if (!values.isNullOrEmpty()) Schema.enumeration(values, d) else Schema.string(d)
            "integer" -> Schema.integer(d)
            "number" -> Schema.double(d)
            "boolean" -> Schema.boolean(d)
            "array" -> Schema.array(schema((m["items"] as? Map<*, *>) ?: mapOf("type" to "string")), d)
            "object" -> {
                val props = (m["properties"] as? Map<*, *>).orEmpty()
                if (props.isEmpty()) Schema.string((d + " (a JSON object, written as text)").trim())
                else {
                    val req = (m["required"] as? List<*>).orEmpty().map { it.toString() }.toSet()
                    val ps = props.entries.associate { it.key.toString() to schema((it.value as? Map<*, *>) ?: emptyMap<String, Any?>()) }
                    Schema.obj(ps, ps.keys.filter { it !in req }, d)
                }
            }
            else -> Schema.string(d)
        }
    }

    /**
     * Gemini 3 wants each function call sent back exactly as it came, thought signature included, so the SDK's own call
     * objects are kept by the call id the agent sees. Calls the cloud did not make (Gemini Nano's, on the phone) are sent
     * back as plain text instead, with their results.
     */
    private val made = object : LinkedHashMap<String, FunctionCallPart>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FunctionCallPart>?): Boolean = size > 1000
    }
    private val ids = AtomicLong()

    /** The agent's messages (OpenAI style) as Firebase contents; the system prompt goes in the model's system instruction. */
    fun contents(messages: List<Map<String, Any?>>): List<Content> {
        val names = HashMap<String, String>()
        val asText = HashSet<String>()
        val out = ArrayList<Content>()
        var pending = ArrayList<Part>()
        // 1.5.184 (hard run 3): gemini-3.8-flash, gemini-3.5-flash-lite and gemini-4-hydrogen refuse the old "function" role
        // ("Role 'function' is not supported. Please use a valid role: user, model."); tool results go back as the user's turn
        fun flush() { if (pending.isNotEmpty()) { out.add(Content("user", pending)); pending = ArrayList() } }
        for (m in messages) {
            when (m["role"]) {
                "user" -> {
                    flush()
                    val t = textOf(m["content"])
                    if (t.isNotEmpty()) out.add(Content("user", listOf(TextPart(t))))
                }
                "assistant" -> {
                    flush()
                    val parts = ArrayList<Part>()
                    val t = textOf(m["content"])
                    if (t.isNotEmpty()) parts.add(TextPart(t))
                    for (tc in (m["tool_calls"] as? List<*>).orEmpty()) {
                        val c = callOf(tc) ?: continue
                        names[c.id] = c.name
                        val part = synchronized(made) { made[c.id] }
                        if (part != null) parts.add(part)
                        else { asText.add(c.id); parts.add(TextPart("[I ran ${c.name} with ${clip(c.args, 1500)}]")) }
                    }
                    if (parts.isNotEmpty()) out.add(Content("model", parts))
                }
                "tool" -> {
                    val id = m["tool_call_id"] as? String ?: ""
                    val result = textOf(m["content"])
                    if (id in asText) { flush(); out.add(Content("user", listOf(TextPart("[Result of ${names[id] ?: "the tool"}]\n" + result)))) }
                    else {
                        val part = synchronized(made) { made[id] }
                        pending.add(FunctionResponsePart(names[id] ?: part?.name ?: "tool", JsonObject(mapOf("result" to JsonPrimitive(result))), part?.id))
                    }
                }
            }
        }
        flush()
        // turns alternate: neighbouring turns of the same role (a tool result, then the app's note) are joined into one
        val joined = ArrayList<Content>()
        for (c in out) {
            val prev = joined.lastOrNull()
            if (prev != null && prev.role == c.role) joined[joined.size - 1] = Content(c.role, prev.parts + c.parts) else joined.add(c)
        }
        return joined
    }

    /** For tests: [part] as if the cloud model had sent it as call [id]. */
    internal fun remember(id: String, part: FunctionCallPart) { synchronized(made) { made[id] = part } }

    /** The cloud reply as a turn for the agent. */
    fun turn(r: GenerateContentResponse): ToolTurn {
        val cand = r.candidates.firstOrNull()
        val text = cand?.content?.parts.orEmpty().filterIsInstance<TextPart>().joinToString("") { it.text }.trim()
        val calls = r.functionCalls.map { fc ->
            val id = "hy${ids.incrementAndGet()}_${System.currentTimeMillis()}"
            synchronized(made) { made[id] = fc }
            ToolCall(id, fc.name, JsonObject(fc.args).toString())
        }
        val stop = when {
            calls.isNotEmpty() -> "tool_calls"
            cand?.finishReason?.name == "MAX_TOKENS" -> "length"
            else -> "stop"
        }
        return ToolTurn(text, calls, stop)
    }

    // ---------------- shared ----------------

    private class Call(val id: String, val name: String, val args: String)

    private fun callOf(tc: Any?): Call? {
        val m = tc as? Map<*, *> ?: return null
        val f = m["function"] as? Map<*, *> ?: return null
        val name = f["name"] as? String ?: return null
        return Call((m["id"] as? String).orEmpty(), name, (f["arguments"] as? String).orEmpty().ifBlank { "{}" })
    }

    private fun args(json: String): Map<String, JsonElement> =
        try { Json.parseToJsonElement(json).jsonObject } catch (e: Exception) { emptyMap() }

    /** The where-it-ran line the app adds to replies; left out when the conversation is sent back to a model. */
    private val LABEL = Regex("""(?m)^[ \t]*((⚡|☁) Ran (on this phone|in the cloud).*$\n?(Sent to the cloud:.*$\n?)?|⚡ Gemini Nano \(on this phone\): I'm passing this to.*$\n?|☁ Passing this to the strongest cloud model.*$\n?)""")

    private fun textOf(c: Any?): String {
        val raw = when (c) {
            null -> ""
            is String -> c
            is List<*> -> c.joinToString("\n") { p -> ((p as? Map<*, *>)?.get("text") as? String).orEmpty() }
            else -> c.toString()
        }
        return LABEL.replace(raw, "").trim()
    }

    private fun clip(s: String, n: Int) = if (s.length <= n) s else s.take(n) + " … [cut]"
}
