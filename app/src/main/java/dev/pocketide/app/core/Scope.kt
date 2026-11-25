package dev.pocketide.app.core

/**
 * Keeps the assistant to what was asked (1.5.82).
 *
 * Found in the 1.5.81 emulator benchmarks: asked only to run the saved play tests and report, the assistant edited app.js to add
 * a test hook and rewrote tests/play.txt so that "reload keeps the entries" no longer reloaded. The tests then passed, and they
 * no longer tested what they were written to test.
 *
 * Two rules:
 * - [reportOnly]: a request that only asks to run, check, test, look at or report on something changes no files. Commands and
 *   page looks still run, so the tests can be played; the findings go in the answer, with any fix described and offered.
 * - [testRefusal]: a saved play test is never weakened (a step removed or changed) unless the request is about changing tests.
 *   A test that fails is a finding about the app, not a fault in the test.
 */
object Scope {
    private val I = RegexOption.IGNORE_CASE

    /** Words that ask for a result to be found out and told. */
    private val CHECK_VERB = Regex("""^\s*(?:please\s+|now\s+|can\s+you\s+|could\s+you\s+)?(run|check|test|verify|look\s+at|look\s+over|review|report|audit|inspect|measure|tell\s+me|confirm|see\s+(?:if|whether)|find\s+out)\b""", I)

    /** Words that ask for something to be changed: any of these makes the request a task. */
    private val CHANGE = Regex("""\b(fix|fixes|fixing|change|update|edit|modify|add|remove|delete|rename|refactor|rewrite|replace|implement|build|create|make|write|improve|clean\s+up|correct|repair|patch|install|set\s+up|move|convert|port|restore|apply|commit|push|deploy|optimi[sz]e|speed\s+up|adjust|tweak|resolve|address)\b""", I)

    /**
     * True when a sentence of [text] starts with an order to change something ("Fix …", "Please add …"). Used where the person
     * also said "change nothing", so a change word inside a question ("Which file defines add?") does not count.
     */
    fun asksForChange(text: String): Boolean = text.split(Regex("""(?<=[.!?])\s+|\n+""")).any {
        Regex("""^\s*(please\s+|now\s+|then\s+|and\s+)?""" + CHANGE.pattern, RegexOption.IGNORE_CASE).containsMatchIn(it.trim())
    }

    /** "run the tests and report", "check the page and tell me": a request to find out, with no change asked for. */
    fun reportOnly(text: String): Boolean {
        val t = text.trim().replace(Regex("""https?://\S+"""), "link")
        if (t.isEmpty() || t.length > 600) return false
        if (!CHECK_VERB.containsMatchIn(t)) return false
        // "write the results to a file", "make a list" would still be report-shaped, but they ask for a file: keep it a task
        return !CHANGE.containsMatchIn(t)
    }

    const val REPORT_NOTE = "\n\nThis request asks you to run or check something and report the result. Do not change any file: no code, no tests, " +
        "no test hooks. Run what was asked, then report exactly what passed and what failed, with the evidence. If something fails, say what you would " +
        "change to fix it and ask whether to make that change."

    const val REPORT_REFUSAL = "Not done: the person asked you to run or check something and report, not to change files. Change nothing. " +
        "Report what you found; if a fix would help, describe it and ask whether to make it."

    /** Sent once when a run-and-report request is answered without any command or page look. */
    const val RUN_FIRST = "You have not run anything yet, so that report is a guess. Do not assume a tool is missing: " +
        "run it with run_command now, then report exactly what it printed. If the command fails, report the error it gives."

    /** True when the request asks for the tests themselves to change ("update the play tests", "fix the test for reload"). */
    fun asksToChangeTests(request: String): Boolean =
        Regex("""\b(tests?|play\s*tests?|play\.txt)\b""", I).containsMatchIn(request) && CHANGE.containsMatchIn(request)

    /** The existing steps a new version of the play tests removes or changes. */
    fun gone(prior: String, content: String): List<String> { val now = steps(content).toSet(); return steps(prior).filter { it !in now }.distinct() }

    /**
     * 1.5.98: a test change the request did not ask for is put to the person (one tap to allow it) instead of only refused. In the
     * logs a request to keep the player's position on exit broke a test that expected the start position; the refusal left the model
     * trying other tools to write it for many rounds and then working around the failing test.
     */
    fun proposalNote(steps: List<String>): String = "Not written yet: this would remove or change ${steps.size} existing step${if (steps.size == 1) "" else "s"} of the saved play tests (" +
        steps.take(3).joinToString("; ") { "\"" + it.take(60) + "\"" } + "). The change has been put to the person, who can allow it with one tap when you finish. " +
        "Do not try to write it another way. Carry on with the rest of the request, and in your answer say in one sentence why the test has to change " +
        "(for example: the request changes what it checks). If the test is right and the app is wrong, fix the app instead."

    /** Lines of a play-test file that are steps (not names, pages or blank lines), normalised for comparison. */
    private fun steps(text: String): List<String> = text.lines().map { it.trim().replace(Regex("""\s+"""), " ") }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.lowercase().startsWith("page:") }

    /**
     * Why a write to the play-test file is refused, or null. Adding tests and steps is always fine; removing or changing an
     * existing step is refused unless the request asks for the tests to change.
     */
    private val RUNS = Regex("""\b(?:node|python3?|pytest|deno\s+run|bun|ruby|php|bash|sh)\s+([\w./-]+)""", I)

    /** The test files the request says to run as its check ("so node test.js prints ok"). */
    fun namedChecks(request: String): Set<String> =
        RUNS.findAll(request).map { it.groupValues[1].removePrefix("./") }.filter { Regex("""(?i)test|spec""").containsMatchIn(it) }.toSet()

    /**
     * 1.5.184 (benchmark run 6, task 08): asked to make signup reject bad emails "so node test.js prints ok", Gemini Nano changed
     * test.js instead of the app. The test the request names as its check is not rewritten: lines may be added, existing ones stay.
     */
    fun checkRefusal(path: String, prior: String?, content: String, request: String): String? {
        val p = path.trimStart('/').removePrefix("./")
        if (prior == null || p !in namedChecks(request)) return null
        val rest = RUNS.replace(request, " ")
        if (asksToChangeTests(rest)) return null
        val gone = gone(prior, content).filter { it.isNotEmpty() }
        if (gone.isEmpty()) return null
        return "Not written: $p is the check the person asked to pass, and this would remove or change ${gone.size} of its line${if (gone.size == 1) "" else "s"} (" +
            gone.take(2).joinToString("; ") { "\"" + it.take(60) + "\"" } + "). A failing test is a finding about the app: change the app so $p passes as it is."
    }

    fun testRefusal(path: String, prior: String?, content: String, request: String): String? {
        if (path.trimStart('/') != PlayTests.FILE || prior == null || asksToChangeTests(request)) return null
        val gone = gone(prior, content)
        if (gone.isEmpty()) return null
        return "Not written: this would remove or change ${gone.size} existing step${if (gone.size == 1) "" else "s"} of the saved play tests (" +
            gone.take(3).joinToString("; ") { "\"" + it.take(60) + "\"" } + "). A failing test is a finding about the app: fix the app so the test " +
            "passes, or tell the person why you think the test is wrong and ask before changing it. New tests and new steps may be added."
    }
}
