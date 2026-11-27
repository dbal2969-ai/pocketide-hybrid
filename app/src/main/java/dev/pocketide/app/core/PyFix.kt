package dev.pocketide.app.core

/**
 * The Python fix path for Gemini Nano (1.5.201), after Agentless (Xia et al. 2024) and the whole-block edit formats Aider recommends
 * for weaker models. In Python run 1126-1710 (1 of 13) 78 of Nano's 309 steps were refused, most of them search text that did not
 * match the file exactly. Here the app does the steps Nano got wrong:
 *  1. it runs the failing test itself;
 *  2. it lists the file's functions, classes and constants ([units]) and asks Nano which one the fix belongs in ([pickPrompt]),
 *     adding its own guesses ([guesses]);
 *  3. it shows Nano that one block whole and asks for the complete fixed block ([fixPrompt]), several times, the first at the
 *     person's temperature and the rest higher, so the answers differ;
 *  4. it puts each answer in place ([candidate], [splice]) and runs the test: the first that passes is kept. If none passes the file
 *     is put back and Nano carries on with its usual tools.
 * Nano never copies search text and never picks a tool. Pure logic; NativeAgent runs it.
 */
object PyFix {
    /** A function, class or constant: [start] is its first line (decorators included), [head] its def/class/name line, [end] the line after it (0-based). */
    data class Unit(val name: String, val kind: String, val start: Int, val head: Int, val end: Int, val indent: Int, val qual: String = name)

    private val HEAD = Regex("""^( *)(?:(async\s+def|def|class)\s+(\w+)|([A-Za-z_]\w*)\s*(?::[^=]+)?=(?!=))""")
    private fun indentOf(l: String) = l.length - l.trimStart(' ').length

    /** Where a block that starts at line [i] ends (exclusive): a def or class runs while lines are indented deeper; a constant while brackets or a triple-quoted string are open. */
    fun endOf(lines: List<String>, i: Int): Int {
        val base = indentOf(lines[i])
        val m = HEAD.find(lines[i])
        var depth = 0; var tri: String? = null; var k = i
        // the head itself: until its brackets and strings close (a def over several lines, a constant with a long string)
        while (k < lines.size) {
            val l = lines[k]; var j = 0
            while (j < l.length) {
                if (tri != null) { if (l.startsWith(tri, j)) { j += 3; tri = null } else j++; continue }
                val c = l[j]
                when {
                    l.startsWith("\"\"\"", j) || l.startsWith("'''", j) -> { tri = l.substring(j, j + 3); j += 3 }
                    c == '#' -> j = l.length
                    c == '"' || c == '\'' -> { j++; while (j < l.length && l[j] != c) { if (l[j] == '\\') j++; j++ }; j++ }
                    c == '(' || c == '[' || c == '{' -> { depth++; j++ }
                    c == ')' || c == ']' || c == '}' -> { depth--; j++ }
                    else -> j++
                }
            }
            k++
            if (tri == null && depth <= 0 && !l.trimEnd().endsWith("\\")) break
        }
        if (m?.groupValues?.get(2).isNullOrEmpty()) return k   // a constant
        while (k < lines.size && (lines[k].isBlank() || indentOf(lines[k]) > base)) k++
        while (k > i + 1 && lines[k - 1].isBlank()) k--
        return k
    }

    /** The functions, classes and constants of [text], methods and class constants included, in file order. */
    fun units(text: String): List<Unit> {
        val lines = text.split("\n")
        val out = ArrayList<Unit>()
        var i = 0
        // which lines are inside a string or a def/constant's continuation, so their "x = 1" is not taken for a constant
        var skipTo = 0
        while (i < lines.size) {
            val l = lines[i]
            val m = if (i >= skipTo) HEAD.find(l) else null
            if (m == null) { i++; continue }
            val indent = m.groupValues[1].length
            val kind = m.groupValues[2].ifEmpty { "constant" }.replace(Regex("""async\s+def"""), "def")
            val name = m.groupValues[3].ifEmpty { m.groupValues[4] }
            // constants: module or class level only, and UPPER_CASE or a class attribute (ReprHighlighter.highlights)
            val inClass = indent > 0 && out.any { it.kind == "class" && it.indent < indent && i < it.end }
            val inDef = out.any { it.kind == "def" && it.indent < indent && i < it.end }
            if (kind == "constant" && (inDef || !(indent == 0 && name.uppercase() == name || inClass))) { skipTo = endOf(lines, i); i = skipTo; continue }
            var s = i
            while (s > 0 && lines[s - 1].trimStart().startsWith("@") && indentOf(lines[s - 1]) == indent) s--
            val e = endOf(lines, i)
            val parent = out.filter { it.kind != "constant" && it.indent < indent && i < it.end }.maxByOrNull { it.indent }
            out.add(Unit(name, kind, s, i, e, indent, if (parent == null) name else parent.qual + "." + name))
            // a constant's continuation lines are not heads; a def's or class's body is searched for methods
            if (kind == "constant") { skipTo = e; i = e } else { skipTo = i + 1; i++ }
        }
        return out
    }

    /** The block's text. */
    fun code(text: String, u: Unit): String = text.split("\n").subList(u.start, u.end).joinToString("\n")

    // ---- the request ----

    private val TEST_PATH = Regex("""(^|/)(tests?|testing)/|(^|/)test_[^/]*\.py$|_test\.py$|conftest\.py$""")

    /** The source file the request asks to fix: a .py path it names that is in the project and is not a test. */
    fun target(request: String, paths: Collection<String>): String? {
        val named = Regex("""[\w./-]+\.py\b""").findAll(request).map { it.value.removePrefix("./") }.toList()
        return named.firstOrNull { it in paths && !TEST_PATH.containsMatchIn(it) }
    }

    /** The test command the request gives, in backticks: one that runs pytest or unittest. */
    fun testCommand(request: String): String? =
        Regex("""`([^`\n]*\b(?:pytest|unittest)\b[^`\n]*)`""").find(request)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    // ---- asking Nano ----

    /** The issue as the request gives it, without the app's project context. */
    fun issue(request: String, max: Int = 2500): String = request.substringAfterLast("My request:").trim().let { if (it.length > max) it.take(max) + " … [cut]" else it }

    fun pickPrompt(path: String, text: String, us: List<Unit>, request: String, failure: String): String {
        val lines = text.split("\n")
        val list = us.take(150).joinToString("\n") { "${it.head + 1}: ${lines[it.head].trim().take(90)}" }
        return "You are fixing a bug in $path.\n\nTHE TASK:\n${issue(request)}\n\nTHE TEST FAILS LIKE THIS:\n${Verify.trim(failure, 1500)}\n\n" +
            "The functions, classes and constants in $path, with line numbers:\n$list\n\n" +
            "Which one must change to fix the bug? Reply with its name only (two names, comma-separated, if you are unsure)."
    }

    /** The names in Nano's pick reply that are units, in the order it gave them. */
    fun picked(reply: String, us: List<Unit>): List<String> {
        val names = us.flatMap { listOf(it.name, it.qual) }.toSet()
        return Regex("""[A-Za-z_](?:[\w.]*\w)?""").findAll(reply).map { it.value }.filter { it in names }.distinct().take(3).toList()
    }

    /** The app's own guesses: the unit the request names most, the ones the failing test calls, the one a traceback points into. */
    fun guesses(path: String, text: String, us: List<Unit>, request: String, failure: String): List<String> {
        val out = ArrayList<String>()
        PyHelper.relevantDef(text, request)?.let { at -> us.lastOrNull { it.head == at - 1 }?.let { out.add(it.qual) } }
        Regex("""(?m)^\S*?${Regex.escape(path.substringAfterLast('/'))}:(\d+)""").findAll(failure).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.let { ln ->
            us.filter { ln - 1 in it.start until it.end }.minByOrNull { it.end - it.start }?.let { out.add(it.qual) }
        }
        val called = Regex("""(?:\.|\b)([A-Za-z_]\w{2,})\(""").findAll(failure + "\n" + issue(request)).map { it.groupValues[1] }.toSet()
        us.filter { it.kind == "def" && it.name in called && it.name != "__init__" }.forEach { out.add(it.qual) }
        shared(text, us, request + "\n" + failure)?.let { out.add(it) }
        // a long class is too much to rewrite: its member that shares most with the test instead (rich_3454: ReprHighlighter.highlights)
        return out.flatMap { q ->
            val u = unitNamed(us, q)
            if (u == null || u.kind != "class" || u.end - u.start <= BIG) listOf(q)
            else listOfNotNull(shared(text, us.filter { it.start > u.head && it.end <= u.end }, request + "\n" + failure))
        }.distinct()
    }

    /** Lines over which a class is narrowed to one of its members. */
    const val BIG = 60

    private val COMMON = setOf("self", "assert", "return", "None", "True", "False", "import", "from", "def", "class", "with", "test",
        "tests", "expected", "result", "value", "print", "console", "fixture", "pytest", "mark", "parametrize", "str", "int", "list", "dict",
        "isinstance", "raise", "lambda", "else", "elif", "while", "for", "this", "that", "should", "when", "issue", "fix", "the", "and", "not")

    /**
     * The block that shares the most distinctive words with the test and its failure (rich_3043: the expected HTML is in a template
     * constant no name in the request points to). Words of 4+ letters, each counted once; ties go to the shorter block. Null when none
     * shares at least three.
     */
    fun shared(text: String, us: List<Unit>, about: String): String? {
        val want = Regex("""[A-Za-z_][A-Za-z0-9_-]{3,}""").findAll(about).map { it.value }.filter { it !in COMMON }.toSet()
        if (want.isEmpty()) return null
        var best: Unit? = null; var score = 2
        for (u in us) {
            if (u.kind == "class" || u.end - u.start > 150) continue
            val n = Regex("""[A-Za-z_][A-Za-z0-9_-]{3,}""").findAll(code(text, u)).map { it.value }.filter { it in want }.toSet().size
            if (n > score || (n == score && best != null && u.end - u.start < best.end - best.start)) { score = n; best = u }
        }
        return best?.qual
    }

    /** The unit called [name] ("Class.method" or a bare name); with several of a bare name (methods of different classes), the longest. */
    fun unitNamed(us: List<Unit>, name: String): Unit? = us.firstOrNull { it.qual == name } ?: us.filter { it.name == name }.maxByOrNull { it.end - it.start }

    fun fixPrompt(path: String, text: String, u: Unit, request: String, failure: String, tried: String = ""): String {
        val what = when (u.kind) { "class" -> "class ${u.name}"; "constant" -> u.name; else -> "function ${u.name}" }
        return "You are fixing a bug in $path.\n\nTHE TASK:\n${issue(request)}\n\nTHE TEST FAILS LIKE THIS:\n${Verify.trim(failure, 1500)}\n\n" +
            "The bug is in $what (lines ${u.start + 1}-${u.end} of $path):\n```python\n${code(text, u)}\n```\n" + tried +
            "\nWrite the whole fixed $what: every line of it, from its first line to its last, changed only where the fix needs it, " +
            "with the same name. Reply with one ```python block and nothing else."
    }

    // ---- Nano's answer ----

    /**
     * The block in Nano's [reply] that replaces [u], indented to [u]'s level; null when it has no such block or the block is the same
     * as before. Decorators written before it are kept with it.
     */
    fun candidate(reply: String, old: String, u: Unit): String? {
        val body = Regex("""```(?:python|py)?[ \t]*\n(.*?)(?:```|$)""", RegexOption.DOT_MATCHES_ALL).find(reply)?.groupValues?.get(1) ?: reply
        val lines = body.trimEnd().split("\n")
        val want = when (u.kind) {
            "constant" -> Regex("""^\s*${Regex.escape(u.name)}\s*(?::[^=]+)?=""")
            "class" -> Regex("""^\s*class\s+${Regex.escape(u.name)}\b""")
            else -> Regex("""^\s*(?:async\s+)?def\s+${Regex.escape(u.name)}\s*\(""")
        }
        val h = lines.indexOfFirst { want.containsMatchIn(it) }.takeIf { it >= 0 } ?: return null
        var s = h
        while (s > 0 && lines[s - 1].trimStart().startsWith("@")) s--
        val e = endOf(lines, h)
        val block = lines.subList(s, e)
        val ind = indentOf(lines[h])
        val moved = block.map { l -> if (l.isBlank()) "" else if (indentOf(l) >= ind) " ".repeat(u.indent) + l.substring(ind) else " ".repeat(u.indent) + l.trimStart() }
        val out = moved.joinToString("\n")
        fun norm(x: String) = x.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.joinToString("\n")
        val oldBlock = old.split("\n").subList(if (s < h) u.start else u.head, u.end).joinToString("\n")
        return if (norm(out) == norm(oldBlock)) null else out
    }

    /** [text] with [u] replaced by [block] (from its decorators when the block has them, else from its def line). */
    fun splice(text: String, u: Unit, block: String): String {
        val lines = text.split("\n")
        val from = if (block.trimStart().startsWith("@")) u.start else u.head
        return (lines.subList(0, from) + block.split("\n") + lines.subList(u.end, lines.size)).joinToString("\n")
    }

    /** The temperature of try [k] (0-based): the person's first, then higher so the answers differ. */
    fun temperature(k: Int, base: Float): Float = if (k == 0) base else maxOf(base, Tuning.pyFixTemperature)

    /** The note shown to Nano after the fix path tried and none passed, with the block it was about. */
    fun gaveUp(path: String, name: String, tries: Int, last: String): String =
        "Not written: PocketIDE tried $tries rewrites of $name in $path and ran the test after each; none passed, so $path is unchanged. " +
            "The test's last output:\n" + Verify.trim(last, 1200) + "\nFix it with your tools now (read_file, replace_function, edit_file)."
}
