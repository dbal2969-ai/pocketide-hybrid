package dev.pocketide.app.core

/**
 * A summary of the work so far, added when a long request is compacted (old tool output shortened to save space). Copilot
 * and Claude Code summarise a long conversation instead of only cutting it, so the model keeps track of what it did.
 * Two parts: facts read straight from the tool calls (files changed, commands run), which cost nothing, and, when a
 * second (cheaper) model is set, a short written summary from that model.
 */
object WorkSummary {
    const val PROMPT = "You summarise another assistant's work so far so it can continue after its older messages are shortened. " +
        "Do not call tools unless you must check one detail. Reply in under 250 words with: what the person asked for (keep their rules, limits and the sites, files or resources they named), what has been done, " +
        "what was tried and failed (with the error), and what is left to do. Use file names. No preamble."

    private val WRITE_TOOLS = setOf("write_file", "edit_file", "edit_lines", "multi_edit", "apply_patch", "doc_edit")

    /** Tool calls in either message format, as (name, arguments). */
    private fun calls(messages: List<Map<String, Any?>>): List<Pair<String, Map<String, Any?>>> {
        val out = ArrayList<Pair<String, Map<String, Any?>>>()
        for (m in messages) {
            if (m["role"] != "assistant") continue
            (m["content"] as? List<*>)?.forEach { b ->
                val blk = b as? Map<*, *> ?: return@forEach
                if (blk["type"] == "tool_use") @Suppress("UNCHECKED_CAST") out.add((blk["name"] as? String ?: "") to ((blk["input"] as? Map<String, Any?>) ?: emptyMap()))
            }
            (m["tool_calls"] as? List<*>)?.forEach { c ->
                val f = (c as? Map<*, *>)?.get("function") as? Map<*, *> ?: return@forEach
                val args = (f["arguments"] as? String)?.let { a -> try { MiniJson.obj(MiniJson.parse(a)) } catch (e: Exception) { null } } ?: emptyMap()
                out.add((f["name"] as? String ?: "") to args)
            }
        }
        return out
    }

    /** Files the assistant changed and commands it ran, read from its tool calls. */
    fun facts(messages: List<Map<String, Any?>>): String {
        val cs = calls(messages)
        val files = cs.filter { it.first in WRITE_TOOLS }.mapNotNull { it.second["path"] as? String }.distinct()
        val cmds = cs.filter { it.first == "run_command" }.mapNotNull { (it.second["command"] as? String)?.lineSequence()?.firstOrNull()?.take(80) }.distinct()
        val sb = StringBuilder()
        if (files.isNotEmpty()) sb.append("Files changed so far: ").append(files.take(30).joinToString(", ")).append(".\n")
        if (cmds.isNotEmpty()) sb.append("Commands run: ").append(cmds.takeLast(10).joinToString("; ")).append(".\n")
        return sb.toString()
    }

    /** The conversation as plain text for the summariser, oldest first, at most [max] characters (the oldest part is kept, since that is what gets shortened). */
    fun transcript(messages: List<Map<String, Any?>>, max: Int = 30_000): String {
        val sb = StringBuilder()
        fun text(v: Any?, limit: Int): String = when (v) {
            is String -> v.take(limit)
            is List<*> -> v.joinToString(" ") { b ->
                val blk = b as? Map<*, *>
                when (blk?.get("type")) {
                    "text" -> (blk["text"] as? String).orEmpty().take(limit)
                    "tool_use" -> "[called ${blk["name"]} ${MiniJson.write(blk["input"]).take(200)}]"
                    "tool_result" -> "[result: ${text(blk["content"], 300)}]"
                    else -> ""
                }
            }
            else -> ""
        }
        for (m in messages) {
            val role = m["role"] as? String ?: continue
            val body = when {
                role == "tool" -> "[result: ${text(m["content"], 300)}]"
                m["tool_calls"] != null -> text(m["content"], 1500) + " " + (m["tool_calls"] as? List<*>).orEmpty().joinToString(" ") { c ->
                    val f = (c as? Map<*, *>)?.get("function") as? Map<*, *>
                    "[called ${f?.get("name")} ${(f?.get("arguments") as? String).orEmpty().take(200)}]"
                }
                else -> text(m["content"], 1500)
            }.trim()
            if (body.isEmpty()) continue
            sb.append(role).append(": ").append(body).append("\n")
            if (sb.length > max) break
        }
        return sb.toString().take(max)
    }

    /** The message added to the conversation. [written] is the second model's summary, or null. */
    fun note(facts: String, written: String?): String =
        "Older messages in this request were shortened to save space. Summary of the work so far:\n" +
            (written?.trim()?.takeIf { it.isNotEmpty() }?.let { it + "\n" } ?: "") + facts +
            "Continue from here. Read a file again before editing it if you need its current text."
}
