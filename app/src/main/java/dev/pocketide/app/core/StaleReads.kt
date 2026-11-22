package dev.pocketide.app.core

/**
 * Drops file contents the assistant read earlier and has since changed: the old text is wrong now and only costs tokens
 * every round. Works on both OpenAI-style and Anthropic-style message lists.
 */
object StaleReads {
    private val CHANGERS = setOf("write_file", "edit_file", "edit_lines", "multi_edit", "apply_patch", "doc_edit")
    private const val MIN_CHARS = 400
    private const val MIN_SAVED = 1500

    private class Call(val name: String, val path: String, val at: Int)

    private fun stub(path: String) = "[Earlier contents of $path left out: the file was changed afterwards. Read it again if you need it.]"

    private fun pathOf(args: Any?): String = when (args) {
        is Map<*, *> -> (args["path"] as? String).orEmpty()
        is String -> (Tools.parseArgs(args)["path"] as? String).orEmpty()
        else -> ""
    }

    /** Shortens stale read results in place. Returns how many were shortened (0 when little would be saved). */
    fun clear(messages: MutableList<Map<String, Any?>>): Int {
        val calls = HashMap<String, Call>()
        for ((i, m) in messages.withIndex()) {
            if (m["role"] != "assistant") continue
            (m["content"] as? List<*>)?.forEach { b ->
                val o = b as? Map<*, *> ?: return@forEach
                if (o["type"] == "tool_use") calls[o["id"] as? String ?: return@forEach] = Call(o["name"] as? String ?: "", pathOf(o["input"]), i)
            }
            (m["tool_calls"] as? List<*>)?.forEach { tc ->
                val t = tc as? Map<*, *> ?: return@forEach
                val f = t["function"] as? Map<*, *> ?: return@forEach
                calls[t["id"] as? String ?: return@forEach] = Call(f["name"] as? String ?: "", pathOf(f["arguments"]), i)
            }
        }
        val lastChange = HashMap<String, Int>()
        for (c in calls.values) if (c.name in CHANGERS && c.path.isNotEmpty()) lastChange[c.path] = maxOf(lastChange[c.path] ?: -1, c.at)
        if (lastChange.isEmpty()) return 0

        fun stale(id: String?, len: Int): Call? {
            val c = calls[id ?: return null] ?: return null
            if (c.name != "read_file" || c.path.isEmpty() || len < MIN_CHARS) return null
            return c.takeIf { (lastChange[it.path] ?: -1) > it.at }
        }
        // first pass: how much would be saved
        var saved = 0
        for (m in messages) {
            if (m["role"] == "tool") { val s = m["content"] as? String ?: continue; if (stale(m["tool_call_id"] as? String, s.length) != null) saved += s.length }
            (m["content"] as? List<*>)?.forEach { b ->
                val o = b as? Map<*, *> ?: return@forEach
                if (o["type"] == "tool_result") { val s = o["content"] as? String ?: return@forEach; if (stale(o["tool_use_id"] as? String, s.length) != null) saved += s.length }
            }
        }
        if (saved < MIN_SAVED) return 0

        var n = 0
        for (i in messages.indices) {
            val m = messages[i]
            if (m["role"] == "tool") {
                val s = m["content"] as? String ?: continue
                val c = stale(m["tool_call_id"] as? String, s.length) ?: continue
                messages[i] = m + ("content" to stub(c.path)); n++
                continue
            }
            val list = m["content"] as? List<*> ?: continue
            var changed = false
            val out = list.map { b ->
                val o = b as? Map<*, *>
                if (o != null && o["type"] == "tool_result") {
                    val s = o["content"] as? String
                    val c = if (s != null) stale(o["tool_use_id"] as? String, s.length) else null
                    if (c != null) { changed = true; n++; @Suppress("UNCHECKED_CAST") ((o as Map<String, Any?>) + ("content" to stub(c.path))) } else b
                } else b
            }
            if (changed) messages[i] = m + ("content" to out)
        }
        return n
    }
}
