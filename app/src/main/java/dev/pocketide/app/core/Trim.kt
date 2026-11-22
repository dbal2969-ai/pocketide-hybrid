package dev.pocketide.app.core

/**
 * Makes requests smaller without losing meaning: collapses repeated log lines, strips terminal colour codes,
 * removes a block the user pasted twice, and shortens long tool output around the parts that matter.
 */
object Trim {
    private val ANSI = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")
    private val PROGRESS = Regex("""^[\s\d.%|#=\->\[\]]*$""")
    private val TIMESTAMP = Regex("""^\s*(?:\[?\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}[.,\d]*Z?\]?|\d{2}:\d{2}:\d{2}[.,\d]*)\s*""")

    /** The line with the timestamp and colour codes taken off, for comparing lines that differ only in time. */
    private fun shape(line: String): String = TIMESTAMP.replace(ANSI.replace(line, ""), "").trim()

    /** Removes colour codes and replaces runs of the same (or same-shaped) line with one line and a count. */
    fun collapse(text: String, minRun: Int = 3): String {
        val lines = ANSI.replace(text, "").replace("\r\n", "\n").split("\n")
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val s = shape(lines[i])
            var j = i + 1
            while (j < lines.size && shape(lines[j]) == s) j++
            val run = j - i
            out.append(lines[i])
            if (run >= minRun && s.isNotEmpty()) out.append("   … same line ×").append(run)
            else if (run > 1) { repeat(run - 1) { out.append("\n").append(lines[i]) } }
            if (j < lines.size) out.append("\n")
            i = j
        }
        return out.toString()
    }

    /**
     * Drops progress-bar noise: lines of only digits, dots, percent signs and bar characters that also have a bar or percent
     * sign in them, or are only dots. A plain number ("4.5", "212", "57.50") is what a command printed and is kept.
     */
    fun dropProgress(text: String): String =
        text.lineSequence().filter { it.isBlank() || !isProgress(it.trim()) }.joinToString("\n")

    private fun isProgress(t: String): Boolean =
        t.length >= 3 && PROGRESS.matches(t) && (t.any { it in "%|#=>[]" } || t.all { it == '.' })

    private val ERROR = Regex("""(error|fail|assert|traceback|exception|panic|warning|cannot|not found|undefined|unexpected|denied|refused|timeout)""", RegexOption.IGNORE_CASE)

    /** Keeps the start and end of long output, plus any lines that look like problems. */
    fun middle(text: String, maxChars: Int, head: Int = 20, tail: Int = 30): String {
        if (text.length <= maxChars) return text
        val lines = text.split("\n")
        if (lines.size <= head + tail) return text.take(maxChars / 2) + "\n… (${text.length - maxChars} characters left out)\n" + text.takeLast(maxChars / 2)
        val keep = sortedSetOf<Int>()
        for (i in 0 until minOf(head, lines.size)) keep.add(i)
        for (i in maxOf(0, lines.size - tail) until lines.size) keep.add(i)
        lines.forEachIndexed { i, l -> if (ERROR.containsMatchIn(l)) keep.add(i) }
        val sb = StringBuilder()
        var prev = -1
        for (i in keep) {
            if (prev >= 0 && i > prev + 1) sb.append("… (${i - prev - 1} lines left out)\n")
            sb.append(lines[i]).append("\n")
            prev = i
        }
        val s = sb.toString().trimEnd()
        return if (s.length <= maxChars) s else s.take(maxChars) + "\n… (cut)"
    }

    /** Everything above, for the output of a command. */
    fun output(text: String, maxChars: Int = 4000): String = middle(dropProgress(collapse(text)).trim(), maxChars)

    private const val MIN_DUP = 200

    /** If a long block appears twice in a row in a message (a double paste), keeps one copy. */
    fun dedupePaste(text: String): String {
        val n = text.length
        if (n < MIN_DUP * 2) return text
        val half = n / 2
        for (len in half downTo MIN_DUP) {
            val a = text.substring(0, len)
            val rest = text.substring(len).trimStart()
            if (rest.startsWith(a.trimStart().trimEnd()) || rest.startsWith(a)) {
                val after = rest.removePrefix(a.trimStart().trimEnd()).removePrefix(a)
                return (a.trimEnd() + "\n[the same block was pasted twice; one copy kept]\n" + after.trimStart()).trimEnd()
            }
        }
        return text
    }

    /** Everything that is safe to do to what the user typed, with no model and no change of meaning. */
    fun userMessage(text: String): String {
        if (text.length < 1500) return text
        val cleaned = dedupePaste(collapse(ANSI.replace(text, ""), 4))
        return if (cleaned.length < text.length) cleaned else text
    }

    /** How much was saved, for showing the person. */
    fun saved(before: String, after: String): Int = (before.length - after.length).coerceAtLeast(0)
}
