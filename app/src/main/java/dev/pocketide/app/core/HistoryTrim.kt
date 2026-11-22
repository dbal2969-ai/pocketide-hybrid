package dev.pocketide.app.core

/**
 * Keeps a long tool-calling run from outgrowing the model's context: when the conversation gets big, old tool results and the
 * text of files written long ago are shortened (the newest messages stay whole). The model can read a file again if it needs it.
 */
object HistoryTrim {
    const val BUDGET = 90_000
    const val KEEP_LAST = 10
    private const val SHORT = 300
    private const val NOTE = "\n[older output removed to save space]"

    private fun cut(s: String): String = if (s.length <= SHORT) s else s.take(150) + NOTE

    private const val PLACEHOLDER = "[old text not kept:"

    /** True when [s] holds the note this file puts where old text was shortened. A model sometimes copies it into a new file or command. */
    fun isPlaceholder(s: String): Boolean = s.contains(PLACEHOLDER) || s.contains("[long text removed:") || s.contains("[older output removed to save space]") || s.contains("[older screenshot removed")

    const val PLACEHOLDER_REFUSAL = "Refused: that is a note the app wrote in place of old text. It is not file content, so nothing was written or run. " +
        "Call read_file for the file to get its real text, then write the complete content yourself."

    private fun shrinkInput(v: Any?): Any? = when (v) {
        is String -> if (v.length > SHORT) "$PLACEHOLDER ${v.length} characters; not file content, do not copy]" else v
        is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to shrinkInput(x) }
        is List<*> -> v.map { shrinkInput(it) }
        else -> v
    }

    /** What one image costs in the budget, in characters (about a thousand tokens), instead of the length of its data. */
    const val IMAGE_COST = 4000

    private fun isImage(v: Any?): Boolean { val t = (v as? Map<*, *>)?.get("type"); return t == "image" || t == "image_url" }

    private fun size(m: Map<String, Any?>): Int {
        var n = 0
        fun add(v: Any?) {
            if (isImage(v)) { n += IMAGE_COST; return }
            when (v) { is String -> n += v.length; is Map<*, *> -> v.values.forEach { add(it) }; is List<*> -> v.forEach { add(it) } }
        }
        add(m)
        return n
    }

    const val SCREENSHOT_GONE = "[older screenshot removed to save space; call look_at_preview again if you need to see the page now]"

    /**
     * Screenshots from look_at_preview, except the newest [keep], are replaced by a short note. They were sent again with
     * every later request (about a thousand tokens each); the page has changed since anyway. Pictures the person attached
     * are kept: only messages that mention look_at_preview count as screenshots. Returns how many images were removed.
     */
    fun dropOldScreenshots(messages: MutableList<Map<String, Any?>>, keep: Int = 1): Int {
        var seen = 0
        var removed = 0
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            val parts = m["content"] as? List<*> ?: continue
            if (m["role"] != "user" || parts.none { isImage(it) }) continue
            val text = parts.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString(" ")
            if (!text.contains("look_at_preview") && !text.contains("here is the page as it is now")) continue
            seen++
            if (seen <= keep) continue
            removed += parts.count { isImage(it) }
            val kept = parts.filterNot { isImage(it) }
            messages[i] = m + ("content" to (listOf(mapOf("type" to "text", "text" to SCREENSHOT_GONE)) + kept))
        }
        return removed
    }

    fun total(messages: List<Map<String, Any?>>): Int = messages.sumOf { size(it) }

    private fun shrink(m: Map<String, Any?>): Map<String, Any?> {
        val role = m["role"]
        val content = m["content"]
        if (role == "tool" && content is String) return m + ("content" to cut(content))
        if (role == "user" && content is List<*>) {
            return m + ("content" to content.map { b ->
                val blk = MiniJson.obj(b)
                if (blk != null && blk["type"] == "tool_result" && blk["content"] is String) blk + ("content" to cut(blk["content"] as String)) else b
            })
        }
        if (role == "assistant") {
            var out = m
            val calls = m["tool_calls"] as? List<*>
            if (calls != null) {
                out = out + ("tool_calls" to calls.map { c ->
                    val call = MiniJson.obj(c) ?: return@map c
                    val fn = MiniJson.obj(call["function"]) ?: return@map c
                    val args = fn["arguments"] as? String ?: return@map c
                    if (args.length <= SHORT) return@map c
                    val parsed = try { MiniJson.obj(MiniJson.parse(args)) } catch (e: Exception) { null } ?: return@map c
                    call + ("function" to (fn + ("arguments" to MiniJson.write(shrinkInput(parsed)))))
                })
            }
            if (content is List<*>) {
                out = out + ("content" to content.map { b ->
                    val blk = MiniJson.obj(b)
                    if (blk != null && blk["type"] == "tool_use" && blk["input"] != null) blk + ("input" to shrinkInput(blk["input"])) else b
                })
            }
            return out
        }
        return m
    }

    /** True when [compact] would shorten something. */
    fun needed(messages: List<Map<String, Any?>>, budget: Int = BUDGET, keepLast: Int = KEEP_LAST): Boolean =
        messages.size > keepLast && total(messages) > budget

    /** Shortens old messages in place when the conversation is over [budget] characters. Returns how many messages were changed. */
    fun compact(messages: MutableList<Map<String, Any?>>, budget: Int = BUDGET, keepLast: Int = KEEP_LAST): Int {
        dropOldScreenshots(messages)
        if (messages.size <= keepLast || total(messages) <= budget) return 0
        var changed = 0
        for (i in 0 until messages.size - keepLast) {
            val before = messages[i]
            val after = shrink(before)
            if (after !== before && after != before) { messages[i] = after; changed++ }
        }
        return changed
    }
}
