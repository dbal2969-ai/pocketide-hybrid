package dev.pocketide.app.core

/**
 * Spoken names for controls labelled only with a symbol (1.5.133, analysis U1). A screen reader said "cross mark" or nothing for
 * ✕, ⋯ and ↺; a control whose label has no letter or digit now gets a name from this table unless its code gives a better one.
 */
object A11yNames {
    private val NAMES = mapOf(
        "✕" to "Close", "×" to "Close", "⋯" to "More", "⋮" to "Menu", "↺" to "History", "⟳" to "Reload", "↻" to "Reload",
        "▶" to "Run", "■" to "Stop", "⏹" to "Stop", "⏸" to "Pause", "＋" to "Add", "✓" to "Done",
        "✎" to "Edit", "⚙" to "Settings", "↪" to "Steer", "⏱" to "Time", "←" to "Back", "→" to "Forward", "↑" to "Up", "↓" to "Down",
        "▸" to "Expand", "▾" to "Collapse", "☰" to "Menu", "🔍" to "Search", "⧉" to "Copy",
    )

    /** True when [label] has no letter or digit, so a screen reader has nothing useful to read. */
    fun symbolOnly(label: CharSequence): Boolean = label.isNotBlank() && label.none { it.isLetterOrDigit() }

    /** A spoken name for a symbol-only [label], or null when the label already reads well or the symbol is unknown. */
    fun name(label: CharSequence): String? {
        val t = label.toString().trim()
        if (symbolOnly(t)) return NAMES[t]
        // "⬇ Save a copy", "▶ Run": read the words, not "downwards arrow" first (1.5.134)
        val i = t.indexOfFirst { it.isLetterOrDigit() }
        return if (i > 0 && t.substring(0, i).none { it.isLetterOrDigit() }) t.substring(i).trim() else null
    }
}
