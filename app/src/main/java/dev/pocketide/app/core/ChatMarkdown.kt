package dev.pocketide.app.core

/**
 * Markdown in the assistant's replies (1.5.127). Replies were shown as plain text, so "**Agent**" and "`index.html`" appeared with
 * their marks (found in the emulator test of the Chat home); the Claude and Copilot apps show them formatted. This turns the
 * common parts into plain text plus style ranges, which the chat view applies as Android spans. Pure logic, so it is tested here.
 *
 * Covered: fenced code blocks (an unclosed fence, while a reply is still arriving, runs to the end), headings, bullets, numbered
 * lists and quotes at the start of a line, and inline `code`, **bold**, __bold__, *italic*, _italic_ and [links](url). Tables and
 * anything else are left as written.
 */
object ChatMarkdown {
    enum class Kind { BOLD, ITALIC, CODE, CODE_BLOCK, HEADING, LINK, QUOTE }
    data class Span(val start: Int, val end: Int, val kind: Kind, val url: String = "")
    class Doc(val text: String, val spans: List<Span>)

    private val FENCE = Regex("""^\s*(```|~~~)""")
    private val HEAD = Regex("""^\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val QUOTE = Regex("""^\s*>\s?(.*)$""")
    private val RULE = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")

    fun parse(src: String): Doc {
        val out = StringBuilder()
        val spans = ArrayList<Span>()
        val lines = src.replace("\r\n", "\n").split("\n")
        var i = 0
        var first = true
        fun newline() { if (!first) out.append('\n'); first = false }
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.find(line)
            if (fence != null) {
                val mark = fence.groupValues[1]
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(mark)) { body.add(lines[i]); i++ }
                i++   // the closing fence, or past the end while the reply is still arriving
                newline()
                val start = out.length
                out.append(body.joinToString("\n"))
                if (out.length > start) spans.add(Span(start, out.length, Kind.CODE_BLOCK))
                continue
            }
            newline()
            when {
                RULE.matches(line) -> out.append("―――")
                HEAD.matches(line) -> {
                    val start = out.length
                    inline(HEAD.matchEntire(line)!!.groupValues[1], out, spans)
                    spans.add(Span(start, out.length, Kind.HEADING))
                }
                BULLET.matches(line) && !RULE.matches(line) -> {
                    val m = BULLET.matchEntire(line)!!
                    out.append(m.groupValues[1]).append("• ")
                    inline(m.groupValues[2], out, spans)
                }
                QUOTE.matches(line) -> {
                    val start = out.length
                    inline(QUOTE.matchEntire(line)!!.groupValues[1], out, spans)
                    spans.add(Span(start, out.length, Kind.QUOTE))
                }
                else -> inline(line, out, spans)
            }
            i++
        }
        return Doc(out.toString(), spans)
    }

    /** One line's inline marks. A mark with no closing partner is left as written. */
    private fun inline(s: String, out: StringBuilder, spans: MutableList<Span>) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) {
                    val start = out.length
                    out.append(s, i + 1, end)
                    spans.add(Span(start, out.length, Kind.CODE))
                    i = end + 1; continue
                }
            }
            if ((c == '*' || c == '_') && s.startsWith("$c$c", i)) {
                val end = s.indexOf("$c$c", i + 2)
                // __ only at word edges, so names like __init__.py stay as written
                val edges = c == '*' || ((i == 0 || !s[i - 1].isLetterOrDigit()) &&
                    (end + 2 >= s.length || !(s[end + 2].isLetterOrDigit() || s[end + 2] == '.')))
                if (end > i + 2 && edges && !s[i + 2].isWhitespace() && !s[end - 1].isWhitespace()) {
                    val start = out.length
                    inline(s.substring(i + 2, end), out, spans)
                    spans.add(Span(start, out.length, Kind.BOLD))
                    i = end + 2; continue
                }
            }
            if (c == '*' || c == '_') {
                // a single mark: not inside a word for _, not followed by a space, and closed before the line ends
                val opens = i + 1 < s.length && !s[i + 1].isWhitespace() && s[i + 1] != c &&
                    (c == '*' || i == 0 || !(s[i - 1].isLetterOrDigit() || s[i - 1] == '_'))
                if (opens) {
                    var end = i + 1
                    while (true) {
                        end = s.indexOf(c, end)
                        if (end < 0) break
                        val closes = !s[end - 1].isWhitespace() && (end + 1 >= s.length || s[end + 1] != c) &&
                            (c == '*' || end + 1 >= s.length || !(s[end + 1].isLetterOrDigit() || s[end + 1] == '.'))
                        if (closes) break
                        end++
                    }
                    if (end > i + 1) {
                        val start = out.length
                        inline(s.substring(i + 1, end), out, spans)
                        spans.add(Span(start, out.length, Kind.ITALIC))
                        i = end + 1; continue
                    }
                }
            }
            if (c == '[') {
                val close = s.indexOf("](", i + 1)
                val paren = if (close > i) s.indexOf(')', close + 2) else -1
                if (close > i + 1 && paren > close + 2 && s.indexOf('\n', i) !in (i until paren)) {
                    val start = out.length
                    out.append(s, i + 1, close)
                    spans.add(Span(start, out.length, Kind.LINK, s.substring(close + 2, paren).trim()))
                    i = paren + 1; continue
                }
            }
            out.append(c); i++
        }
    }
}
