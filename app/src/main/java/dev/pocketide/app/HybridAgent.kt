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

    /** One agent turn. Blocking. [tools] is the OpenAI-style tool list the agent would send (Tools.body). */
    fun run(ctx: Context, system: String, messages: List<Map<String, Any?>>, docs: Boolean, tools: List<*>?): Result {
        val mode = HybridScreen.mode(ctx)
        val limit = HybridAi.settings(ctx).onDeviceMaxChars
        var note = ""
        if (mode != HybridAi.Mode.PREFER_IN_CLOUD) {
            val prompt = phonePrompt(docs, messages, limit)
            if (prompt == null) {
                if (mode == HybridAi.Mode.ONLY_ON_DEVICE)
                    return Result(ToolTurn("This request is longer than the on-phone limit ($limit characters), and this mode never uses the cloud. " +
                        "Raise the limit or change where requests run (Models → Hybrid AI).", emptyList(), "stop"), true, 0.0, "")
                note = "Sent to the cloud: the conversation is longer than the on-phone limit ($limit characters)."
            } else {
                val t0 = System.nanoTime()
                val reply = try { HybridAi.onDeviceText(ctx, prompt) } catch (e: Exception) {
                    if (mode == HybridAi.Mode.ONLY_ON_DEVICE) throw e
                    note = "Sent to the cloud: Gemini Nano could not run (${(e.message ?: e.javaClass.simpleName).take(80)})."
                    null
                }
                val secs = (System.nanoTime() - t0) / 1e9
                if (reply != null) {
                    val (turn, why) = phoneTurn(reply, messages)
                    if (turn != null) return Result(turn, true, secs, "")
                    if (mode == HybridAi.Mode.ONLY_ON_DEVICE)
                        return Result(ToolTurn(reply.trim(), emptyList(), "stop"), true, secs, "Gemini Nano $why; this mode never uses the cloud.")
                    note = "Sent to the cloud: Gemini Nano $why."
                }
            }
        }
        val t0 = System.nanoTime()
        val model = HybridAi.cloudToolModel(ctx, system, declarations(tools))
        val r = runBlocking { model.generateContent(contents(messages)) }
        return Result(turn(r), false, (System.nanoTime() - t0) / 1e9, note)
    }

    // ---------------- phone: small local model mode ----------------

    private val WRITES = setOf("write_file", "edit_file", "edit_lines", "multi_edit", "apply_patch", "delete_file")
    /** "I created / have updated / fixed …", or "… has been added": a claim that files changed. */
    private val CLAIM = Regex("""(?i)\b(I\s+(have\s+|'ve\s+)?|(has|have)\s+been\s+)(created|updated|wrote|written|modified|added|saved|edited|fixed|implemented|changed|built|made)\b""")

    /** The conversation as small local model mode expects it; null when it does not fit in [limit] characters. */
    fun phonePrompt(docs: Boolean, messages: List<Map<String, Any?>>, limit: Int): String? {
        val head = SmallLocal.system(Tools.smallCatalogue(docs)) + "\n\nThe conversation so far, oldest first:\n"
        val tail = "\nYour reply, one JSON object:"
        val lines = messages.mapNotNull { line(it) }
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
        return head + first + (if (skipped > 0) "[$skipped earlier steps left out]\n" else "") + recent.joinToString("") + tail
    }

    private fun line(m: Map<String, Any?>): String? {
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
            "tool" -> "RESULT: ${clip(text, 2000)}\n"
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
    private val LABEL = Regex("""(?m)^[ \t]*(⚡|☁) Ran (on this phone|in the cloud).*$\n?(Sent to the cloud:.*$\n?)?""")

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
