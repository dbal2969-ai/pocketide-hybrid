package dev.pocketide.app.core

/**
 * "Rename getUser to findUserById everywhere": before the work is called done, the project is searched for the old name as a
 * whole word, and places that still use it go back to the model once. Llama 3.3 70B (hard suite, 1.5.90 candidate) made eleven
 * edits, missed the call inside getUserName, ran tests that did not reach it, and said every use was renamed.
 */
object RenameCheck {
    private const val ID = """[A-Za-z_$][\w$]*"""
    private val ASKED = Regex(
        """(?i)\brename\s+(?:the\s+(?:function|method|class|variable|field|property|constant|type|module|component|hook)\s+)?[`'"]?($ID)(?:\(\))?[`'"]?\s+(?:to|into|as)\s+[`'"]?($ID)""")
    // "Rename x to y only in parse.js", "just in this function": the old name is meant to stay elsewhere
    private val NARROW = Regex("""(?i)\b(only|just)\b|\b(in|inside|within)\s+(this|that|the)\s+(function|method|loop|block|class)\b""")
    /** Files that record the past, where the old name rightly stays. */
    private val HISTORY = Regex("""(?i)(^|/)(changelog|changes|history|news)[^/]*$""")
    const val SHOWN = 12

    data class Rename(val old: String, val new: String)

    /** The rename the request asks for, or null. */
    fun asked(request: String): Rename? {
        if (NARROW.containsMatchIn(request)) return null
        val m = ASKED.find(request) ?: return null
        val old = m.groupValues[1]
        val new = m.groupValues[2]
        if (old.length < 3 || old == new) return null
        return Rename(old, new)
    }

    /** "path:line: text" for each line that still has [old] as a whole word (case-sensitive), and how many there are. */
    fun left(old: String, paths: Collection<String>, text: (String) -> String?): Pair<List<String>, Int> {
        val word = Regex("(?<![\\w$])" + Regex.escape(old) + "(?![\\w$])")
        val out = ArrayList<String>()
        var n = 0
        for (p in paths.sorted()) {
            if (HISTORY.containsMatchIn(p)) continue
            val t = text(p) ?: continue
            if (!t.contains(old)) continue
            for ((i, l) in t.split("\n").withIndex()) {
                if (!word.containsMatchIn(l)) continue
                n++
                if (out.size < SHOWN) out.add("$p:${i + 1}: ${l.trim().take(160)}")
            }
        }
        return out to n
    }

    fun ask(r: Rename, lines: List<String>, count: Int): String =
        "The rename is not finished: ${r.old} still appears in $count place(s):\n" + lines.joinToString("\n") +
            (if (count > lines.size) "\n… and ${count - lines.size} more (search for ${r.old} to see them all)." else "") +
            "\nChange each one to ${r.new}, run the checks again, then give your final answer. If one of them must keep the old name, say which and why."
}
