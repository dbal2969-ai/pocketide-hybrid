package dev.pocketide.app.core

/**
 * v0.4 W3: turns compiler, interpreter and test output into a list of problems (file, line, message)
 * for the Problems panel. Pure logic, no Android imports.
 *
 * Understands Python tracebacks and SyntaxErrors (python3 and py_compile), Node stack traces and
 * `node --check`, TypeScript (tsc, both styles), Go (build, vet, test), gcc/clang, javac, Rust (rustc/cargo)
 * and shell scripts. Paths are made project-relative: /work/ (the project inside Linux), ./, and any
 * extra prefixes (the project's real folder on Android) are stripped; paths elsewhere are dropped.
 */
enum class Severity { ERROR, WARNING }

data class Problem(val file: String, val line: Int, val col: Int, val severity: Severity, val message: String) {
    fun where(): String = if (col > 0) "$file:$line:$col" else "$file:$line"
}

object Problems {
    private val ANSI = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")

    // gcc/clang/go/generic:  file:line:col: [error|warning|fatal error|note:] message
    private val GENERIC = Regex("""^(?:vet:\s*|#\s*)?((?:\.{0,2}/)?[\w@+\-./]+\.[A-Za-z0-9]+):(\d+):(?:(\d+):)?\s*(?:(fatal error|error|warning|note|info)\s*:\s*)?(.+)$""")
    // tsc old style:  src/a.ts(3,5): error TS2322: ...
    private val TSC_PAREN = Regex("""^([\w@+\-./]+\.[A-Za-z0-9]+)\((\d+),(\d+)\):\s*(error|warning)\s+(TS\d+:\s*.+)$""")
    // tsc new style:  src/a.ts:3:5 - error TS2322: ...
    private val TSC_DASH = Regex("""^([\w@+\-./]+\.[A-Za-z0-9]+):(\d+):(\d+)\s+-\s+(error|warning)\s+(TS\d+:\s*.+)$""")
    // javac:  Main.java:3: error: ';' expected
    private val JAVAC = Regex("""^([\w@+\-./]+\.java):(\d+):\s*(error|warning):\s*(.+)$""")
    // Python frame:  File "/work/app.py", line 12, in <module>
    private val PY_FRAME = Regex("""^\s*File "([^"]+)", line (\d+)""")
    // old py_compile / IndentationError:  ... (app.py, line 3)
    private val PY_PAREN = Regex("""^(?:Sorry:\s*)?(\w*(?:Error|Exception)):?\s*(.*)\(([\w@+\-./]+\.py), line (\d+)\)\s*$""")
    private val EXC_LINE = Regex("""^(\w+(?:\.\w+)*(?:Error|Exception|Exit|Interrupt|Warning)|AssertionError|KeyboardInterrupt|StopIteration)(?::\s*(.*))?$""")
    // Node: header "/work/app.js:5" then later "ReferenceError: foo is not defined"
    private val NODE_HEAD = Regex("""^((?:/|\./)?[\w@+\-./]+\.(?:m?js|cjs|ts|mts|jsx|tsx)):(\d+)$""")
    private val NODE_FRAME = Regex("""^\s*at (?:.*?\()?((?:file://)?(?:/|\./)?[\w@+\-./]+\.(?:m?js|cjs|ts|mts|jsx|tsx)):(\d+):(\d+)\)?\s*$""")
    private val JS_ERROR = Regex("""^(\w*Error|Error)(?:\s*\[[\w_]+\])?:\s*(.*)$""")
    // Rust: "error[E0425]: msg" then " --> src/main.rs:2:5"
    private val RUST_MSG = Regex("""^(error|warning)(?:\[\w+\])?:\s*(.+)$""")
    private val RUST_AT = Regex("""^\s*-->\s*([\w@+\-./]+\.rs):(\d+):(\d+)""")
    // sh/bash/ash:  run.sh: line 3: foo: not found
    private val SH_LINE = Regex("""^((?:\./)?[\w@+\-./]+\.(?:sh|bash)):\s*line (\d+):\s*(.+)$""")
    // dash/ash -n style:  run.sh: 3: Syntax error: ...
    private val SH_NUM = Regex("""^((?:\./)?[\w@+\-./]+\.(?:sh|bash)):\s*(\d+):\s*(.+)$""")

    /**
     * Parses output into problems. prefixes are extra absolute folders that mean "the project"
     * (for runs outside Linux). Results are de-duplicated and capped at 100.
     */
    fun parse(output: String, prefixes: List<String> = emptyList()): List<Problem> {
        val lines = output.replace("\r", "").split("\n").map { ANSI.replace(it, "") }
        val out = LinkedHashMap<String, Problem>()
        fun add(path: String, line: Int, col: Int, sev: Severity, msg: String) {
            val rel = relative(path, prefixes) ?: return
            if (line <= 0 || msg.isBlank()) return
            val p = Problem(rel, line, col, sev, msg.trim().take(400))
            out.putIfAbsent("${p.file}:${p.line}:${p.message}", p)
        }

        var i = 0
        while (i < lines.size && out.size < 100) {
            val l = lines[i]

            // --- Python traceback / SyntaxError: collect frames up to the exception line ---
            val frame = PY_FRAME.find(l)
            if (frame != null) {
                val frames = ArrayList<Pair<String, Int>>()
                var j = i
                var exc: String? = null
                while (j < lines.size) {
                    val f = PY_FRAME.find(lines[j])
                    if (f != null) { frames.add(f.groupValues[1] to f.groupValues[2].toInt()); j++; continue }
                    val e = EXC_LINE.matchEntire(lines[j].trim())
                    if (e != null && !lines[j].startsWith(" ")) { exc = lines[j].trim(); break }
                    if (lines[j].startsWith("Traceback") || lines[j].isBlank() && j > i + 12) break
                    j++
                }
                if (exc != null) {
                    // report at the deepest frame inside the project
                    val mine = frames.lastOrNull { relative(it.first, prefixes) != null }
                    if (mine != null) add(mine.first, mine.second, 0, Severity.ERROR, exc)
                    i = j + 1
                    continue
                }
                i++
                continue
            }
            val pyParen = PY_PAREN.find(l)
            if (pyParen != null) {
                add(pyParen.groupValues[3], pyParen.groupValues[4].toInt(), 0, Severity.ERROR,
                    (pyParen.groupValues[1] + ": " + pyParen.groupValues[2]).trim().trimEnd(':'))
                i++
                continue
            }

            // --- Node: "file.js:5" header, then the error line a few lines below ---
            val head = NODE_HEAD.matchEntire(l.trim())
            if (head != null) {
                var j = i + 1
                var msg: String? = null
                while (j < lines.size && j <= i + 8) {
                    val e = JS_ERROR.matchEntire(lines[j].trim())
                    if (e != null) { msg = lines[j].trim(); break }
                    j++
                }
                if (msg != null) {
                    add(head.groupValues[1], head.groupValues[2].toInt(), 0, Severity.ERROR, msg)
                    // skip its stack trace
                    j++
                    while (j < lines.size && NODE_FRAME.matches(lines[j])) j++
                    i = j
                    continue
                }
            }
            // a JS error with only a stack trace: report at the first project frame
            val jsErr = JS_ERROR.matchEntire(l.trim())
            if (jsErr != null && i + 1 < lines.size && NODE_FRAME.matches(lines[i + 1])) {
                var j = i + 1
                var reported = false
                while (j < lines.size && NODE_FRAME.matches(lines[j])) {
                    val f = NODE_FRAME.matchEntire(lines[j])!!
                    if (!reported && relative(f.groupValues[1], prefixes) != null) {
                        add(f.groupValues[1], f.groupValues[2].toInt(), f.groupValues[3].toInt(), Severity.ERROR, l.trim())
                        reported = true
                    }
                    j++
                }
                i = j
                continue
            }

            // --- Rust ---
            val rm = RUST_MSG.matchEntire(l)
            if (rm != null && i + 1 < lines.size) {
                val at = (i + 1..minOf(i + 3, lines.size - 1)).firstNotNullOfOrNull { RUST_AT.find(lines[it]) }
                if (at != null) {
                    add(at.groupValues[1], at.groupValues[2].toInt(), at.groupValues[3].toInt(),
                        if (rm.groupValues[1] == "warning") Severity.WARNING else Severity.ERROR, rm.groupValues[2])
                    i++
                    continue
                }
            }

            // --- one-line formats ---
            val t = l.trim()
            val tp = TSC_PAREN.matchEntire(t)
            val td = if (tp == null) TSC_DASH.matchEntire(t) else null
            val jv = if (tp == null && td == null) JAVAC.matchEntire(t) else null
            val sl = SH_LINE.matchEntire(t)
            val sn = if (sl == null) SH_NUM.matchEntire(t) else null
            when {
                tp != null -> add(tp.groupValues[1], tp.groupValues[2].toInt(), tp.groupValues[3].toInt(), sev(tp.groupValues[4]), tp.groupValues[5])
                td != null -> add(td.groupValues[1], td.groupValues[2].toInt(), td.groupValues[3].toInt(), sev(td.groupValues[4]), td.groupValues[5])
                jv != null -> add(jv.groupValues[1], jv.groupValues[2].toInt(), 0, sev(jv.groupValues[3]), jv.groupValues[4])
                sl != null -> add(sl.groupValues[1], sl.groupValues[2].toInt(), 0, Severity.ERROR, sl.groupValues[3])
                sn != null -> add(sn.groupValues[1], sn.groupValues[2].toInt(), 0, Severity.ERROR, sn.groupValues[3])
                else -> {
                    val m = GENERIC.matchEntire(t)
                    if (m != null) {
                        val kind = m.groupValues[4]
                        val file = m.groupValues[1]
                        // skip notes, and file:line references that are not source files
                        if (kind != "note" && kind != "info" && looksLikeSource(file)) {
                            add(file, m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull() ?: 0,
                                if (kind == "warning") Severity.WARNING else Severity.ERROR, m.groupValues[5])
                        }
                    }
                }
            }
            i++
        }
        return out.values.toList()
    }

    private fun sev(s: String) = if (s == "warning") Severity.WARNING else Severity.ERROR

    private val SOURCE_EXT = setOf("c", "h", "cc", "cpp", "hpp", "cxx", "go", "rs", "java", "kt", "py", "js", "mjs", "cjs", "ts", "tsx",
        "jsx", "mts", "rb", "php", "swift", "cs", "zig", "lua", "sh", "html", "css", "scss", "vue", "svelte", "json", "toml", "yaml", "yml", "mod")

    private fun looksLikeSource(f: String) = f.substringAfterLast('.', "").lowercase() in SOURCE_EXT

    /** A project-relative path, or null for files outside the project (libraries, the standard library, <stdin>). */
    fun relative(path: String, prefixes: List<String> = emptyList()): String? {
        var p = path.trim().removePrefix("file://")
        if (p.isEmpty() || p.startsWith("<") || p.contains("node:internal") || p.contains("node_modules/") || p.contains("/site-packages/")) return null
        for (pre in listOf("/work/") + prefixes.map { it.trimEnd('/') + "/" }) {
            if (p.startsWith(pre)) { p = p.substring(pre.length); break }
        }
        if (p.startsWith("/")) return null
        while (p.startsWith("./")) p = p.substring(2)
        if (p.isEmpty() || p.split('/').contains("..")) return null
        return p
    }

    /** Text for "Ask AI to fix": the problems, then the end of the output they came from. */
    fun askText(problems: List<Problem>, output: String, command: String?): String {
        val sb = StringBuilder()
        sb.append(if (command.isNullOrBlank()) "Running the project reported these problems:\n" else "Running `$command` reported these problems:\n")
        for (p in problems.take(20)) sb.append("- ").append(p.where()).append(": ").append(p.message).append('\n')
        val tail = output.replace("\r", "").lines().takeLast(40).joinToString("\n").trim()
        if (tail.isNotEmpty()) sb.append("\nOutput:\n```\n").append(tail.takeLast(3000)).append("\n```\n")
        sb.append("\nPlease fix them.")
        return sb.toString()
    }
}

/** v0.4 W3: finds the address of a local server in its output ("Listening on http://localhost:3000"). */
object ServerUrls {
    private val ANSI = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")
    private val URL = Regex("""https?://(localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1?\]|\[::\])(?::(\d{2,5}))?(/[^\s'"<>)\]]*)?""", RegexOption.IGNORE_CASE)
    private val PORT_PHRASE = Regex("""(?:listening|running|serving|started|available|server)\b[^\n]{0,40}?\b(?:on\s+)?(?:port\s+|:)(\d{2,5})\b""", RegexOption.IGNORE_CASE)

    /** Local URLs mentioned in text, normalised to http://127.0.0.1:PORT[/path], in order of appearance. */
    fun find(text: String): List<String> {
        val t = ANSI.replace(text, "")
        val out = LinkedHashSet<String>()
        for (m in URL.findAll(t)) {
            val port = m.groupValues[2].toIntOrNull()
            if (port != null && port !in 1..65535) continue
            var path = m.groupValues[3].trimEnd('.', ',', ';', ':')
            if (path == "/") path = ""
            out.add("http://127.0.0.1" + (if (port != null) ":$port" else "") + path)
        }
        if (out.isEmpty()) {
            for (m in PORT_PHRASE.findAll(t)) {
                val port = m.groupValues[1].toInt()
                if (port in 80..65535) out.add("http://127.0.0.1:$port")
            }
        }
        return out.toList()
    }

    /** The port of a normalised URL, or null. */
    fun port(url: String): Int? = Regex("""^[a-z]+://[^/:]+:(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
}
