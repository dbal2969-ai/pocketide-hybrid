package dev.pocketide.app.core

/**
 * Next-edit suggestions, the rename case (1.5.113): after you rename a name on one line (playerHp → playerHealth), the editor offers
 * the same change at the other places in the file that use the old name, as Copilot's next edit suggestions do for renames. Words
 * inside strings and comments are left alone. No model call: instant and free.
 *
 * The model case (1.5.125): when no rename fits, the editor asks the suggestion model (the one inline completion uses) for the one
 * edit most likely to follow elsewhere in the same file, as Copilot's next edit suggestions do: a parameter was added, so a call
 * needs the new argument; a field was added, so the place that builds the object needs it. The reply must be one exact
 * SEARCH/REPLACE block, and it is checked before anything is shown (old text found exactly once, outside the lines just edited,
 * small). Anything else shows nothing: a wrong suggestion costs the person more than no suggestion. Everything here is pure so the
 * prompt, the parser and the checks are tested on the JVM.
 */
object NextEdit {
    private val WORD = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
    private val KEYWORDS = setOf("const", "let", "var", "function", "return", "if", "else", "for", "while", "new", "class", "def", "import", "from",
        "export", "true", "false", "null", "undefined", "this", "self", "in", "of", "and", "or", "not", "await", "async", "static", "public", "private", "val", "fun")

    /** The one name [before] renamed to in [after] (the rest of the line the same), or null. */
    fun renamed(before: String, after: String): Pair<String, String>? {
        if (before == after) return null
        val a = WORD.findAll(before).toList(); val b = WORD.findAll(after).toList()
        if (a.size != b.size || a.isEmpty()) return null
        // what lies between the names must be the same
        fun gaps(s: String, ms: List<MatchResult>): List<String> = (0..ms.size).map { i -> s.substring(if (i == 0) 0 else ms[i - 1].range.last + 1, if (i == ms.size) s.length else ms[i].range.first) }
        if (gaps(before, a) != gaps(after, b)) return null
        val pairs = a.indices.filter { a[it].value != b[it].value }.map { a[it].value to b[it].value }.distinct()
        if (pairs.size != 1) return null
        val (old, new) = pairs[0]
        if (old.length < 2 || old in KEYWORDS || new in KEYWORDS) return null
        return old to new
    }

    /** Where [word] appears as a whole word in code (not in strings or comments): start offsets. */
    fun places(text: String, word: String): List<Int> {
        val out = ArrayList<Int>()
        var i = 0; var q: Char? = null
        val py = looksPython(text)
        while (i < text.length) {
            val ch = text[i]
            if (q != null) { if (ch == '\\') { i += 2; continue }; if (ch == q) q = null; i++; continue }
            when {
                ch == '"' || ch == '\'' || ch == '`' -> { q = ch; i++ }
                ch == '/' && i + 1 < text.length && text[i + 1] == '/' -> { i = text.indexOf('\n', i).let { if (it < 0) text.length else it } }
                ch == '#' && py -> { i = text.indexOf('\n', i).let { if (it < 0) text.length else it } }
                ch == '/' && i + 1 < text.length && text[i + 1] == '*' -> { i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 } }
                text.startsWith(word, i) && (i == 0 || !isWord(text[i - 1])) && (i + word.length >= text.length || !isWord(text[i + word.length])) -> { out.add(i); i += word.length }
                else -> i++
            }
        }
        return out
    }

    private fun looksPython(text: String): Boolean = Regex("""(?m)^\s*(def|import|from)\s""").containsMatchIn(text) && !text.contains("function ")
    private fun isWord(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    /** [text] with [old] renamed to [new] at [places] except those inside [skip] (the line already changed). */
    fun apply(text: String, old: String, new: String, places: List<Int>, skip: IntRange): String {
        val sb = StringBuilder(); var last = 0
        for (p in places.sorted()) { if (p in skip) continue; sb.append(text, last, p).append(new); last = p + old.length }
        return sb.append(text, last, text.length).toString()
    }

    /** The line that changed between [before] and [after] when exactly one did: its index, old text, new text. */
    fun changedLine(before: String, after: String): Triple<Int, String, String>? {
        val a = before.split('\n'); val b = after.split('\n')
        if (a.size != b.size) return null
        val diff = a.indices.filter { a[it] != b[it] }
        return if (diff.size == 1) Triple(diff[0], a[diff[0]], b[diff[0]]) else null
    }

    // ---- the model case (1.5.125) ----

    /** Wait this long after the last keystroke before asking: shorter would ask in the middle of a word. */
    const val DEBOUNCE_MS = 1200L
    /** A next edit is one small change, not a rewrite: more lines than this on either side is refused. */
    const val MAX_EDIT_LINES = 8
    const val MAX_EDIT_CHARS = 600
    /** A recent edit bigger than this (a paste, a reformat) is not something to follow up on. */
    const val MAX_DIFF_LINES = 16
    /** Files bigger than this are sent as a window around the edit, to keep the request small and fast. */
    const val FILE_CHARS = 16_000
    /** Above this nothing is asked: copying the file at each pause would cost too much memory. */
    const val MAX_FILE = 200_000

    val SYSTEM = """
        |You suggest the next edit in a code editor, like an IDE's "next edit suggestions".
        |You get a file, the edit the person just made (as a small diff), and where the cursor is.
        |Find the ONE edit the person most likely has to make next ELSEWHERE in the same file because of their edit.
        |Examples: a parameter was added to a function, so a call needs the new argument; a constant changed its unit, so a use
        |needs converting; a field was added, so a place that builds the object needs it.
        |Reply with exactly one block in this form and nothing else:
        |<<<<<<< SEARCH
        |the exact lines to change, copied from the file, enough of them to be unique in the file
        |=======
        |the same lines after the change
        |>>>>>>> REPLACE
        |Copy the SEARCH lines exactly, whole lines, at most $MAX_EDIT_LINES. Do not change the lines the person just edited.
        |If no edit follows from theirs, reply with NONE.
        |""".trimMargin()

    /** Whether to ask at all: the person's choice when they made one, else on except on a metered connection (mobile data). */
    fun enabled(stored: Boolean?, metered: Boolean): Boolean = stored ?: !metered

    /**
     * The edit just made: [start] to [end] are the changed lines in the new text (end exclusive; equal for a deletion),
     * [firstLine] is 0-based, [lines] the larger of the removed and added line counts, [diff] the change as - and + lines.
     */
    data class Recent(val start: Int, val end: Int, val firstLine: Int, val lines: Int, val diff: String)

    /** The changed lines between [before] and [after], found by trimming the lines the two share at both ends; null when equal. */
    fun recentEdit(before: String, after: String): Recent? {
        if (before == after) return null
        val a = before.split('\n'); val b = after.split('\n')
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val removed = a.subList(pre, a.size - suf); val added = b.subList(pre, b.size - suf)
        val start = b.subList(0, pre).sumOf { it.length + 1 }.coerceAtMost(after.length)
        val end = if (added.isEmpty()) start else (start + added.sumOf { it.length + 1 } - 1).coerceAtMost(after.length)
        val diff = buildString {
            append("@@ line ").append(pre + 1).append(" @@")
            removed.forEach { append("\n-").append(it) }
            added.forEach { append("\n+").append(it) }
        }
        return Recent(start, end, pre, maxOf(removed.size, added.size), diff)
    }

    /**
     * The edit to follow up on, from the texts at the last few pauses ([snapshots], oldest first) to [now]: the oldest one whose
     * change is still small, so two quick edits in a row (the parameter, then its type) are seen together. Null when nothing changed
     * or every change is too big.
     */
    fun pickBase(snapshots: List<String>, now: String): Recent? = pickBaseText(snapshots, now)?.second

    /** As [pickBase], with the snapshot it compared against (the file before the change; Sweep's format needs it, 1.5.143). */
    fun pickBaseText(snapshots: List<String>, now: String): Pair<String, Recent>? {
        for (s in snapshots) { val r = recentEdit(s, now) ?: continue; if (r.lines <= MAX_DIFF_LINES) return s to r }
        return null
    }

    /** The user message: the file (or a window of it), the diff and the cursor's line. */
    fun prompt(path: String, text: String, recent: Recent, cursor: Int): String {
        var from = 0; var to = text.length
        if (text.length > FILE_CHARS) {
            from = (recent.start - FILE_CHARS / 2).coerceAtLeast(0)
            to = (from + FILE_CHARS).coerceAtMost(text.length)
            from = (to - FILE_CHARS).coerceAtLeast(0)
            if (from > 0) text.indexOf('\n', from).let { if (it in from until to) from = it + 1 }
            if (to < text.length) text.lastIndexOf('\n', to).let { if (it > from) to = it }
        }
        val c = cursor.coerceIn(0, text.length)
        val cursorLine = text.substring(0, c).count { it == '\n' }
        val lineText = text.substring(text.lastIndexOf('\n', c - 1) + 1, text.indexOf('\n', c).let { if (it < 0) text.length else it })
        val part = if (from > 0 || to < text.length) " (part of the file, from line ${text.substring(0, from).count { it == '\n' } + 1})" else ""
        return "File: ${path.ifEmpty { "untitled" }}$part\n\n${text.substring(from, to)}\n\n" +
            "The edit I just made (- removed, + added):\n${recent.diff}\n\n" +
            "The cursor is on line ${cursorLine + 1}: ${lineText.trim()}\n\n" +
            "Reply with the one SEARCH/REPLACE block for my next edit elsewhere in this file, or NONE."
    }

    data class Suggestion(val old: String, val new: String)

    private val OPEN = Regex("""^\s*<{5,9}\s*SEARCH\b.*$""")
    private val MID = Regex("""^\s*={5,9}\s*$""")
    private val CLOSE = Regex("""^\s*>{5,9}\s*REPLACE\b.*$""")

    /**
     * The one SEARCH/REPLACE block in [reply], or null for NONE, no block, a broken block or more than one block (a next edit is
     * one change; two blocks mean the model was unsure which). Fences and words around the block are ignored.
     */
    fun parse(reply: String): Suggestion? {
        val s = LocalModels.stripThinking(reply).text.replace("\r", "")
        if (s.isBlank()) return null
        val lines = s.split('\n')
        val found = ArrayList<Suggestion>()
        var i = 0
        while (i < lines.size) {
            if (!OPEN.matches(lines[i])) { i++; continue }
            val mid = (i + 1 until lines.size).firstOrNull { MID.matches(lines[it]) || OPEN.matches(lines[it]) || CLOSE.matches(lines[it]) }
            if (mid == null || !MID.matches(lines[mid])) return null
            val close = (mid + 1 until lines.size).firstOrNull { CLOSE.matches(lines[it]) || OPEN.matches(lines[it]) || MID.matches(lines[it]) }
            if (close == null || !CLOSE.matches(lines[close])) return null
            found.add(Suggestion(lines.subList(i + 1, mid).joinToString("\n"), lines.subList(mid + 1, close).joinToString("\n")))
            i = close + 1
        }
        return found.singleOrNull()
    }

    sealed class Verdict {
        /** Shown: replace [old] at offset [at] (on 0-based [line]) with [new]. */
        data class Ok(val at: Int, val line: Int, val old: String, val new: String) : Verdict()
        data class No(val why: String) : Verdict()
    }

    /** Checks [s] against [text] before anything is shown. [recent] is the edit just made, whose lines the suggestion must not touch. */
    fun validate(text: String, s: Suggestion, recent: Recent?): Verdict {
        if (s.old.isBlank()) return Verdict.No("empty")
        if (s.old == s.new) return Verdict.No("no change")
        if (s.old.count { it == '\n' } + 1 > MAX_EDIT_LINES || s.new.count { it == '\n' } + 1 > MAX_EDIT_LINES ||
            s.old.length > MAX_EDIT_CHARS || s.new.length > MAX_EDIT_CHARS) return Verdict.No("too big")
        // leftover block markers or fences in the new text mean the reply was malformed
        if (Regex("""(?m)^\s*(<{5,}|={5,}|>{5,}|```)""").containsMatchIn(s.new)) return Verdict.No("markers")
        val at = text.indexOf(s.old)
        if (at < 0) return Verdict.No("not found")
        if (text.indexOf(s.old, at + 1) >= 0) return Verdict.No("not unique")
        // touching the lines just edited would fight the person's own change (or undo it)
        if (recent != null && recent.end > recent.start && at < recent.end && recent.start < at + s.old.length) return Verdict.No("inside the edit just made")
        return Verdict.Ok(at, text.substring(0, at).count { it == '\n' }, s.old, s.new)
    }

    /** [text] with the suggestion applied. */
    fun applyTo(text: String, ok: Verdict.Ok): String = text.substring(0, ok.at) + ok.new + text.substring(ok.at + ok.old.length)

    /** The bar's label: where, and the first changed line before and after, kept short. */
    fun describe(ok: Verdict.Ok): String {
        val o = ok.old.split('\n'); val n = ok.new.split('\n')
        val k = o.indices.firstOrNull { it >= n.size || o[it] != n[it] } ?: minOf(o.size, n.size - 1)
        fun cut(x: String) = x.trim().let { if (it.length > 40) it.take(39) + "…" else it }
        val before = cut(o.getOrElse(k) { "" }); val after = cut(n.getOrElse(k) { "" })
        val more = maxOf(o.size, n.size).let { if (it > 1) " ($it lines)" else "" }
        return "Next edit, line ${ok.line + k + 1}$more: ${before.ifEmpty { "(new line)" }} → ${after.ifEmpty { "(removed)" }}"
    }
}
