package dev.pocketide.app.core

/**
 * The Python helper for Gemini Nano (1.5.194), an optional setting ("Python helper for Gemini Nano", off by default). Reliability run
 * 1126-0816 passed 1 of 39 Python runs: 80 of 104 refused Python saves were IndentationErrors, Nano read files 402 times and ran the
 * tests 58 times, and the files to fix run to 1,153 lines while Nano's prompt holds about 350. The helper gives Nano:
 * - a short note on working in Python ([NOTE]);
 * - its edits put at the indentation of the lines they replace ([reindentEdit], [reindentLines]);
 * - for a long Python file, its classes and functions with line numbers instead of a cut-off read ([outline]);
 * - after a failing pytest run, the source lines where it failed ([whereFailed]).
 * Pure logic; NativeAgent and HybridAgent call it when the setting is on.
 */
object PyHelper {
    /** Added to Nano's system prompt (kept short: Nano's prompt is about 14,000 characters). */
    const val NOTE = "Python: indentation is part of the code. Copy each line's leading spaces exactly; inside a function the body is 4 spaces " +
        "deeper than its def. To change a function, read_file it with start and end, then edit_lines with those line numbers. Run the " +
        "test with run_command; when it fails, fix the source line it points to, never the test."

    /** Files longer than this are answered with an outline when read whole. */
    const val LONG = 300

    fun isPython(path: String) = path.endsWith(".py", ignoreCase = true)

    /** Whether the helper applies: the project has Python files or the request is about Python. */
    fun applies(paths: Collection<String>, request: String): Boolean =
        paths.any { isPython(it) } || Regex("""(?i)\bpython\b|\.py\b|\bpytest\b""").containsMatchIn(request)

    private fun indentOf(line: String) = line.takeWhile { it == ' ' }.length
    private fun shift(line: String, by: Int): String = when {
        line.isBlank() || by == 0 -> line
        by > 0 -> " ".repeat(by) + line
        else -> line.drop(minOf(-by, indentOf(line)))
    }

    /**
     * [replace] put at the indentation of the text it replaces. Nano's commonest broken Python edit: the search is a line without its
     * leading spaces (`def isatty(self) -> bool:`), found in the middle of the line, and the replacement starts with the spaces again,
     * so the def ends up indented twice; or the replacement's first line is at column 0 and its body one level too shallow. The first
     * line takes the search's own indentation; every other line moves by the same amount. Files indented with tabs are left alone.
     */
    fun reindentEdit(cur: String, search: String, replace: String): String {
        if (search.isEmpty() || replace.isBlank() || '\t' in cur) return replace
        val at = cur.indexOf(search)
        if (at < 0 || cur.indexOf(search, at + 1) >= 0) return replace
        val lineStart = cur.lastIndexOf('\n', at - 1) + 1
        val prefix = cur.substring(lineStart, at)
        if (prefix.isNotBlank()) return replace   // the search starts mid-line after code: not a block edit
        val sFirst = search.lines().first()
        val target = prefix.length + indentOf(sFirst)
        val rl = replace.split("\n")
        val firstIdx = rl.indexOfFirst { it.isNotBlank() }
        if (firstIdx < 0) return replace
        val r0 = indentOf(rl[firstIdx])
        // the first line: its own text at the search's indentation (the file's prefix is kept before it)
        val by = target - r0
        if (by == 0 && prefix.isEmpty()) return replace
        return rl.mapIndexed { i, l ->
            when {
                i < firstIdx -> l
                i == firstIdx -> " ".repeat(indentOf(sFirst)) + l.trimStart(' ')
                else -> shift(l, by)
            }
        }.joinToString("\n")
    }

    /**
     * [text] for edit_lines [start]..[end] put at the indentation of the first line it replaces (or, for an insert, of the line it
     * goes before; at the end of the file, of the last line).
     */
    fun reindentLines(cur: String, start: Int, end: Int, text: String): String {
        if (text.isBlank() || '\t' in cur) return text
        val cl = cur.split("\n")
        val ref = (start - 1 until cl.size).firstOrNull { it >= 0 && cl[it].isNotBlank() }?.let { cl[it] }
            ?: cl.lastOrNull { it.isNotBlank() } ?: return text
        // inserting right after a line that opens a block (ends with ":"): the new lines go one level deeper than it
        val opener = if (end < start && start >= 2) cl.getOrNull(start - 2)?.takeIf { it.trimEnd().endsWith(":") } else null
        val target = opener?.let { indentOf(it) + 4 } ?: indentOf(ref)
        val tl = text.split("\n")
        val first = tl.firstOrNull { it.isNotBlank() } ?: return text
        val by = target - indentOf(first)
        return if (by == 0) text else tl.joinToString("\n") { shift(it, by) }
    }

    private val DEF = Regex("""^(\s*)(async\s+def|def|class)\s+(\w+)""")

    /** A long Python file's classes and functions with their line numbers, or null when the file is short enough to read whole. */
    fun outline(path: String, text: String, long: Int = LONG): String? {
        val lines = text.split("\n")
        if (lines.size <= long) return null
        val items = lines.mapIndexedNotNull { i, l -> DEF.find(l)?.let { "${i + 1}: ${l.trimEnd().take(100)}" } }
        if (items.isEmpty()) return null
        return "$path has ${lines.size} lines, too many to show whole. Its classes and functions, with line numbers:\n" +
            items.take(120).joinToString("\n") + (if (items.size > 120) "\n… and ${items.size - 120} more" else "") +
            "\nRead the one you need with read_file start and end (from its line to the next one's)."
    }

    /** The lines of the function around line [at] (1-based) of [text], numbered, at most [max] lines. */
    fun functionAt(text: String, at: Int, max: Int = 50): String {
        val cl = text.split("\n")
        val i = (at - 1).coerceIn(0, maxOf(0, cl.size - 1))
        var s = i
        while (s > 0 && !(DEF.containsMatchIn(cl[s]) && indentOf(cl[s]) <= indentOf(cl[i]))) s--
        val base = indentOf(cl[s])
        var e = s + 1
        while (e < cl.size && (cl[e].isBlank() || indentOf(cl[e]) > base)) e++
        val from = if (e - s > max) maxOf(s, i - max / 2) else s
        val to = minOf(e, from + max)
        return (from until to).joinToString("\n") { "${it + 1}: ${cl[it]}" }
    }

    private val FRAME = Regex("""(?m)^([\w./-]+\.py):(\d+)(?::|\s)""")
    private val TEST_PATH = Regex("""(^|/)(tests?|testing)/|(^|/)test_[^/]*\.py$|_test\.py$|conftest\.py$""")

    /**
     * After a failing pytest run: the deepest frame in a source file (not a test), with the lines of its function, so Nano sees
     * where to fix without reading the whole file. Null when the output names no source frame.
     */
    fun whereFailed(output: String, read: (String) -> String?): String? {
        if (!Regex("""(?i)\bfailed\b|\berror\b|Traceback""").containsMatchIn(output)) return null
        val frames = FRAME.findAll(output).map { it.groupValues[1].removePrefix("./") to it.groupValues[2].toInt() }.toList()
        val src = frames.lastOrNull { !TEST_PATH.containsMatchIn(it.first) } ?: return null
        val text = read(src.first) ?: return null
        return "Where it failed in the code: ${src.first} line ${src.second}, in these lines:\n" + functionAt(text, src.second)
    }
}
