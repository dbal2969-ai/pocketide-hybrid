package dev.pocketide.app.core

class ModelException(message: String) : Exception(message)

/**
 * The text of one streamed delta. Some OpenAI-compatible servers (seen with Cloudflare Workers AI) send a token that is only
 * digits as a JSON number ("content": 3000) instead of a string. Reading only strings dropped those tokens, which left
 * files with `const port =;` and `'px Arial'`. Numbers are taken from the raw line so "3000" and "1.50" stay exact.
 */
object StreamText {
    private val RAW_NUMBER = Regex(""""content"\s*:\s*(-?\d[\d.eE+\-]*)""")

    fun of(v: Any?, payload: String): String = when (v) {
        is String -> v
        is Number -> RAW_NUMBER.find(payload)?.groupValues?.get(1)
            ?: v.toDouble().let { d -> if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString() }
        else -> ""
    }
}

/** Reads the streamed reply of an OpenAI-style or Anthropic-style chat API, one line at a time. */
class SseDecoder(private val anthropic: Boolean) {
    val usage = UsageCollector(anthropic)
    /** Characters of reasoning ("thinking") seen so far; some servers stream it apart from the answer. */
    var reasoningChars = 0
        private set

    /** Returns the text carried by one line of the stream, or "" if the line carries none. Throws on an error event. */
    fun feed(rawLine: String): String {
        val line = rawLine.trim()
        if (!line.startsWith("data:")) return ""
        val payload = line.substring(5).trim()
        if (payload.isEmpty() || payload == "[DONE]") return ""
        val ev = try { MiniJson.obj(MiniJson.parse(payload)) } catch (e: MiniJson.JsonError) { null } ?: return ""
        val err = ev["error"]
        if (err != null) {
            val m = MiniJson.obj(err)?.get("message") ?: err
            throw ModelException(m.toString())
        }
        usage.feed(ev)
        if (anthropic) {
            if (ev["type"] != "content_block_delta") return ""
            return MiniJson.obj(ev["delta"])?.get("text")?.toString() ?: ""
        }
        val first = MiniJson.list(ev["choices"])?.firstOrNull()
        val delta = MiniJson.obj(MiniJson.obj(first)?.get("delta"))
        (delta?.get("reasoning") ?: delta?.get("reasoning_content"))?.let { if (it is String) reasoningChars += it.length }
        return StreamText.of(delta?.get("content"), payload)
    }

    companion object {
        /** Pulls a readable message out of an error response body, or null if there is none. */
        fun errorMessage(body: String): String? {
            // 1.5.178: a web page instead of JSON (Ollama Cloud's edge answered Android's default User-Agent with an HTML 403 page,
            // which the chat showed tag by tag): its text, without the tags
            val t = body.trimStart()
            if (t.startsWith("<") && Regex("(?i)<(!doctype|html|title|body)").containsMatchIn(t)) {
                val text = t.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(200)
                return if (text.isEmpty()) null else "The server sent a web page instead of an answer: $text"
            }
            val root = try { MiniJson.obj(MiniJson.parse(body)) } catch (e: MiniJson.JsonError) { return null } ?: return null
            // Cloudflare answers {"errors":[{"message":"...","code":3040}],"success":false}
            val first = (root["errors"] as? List<*>)?.firstOrNull()
            if (root["error"] == null && first != null) return (MiniJson.obj(first)?.get("message") ?: first).toString()
            val err = root["error"] ?: return null
            return (MiniJson.obj(err)?.get("message") ?: err).toString()
        }
    }
}
