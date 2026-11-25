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
        /** The skill the task checks (the local suite groups its report by it). */
        val skill: String = "",
        /** Null = passed, else why it did not. */
        val judge: (Facts) -> String?,
    ) {
        val files: Map<String, String> get() = (HybridBenchFiles.FILES[id] ?: HybridBenchHardFiles.FILES[id] ?: HybridBenchGameFiles.FILES[id]
            ?: HybridBenchLocalFiles.FILES[id]).orEmpty()
    }

    /** [other]: steps by another model (the strong model in "Hybrid helps my strong model"), which carry no ⚡ or ☁ label. */
    class Counts(val phone: Int, val cloud: Int, val handoffs: List<String>, val other: Int = 0, val nano: Nano = Nano())

    /**
     * How Gemini Nano's steps went (the local suite, 1.5.184): tool calls the app refused (an edit that did not match, a file not
     * saved), steps that repeated a read with nothing new, replies that were not a usable action, and the app's repairs that stepped in.
     */
    class Nano(val refused: Int = 0, val repeats: Int = 0, val unusable: Int = 0, val repairs: Int = 0)

    private val REFUSED = Regex("""(?m)^→ (Edit not applied|NOT SAVED|Not written|Error\b|Could not|No such file)""")
    private val REPEAT = Regex("""(?m)^→ (You already read|.{0,80}has not changed since)""")
    private val UNUSABLE = Regex("""did not reply with one JSON action|asked for a tool that does not exist|gave an empty answer""")
    private val REPAIR = Regex("""that exact action already failed|tried to undo a change that had just passed|No internet connection, so this stayed""")

    fun nano(transcript: String) = Nano(REFUSED.findAll(transcript).count(), REPEAT.findAll(transcript).count(),
        UNUSABLE.findAll(transcript).count(), REPAIR.findAll(transcript).count())

    /** The text of the model's last reply in a chat log (what it answered), or "". */
    fun lastAnswer(transcript: String): String {
        val i = transcript.lastIndexOf("· assistant said\n")
        if (i < 0) return ""
        return transcript.substring(i + "· assistant said\n".length).substringBefore("\n-----").substringBefore("\n=====")
            .lines().filterNot { it.startsWith("⚡") || it.startsWith("☁") }.joinToString("\n").trim()
    }

    private val STEP = Regex("""· round (\d+) · assistant (said|thought)""")

    /** The model steps in a chat log: each request's rounds that have an assistant entry (1.5.184). */
    fun steps(transcript: String): Int = transcript.split("request started").sumOf { part -> STEP.findAll(part).map { it.groupValues[1] }.toSet().size }

    class Result(val task: Task, val passed: Boolean, val why: String, val counts: Counts, val seconds: Long, val ended: String)

    private val PHONE = Regex("""⚡ Ran on this phone""")
    private val CLOUD = Regex("""☁ Ran in the cloud""")
    private val HANDOFF = Regex("""I'm passing this to (.+?)\. Why: (.+?)\.?\s*$""", RegexOption.MULTILINE)

    /** Where the steps in a chat log ran, and every handoff with its reason ("target: why"). */
    fun counts(transcript: String): Counts = Counts(
        PHONE.findAll(transcript).count(),
        CLOUD.findAll(transcript).count(),
        HANDOFF.findAll(transcript).map { it.groupValues[1].trim() + ": " + it.groupValues[2].trim() }.distinct().toList(),
        maxOf(0, steps(transcript) - PHONE.findAll(transcript).count() - CLOUD.findAll(transcript).count()),
        nano(transcript),
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

    /**
     * The game suite (bench/hybrid/games, 1.5.184): browser games checked by the app's own play tests. Every play test fails as given
     * and passes with the reference fix, played with the app's play-test script in Chromium (check_games.js).
     */
    val GAMES: List<Task> = listOf(
        Task("gm01-snake-from-spec", "Snake from a spec", "Build the Snake game exactly as GAME.md describes, so the play tests in tests/play.txt pass", command = null) { f ->
            game(f, "game.js") ?: if (!f.files["game.js"].orEmpty().contains("window.game")) "game.js does not set window.game" else null
        },
        Task("gm02-fix-keyboard", "Fix keys that do nothing", "The ball moves but the paddle ignores the arrow keys. Fix it so the keys work without clicking the game first, and keep the paddle inside the court. Make the play tests in tests/play.txt pass", command = null) { f ->
            game(f, "game.js")
        },
        Task("gm03-add-pause-and-best", "Add features to a working game", "Add pause and resume with the P key (status shows Paused), and keep the best score in localStorage under snakeBest, shown in #best as Best: N. Snake must keep working. Make the play tests in tests/play.txt pass", command = null) { f ->
            game(f, "game.js")
        },
        Task("gm04-tictactoe-opponent", "A game with an opponent", "Build tic-tac-toe with a computer opponent exactly as GAME.md describes, so the play tests in tests/play.txt pass", command = null) { f ->
            game(f, "game.js") ?: if (!f.files["index.html"].orEmpty().contains("cell")) "index.html has no cells" else null
        },
        Task("gm05-restart-speedup", "A restart that speeds the game up", "Every time Restart is pressed the game gets faster. Fix it so the play tests in tests/play.txt pass", command = null) { f ->
            game(f, "game.js")
        },
    )

    private fun unchanged(f: Facts): String? = if (f.files.filterKeys { !it.startsWith(".") } != f.original) "files were changed; the task asked for no change" else null
    private fun inPrivate(f: Facts): String? = if (counts(f.transcript).cloud > 0) "a step ran in the cloud in Private mode" else null
    private fun code(f: Facts): String? = inPrivate(f) ?: if (commandOk(f)) null else commandWhy(f)
    private fun said(f: Facts, what: String, re: Regex): String? = inPrivate(f) ?: unchanged(f) ?: lastAnswer(f.transcript).let {
        if (re.containsMatchIn(it)) null else "the answer does not say $what: \"${it.take(120)}\""
    }

    /**
     * The local suite (bench/hybrid/local, 1.5.184): twenty tiny tasks for Gemini Nano alone, in Private mode, grouped by skill, to
     * tune how PocketIDE drives it (its instructions, examples, tools, settings and repairs; the model itself cannot be changed).
     * Every code task fails as given and passes with its reference fix (check_hard.py).
     */
    val LOCAL: List<Task> = listOf(
        Task("l01-find-file", "Find which file defines a name", "Which file defines MAX_LIVES? Reply with the file name only. Change nothing.", command = null, privateMode = true, skill = "Find and explain") { f ->
            said(f, "rules.js", Regex("""rules\.js"""))
        },
        Task("l02-explain-function", "Say what a function returns", "What does step() in snake.js return? Answer in one sentence. Change nothing.", command = null, privateMode = true, skill = "Find and explain") { f ->
            said(f, "that it returns the body's length", Regex("""(?i)length|number of|how many|size|count"""))
        },
        Task("l03-explain-no-change", "Explain code without changing it", "Explain what parse() in csv.js does. Change nothing.", command = null, privateMode = true, skill = "Find and explain") { f ->
            said(f, "commas and quotes", Regex("""(?is)(?=.*comma)(?=.*quot)"""))
        },
        Task("l04-report-test-result", "Run a test and report it", "Run node test.js and tell me whether it passes. Change nothing.", privateMode = true, skill = "Find and explain") { f ->
            inPrivate(f) ?: unchanged(f) ?: when {
                !Regex("""tool run_command[^\n]*\n\{"command":"node test\.js""").containsMatchIn(f.transcript) -> "node test.js was not run"
                !Regex("""(?i)\b(pass(es|ed)?|ok|succeed(s|ed)?)\b""").containsMatchIn(lastAnswer(f.transcript)) -> "the answer does not say it passes"
                else -> null
            }
        },
        Task("l05-fix-operator", "Fix a wrong operator", "Fix add in app.js so node test.js prints ok", privateMode = true, skill = "Fix one line") { f -> code(f) },
        Task("l06-fix-text", "Fix a typo in a string", "greet in greet.js should return 'Hello, Ana!' for Ana. Fix it so node test.js prints ok", privateMode = true, skill = "Fix one line") { f -> code(f) },
        Task("l07-fix-off-by-one", "Fix an off-by-one", "sumTo(n) in sum.js should add the numbers 1 to n. Fix it so node test.js prints ok", privateMode = true, skill = "Fix one line") { f -> code(f) },
        Task("l08-fix-property", "Fix a wrong property name", "label() in user.js prints undefined for the name. Fix it so node test.js prints ok", privateMode = true, skill = "Fix one line") { f -> code(f) },
        Task("l09-fix-and-verify", "Fix, then check with the test", "withTax in price.js should add 20% tax. Fix it, then run node test.js to check that it prints ok", privateMode = true, skill = "Fix one line") { f ->
            code(f) ?: if (f.transcript.lastIndexOf("tool run_command") < f.transcript.lastIndexOf("tool edit_")) "the test was not run after the last change" else null
        },
        Task("l10-add-function", "Add a function", "Add a function double(n) to math.js that returns n * 2, and export it. node test.js must print ok", privateMode = true, skill = "Add new code") { f -> code(f) },
        Task("l11-add-key", "Add a key control", "In controls.js, make the ArrowUp key call move('up'). node test.js must print ok", privateMode = true, skill = "Add new code") { f -> code(f) },
        Task("l12-add-touch", "Add a touch control", "In controls.js, add touch control: a touchstart on the document calls move('tap'). Keep the keys working. node test.js must print ok", privateMode = true, skill = "Add new code") { f -> code(f) },
        Task("l13-add-switch-case", "Add a case to a switch", "Make delta('left') in dir.js return [-1, 0]. node test.js must print ok", privateMode = true, skill = "Add new code") { f -> code(f) },
        Task("l14-add-field", "Add a field to an object", "Add speed: 150 to the config in config.js. node test.js must print ok", privateMode = true, skill = "Add new code") { f -> code(f) },
        Task("l15-keep-existing", "Add a line, keep the rest", "Make tick() in loop.js also count frames: state.frames goes up by one each tick. node test.js must print ok", privateMode = true, skill = "Add new code") { f -> code(f) },
        Task("l16-new-json", "Create a JSON file", "Create config.json with speed 150 and lives 3. node test.js must print ok", privateMode = true, skill = "New file") { f -> code(f) },
        Task("l17-new-module", "Create a module", "main.js needs greet.js. Create greet.js exporting greet(name) that returns 'Hello, ' + name + '!'. node test.js must print ok", privateMode = true, skill = "New file") { f -> code(f) },
        Task("l18-rename-two-files", "Rename across two files", "Rename getScore to readScore in score.js and ui.js. node test.js must print ok", privateMode = true, skill = "Two files or a stale quote") { f -> code(f) },
        Task("l19-html-and-js", "Change HTML and JS together", "Add a Reset button (id reset) to index.html, and a reset() function in app.js that sets count to 0, exported like add. node test.js must print ok", privateMode = true, skill = "Two files or a stale quote") { f -> code(f) },
        Task("l20-stale-snippet", "Edit from a quote that differs", "In timer.js, change the line `seconds: 60 // one minute` so the timer is 90 seconds. node test.js must print ok", privateMode = true, skill = "Two files or a stale quote") { f -> code(f) },
    )

    /** A game task passed when [code] changed, the play tests are as given, and the app's checks passed (the play tests run there). */
    private fun game(f: Facts, code: String): String? = when {
        f.files[code] == f.original[code] -> "$code was not changed"
        f.files[PlayTests.FILE] != f.original[PlayTests.FILE] -> "the play tests were changed"
        !f.transcript.contains("outcome: ✓") -> "the app's checks did not pass (no ✓ outcome)"
        else -> null
    }

    /**
     * "Hybrid helps my strong model" (1.5.184): the benchmark runs in the Cascade workflow with Hybrid AI as the draft model, so Gemini
     * Nano does the cheap steps on the phone and passes what it cannot do to the person's strong model's API, which also reviews and
     * finishes the work. Why [strong] cannot be the strong model, or null when it can. Cloudflare is left out at the owner's request
     * (it bills past a small free allowance).
     */
    fun strongProblem(strong: Workflow.Slot?, hasKey: Boolean): String? = when {
        strong == null -> "Pick a strong model in Settings → Workflow first."
        strong.provider == "hybrid" || strong.provider == "demo" -> "The strong model must be another provider's API, not ${Providers.byId(strong.provider).name}. Pick one in Settings → Workflow."
        strong.provider == "cloudflare" -> "Cloudflare is not used for this benchmark (it bills past a small free allowance). Pick another strong model in Settings → Workflow."
        !hasKey -> "${Providers.byId(strong.provider).name} has no API key saved. Add it in Settings, or pick another strong model."
        else -> null
    }

    /** The results as a markdown report. [problem] = why the run stopped early, if it did. */
    fun report(results: List<Result>, about: String, problem: String?, total: Int = ALL.size, title: String = "Hybrid AI benchmark"): String = buildString {
        val passed = results.count { it.passed }
        append("# ").append(title).append("\n\n").append(about).append("\n\n")
        if (problem != null) append("**Stopped early:** ").append(problem).append("\n\n")
        append("**Passed ").append(passed).append(" of ").append(total).append("**")
        val phone = results.sumOf { it.counts.phone }; val cloud = results.sumOf { it.counts.cloud }
        val other = results.sumOf { it.counts.other }
        // other steps are counted only when another model worked (Hybrid helps my strong model); otherwise they are app rounds
        val strong = title.contains("helping")
        if (phone + cloud > 0) append(" · steps on this phone: ").append(phone).append(" of ").append(phone + cloud + if (strong) other else 0)
        if (strong) append(" · steps by the strong model: ").append(other)
        // the local suite: how Nano's steps went, and the score per skill
        if (results.isNotEmpty() && results.all { it.task.skill.isNotEmpty() }) {
            val n = results.map { it.counts.nano }
            append("\n\n**Gemini Nano's steps:** ").append(n.sumOf { it.refused }).append(" refused by the app (edits that did not match, files not saved) · ")
                .append(n.sumOf { it.repeats }).append(" repeated reads · ").append(n.sumOf { it.unusable }).append(" replies that were not a usable action · ")
                .append(n.sumOf { it.repairs }).append(" repairs by the app")
            append("\n\n| Skill | Passed |\n| --- | --- |\n")
            for ((skill, rs) in results.groupBy { it.task.skill }) append("| ").append(skill).append(" | ").append(rs.count { it.passed }).append(" of ").append(rs.size).append(" |\n")
        }
        append("\n\n| # | Task | Result | Why not | On phone / in cloud | Handoffs | Time | Ended |\n| --- | --- | --- | --- | --- | --- | --- | --- |\n")
        for (r in results) {
            append("| ").append(r.task.id.substringBefore('-')).append(" | ").append(r.task.title).append(" | ").append(if (r.passed) "✓ passed" else "✗ failed")
                .append(" | ").append(r.why.replace("|", "/")).append(" | ").append(r.counts.phone).append(" / ").append(r.counts.cloud).append(if (strong) " / strong " + r.counts.other else "")
                .append(" | ").append(r.counts.handoffs.joinToString("; ").replace("|", "/").ifEmpty { "none" })
                .append(" | ").append(r.seconds / 60).append(" min ").append(r.seconds % 60).append(" s | ").append(r.ended).append(" |\n")
        }
        append("\nEach task's folder has its files after the run (files/), the chat log (transcript.txt) and the check's output (check.txt).\n")
        append("Not run automatically: the airplane-mode check (Quick assist with no connection).\n")
    }
}
