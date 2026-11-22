package dev.pocketide.app.core

/** The live line shown while the assistant works: what it is doing and for how long, instead of a bare "…". */
object WorkingStatus {
    /** Text that only says "working" and may be replaced by the live line. */
    fun isPlaceholder(text: String): Boolean = text.isBlank() || text == "…" || text == "Thinking…"

    private fun clean(phase: String): String = phase.trim().trimEnd('…', '.', ' ').ifEmpty { "Working" }

    /** The kind of a phase, its first word ("Reading app.js" and "Reading b.js" are one kind), for screen-reader announcements. */
    fun kind(phase: String): String = clean(phase).substringBefore(' ').lowercase()

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        else -> "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
    }

    /** "Reading app.js..  12s". [tick] animates the dots. A slow wait adds a hint that Stop is available. */
    fun line(phase: String, seconds: Long, tick: Int, chars: Int = 0): String {
        val base = clean(phase)
        val dots = ".".repeat(tick.mod(3) + 1).padEnd(3, ' ')
        val hint = if (seconds >= 45 && base.startsWith("Thinking") && chars == 0) "\nThis is taking a while. You can tap Stop and send it again." else ""
        return "$base$dots  ${duration(seconds)}${if (chars > 0) "  ·  ${size(chars)}" else ""}$hint"
    }

    /** Amount received so far: "850 characters", "1.2k characters". */
    fun size(chars: Int): String = if (chars < 1000) "$chars characters" else String.format(java.util.Locale.US, "%.1fk characters", chars / 1000.0)

    /** What a tool call is doing right now, in plain words. */
    fun forTool(name: String, args: Map<String, Any?>): String {
        fun g(k: String) = (args[k] as? String).orEmpty()
        return when (name) {
            "read_file" -> "Reading ${g("path")}"
            "list_files" -> if (g("dir").isEmpty()) "Looking at the files" else "Looking in ${g("dir")}"
            "repo_map" -> "Mapping the project"
            "search" -> "Searching for '${g("pattern").take(30)}'"
            "find_code" -> "Looking for '${g("query").take(30)}'"
            "find_references" -> "Asking the language server about ${g("name").take(30)}"
            "write_file" -> "Writing ${g("path")}"
            "edit_file", "edit_lines" -> "Editing ${g("path")}"
            "multi_edit" -> "Making several edits"
            "apply_patch" -> "Applying a patch"
            "look_at_images" -> "Looking at the pictures"
            "game_cache" -> "Reading the game cache"
            "game_data" -> "Reading the server data"
            "project_audit" -> "Checking the project"
            "game_assets" -> "Making game files"
            "run_command" -> "Running ${g("command").lineSequence().firstOrNull().orEmpty().take(40)}"
            "doc_outline" -> "Reading ${g("path")}"
            "doc_edit" -> "Editing ${g("path")}"
            "find_symbol" -> "Looking for ${g("name")}"
            "file_outline" -> "Reading ${g("path")}"
            "update_plan" -> "Updating the plan"
            "look_at_preview" -> "Looking at the page"
            "explore" -> "Explorer is reading"
            else -> "Working"
        }
    }
}

/** What to offer after a failed request. */
object RetryAdvice {
    /** True for failures that a second try may fix (network, busy or overloaded server, rate limit), false for wrong key or bad request. */
    fun worthRetrying(message: String): Boolean {
        val m = message.lowercase()
        if (Regex("""invalid|incorrect|unauthori[sz]ed|api key|forbidden|permission|billing|credit|quota exceeded|not found|no such model|does not exist|context length|too large""").containsMatchIn(m) &&
            !m.contains("could not reach")) return false
        return Regex("""could not reach|timed? ?out|timeout|connection|network|unable to resolve|overloaded|busy|try again|rate limit|too many|429|500|502|503|504|unavailable|temporar|reset""").containsMatchIn(m)
    }

    /** Other provider ids that can be tried right now: the ones with a key saved, except the current one and the demo. */
    fun otherProviders(current: String, all: List<Pair<String, Boolean>>): List<String> =
        all.filter { (id, usable) -> usable && id != current }.map { it.first }
}
