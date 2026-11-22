package dev.pocketide.app.core

/**
 * Guards for write_file over a file that already exists. write_file replaces the whole file, so a rewrite that drops most of
 * a game or a plan is easy to make by accident (1.5.74: a 822-line game entry point was replaced by a 43-line one). Changing an
 * existing file is done with edit_file or edit_lines; a whole rewrite is refused unless the person asked for one.
 */
object Rewrite {
    /** Files that start the game or the page: replacing one replaces everything it loads. */
    val ENTRY = setOf("index.html", "main.js", "style.css")

    /** A file longer than this is changed with edits, never written whole. */
    const val BIG_LINES = 150

    /** Shorter files are only refused when they are entry files or a write would cut them by half. */
    const val SHRINK_MIN_LINES = 20

    /** The person asked for a rewrite (their words, not the assistant's). */
    fun asked(request: String): Boolean =
        Regex("""(?i)\b(rewrite|from scratch|start over|rebuild|replace (?:the|this|that) (?:whole|entire|file))\b""").containsMatchIn(request)

    fun lines(s: String): Int = if (s.isEmpty()) 0 else s.count { it == '\n' } + 1

    /** Why a whole-file write over an existing file is refused, or null when it may go ahead. */
    fun refusal(path: String, prior: String?, content: String, request: String, small: Boolean = false): String? {
        if (prior == null || asked(request)) return null
        val was = lines(prior)
        val now = lines(content)
        // 1.5.178: a small local model rewrites short files whole (Aider gives weaker models the whole-file format; exact
        // search/replace is where the 1.5B and 7B failed most). The size and shrink guards still hold.
        val entry = path.substringAfterLast('/') in ENTRY && !small
        val edit = "Change it with edit_file or edit_lines, reading the part you change first. Write it whole only when the person asks for a rewrite."
        if (was > BIG_LINES || (entry && was >= SHRINK_MIN_LINES))
            return "Not written: $path has $was lines. write_file replaces the whole file. $edit"
        if (was >= SHRINK_MIN_LINES && now < was / 2)
            return "Not written: $path would shrink from $was to $now lines and drop most of it. $edit"
        return null
    }

    /** The steps (headings, bullets, numbered items) of [prior] that [content] leaves out. */
    fun droppedSteps(prior: String, content: String): List<String> {
        val now = content.lines().map { norm(it) }.toSet()
        return prior.lines().map { norm(it) }.filter { isStep(it) && it !in now }.distinct()
    }

    /** A plan rewrite that drops steps is refused unless the person asked for it. */
    fun planRefusal(path: String, prior: String?, content: String, request: String): String? {
        if (prior == null || !PlanGate.isPlan(path) || asked(request)) return null
        val gone = droppedSteps(prior, content)
        if (gone.isEmpty()) return null
        val shown = gone.take(3).joinToString("; ") { "\"" + it.take(60) + "\"" }
        return "Not written: $path would drop ${gone.size} step${if (gone.size == 1) "" else "s"} ($shown). " +
            "Keep every step: add new steps or tick finished ones, and leave the others as they are."
    }

    private fun isStep(l: String): Boolean = l.isNotEmpty() && (l.startsWith("#") || Regex("""^([-*+]|\d+[.)])\s""").containsMatchIn(l))

    /** Lower case, one space between words, and a ticked box counted as an open one, so a tick does not count as a change. */
    private fun norm(s: String): String = s.replace(Regex("""\[[xX ]\]"""), "[ ]").trim().lowercase().replace(Regex("""\s+"""), " ")
}
