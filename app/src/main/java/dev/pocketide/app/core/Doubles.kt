package dev.pocketide.app.core

/**
 * Things a JavaScript file now does twice where only the last one counts (1.5.184, local run 3, l19): Gemini Nano added
 * `module.exports = { add, get, reset }` and left the old `module.exports = { add, get }` below it, so reset was never exported, and
 * it declared `function reset()` twice. The file is valid JavaScript, so the syntax check passed; this note says what went wrong.
 */
object Doubles {
    private val EXPORTS = Regex("""(?m)^[^\n/]*\bmodule\.exports\s*=(?!=)""")
    private val CAPITAL_KEYWORD = Regex("""(?m)^\s*(Default|Case|Return|Break|Continue)\b\s*:""")
    private val FUNCTION = Regex("""(?m)^\s*(?:export\s+)?(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(""")

    /** What [text] does twice that [before] did not, as a note to append to a write's result; "" when nothing. */
    fun note(path: String, text: String, before: String?): String {
        if (!Regex("""\.(m|c)?js$""").containsMatchIn(path)) return ""
        val out = ArrayList<String>()
        val exports = EXPORTS.findAll(text).count()
        if (exports > 1 && exports > (before?.let { EXPORTS.findAll(it).count() } ?: 0))
            out.add("$path now sets module.exports $exports times; only the last one counts, so remove the others")
        val twice = FUNCTION.findAll(text).map { it.groupValues[1] }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val before2 = before?.let { b -> FUNCTION.findAll(b).map { it.groupValues[1] }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys }.orEmpty()
        for (f in twice - before2) out.add("$path now declares function $f twice; the second replaces the first, so keep one")
        // 1.5.184 (local run 3, l13): "Default: return [0, 0];" is a label in JavaScript, not the switch's default case
        val caps = CAPITAL_KEYWORD.findAll(text).map { it.groupValues[1] }.toSet() - (before?.let { b -> CAPITAL_KEYWORD.findAll(b).map { it.groupValues[1] }.toSet() } ?: emptySet())
        for (k in caps) out.add("$path has \"$k:\" with a capital letter; JavaScript reads it as a label, not ${k.lowercase()}, so write ${k.lowercase()}")
        return if (out.isEmpty()) "" else "\nWarning: " + out.joinToString("; ") + "."
    }
}
