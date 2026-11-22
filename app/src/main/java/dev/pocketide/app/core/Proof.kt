package dev.pocketide.app.core

/**
 * Proof before "done": the app keeps its own record of the checks that actually ran in a request (commands, the page, play
 * tests, the project's tests), stops a summary that claims more than they show, and ends the reply with that record, so the
 * person reads what was checked from the app, not from the model's own account. Pure logic.
 */
object Proof {
    /**
     * Rounds the model may go on after a check that ran the program passed, with no file changed since (1.5.83). One run, its
     * work done and checked, kept auditing the same numbers with node, then python, then the shell for 13 minutes: different
     * commands, so the identical-call rule did not see it. Only rounds that do nothing but run checks count (reading for the next
     * step does not), and only when the plan has no open steps. Past this many such rounds it is asked for its final report.
     */
    const val AFTER_PASS_ROUNDS = 4

    /** True when a check that ran the program passed after the last change and before [round]. */
    fun passedBefore(checks: List<Check>, writeMark: Int, round: Int): Boolean =
        checks.any { it.runtime && it.ok == true && it.round >= writeMark && it.round < round }

    /** True when the run should report now: [checkRounds] check-only rounds after a pass, and no open plan steps. */
    fun checkedTooLong(checkRounds: Int, openSteps: Int): Boolean = checkRounds >= AFTER_PASS_ROUNDS && openSteps == 0

    /** One check that ran: what it was, whether it passed (null: no clear verdict), the round, and whether it ran the program. */
    class Check(val what: String, val ok: Boolean?, val round: Int, val runtime: Boolean)

    private val I = RegexOption.IGNORE_CASE
    private val SYNTAX_ONLY = Regex("""(--check\b|-m\s+py_compile|\bpy_compile\b|\bnode\s+-c\b|--noEmit|\bjq\s+(-e\s+)?empty\b|\bjson\.tool\b|\bsh\s+-n\b|\bbash\s+-n\b|\bkotlinc\b|\bjavac\b|\bgcc\s+-fsyntax-only)""", I)
    private val CHECK_COMMAND = Regex("""(\btest\b|\btests\b|pytest|jest|vitest|mocha|unittest|go\s+(test|vet|build|run)|cargo\s+(test|check|build|run)|\btsc\b|eslint|\bmake\b|gradlew|\bmvn\b|\bnode\s+\S|\bpython3?\s+\S|\bjava\s+\S|\bcurl\b|npm\s+(run|start|test)|npx\s|deno\s|bun\s|php\s|ruby\s|--check|py_compile|sh\s+-n)""", I)

    /** A command worth recording as a check (tests, running or compiling the program), not an install or a file operation. */
    fun isCheckCommand(cmd: String, exists: (String) -> Boolean = { false }): Boolean =
        !Regex("""^\s*(npm|pip3?|apk|apt(-get)?|yarn|pnpm)\s+(install|add|i)\b""", I).containsMatchIn(cmd) &&
            segments(cmd).any { realCheck(it, exists) }

    // 1.5.170 (HANDOFF-FABLE-TRUST-RESPONSE change 2): `ls tests`, `cat test.js`, `echo test`, `mkdir tests` and `node --version` all
    // counted as passed checks, ticked plan steps and turned the "nothing was checked" ask off. The 1.5.91 analysis saw Llama 3.3 take
    // "a module ran with no output" as proof. A check now has to run tests, or run or load something in the project.
    private val READER = Regex("""^\s*(ls|cat|echo|printf|grep|egrep|rg|find|head|tail|wc|mkdir|touch|cp|mv|rm|pwd|which|type|whereis|stat|file|tree|du|df|env|export|true|false|cd|sed|awk|sort|uniq|diff|less|more|date|whoami|id|uname|chmod|ln|tee|xargs|basename|dirname|realpath|git\s+(status|log|diff|show|branch))\b""", I)
    private val VERSION = Regex("""^\s*\S+\s+(--version|-v|-V|version|--help|-h)\s*$""", I)
    private val RUNNER = Regex("""\b(pytest|jest|vitest|mocha|ava|tap|unittest|go\s+test|go\s+vet|go\s+build|cargo\s+(test|check|build|run)|tsc|eslint|make|gradlew|mvn|npm\s+(test|t|start|run\s+\S+)|yarn\s+(test|run|\w+)|pnpm\s+(test|run)|npx\s+\S+|deno\s+(test|run)|bun\s+(test|run)|node\s+--test|python3?\s+-m\s+(pytest|unittest|doctest))\b""", I)
    private val INTERP = Regex("""^\s*(?:timeout\s+\d+\s+)?(node|nodejs|python3?|deno|bun|ruby|php|java|bash|sh|perl|lua)\b([\s\S]*)$""", I)
    private val PATH_IN = Regex("""(?:^|[\s'"(=])(\.{0,2}/?[\w@.-]+(?:/[\w@.-]+)*\.(?:js|mjs|cjs|ts|py|rb|php|java|sh|lua|pl|json|html|csv))\b""")
    private val CODE_LOADS = Regex("""\b(require|import|open|load|readFileSync|fetch)\s*\(|\bfrom\s+['"]?\.|\bimport\s+\w""", I)

    private val HEREDOC = Regex("""<<-?\s*['"]?([A-Za-z_]\w*)['"]?""")

    /**
     * The command cut into its parts at `&&`, `||`, `;`, `|` and line ends, but not inside quotes or a here-document (1.5.172).
     * Before, `node -e "` followed by the code on the next lines was cut at each line end, so the part that names node held no
     * code and the code held no node: two passing free-model runs that had run a real check were labelled "not checked".
     * Quotes that never close (an apostrophe in a comment) fall back to the plain cut.
     */
    internal fun segments(cmd: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var quote = '\u0000'; var i = 0
        fun flush() { val t = cur.toString().trim(); if (t.isNotEmpty()) out.add(t); cur.setLength(0) }
        while (i < cmd.length) {
            val c = cmd[i]
            if (quote != '\u0000') {
                if (c == '\\' && quote == '"' && i + 1 < cmd.length) { cur.append(c).append(cmd[i + 1]); i += 2; continue }
                if (c == quote) quote = '\u0000'
                cur.append(c); i++; continue
            }
            if (c == '\\' && i + 1 < cmd.length) { cur.append(c).append(cmd[i + 1]); i += 2; continue }
            if (c == '"' || c == '\'') { quote = c; cur.append(c); i++; continue }
            if (c == '<' && cmd.startsWith("<<", i) && !cmd.startsWith("<<<", i)) {
                val m = HEREDOC.find(cmd, i)
                val lineEnd = cmd.indexOf('\n', i)
                if (m != null && m.range.first == i && lineEnd >= 0) {
                    // the body runs to a line that is only the tag; it stays with the command that reads it
                    val close = Regex("""\n[ \t]*""" + Regex.escape(m.groupValues[1]) + """[ \t]*(?=\n|$)""").find(cmd, lineEnd)
                    val end = close?.range?.last?.plus(1) ?: cmd.length
                    cur.append(cmd, i, end); i = end; flush(); continue
                }
            }
            if (c == '\n' || c == ';') { flush(); i++; continue }
            if (c == '&' && cmd.startsWith("&&", i)) { flush(); i += 2; continue }
            if (c == '|') { flush(); i += if (cmd.startsWith("||", i)) 2 else 1; continue }
            cur.append(c); i++
        }
        if (quote != '\u0000') return cmd.split(Regex("""&&|\|\||;|\||\n""")).map { it.trim() }.filter { it.isNotEmpty() }
        flush()
        return out
    }

    /** One command segment that shows something works: a test runner, or the project's own code run or loaded. */
    fun realCheck(seg: String, exists: (String) -> Boolean): Boolean {
        val s = seg.trim()
        if (READER.containsMatchIn(s) || VERSION.containsMatchIn(s)) return false
        if (Regex("""^\s*curl\b.*\b(localhost|127\.0\.0\.1)""", I).containsMatchIn(s)) return true
        if (RUNNER.containsMatchIn(s)) return true
        val m = INTERP.find(s) ?: return false
        val rest = s.substring(m.groups[2]!!.range.first)   // to the end: code after -e, or a here-document, may run over several lines
        // a file of the project run as a program, or code (-e / -c) that loads one
        // "cd src && node ../test.js": a path that climbs out of the folder is tried from the project root too
        val files = PATH_IN.findAll(rest).map { it.groupValues[1].removePrefix("./") }.flatMap { listOf(it, it.replace(Regex("""^(\.\./)+"""), "")) }.toList()
        if (files.any { exists(it) }) return true
        if ((Regex("""\s-(e|c|p)\b""").containsMatchIn(rest) || HEREDOC.containsMatchIn(rest)) && CODE_LOADS.containsMatchIn(rest)) return true
        // "python3 -m mymodule": a module of the project
        Regex("""-m\s+([\w.]+)""").find(rest)?.groupValues?.get(1)?.let { mod -> if (exists(mod.replace('.', '/') + ".py") || exists(mod.replace('.', '/') + "/__init__.py")) return true }
        return false
    }

    /** True when the command only checks syntax: it shows the code parses, not that it works. */
    fun syntaxOnly(cmd: String): Boolean = SYNTAX_ONLY.containsMatchIn(cmd)

    /**
     * 1.5.175: a check that only prints. In the free-model survey (1.5.174, 111 runs labelled "finished (checked)"), 37 rested only on
     * `node -e` / `python3 -c` code that printed results and asserted nothing, so nothing in it could fail on a wrong result; 7 of them
     * were wrong (Nemotron 3 Ultra printed every rule of a parser but never tried "250 1h", and the label still said "Done and checked").
     * Such a run is recorded with no verdict ([PRINTED]), so it does not earn "checked" and the model is asked once to assert.
     */
    const val PRINTED = " (printed only)"
    private val ASSERTS = Regex("""\b(assert\w*|throw|raise|process\.exit(Code)?|sys\.exit|exit\s*\(|expect\s*\(|fail\s*\(|strictEqual|deepEqual|AssertionError)\b|\bexit\s+[1-9]|\|\|\s*exit""", I)

    /** True when every part of [cmd] that checks something is code given inline (-e, -c, -p or a here-document) that asserts nothing. */
    fun printOnly(cmd: String, exists: (String) -> Boolean = { false }): Boolean {
        val checks = segments(cmd).filter { realCheck(it, exists) }
        return checks.isNotEmpty() && checks.all { seg ->
            val m = INTERP.find(seg.trim()) ?: return@all false
            val rest = seg.trim().substring(m.groups[2]!!.range.first)
            !RUNNER.containsMatchIn(seg) && (Regex("""\s-(e|c|p)\b""").containsMatchIn(rest) || HEREDOC.containsMatchIn(rest)) && !ASSERTS.containsMatchIn(rest)
        }
    }

    /** How a command shows in labels: its first line, and "…" when the code goes on (1.5.175: was `node -e "` alone). */
    fun shown(cmd: String): String {
        val lines = cmd.trim().lines()
        val first = lines.first().trimEnd()
        return if (lines.size > 1 || first.length > 70) first.take(70) + "…" else first
    }

    fun command(cmd: String, code: Int, round: Int, exists: (String) -> Boolean = { false }): Check {
        val printed = code == 0 && printOnly(cmd, exists)
        return Check("`" + shown(cmd) + "`" + (if (code == 0) "" else " (exit $code)") + (if (printed) PRINTED else ""), if (printed) null else code == 0, round, !syntaxOnly(cmd))
    }

    /** True when the checks after [since] ran the program but only printed: none passed, and one printed (1.5.175). */
    fun onlyPrinted(checks: List<Check>, since: Int): Boolean {
        val after = checks.filter { it.runtime && it.round >= since }
        return after.isNotEmpty() && after.none { it.ok == true } && after.any { it.ok == null && it.what.endsWith(PRINTED) }
    }

    /** Sent once when the checks after the last change only printed results (1.5.175). */
    fun printedAsk(checks: List<Check>, since: Int): String {
        val c = checks.lastOrNull { it.round >= since && it.what.endsWith(PRINTED) }?.what?.removeSuffix(PRINTED).orEmpty()
        return "Not checked yet: your checks after the last change only printed results" + (if (c.isEmpty()) "" else " ($c)") +
            ", so nothing in them fails when a result is wrong. Write what each rule in the request expects as a check that fails on a wrong " +
            "result (assert, or throw / exit 1 with a message), include the error cases and edge cases the request names, and run it. " +
            "If one fails, fix the code and run it again."
    }

    private val CLAIM = Regex("""\b(all\s+(?:\d+\s+)?(?:\w+\s+)?(?:tests?|checks?)\s+(?:now\s+)?pass(?:ed|es)?|tests?\s+(?:now\s+)?pass(?:es|ed)?|(?:it|this|everything|the\s+(?:game|page|app|preview|site|server|build|fix))\s+(?:now\s+)?(?:works|loads|runs|plays|starts)|(?:verified|confirmed)\s+(?:that|it|the|in)|works\s+(?:now|correctly|as expected)|no\s+(?:more\s+)?(?:errors|problems)\s+(?:remain|now|left)|(?:is|are)\s+(?:now\s+)?(?:fixed|working))\b""", I)

    /** Phrases in a summary that claim the work was shown to work. */
    fun claims(text: String): List<String> = CLAIM.findAll(text).map { it.value.trim() }.distinct().take(3).toList()

    /** Whether a check that ran the program happened at or after [since] (the round of the last change). */
    fun runtimeSince(checks: List<Check>, since: Int): Boolean = checks.any { it.runtime && it.round >= since && it.ok != false }

    /** The last check that ran the program after the last change ([since]), when it failed; null when it passed or none ran. */
    fun lastFailed(checks: List<Check>, since: Int): Check? = checks.lastOrNull { it.runtime && it.round >= since }?.takeIf { it.ok == false }

    /**
     * Sent once when the work would end on a failed check (1.5.90). Llama 3.3 70B ended 3 of 13 hard benchmark tasks with "I could
     * not check it because the code has a syntax error": an honest report, but of a failure it could have fixed.
     */
    fun failedAsk(c: Check): String = "Not done yet: the last check after your last change failed (" + c.what + "). Read its output, fix the cause, " +
        "and run the check again. If it fails on purpose (for example a test written to show a bug), or something outside the code stops you, " +
        "say exactly that in your answer."

    /** Sent once when the summary claims more than the checks show. */
    fun nudge(claims: List<String>, checks: List<Check>, since: Int): String {
        val after = checks.filter { it.round >= since }
        val ran = if (after.isEmpty()) "No check ran after your last change." else "After your last change only these ran: " + after.joinToString(", ") { it.what + if (it.ok == false) " (failed)" else "" } + "."
        return "Your summary says \"" + claims.joinToString("\", \"") + "\", but nothing that ran shows it. $ran " +
            "Run a check that does (the tests, the program itself with run_command, or look_at_preview for a page), or rewrite the summary to say what is not checked. " +
            "Do not claim more than the results show."
    }

    /** The line added to the final reply: the app's own record of the checks after the last change. */
    fun footer(checks: List<Check>, since: Int): String {
        val after = checks.filter { it.round >= since }.distinctBy { it.what }
        if (after.isEmpty()) return "Checks run by the app's record: none after the last change, so this is untested."
        val shown = after.takeLast(6).joinToString(", ") { it.what + when (it.ok) { true -> " ✓"; false -> " ✗"; null -> "" } }
        val syntax = after.all { !it.runtime }
        return "Checks run by the app's record: $shown" + if (syntax) " (syntax only: the program itself was not run)." else "."
    }
}
