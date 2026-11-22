package dev.pocketide.app.core

/** Finds the bracket that matches the one at (or just before) the cursor. Pure, so it is JVM-testable. */
object Brackets {
    private const val OPEN = "([{"
    private const val CLOSE = ")]}"
    const val WINDOW = 20_000

    /** Positions of a bracket next to the cursor and its partner, or null. Prefers the character before the cursor. */
    fun match(text: CharSequence, cursor: Int): Pair<Int, Int>? {
        for (at in listOf(cursor - 1, cursor)) {
            if (at < 0 || at >= text.length) continue
            val c = text[at]
            val o = OPEN.indexOf(c)
            if (o >= 0) return partner(text, at, c, CLOSE[o], +1)?.let { at to it }
            val k = CLOSE.indexOf(c)
            if (k >= 0) return partner(text, at, c, OPEN[k], -1)?.let { at to it }
        }
        return null
    }

    private fun partner(text: CharSequence, from: Int, self: Char, other: Char, step: Int): Int? {
        var depth = 0
        var i = from
        val limit = if (step > 0) minOf(text.length, from + WINDOW) else maxOf(-1, from - WINDOW)
        while (i != limit) {
            val c = text[i]
            if (c == self) depth++
            else if (c == other) { depth--; if (depth == 0) return i }
            i += step
        }
        return null
    }

    /** 1-based line number of a character offset. */
    fun lineOf(text: CharSequence, offset: Int): Int {
        var n = 1
        for (i in 0 until offset.coerceAtMost(text.length)) if (text[i] == '\n') n++
        return n
    }

    /** Character offset of the start of a 1-based line (clamped to the text). */
    fun offsetOfLine(text: CharSequence, line: Int): Int {
        if (line <= 1) return 0
        var n = 1
        for (i in 0 until text.length) {
            if (text[i] == '\n') { n++; if (n == line) return i + 1 }
        }
        return text.length
    }
}
