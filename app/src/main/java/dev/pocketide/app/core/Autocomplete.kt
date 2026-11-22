package dev.pocketide.app.core

/** Prompt building and reply cleanup for inline AI completion. Pure, so it is JVM-testable. */
object Autocomplete {
    const val SIDE = 3000
    const val MAX_LINES = 12
    const val MAX_CHARS = 800
    const val CURSOR = "<CURSOR>"

    val SYSTEM = """
        |You are a code completion engine inside a mobile code editor.
        |You get a file with the marker $CURSOR where the cursor is.
        |Reply with ONLY the text to insert at $CURSOR: no explanations, no markdown fences, no repeated code from before or after the cursor.
        |Complete the current line or statement, at most a few lines. If nothing sensible fits, reply with nothing.
        |""".trimMargin()

    data class Context(val before: String, val after: String)

    /** The text around the cursor, cut to about SIDE characters each way at line boundaries. */
    fun around(text: String, cursor: Int): Context {
        val c = cursor.coerceIn(0, text.length)
        var start = (c - SIDE).coerceAtLeast(0)
        if (start > 0) text.indexOf('\n', start).let { if (it in start until c) start = it + 1 }
        var end = (c + SIDE).coerceAtMost(text.length)
        if (end < text.length) text.lastIndexOf('\n', end).let { if (it > c) end = it }
        return Context(text.substring(start, c), text.substring(c, end))
    }

    fun userMessage(path: String, ctx: Context): String =
        "File: ${path.ifEmpty { "untitled" }}\n\n${ctx.before}$CURSOR${ctx.after}"

    /** Turns a model reply into the text to insert, or "" if there is nothing useful. */
    fun clean(reply: String, ctx: Context): String {
        var s = LocalModels.stripThinking(reply).text.replace("\r", "")
        // a fenced reply: keep the inside of the first fence
        val fence = Regex("""```[\w+-]*\n([\s\S]*?)(?:\n```|$)""").find(s)
        if (fence != null) s = fence.groupValues[1]
        s = s.replace(CURSOR, "")
        // models often echo the current line before the cursor; drop that part
        val linePrefix = ctx.before.substringAfterLast('\n')
        if (linePrefix.isNotBlank()) {
            val trimmedPrefix = linePrefix.trimStart()
            if (s.startsWith(linePrefix)) s = s.substring(linePrefix.length)
            else if (trimmedPrefix.length >= 2 && s.trimStart().startsWith(trimmedPrefix)) s = s.trimStart().substring(trimmedPrefix.length)
        }
        // and the text right after the cursor at the end (a short echo like "}" only when it sits on its own)
        val afterT = ctx.after.trimStart()
        val sT = s.trimEnd()
        for (k in minOf(afterT.length, 200) downTo 1) {
            val piece = afterT.substring(0, k).trimEnd()
            if (piece.isEmpty() || !sT.endsWith(piece)) continue
            val cut = sT.length - piece.length
            val onItsOwn = cut > 0 && (sT[cut - 1] == '\n' || sT[cut - 1] == ' ')
            if (piece.length >= 3 || onItsOwn) { s = sT.substring(0, cut); break }
        }
        // keep it short
        val lines = s.split('\n')
        if (lines.size > MAX_LINES) s = lines.take(MAX_LINES).joinToString("\n")
        if (s.length > MAX_CHARS) s = s.substring(0, MAX_CHARS)
        s = s.trimEnd(' ', '\t', '\n')
        // a reply that is only an apology or explanation is not code
        if (s.isBlank()) return ""
        val lower = s.lowercase()
        if (lines.size <= 2 && (lower.startsWith("sorry") || lower.startsWith("i can") || lower.startsWith("i'm") || lower.startsWith("here is") || lower.startsWith("here's"))) return ""
        return s
    }
}
