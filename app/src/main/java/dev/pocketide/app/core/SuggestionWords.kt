package dev.pocketide.app.core

/** Helpers for taking an inline suggestion one word at a time. */
object SuggestionWords {
    /** How many characters to take for the next word: leading spaces, then a run of letters/digits/underscores, or one symbol. 0 for empty text. */
    fun nextLength(s: String): Int {
        var i = 0
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        if (i < s.length && s[i] == '\n') return i + 1
        if (i >= s.length) return s.length
        if (s[i].isLetterOrDigit() || s[i] == '_') { while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++ }
        else i++
        return i
    }
}
