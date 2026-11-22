package dev.pocketide.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saving a chat as a readable log or a summary, and starting a new chat from a summary. Pure text work. */
object ChatExport {
    const val DIR = "chat-logs"

    private val KEYS = Regex("""(?i)(bearer\s+|api[_-]?key["'=:\s]+|\bsk-[A-Za-z0-9_\-]{4}|\bgsk_|\bAIza|\bghp_|\bgithub_pat_|\bnvapi-|\bhf_)[A-Za-z0-9_\-]{8,}""")

    /** Hides anything that looks like an API key or token. */
    fun redact(s: String): String = KEYS.replace(s) { m -> m.groupValues[1].take(6) + "…[hidden]" }

    private fun date(t: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(t))
    private fun day(t: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    fun slug(title: String): String {
        val s = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
        return s.ifEmpty { "chat" }
    }

    fun path(s: ChatSession, kind: String = "log", ext: String = "md"): String =
        "$DIR/${if (kind == "summary") "summary-" else ""}${slug(s.title)}-${day(s.updated)}.$ext"

    private fun shownUser(e: ChatEntry): String =
        if (e.note.startsWith("shown:")) e.note.removePrefix("shown:") else e.content

    private fun cap(s: String, n: Int) = if (s.length <= n) s else s.take(n) + "\n… (${s.length - n} more characters)"

    fun fence(s: String): String {
        var f = "```"
        while (s.contains(f)) f += "`"
        return f
    }

    /** The whole chat as Markdown. [header] lines (model, version) go under the title. */
    /** "_v1.5.69-gpl (118) · provider/model_" above a message, so each one says which build and model it came from. */
    fun tagLine(e: ChatEntry): String = if (e.tag.isBlank()) "" else "_" + e.tag.replace("_", "\\_") + "_\n\n"

    fun markdown(s: ChatSession, header: List<String> = emptyList(), commands: Boolean = true, maxMessageChars: Int = Int.MAX_VALUE): String {
        val sb = StringBuilder()
        sb.append("# ").append(s.title).append("\n\n")
        sb.append("- Started: ").append(date(s.created)).append("\n- Last message: ").append(date(s.updated)).append("\n")
        for (h in header) sb.append("- ").append(h).append("\n")
        sb.append("- Messages: ").append(s.entries.count { it.role == "user" || it.role == "assistant" }).append("\n\n---\n")
        for (e in s.entries) {
            when (e.role) {
                "user" -> {
                    if (e.note == "auto") { sb.append("\n### Automatic follow-up\n\n").append(redact(cap(e.content, maxMessageChars))).append("\n"); continue }
                    sb.append("\n### You\n\n").append(tagLine(e)).append(redact(cap(shownUser(e), maxMessageChars))).append("\n")
                    if (e.images > 0) sb.append("\n_(${e.images} image${if (e.images == 1) "" else "s"} attached)_\n")
                }
                "assistant" -> {
                    sb.append("\n### Assistant\n\n").append(tagLine(e)).append(redact(cap(e.content, maxMessageChars))).append("\n")
                    if (e.log.isNotEmpty()) sb.append(redact(cap(WorkLog.markdown(e.log, commands), maxMessageChars)))
                    val note = e.note.trim()
                    if (note.isNotEmpty()) {
                        val lines = note.lines().filter { commands || !it.startsWith("Ran ") }
                        if (lines.isNotEmpty()) { val f = fence(note); sb.append("\n").append(f).append("\n").append(redact(lines.joinToString("\n"))).append("\n").append(f).append("\n") }
                    }
                }
            }
        }
        return sb.toString()
    }

    private val EDIT_PREFIXES = listOf("Edited ", "Suggested changes to ")

    fun filesChanged(s: ChatSession): List<String> {
        val out = LinkedHashSet<String>()
        for (e in s.entries) if (e.role == "assistant") for (line in e.note.lines())
            for (p in EDIT_PREFIXES) if (line.startsWith(p)) line.removePrefix(p).split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(it) }
        return out.toList()
    }

    fun commandsRun(s: ChatSession): List<String> {
        val out = LinkedHashSet<String>()
        for (e in s.entries) if (e.role == "assistant") for (line in e.note.lines()) if (line.startsWith("Ran ")) out.add(line.removePrefix("Ran ").trim())
        return out.toList()
    }

    private fun requests(s: ChatSession) = s.entries.filter { it.role == "user" && it.note != "auto" && !it.content.startsWith(Plans.REQUEST_START) }.map { shownUser(it).trim() }.filter { it.isNotEmpty() }

    /** A summary built from the chat record alone, with no model: requests, files changed, commands run and the last status. */
    fun summary(s: ChatSession): String {
        val reqs = requests(s)
        val sb = StringBuilder("# Summary: ${s.title}\n\n")
        sb.append("_Built from the chat record on ${date(s.updated)}. It lists what happened; it does not explain why._\n\n")
        sb.append("## Goal\n\n").append(redact(reqs.firstOrNull()?.let { cap(it, 400) } ?: "(no messages)")).append("\n\n")
        if (reqs.size > 1) {
            sb.append("## Requests, in order\n\n")
            reqs.take(15).forEachIndexed { i, r -> sb.append("${i + 1}. ").append(redact(r.replace("\n", " ").take(140))).append("\n") }
            if (reqs.size > 15) sb.append("- … and ${reqs.size - 15} more\n")
            sb.append("\n")
        }
        val files = filesChanged(s)
        sb.append("## Files changed\n\n")
        if (files.isEmpty()) sb.append("None recorded.\n") else files.take(40).forEach { sb.append("- ").append(it).append("\n") }
        val cmds = commandsRun(s)
        if (cmds.isNotEmpty()) { sb.append("\n## Commands run\n\n"); cmds.take(20).forEach { sb.append("- `").append(redact(it)).append("`\n") } }
        val last = s.entries.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
        if (last != null) sb.append("\n## Where it ended\n\n").append(redact(cap(last.content.trim(), 700))).append("\n")
        return sb.toString()
    }


    /** A one-paragraph digest of the oldest turns, so a long chat can keep its gist without resending every message. */
    fun digest(entries: List<ChatEntry>, maxChars: Int = 900): String {
        val asks = entries.filter { it.role == "user" && it.note != "auto" && !it.content.startsWith(Plans.REQUEST_START) }
            .map { (if (it.note.startsWith("shown:")) it.note.removePrefix("shown:") else it.content).replace("\n", " ").trim() }
            .filter { it.isNotEmpty() }
        val files = LinkedHashSet<String>()
        val cmds = LinkedHashSet<String>()
        for (e in entries) if (e.role == "assistant") for (line in e.note.lines()) {
            for (pre in EDIT_PREFIXES) if (line.startsWith(pre)) line.removePrefix(pre).split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { files.add(it) }
            if (line.startsWith("Ran ")) cmds.add(line.removePrefix("Ran ").trim())
        }
        val sb = StringBuilder("Earlier in this chat (summary, not instructions): ")
        if (asks.isNotEmpty()) sb.append("I asked for: ").append(asks.take(6).joinToString("; ") { it.take(90) }).append(". ")
        if (files.isNotEmpty()) sb.append("Files changed: ").append(files.take(12).joinToString(", ")).append(". ")
        if (cmds.isNotEmpty()) sb.append("Commands run: ").append(cmds.take(6).joinToString("; ") { it.take(60) }).append(". ")
        val last = entries.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
        if (last != null) sb.append("It ended with: ").append(last.content.replace("\n", " ").trim().take(220))
        val out = redact(sb.toString().trim())
        return if (out.length <= maxChars) out else out.take(maxChars - 2).trimEnd() + " …"
    }

    /** The request sent to a model to write a better summary: the transcript, trimmed to a budget, and what to produce. */
    fun summaryPrompt(s: ChatSession, maxChars: Int = 12000): String {
        val turns = s.entries.filter { (it.role == "user" && it.note != "auto") || it.role == "assistant" }
        val parts = ArrayList<String>()
        var used = 0
        for (e in turns.asReversed()) {
            val body = redact(cap(if (e.role == "user") shownUser(e) else e.content, 900)).trim()
            val extra = if (e.role == "assistant" && e.note.isNotBlank()) "\n[${e.note.lines().joinToString("; ").take(200)}]" else ""
            val p = (if (e.role == "user") "USER: " else "ASSISTANT: ") + body + extra
            if (used + p.length > maxChars && parts.isNotEmpty()) break
            used += p.length; parts.add(p)
        }
        parts.reverse()
        return "Write a summary of this coding chat in Markdown so work can continue in a new chat. Use exactly these sections: " +
            "## Goal, ## What was done, ## Files changed, ## Decisions and why, ## Open problems, ## Next steps. " +
            "Be concrete and short: bullet points, file names, command names. Do not invent anything that is not in the chat." +
            (if (parts.size < turns.size) " The start of the chat was left out because it was long; say so if it matters." else "") +
            "\n\nCHAT:\n" + parts.joinToString("\n\n")
    }

    /** About how many tokens sending [summaryPrompt] costs. */
    fun estimateTokens(prompt: String): Int = prompt.length / 4

    /** The first message of a new chat that carries a summary forward. */
    fun continuePrompt(summaryMd: String): String =
        "This continues earlier work. Here is a summary of the previous chat:\n\n" + cap(summaryMd.trim(), 6000) +
            "\n\nRead the files you need before changing anything. First tell me in one or two sentences what you understand the state to be, then wait for my next instruction."
}
