package dev.pocketide.app.core

/**
 * Saved play tests: tests/play.txt in the project holds one test per feature, each a "# name" line followed by look_at_preview
 * steps (tap, key, wait, run, expect, until). look_at_preview test=all (or a name) plays them, each on a freshly loaded page, and
 * the app plays them all again before the work is called done whenever code changed since they last passed, so a later change
 * cannot quietly break an earlier feature. A feature in PLAN.md counts as done only when its test passes.
 *
 *   # walking
 *   tap 200 300
 *   until window.__game.me.path.length === 0
 *   expect window.__game.me.x !== 3222
 */
object PlayTests {
    const val FILE = "tests/play.txt"
    const val MAX_TESTS = 16

    class Test(val name: String, val steps: String, val page: String = "")

    /** The tests in the file, in order. Steps before the first "# name" form a test called "start". "page: x.html" picks a page. */
    fun parse(text: String): List<Test> {
        val out = ArrayList<Test>(); var name: String? = null; val body = StringBuilder(); var page = ""
        fun flush() { val b = body.toString().trim(); if (name != null || b.isNotEmpty()) out.add(Test(name ?: "start", b, page)); body.setLength(0); page = "" }
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.startsWith("#")) { if (name != null || body.isNotBlank()) flush(); name = l.trimStart('#').trim().ifEmpty { "test ${out.size + 1}" }; continue }
            if (l.lowercase().startsWith("page:")) { page = l.substringAfter(':').trim(); continue }
            if (l.isNotEmpty()) body.append(l).append('\n')
        }
        flush()
        return out.filter { it.steps.isNotBlank() }
    }

    /** Tests matching [which]: "all" (or empty) for every test, else those whose name contains it. */
    fun pick(tests: List<Test>, which: String): List<Test> {
        val w = which.trim().lowercase()
        return (if (w.isEmpty() || w == "all") tests else tests.filter { it.name.lowercase().contains(w) }).take(MAX_TESTS)
    }

    /**
     * Saved tests a write cut in two (1.5.173). A line starting with # names a new test, so a model that added "# the floor is
     * drawn, not just counted" as a comment inside "Lumbridge castle" moved that test's last steps into a new test played on a fresh
     * page: the old test still passed on 3 of its checks, and the new one could never pass. Found from what [before] held.
     */
    fun splits(before: String, after: String): List<String> {
        val old = parse(before).associate { it.name to it.steps.lines().filter { l -> l.isNotBlank() } }
        val now = parse(after)
        val out = ArrayList<String>()
        for (i in 1 until now.size) {
            val t = now[i]; val prev = now[i - 1]
            if (t.name in old) continue
            val was = old[prev.name] ?: continue
            val kept = prev.steps.lines().filter { it.isNotBlank() }
            if (kept.size >= was.size || was.take(kept.size) != kept) continue
            val lost = was.size - kept.size
            out.add("the line \"# ${t.name.take(80)}\" starts a new test: in $FILE a line starting with # names a test, and each test plays on a freshly " +
                "loaded page. \"${prev.name}\" lost its last $lost step${if (lost == 1) "" else "s"} to it. If it was meant as a comment, remove that # line")
        }
        return out
    }

    /** Problems in the file that would stop it running (shown to the model when it writes the file). */
    fun problems(text: String): List<String> = parse(text).mapNotNull { t ->
        val p = PageSteps.parse(t.steps)
        when {
            p.error != null -> "\"${t.name}\": ${p.error}"
            p.checks == 0 -> "\"${t.name}\" has no expect or until line, so it can never fail: add a check of what the feature must do"
            else -> null
        }
    }

    /** One test's look_at_preview report shortened to its verdict, checks and errors. */
    fun short(name: String, report: String): String {
        val lines = report.lines()
        val keep = ArrayList<String>()
        lines.firstOrNull { it.startsWith("VERDICT:") }?.let { keep.add(it) }
        var inChecks = false; var inErrors = false
        for (l in lines) {
            if (l.startsWith("Checks (")) { inChecks = true; keep.add(l); continue }
            if (l.startsWith("Console errors and missing files")) { inErrors = true; keep.add(l); continue }
            if ((inChecks || inErrors) && l.startsWith("- ")) { keep.add(l); continue }
            inChecks = false; inErrors = false
            if (l.startsWith("Steps not played") || l.startsWith("Game state (render_game_to_text)")) keep.add(l.take(400))
        }
        return "## $name\n" + keep.joinToString("\n")
    }

    fun passed(report: String): Boolean = report.contains("VERDICT: PASS")

    /** The combined report of several tests. */
    fun summary(results: List<Pair<String, String>>): String {
        val ok = results.count { passed(it.second) }
        val head = if (ok == results.size) "PLAY TESTS: PASS - all ${results.size} saved test${if (results.size == 1) "" else "s"} in $FILE passed."
            else "PLAY TESTS: FAIL - ${results.size - ok} of ${results.size} saved tests in $FILE failed. Fix the game or app so they pass; change a test only when the request itself changed."
        return head + "\n\n" + results.joinToString("\n\n") { short(it.first, it.second) }
    }

    const val SAME_FAILURES = 3
    const val STEP_NOTE = "This step has failed the same way 3 times. Check the step itself: a step that types text into a number field, " +
        "or expects something the page never does, can never pass."

    /** The first failing check of a report ("- FAIL step -> value"), or the verdict line when no check is listed; null when it passed. */
    fun failure(report: String): String? {
        if (passed(report)) return null
        return report.lines().firstOrNull { it.startsWith("- FAIL ") }?.trim() ?: report.lines().firstOrNull { it.startsWith("VERDICT:") }?.trim() ?: "failed"
    }

    /**
     * Counts, over one request, how often each saved test failed at the same step with the same message (roadmap phase 1d). The
     * third time, the model is told once per test to look at the step, not at the app again: a step that can never pass sent runs
     * round and round changing working code. A pass, or a different failure, starts the count again.
     */
    class Repeats {
        private val last = HashMap<String, Pair<String, Int>>()
        private val said = HashSet<String>()

        /** The lines to add under the report of [results] (test name to its report); empty when nothing repeated enough. */
        fun notes(results: List<Pair<String, String>>): String {
            val out = ArrayList<String>()
            for ((name, report) in results) {
                val f = failure(report)
                if (f == null) { last.remove(name); continue }
                val n = if (last[name]?.first == f) last[name]!!.second + 1 else 1
                last[name] = f to n
                if (n >= SAME_FAILURES && said.add(name)) out.add("\"$name\": $STEP_NOTE")
            }
            return if (out.isEmpty()) "" else "\n\n" + out.joinToString("\n")
        }
    }

    const val NOTE = "PLAY TESTS: for a game or app, save a play test for each feature in $FILE: a line \"# feature name\", then look_at_preview steps that use it " +
        "and check the result with expect or until (JavaScript about the page or the game's state, e.g. expect window.__game.inv.count(1511) > 0; until document.querySelector('#score').textContent !== '0'). " +
        "Give games a window.__game object (or render_game_to_text()) so tests can read their state and call their actions. Run them with look_at_preview test=all. " +
        "The app plays every saved test again before the work counts as done, and sends failures back."
}
