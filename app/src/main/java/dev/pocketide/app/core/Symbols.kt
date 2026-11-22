package dev.pocketide.app.core

/**
 * A light index of where things are defined across the project: functions, classes, types, constants,
 * CSS rules and HTML ids. Pattern-based, not a compiler, so it is fast on a phone and works for many languages.
 */
object Symbols {
    data class Symbol(val name: String, val kind: String, val path: String, val line: Int, val text: String)

    private class Rule(val kind: String, val regex: Regex, val group: Int = 1)

    private val CODE = listOf(
        Rule("function", Regex("""^\s*(?:export\s+)?(?:public\s+|private\s+|protected\s+|internal\s+|static\s+|final\s+|open\s+|override\s+|suspend\s+|async\s+|inline\s+)*(?:fun|function|def|func|fn)\s+([A-Za-z_$][\w$]*)""")),
        Rule("class", Regex("""^\s*(?:export\s+)?(?:public\s+|private\s+|protected\s+|internal\s+|abstract\s+|final\s+|open\s+|sealed\s+|data\s+|static\s+)*(class|interface|object|enum|struct|trait|protocol|record)\s+([A-Za-z_$][\w$]*)""", RegexOption.IGNORE_CASE), 2),
        Rule("type", Regex("""^\s*(?:export\s+)?type\s+([A-Za-z_$][\w$]*)\s*[=<]""")),
        Rule("value", Regex("""^\s*(?:export\s+)?(?:const|let|var|val)\s+([A-Za-z_$][\w$]*)\s*(?::[^=]+)?=\s*(?:function|\([^)]*\)\s*=>|async|\{|\[|new\s)""")),
        Rule("value", Regex("""^\s*(?:export\s+)?(?:const|final|static)\s+[\w<>\[\].]+\s+([A-Z_][A-Z0-9_]{2,})\s*=""")),
        Rule("method", Regex("""^\s*func\s+\([^)]*\)\s*([A-Za-z_]\w*)""")),
        Rule("rule", Regex("""^\s*([.#][A-Za-z_][\w\-]*(?:\s*[,>+~]\s*[.#]?[\w\-]+)*)\s*\{""")),
    )

    private val HTML_ID = Regex("""\bid\s*=\s*["']([\w\-]+)["']""")
    private val MD_HEAD = Regex("""^(#{1,4})\s+(.+?)\s*$""")
    private val TEXT_EXT = setOf("kt", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "go", "rs", "c", "cc", "cpp", "h", "hpp", "rb", "php", "cs", "swift", "scala", "css", "scss", "html", "htm", "md", "sh")

    fun indexable(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in TEXT_EXT && !RunTargets.ignored(path)

    /** Symbols defined in one file. */
    fun inFile(path: String, text: String, max: Int = 400): List<Symbol> {
        val ext = path.substringAfterLast('.', "").lowercase()
        val out = ArrayList<Symbol>()
        var inFence = false
        for ((i, raw) in text.lineSequence().withIndex()) {
            if (out.size >= max) break
            val line = if (raw.length > 400) raw.take(400) else raw
            if (ext == "md") {
                if (line.trimStart().startsWith("```")) { inFence = !inFence; continue }
                if (inFence) continue
                MD_HEAD.matchEntire(line)?.let { out.add(Symbol(it.groupValues[2], "heading", path, i + 1, line.trim())) }
                continue
            }
            if (ext == "html" || ext == "htm") {
                for (m in HTML_ID.findAll(line)) out.add(Symbol("#" + m.groupValues[1], "id", path, i + 1, line.trim()))
                continue
            }
            for (r in CODE) {
                if ((r.kind == "rule") != (ext == "css" || ext == "scss")) continue
                val m = r.regex.find(line) ?: continue
                val name = m.groupValues.getOrNull(r.group)?.trim() ?: continue
                if (name.isEmpty()) continue
                out.add(Symbol(name, r.kind, path, i + 1, line.trim().take(160)))
                break
            }
        }
        return out
    }

    /** Everything defined in the project, newest-sorted by path then line. [read] returns a file's text or null. */
    fun index(paths: Collection<String>, read: (String) -> String?, maxFiles: Int = 400): List<Symbol> {
        val out = ArrayList<Symbol>()
        for (p in paths.filter { indexable(it) }.take(maxFiles)) {
            val t = read(p) ?: continue
            if (t.length > 400_000) continue
            out.addAll(inFile(p, t))
            if (out.size > 4000) break
        }
        return out
    }

    /** An outline of one file for the model or the user. */
    fun outline(path: String, text: String): String {
        val s = inFile(path, text)
        if (s.isEmpty()) return "No definitions found in $path (${text.lines().size} lines)."
        return "$path (${text.lines().size} lines), ${s.size} definition${if (s.size == 1) "" else "s"}:\n" +
            s.joinToString("\n") { "  ${it.line}: ${it.kind} ${it.name}" }
    }

    private fun wordRegex(name: String) = Regex("""(?<![\w$])${Regex.escape(name)}(?![\w$])""")

    /** Where a name is defined and where it is used. */
    fun find(name: String, paths: Collection<String>, read: (String) -> String?, maxUses: Int = 60): String {
        val clean = name.trim().removePrefix(".").removePrefix("#")
        if (clean.isEmpty()) return "Give a name to look for."
        val defs = index(paths, read).filter { it.name.equals(name.trim(), true) || it.name.trimStart('.', '#').equals(clean, true) }
        val rx = wordRegex(clean)
        val uses = ArrayList<String>()
        var files = 0
        for (p in paths.filter { indexable(it) }) {
            val t = read(p) ?: continue
            if (!t.contains(clean)) continue
            files++
            for ((i, line) in t.lineSequence().withIndex()) {
                if (uses.size >= maxUses) break
                if (rx.containsMatchIn(line)) uses.add("$p:${i + 1}: ${line.trim().take(140)}")
            }
            if (uses.size >= maxUses) break
        }
        val sb = StringBuilder()
        sb.append(if (defs.isEmpty()) "No definition of '$clean' found.\n" else "Defined:\n" + defs.joinToString("\n") { "  ${it.path}:${it.line}: ${it.kind} ${it.name}" } + "\n")
        sb.append(if (uses.isEmpty()) "No uses found." else "Used (${uses.size}${if (uses.size >= maxUses) "+" else ""}):\n" + uses.joinToString("\n") { "  $it" })
        return sb.toString()
    }

    /** Names that look like they were meant to be something that exists: for "did you mean" hints. */
    fun similar(name: String, all: Collection<Symbol>, max: Int = 5): List<String> {
        val n = name.lowercase()
        return all.map { it.name }.distinct()
            .filter { it.lowercase() != n && (it.lowercase().contains(n) || n.contains(it.lowercase())) }
            .sortedBy { kotlin.math.abs(it.length - name.length) }.take(max)
    }

    /** A compact map of the project for a prompt: each file with its main definitions. */
    fun projectMap(paths: Collection<String>, read: (String) -> String?, maxChars: Int = 4000): String {
        val byFile = index(paths, read).groupBy { it.path }
        val sb = StringBuilder()
        for ((p, syms) in byFile) {
            if (sb.length > maxChars) { sb.append("…\n"); break }
            sb.append(p).append(": ").append(syms.take(12).joinToString(", ") { it.name }).append(if (syms.size > 12) ", …\n" else "\n")
        }
        return sb.toString().trim()
    }
}
