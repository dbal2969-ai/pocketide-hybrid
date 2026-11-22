package dev.pocketide.app.core

/**
 * Several exact edits in one tool call (Copilot offers the same as multi_replace_string_in_file: fewer rounds, so less
 * time and money). Edits apply in order; an edit sees the result of the ones before it, in the same file too. A failed
 * edit does not stop the others, and the reply says which failed and why.
 */
object MultiEdit {
    const val MAX = 12

    class Item(val path: String, val search: String, val replace: String)
    class Outcome(val texts: LinkedHashMap<String, String>, val notes: List<String>, val applied: Int)

    /** The edits from the tool's "edits" argument (a list of objects with path, search, replace). */
    fun parse(raw: Any?): List<Item> =
        (raw as? List<*>).orEmpty().take(MAX).mapNotNull { x ->
            val m = x as? Map<*, *> ?: return@mapNotNull null
            val p = (m["path"] as? String)?.trim().orEmpty()
            val s = m["search"] as? String ?: return@mapNotNull null
            if (p.isEmpty()) null else Item(p, s, (m["replace"] as? String).orEmpty())
        }

    /** [current] gives a file's text (null when there is none); [refuse] gives a reason a path may not be edited, or null. */
    fun run(items: List<Item>, current: (String) -> String?, refuse: (String) -> String?): Outcome {
        val texts = LinkedHashMap<String, String>()
        val notes = ArrayList<String>()
        var applied = 0
        for ((i, it) in items.withIndex()) {
            val n = i + 1
            val why = refuse(it.path)
            if (why != null) { notes.add("Edit $n (${it.path}) not applied: $why"); continue }
            val cur = texts[it.path] ?: current(it.path)
            if (cur == null) { notes.add("Edit $n: no text file named ${it.path}. Use write_file to create it."); continue }
            val res = EditBlocks.apply(cur, EditBlock(it.path, it.search, it.replace))
            val t = res.text
            if (t == null) notes.add("Edit $n (${it.path}) not applied: ${res.error ?: "the search text was not found"}")
            else { texts[it.path] = t; applied++ }
        }
        return Outcome(texts, notes, applied)
    }
}
