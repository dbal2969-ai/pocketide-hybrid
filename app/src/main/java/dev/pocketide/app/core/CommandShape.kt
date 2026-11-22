package dev.pocketide.app.core

/**
 * Shortens the output of well-known commands before the assistant reads it, keeping what matters for that command (the idea
 * behind RTK, written for PocketIDE): a test run keeps its failures and summary, git status drops its hints, git log becomes one
 * line per commit, an install keeps its result and errors. Output it does not recognise is returned unchanged. Pure logic.
 */
object CommandShape {
    /** Output shorter than this is left alone: it is cheap and may be the whole answer. */
    const val MIN_CHARS = 800
    private const val STACK_KEEP = 3

    private val TEST = Regex("""\b(npm (run )?test|npm t\b|npx (jest|vitest|mocha)|yarn (run )?test|pnpm (run )?test|vitest|jest|mocha|pytest|python3? -m (pytest|unittest)|go test|cargo test|gradlew? test|\./gradlew \w*[tT]est|mvn test|node --test|deno test|bun test)\b""")
    private val GIT_STATUS = Regex("""\bgit status\b(?!.*(--short|-s\b|--porcelain))""")
    private val GIT_LOG = Regex("""\bgit log\b(?!.*(--oneline|--format|--pretty))""")
    private val GIT_DIFF = Regex("""\bgit (diff|show)\b""")
    private val NPM_INSTALL = Regex("""\b(npm (install|i|ci|add)|yarn( add| install)?$|pnpm (install|add|i))\b""")
    private val PIP_INSTALL = Regex("""\bpip3? install\b|-m pip install\b""")

    // a test that passed: jest/vitest ✓, mocha ✔, pytest PASSED, go --- PASS / === RUN, cargo "test x ... ok", tap "ok 1"
    private val PASSED = Regex("""^\s*(✓|✔|√|\[\s*PASS\s*]|=== RUN\b|=== PAUSE\b|=== CONT\b|--- PASS\b|ok \d+ |PASS\s+\S+\.(js|ts|tsx|jsx|mjs)\b).*|.*\bPASSED\b.*|^test .+ \.\.\. ok$""")
    // pytest progress such as "tests/test_a.py ....    [ 50%]" (no F or E in it)
    private val PROGRESS_DOTS = Regex("""^\S+\.py \.+\s*\[\s*\d+%]\s*$""")
    private val STACK = Regex("""^\s+at |^\s+File ".+", line \d+""")
    private val GIT_HINT = Regex("""^\s*\(use "git .+\)\s*$|^\s*\(commit or discard .+\)\s*$""")
    private val INDEX_LINE = Regex("""^index [0-9a-f]+\.\.[0-9a-f]+""")
    private val NPM_NOISE = Regex("""^npm (WARN|warn) deprecated\b.*""")
    private val NPM_KEEP = Regex("""\b(added|removed|changed|audited|up to date|vulnerabilit|found \d+|ERR!|error)\b""", RegexOption.IGNORE_CASE)
    private val PIP_NOISE = Regex("""^\s*(Collecting|Downloading|Using cached|Requirement already satisfied|Obtaining|Preparing metadata|Building wheel|Created wheel|Stored in directory|Installing collected packages|Getting requirements|Installing build dependencies|  Downloading)\b.*""")

    /** The shortened output, or [output] unchanged when the command is not one it knows or the output is short. */
    fun shape(command: String, code: Int, output: String): String {
        if (output.length < MIN_CHARS) return output
        val c = command.trim()
        val lines = output.replace("\r\n", "\n").split('\n')
        return when {
            TEST.containsMatchIn(c) -> tests(lines, code)
            GIT_LOG.containsMatchIn(c) -> gitLog(lines)
            GIT_STATUS.containsMatchIn(c) -> keep(lines, "hint line") { !GIT_HINT.matches(it) }
            GIT_DIFF.containsMatchIn(c) -> keep(lines, "index line") { !INDEX_LINE.containsMatchIn(it) }
            NPM_INSTALL.containsMatchIn(c) -> install(lines, code, { NPM_NOISE.matches(it) }, { NPM_KEEP.containsMatchIn(it) || it.startsWith("npm ERR") })
            PIP_INSTALL.containsMatchIn(c) -> install(lines, code, { PIP_NOISE.matches(it) }, { it.startsWith("Successfully installed") || it.contains("ERROR") || it.contains("error:") })
            else -> output
        }
    }

    private fun keep(lines: List<String>, what: String, ok: (String) -> Boolean): String {
        val kept = lines.filter(ok)
        val dropped = lines.size - kept.size
        return kept.joinToString("\n") + if (dropped > 0) "\n[$dropped $what${if (dropped == 1) "" else "s"} left out]" else ""
    }

    /** A passing run: its summary. A failing run: everything except passing tests, with long stack traces cut. */
    private fun tests(lines: List<String>, code: Int): String {
        val body = lines.filter { it.isNotBlank() }
        if (code == 0) {
            val tail = body.takeLast(8)
            val dropped = body.size - tail.size
            return (if (dropped > 0) "[all tests passed; ${dropped} lines of passing output left out]\n" else "") + tail.joinToString("\n")
        }
        val out = ArrayList<String>()
        var passed = 0; var stackRun = 0; var stackCut = 0
        for (l in lines) {
            if (PASSED.matches(l) || PROGRESS_DOTS.matches(l)) { passed++; continue }
            if (STACK.containsMatchIn(l)) {
                stackRun++
                if (stackRun > STACK_KEEP) { stackCut++; continue }
            } else {
                if (stackCut > 0) { out.add("      … $stackCut more stack line${if (stackCut == 1) "" else "s"}"); stackCut = 0 }
                stackRun = 0
            }
            out.add(l)
        }
        if (stackCut > 0) out.add("      … $stackCut more stack line${if (stackCut == 1) "" else "s"}")
        return (if (passed > 0) "[$passed passing test line${if (passed == 1) "" else "s"} left out; failures and summary below]\n" else "") + out.joinToString("\n")
    }

    /** "commit abc…\nAuthor: …\nDate: …\n\n    subject" becomes "abc1234 date author: subject". */
    private fun gitLog(lines: List<String>): String {
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (!l.startsWith("commit ")) { i++; continue }
            val hash = l.removePrefix("commit ").trim().take(7)
            var author = ""; var date = ""; var subject = ""
            var j = i + 1
            while (j < lines.size && !lines[j].startsWith("commit ")) {
                val t = lines[j]
                when {
                    t.startsWith("Author:") -> author = t.removePrefix("Author:").trim().substringBefore(" <")
                    t.startsWith("Date:") -> date = t.removePrefix("Date:").trim()
                    subject.isEmpty() && t.startsWith("    ") && t.isNotBlank() -> subject = t.trim()
                }
                j++
            }
            out.add("$hash $date $author: $subject")
            i = j
        }
        return if (out.isEmpty()) lines.joinToString("\n") else out.joinToString("\n")
    }

    /** An install keeps its result lines and errors; a failing one keeps everything but the noise. */
    private fun install(lines: List<String>, code: Int, noise: (String) -> Boolean, result: (String) -> Boolean): String {
        val noisy = lines.count(noise)
        val kept = if (code == 0) lines.filter { result(it) } else lines.filter { !noise(it) && it.isNotBlank() }
        if (kept.isEmpty()) return lines.filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
        return kept.joinToString("\n") + if (noisy > 0) "\n[$noisy download and deprecation line${if (noisy == 1) "" else "s"} left out]" else ""
    }
}
