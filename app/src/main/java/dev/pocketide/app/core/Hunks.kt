package dev.pocketide.app.core

/**
 * The separate changed parts of a file, so one part of the assistant's edit can be undone and the rest kept
 * (Copilot and VS Code offer Keep/Undo per change). A part is found again by the lines around it, so parts can be
 * undone in any order, and an undo still works after other parts were undone.
 */
object Hunks {
    data class Hunk(
        /** Line in the changed file where the part starts (1-based, for showing). */
        val line: Int,
        val removed: List<String>,
        val added: List<String>,
        val before: List<String>,
        val after: List<String>
    ) {
        val label: String get() = "Lines ${line}${if (added.size > 1) "-" + (line + added.size - 1) else ""} (" +
            listOfNotNull(if (removed.isNotEmpty()) "-${removed.size}" else null, if (added.isNotEmpty()) "+${added.size}" else null).joinToString(" ") + ")"
    }

    private const val CONTEXT = 2

    fun split(old: String, new: String): List<Hunk>? {
        val ops = Diff.lines(old, new) ?: return null
        val out = ArrayList<Hunk>()
        var k = 0
        var newLine = 0
        while (k < ops.size) {
            if (ops[k].tag == ' ') { newLine++; k++; continue }
            val start = k
            val removed = ArrayList<String>(); val added = ArrayList<String>()
            while (k < ops.size && ops[k].tag != ' ') { if (ops[k].tag == '-') removed.add(ops[k].line) else added.add(ops[k].line); k++ }
            val before = ops.subList(maxOf(0, start - CONTEXT), start).filter { it.tag == ' ' }.map { it.line }
            val after = ops.subList(k, minOf(ops.size, k + CONTEXT)).filter { it.tag == ' ' }.map { it.line }
            out.add(Hunk(newLine + 1, removed, added, before, after))
            newLine += added.size
        }
        return out
    }

    /** [current] with [h] put back the way it was, or null when that part can no longer be found unchanged. */
    fun revert(current: String, h: Hunk): String? {
        val trailing = current.endsWith("\n")
        val lines = if (current.isEmpty()) mutableListOf() else current.removeSuffix("\n").split("\n").toMutableList()
        val block = h.before + h.added + h.after
        var at = -1
        if (block.isNotEmpty()) {
            for (i in 0..(lines.size - block.size)) {
                if (lines.subList(i, i + block.size) == block) { if (at >= 0) return null; at = i }
            }
        } else at = 0
        if (at < 0) return null
        val s = at + h.before.size
        repeat(h.added.size) { lines.removeAt(s) }
        lines.addAll(s, h.removed)
        return lines.joinToString("\n") + if (trailing && lines.isNotEmpty()) "\n" else ""
    }
}
