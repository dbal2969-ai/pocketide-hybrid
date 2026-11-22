package dev.pocketide.app.core

/**
 * Code whose line breaks were escaped twice in the tool arguments: `"replace": "require('x');\\nfunction f() {\\n  return 1"`
 * reaches the app holding the two characters backslash and n, and writing it breaks the file. Llama 3.3 70B on the hard suite
 * (1.5.89) broke src/util/text.js this way and then could not repair it.
 *
 * Only a written-out \n outside string quotes counts, scored at code line breaks: one point when it follows one of `;{}():,[`,
 * one when indentation follows it. Two points are needed (one `;\n  return`, or two breaks), so a regex such as `/\n/` or a
 * comment that mentions "\n" is left alone.
 * Only those \n become line breaks; nothing inside quotes and no other escape is touched.
 */
object OverEscaped {
    private val PROSE = setOf("md", "markdown", "txt", "rst", "csv", "tsv", "log")
    private const val BEFORE = ";{}():,["
    private const val MIN_SCORE = 2

    /** Positions of the backslash of each written-out \n outside quotes, and their code line break score. */
    private fun scan(text: String): Pair<List<Int>, Int> {
        val at = ArrayList<Int>()
        var score = 0
        var quote = 0.toChar()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                if (quote == 0.toChar() && text[i + 1] == 'n') {
                    at.add(i)
                    val prev = text.getOrNull(if (i > 0 && text[i - 1] == 'r' && i > 1 && text[i - 2] == '\\') i - 3 else i - 1)
                    val after = text.substring(i + 2, minOf(text.length, i + 4))
                    if (prev != null && prev in BEFORE) score++
                    if (after == "  " || after.startsWith("\t")) score++
                }
                i += 2; continue
            }
            when {
                c == '\n' && quote != '`' -> quote = 0.toChar()
                quote == 0.toChar() && (c == '"' || c == '\'' || c == '`') -> quote = c
                c == quote -> quote = 0.toChar()
            }
            i++
        }
        return at to score
    }

    /** True when [text], for the file at [path], looks like code whose line breaks were escaped twice. */
    fun looks(text: String, path: String): Boolean {
        if (path.substringAfterLast('.', "").lowercase() in PROSE) return false
        return scan(text).second >= MIN_SCORE
    }

    /** [text] with each written-out \n outside quotes (and a \r just before it) turned into a line break. */
    fun decode(text: String): String {
        val at = scan(text).first.toHashSet()
        if (at.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (i in at) {
                if (sb.length >= 2 && sb.endsWith("\\r")) sb.setLength(sb.length - 2)
                sb.append('\n'); i += 2; continue
            }
            sb.append(text[i]); i++
        }
        return sb.toString()
    }

    /** Told to the model after a fix, so it sends real line breaks next time. */
    const val NOTE = " Note: the text had line breaks written out as \\n (escaped twice), which would have broken the file; " +
        "they were written as real line breaks. Send real line breaks in tool arguments."
}
