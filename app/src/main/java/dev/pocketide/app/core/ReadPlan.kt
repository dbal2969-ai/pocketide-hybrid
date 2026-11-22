package dev.pocketide.app.core

/**
 * How much of a file a read sends (1.5.99).
 *
 * In the game-developer logs the model read a 900-line main.js 40 to 45 times in one request, in 150-line pieces, thinking for about
 * half a minute before each: most of an hour went on paging through files it had already seen. A file that fits in one reply is now
 * sent whole the first time any part of it is asked for, and asking again for an unchanged file gets a short reminder instead of the
 * same text. The record of what was shown is kept per file version, so an edit (or a command that changes the file) sends it again.
 */
object ReadPlan {
    /** Files longer than this are still read in pieces, however much room there is. */
    const val WHOLE_LINES = 2_000

    /** True when the whole file fits in one reply ([cap] characters, with line numbers) and is not too long to take in at once. */
    fun whole(text: String, cap: Int): Boolean {
        val lines = text.count { it == '\n' } + 1
        if (lines > WHOLE_LINES) return false
        // each shown line gains "N: " (about 6 characters)
        return text.length + lines * 6 <= cap
    }

    /** The line before a whole file sent for a piece. */
    fun wholeNote(path: String, text: String, start: Int?, end: Int?): String {
        val n = text.count { it == '\n' } + 1
        val asked = if (start == null) "" else " (you asked for lines $start-${end ?: (start + AgentActions.RANGE_LINES - 1)})"
        return "The whole of $path ($n lines) is below$asked, so you will not need more pieces. It stays current until the file changes.\n"
    }

    /** The reply to reading a file again that has not changed since it was sent whole. */
    fun seen(path: String, start: Int?, end: Int?): String {
        val part = if (start == null) "" else " Lines $start-${end ?: (start + AgentActions.RANGE_LINES - 1)} are in that read."
        return "$path has not changed since the whole file was shown to you above.$part Use what you already have; to find something in it, " +
            "use search (its matches come with line numbers) rather than reading it again."
    }
}
