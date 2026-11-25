package dev.pocketide.app.core

/**
 * The hybrid AI benchmark (bench/hybrid): eight coding tasks run on the phone with the real agent and Gemini Nano
 * (HybridBench runs them), each judged by its own check. The same tasks are in bench/hybrid as zips to run by hand.
 */
object HybridBenchTasks {
    /** What the runner saw after a task: the project's files before and after, the check command's result and the chat log. */
    class Facts(
        val files: Map<String, String>,
        val original: Map<String, String>,
        val commandExit: Int?,
        val commandOut: String,
        val transcript: String,
        val ended: String,
    )

    class Task(
        val id: String,
        val title: String,
        val prompt: String,
        /** Run after the agent finishes; null = no command. */
        val command: String? = "node test.js",
        /** Run with Private mode on (Gemini Nano only). */
        val privateMode: Boolean = false,
        /** Null = passed, else why it did not. */
        val judge: (Facts) -> String?,
    ) {
        val files: Map<String, String> get() = (HybridBenchFiles.FILES[id] ?: HybridBenchHardFiles.FILES[id]).orEmpty()
    }

    class Counts(val phone: Int, val cloud: Int, val handoffs: List<String>)

    class Result(val task: Task, val passed: Boolean, val why: String, val counts: Counts, val seconds: Long, val ended: String)

    private val PHONE = Regex("""⚡ Ran on this phone""")
    private val CLOUD = Regex("""☁ Ran in the cloud""")
    private val HANDOFF = Regex("""I'm passing this to (.+?)\. Why: (.+?)\.?\s*$""", RegexOption.MULTILINE)

    /** Where the steps in a chat log ran, and every handoff with its reason ("target: why"). */
    fun counts(transcript: String): Counts = Counts(
        PHONE.findAll(transcript).count(),
        CLOUD.findAll(transcript).count(),
        HANDOFF.findAll(transcript).map { it.groupValues[1].trim() + ": " + it.groupValues[2].trim() }.distinct().toList(),
    )

    /** The check command printed "ok" on its own line and exited cleanly. */
    fun commandOk(f: Facts): Boolean = f.commandExit == 0 && f.commandOut.lines().any { it.trim() == "ok" }

    private fun commandWhy(f: Facts): String =
        if (f.commandExit == null) "the check did not run" else "node test.js failed: " + f.commandOut.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(160)

    private val DATE_LIBS = listOf("date-fns", "dayjs", "day.js", "moment", "luxon", "js-joda", "temporal", "date-and-time", "spacetime")

    val ALL: List<Task> = listOf(
        Task("01-fix-add", "Fix a bug", "Fix add in app.js so node test.js prints ok") { f ->
            if (commandOk(f)) null else commandWhy(f)
        },
        Task("02-explain", "Explain code", "Explain what parseCSV in csv.js does and how it handles quoted commas. Do not change any file.", command = null) { f ->
            when {
                f.files != f.original -> "a file changed"
                f.ended != "done" -> "the request ended: ${f.ended}"
                else -> null
            }
        },
        Task("03-todo-feature", "Add a UI feature", "Add a \"Clear completed\" button that removes done items. Make the play test in tests/play.txt pass", command = null) { f ->
            when {
                !f.files["index.html"].orEmpty().contains("clear completed", ignoreCase = true) -> "index.html has no Clear completed button"
                !f.transcript.contains("outcome: ✓") -> "the app's checks did not pass (no ✓ outcome)"
                else -> null
            }
        },
        Task("04-rename", "Rename across files", "Rename calcTotal to orderTotal everywhere and keep node test.js passing") { f ->
            when {
                !commandOk(f) -> commandWhy(f)
                f.files.any { (p, t) -> p.endsWith(".js") && t.contains("calcTotal") } -> "calcTotal is still used"
                else -> null
            }
        },
        Task("05-keep-definition", "Add code without breaking it", "Add setDay(day, id) and dayOf(day) to app.js so a recipe id can be stored per day. node test.js must print ok") { f ->
            when {
                !commandOk(f) -> commandWhy(f)
                !Regex("""(let|var|const)\s+selected\s*=""").containsMatchIn(f.files["app.js"].orEmpty()) -> "the definition of selected is gone"
                else -> null
            }
        },
        Task("06-research-handoff", "Research (needs the web)", "Research the three most popular JavaScript date libraries and write DATES.md comparing their size and licence", command = null) { f ->
            val doc = f.files["DATES.md"].orEmpty().lowercase()
            val libs = DATE_LIBS.count { doc.contains(it) }
            when {
                doc.isBlank() -> "DATES.md was not written"
                libs < 3 -> "DATES.md names $libs known date libraries, not 3"
                counts(f.transcript).handoffs.none { it.contains("web") } -> "no handoff for the web was shown"
                else -> null
            }
        },
        Task("07-long-file", "A file too long for the phone", "total() in inventory.js returns NaN. Find why and fix it so node test.js prints ok") { f ->
            if (commandOk(f)) null else commandWhy(f)
        },
        Task("08-private-validate", "Private mode (phone only)", "Make signup reject empty or malformed emails so node test.js prints ok", privateMode = true) { f ->
            when {
                !commandOk(f) -> commandWhy(f)
                counts(f.transcript).cloud > 0 -> "a step ran in the cloud in Private mode"
                else -> null
            }
        },
    )

    /**
     * The hard suite (bench/hybrid/hard, 1.5.184): ten tasks that need more than one step of reasoning, more than one file, or more
     * than the phone has. Every command task fails as given and passes with its reference fix (bench/hybrid/check_hard.py).
     */
    val HARD: List<Task> = listOf(
        Task("h01-two-bugs-two-files", "Two bugs in two files", "Cart totals are wrong: coupons and bulk prices come out wrong. Find every cause and fix it so node test.js prints ok") { f ->
            if (commandOk(f)) null else commandWhy(f)
        },
        Task("h02-spec-to-code", "Code from a spec", "Implement parseDuration and formatDuration in duration.js exactly as SPEC.md describes, so node test.js prints ok") { f ->
            if (commandOk(f)) null else commandWhy(f)
        },
        Task("h03-async-order", "An async bug", "loadAll in loader.js returns too few results, in the wrong order, and throws when one id fails. Fix it so failed ids give { id, error: message } in their place. node test.js must print ok") { f ->
            if (commandOk(f)) null else commandWhy(f)
        },
        Task("h04-split-module", "Refactor without breaking callers", "Split utils.js: move slugify and titleCase to strings.js, and clamp, sum and avg to numbers.js. Keep unique in utils.js, and keep utils.js exporting everything so app.js and other callers still work. node test.js must print ok") { f ->
            if (commandOk(f)) null else commandWhy(f)
        },
        Task("h05-find-the-cause", "Find the cause from a failing test", "node test.js fails. Find the cause and fix it so it prints ok") { f ->
            when {
                !commandOk(f) -> commandWhy(f)
                f.files["src/report.js"] != f.original["src/report.js"] || f.files["src/invoice.js"] != f.original["src/invoice.js"] -> "the callers were patched instead of the shared date helper"
                else -> null
            }
        },
        Task("h06-ui-filters", "UI filters with a play test", "Add All, Active and Done filter buttons above the list that show only the matching items and keep working as items are added or ticked. Make the play test in tests/play.txt pass", command = null) { f ->
            val html = f.files["index.html"].orEmpty() + f.files["app.js"].orEmpty()
            when {
                listOf("All", "Active", "Done").any { !html.contains(">$it<") && !html.contains("'$it'") && !html.contains("\"$it\"") } -> "the All, Active and Done buttons are not all there"
                !f.transcript.contains("outcome: ✓") -> "the app's checks did not pass (no ✓ outcome)"
                else -> null
            }
        },
        Task("h07-long-file-two-bugs", "Two bugs in a very long file", "inventory.js gives wrong results: totalValue is NaN and needsReorder misses items. Find both bugs and fix them so node test.js prints ok") { f ->
            if (commandOk(f)) null else commandWhy(f)
        },
        Task("h08-private-password", "Private mode: several rules", "Implement checkPassword in password.js as its comment describes, with the problems in that order, so node test.js prints ok", privateMode = true) { f ->
            when {
                !commandOk(f) -> commandWhy(f)
                counts(f.transcript).cloud > 0 -> "a step ran in the cloud in Private mode"
                else -> null
            }
        },
        Task("h09-research-to-json", "Research, then a checked file", "Look up the SPDX licence identifiers of the npm packages express, lodash and react, and write licences.json mapping each package name to its identifier. node test.js must print ok") { f ->
            when {
                !commandOk(f) -> commandWhy(f)
                counts(f.transcript).handoffs.none { it.contains("web") } -> "no handoff for the web was shown"
                else -> null
            }
        },
        Task("h10-shared-helper", "Fix a shared helper safely", "Invoices show prices like \$12.5 instead of \$12.50. Fix it without breaking receipts, so node test.js prints ok") { f ->
            if (commandOk(f)) null else commandWhy(f)
        },
    )

    /** The results as a markdown report. [problem] = why the run stopped early, if it did. */
    fun report(results: List<Result>, about: String, problem: String?, total: Int = ALL.size, title: String = "Hybrid AI benchmark"): String = buildString {
        val passed = results.count { it.passed }
        append("# ").append(title).append("\n\n").append(about).append("\n\n")
        if (problem != null) append("**Stopped early:** ").append(problem).append("\n\n")
        append("**Passed ").append(passed).append(" of ").append(total).append("**")
        val phone = results.sumOf { it.counts.phone }; val cloud = results.sumOf { it.counts.cloud }
        if (phone + cloud > 0) append(" · steps on this phone: ").append(phone).append(" of ").append(phone + cloud)
        append("\n\n| # | Task | Result | Why not | On phone / in cloud | Handoffs | Time | Ended |\n| --- | --- | --- | --- | --- | --- | --- | --- |\n")
        for (r in results) {
            append("| ").append(r.task.id.take(2)).append(" | ").append(r.task.title).append(" | ").append(if (r.passed) "✓ passed" else "✗ failed")
                .append(" | ").append(r.why.replace("|", "/")).append(" | ").append(r.counts.phone).append(" / ").append(r.counts.cloud)
                .append(" | ").append(r.counts.handoffs.joinToString("; ").replace("|", "/").ifEmpty { "none" })
                .append(" | ").append(r.seconds / 60).append(" min ").append(r.seconds % 60).append(" s | ").append(r.ended).append(" |\n")
        }
        append("\nEach task's folder has its files after the run (files/), the chat log (transcript.txt) and the check's output (check.txt).\n")
        append("Not run automatically: the airplane-mode check (Quick assist with no connection).\n")
    }
}
