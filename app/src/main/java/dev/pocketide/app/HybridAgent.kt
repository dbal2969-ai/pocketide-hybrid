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
    class Result(val turn: ToolTurn, val onDevice: Boolean, val seconds: Double, val note: String)

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

    fun phone(ctx: Context, messages: List<Map<String, Any?>>, docs: Boolean): Phone {
        val mode = HybridScreen.mode(ctx)
        if (mode == HybridAi.Mode.PREFER_IN_CLOUD) return Phone(null, "")
        // once a step of this request was passed on, the rest of it stays with that model (Nano would only start over)
        val said = messages.filter { it["role"] == "assistant" }.map { (it["content"] as? String).orEmpty() }
        if (mode == HybridAi.Mode.PREFER_ON_DEVICE && said.any { it.contains(HANDOFF_MARK) })
            return Phone(null, "", stay = true, strongFailed = said.any { it.contains(FALLBACK_MARK) })
        val limit = HybridAi.settings(ctx).onDeviceMaxChars
        val only = mode == HybridAi.Mode.ONLY_ON_DEVICE
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
        if (only) return Phone(Result(ToolTurn(reply.trim(), emptyList(), "stop"), true, secs, "Gemini Nano $why; this mode never uses the cloud."), "")
        return Phone(null, "Gemini Nano $why")
    }

    /** The turn on the cloud model through Firebase AI Logic, with every tool as a function. Blocking. */
    fun cloud(ctx: Context, system: String, messages: List<Map<String, Any?>>, tools: List<*>?): Result {
        testCloud?.let { f -> return Result(f(system, messages, tools), false, 0.5, "") }
        val t0 = System.nanoTime()
        val model = HybridAi.cloudToolModel(ctx, system, declarations(tools))
        val r = HybridCloud.traced("hybrid_cloud") { runBlocking { model.generateContent(contents(messages)) } }
        return Result(turn(r), false, (System.nanoTime() - t0) / 1e9, "")
    }

    /** One agent turn, phone first, then the cloud model. Blocking. [tools] is the OpenAI-style tool list (Tools.body). */
    fun run(ctx: Context, system: String, messages: List<Map<String, Any?>>, docs: Boolean, tools: List<*>?): Result {
        val p = phone(ctx, messages, docs)
        p.result?.let { return it }
        val c = cloud(ctx, system, messages, tools)
        return if (p.reason.isEmpty()) c else Result(c.turn, false, c.seconds, "Sent to the cloud: ${p.reason}.")
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

    /** Room for the newest tool result in Nano's prompt: what [limit] leaves after the instructions, the request and a little more. */
    fun resultRoom(docs: Boolean, messages: List<Map<String, Any?>>, limit: Int): Int {
        val first = messages.firstNotNullOfOrNull { line(it) }.orEmpty()
        return maxOf(2000, limit - head(docs).length - TAIL.length - first.length - 1500)
    }

    private fun head(docs: Boolean) = SmallLocal.system(Tools.smallCatalogue(docs)) + "\n\nThe conversation so far, oldest first:\n"
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
        val head = head(docs)
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
            if (Tools.paramTypes(c.name) == null) return null to "asked for a tool that does not exist (${c.name})"
            return ToolTurn(p.text, p.calls, "tool_calls") to ""
        }
        if (dev.pocketide.app.core.HybridRoute.refused(p.text)) return null to "said it could not do it"
        val wrote = messages.any { m -> (m["tool_calls"] as? List<*>).orEmpty().any { callOf(it)?.name in WRITES } }
        if (!wrote && CLAIM.containsMatchIn(p.text)) return null to "said it changed files, but no file was written"
        if (p.text.isBlank()) return null to "gave an empty answer"
        return ToolTurn(p.text, emptyList(), "stop") to ""
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
        fun flush() { if (pending.isNotEmpty()) { out.add(Content("function", pending)); pending = ArrayList() } }
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
        return out
    }

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
    private val LABEL = Regex("""(?m)^[ \t]*((⚡|☁) Ran (on this phone|in the cloud).*$\n?(Sent to the cloud:.*$\n?)?|⚡ Gemini Nano \(on this phone\): I'm passing this to.*$\n?)""")

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
