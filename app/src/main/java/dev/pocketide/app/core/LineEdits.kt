package dev.pocketide.app.core

/**
 * 1.5.218 (reliability run 1127-2155): what edit_lines tells the model after a change. The old message, "Edited lines 2-1 of
 * controls.js. Line numbers after line 1 have shifted; read again before another edit_lines.", read to Gemini Nano as a failure:
 * in 7 of the 14 tasks that used edit_lines its next thought blamed "line number shifting", and in l12 it inserted the same
 * listener eight times (the test then saw eight taps for one touch). The new message says what was done in plain words, that it
 * is saved, and shows the lines as they are now, so nothing has to be read again; and the same insert is not made twice.
 */
object LineEdits {
    private fun count(n: Int) = if (n == 1) "1 line" else "$n lines"

    /** The message after edit_lines changed [path]: [start] and [end] as sent, [text] as written, [now] the file after it. */
    fun done(path: String, start: Int, end: Int, text: String, now: String): String {
        val n = if (text.isEmpty()) 0 else text.split("\n").size
        val what = when {
            end < start -> "Inserted ${count(n)} into $path after line ${start - 1}" + (if (n > 0) ": " + (if (n == 1) "it is now line $start" else "they are now lines $start-${start + n - 1}") else "") +
                ". It is saved, so do not insert it again."
            n == 0 -> "Removed ${if (start == end) "line $start" else "lines $start-$end"} from $path. It is saved."
            else -> "Replaced ${if (start == end) "line $start" else "lines $start-$end"} of $path with ${count(n)}" +
                (if (n == 1) " (now line $start)" else " (now lines $start-${start + n - 1})") + ". It is saved."
        }
        val lines = now.split("\n")
        val from = maxOf(1, start - 2)
        val to = minOf(lines.size, start + maxOf(n, 1) - 1 + 2)
        val shown = (from..to).toList().let { all -> if (all.size <= 16) all.map { it as Int? } else all.take(7).map { it as Int? } + listOf<Int?>(null) + all.takeLast(7).map { it as Int? } }
        val excerpt = shown.joinToString("\n") { i -> if (i == null) "…" else "$i: ${lines[i - 1]}" }
        return "$what\n$path around there now, with its line numbers (use these for another edit_lines):\n$excerpt"
    }

    /** The key of an insert, by what it inserts and where it goes in words, not by line number (numbers shift after every insert). */
    fun insertKey(path: String, text: String): String = path + "\u0000" + text.split("\n").joinToString("\n") { it.trim() }.trim()

    /**
     * Why an insert is refused, or null: the same lines were already inserted in this request ([applied]: [insertKey]s), and they
     * are still in the file. Short lines (a closing brace, "break;") may be inserted as often as needed.
     */
    fun repeatedInsert(path: String, start: Int, end: Int, text: String, cur: String, applied: Set<String>): String? {
        if (end >= start || insertKey(path, text) !in applied) return null
        val want = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (want.isEmpty() || want.sumOf { it.length } < 12) return null
        val have = cur.split("\n").map { it.trim() }
        val at = (0..have.size - want.size).firstOrNull { i -> want.indices.all { have[i + it] == want[it] } } ?: return null
        return "Not inserted: $path already has ${if (want.size == 1) "this exact line at line ${at + 1}" else "these exact lines at lines ${at + 1}-${at + want.size}"}, " +
            "from your earlier edit, which was saved. Inserting again would add a second copy. Run the check now, or make a different change."
    }
}
