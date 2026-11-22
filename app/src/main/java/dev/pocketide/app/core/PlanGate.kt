package dev.pocketide.app.core

/**
 * A PLAN.md checklist can only say "done" for work that a check has passed. When the assistant writes changes to code after
 * the last passing check, every ticked box in the plan file goes back to open, so a file write cannot skip the check.
 */
object PlanGate {
    fun isPlan(path: String): Boolean = path.substringAfterLast('/').equals("PLAN.md", ignoreCase = true)

    private val TICKED = Regex("""(?m)^(\s*(?:[-*+]|\d+[.)])?\s*)\[[xX]\]""")
    private val OPEN_OR_TICKED = Regex("""(?m)^\s*(?:[-*+]|\d+[.)])?\s*\[[ xX]\]""")

    /** How many checklist items are ticked, and how many items there are. */
    fun counts(content: String): Pair<Int, Int> {
        val all = OPEN_OR_TICKED.findAll(content).count()
        val done = TICKED.findAll(content).count()
        return done to all
    }

    /** The item text of each ticked line, so a tick can be recognised again after the file is rewritten. */
    fun tickedItems(content: String): Set<String> =
        TICKED.findAll(content).map { m -> item(content, m.range.last + 1) }.toSet()

    private fun item(content: String, from: Int): String {
        val end = content.indexOf('\n', from).let { if (it < 0) content.length else it }
        return content.substring(from, end).trim().lowercase().replace(Regex("\\s+"), " ")
    }

    /**
     * Opens again only the boxes ticked since [old] (the file as it was before this write). Items that were already
     * ticked stay ticked: they were accepted when a check had passed.
     */
    fun reopenNew(old: String?, content: String): Pair<String, Int> {
        val kept = if (old == null) emptySet() else tickedItems(old)
        var n = 0
        val out = TICKED.replace(content) { m ->
            if (item(content, m.range.last + 1) in kept) m.value else { n++; m.groupValues[1] + "[ ]" }
        }
        return out to n
    }

    /** Puts every ticked box back to open. Returns the new text and how many boxes changed. */
    fun reopen(content: String): Pair<String, Int> {
        var n = 0
        val out = TICKED.replace(content) { m -> n++; m.groupValues[1] + "[ ]" }
        return out to n
    }
}
