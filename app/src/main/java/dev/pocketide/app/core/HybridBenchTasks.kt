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
        /** 1.5.185: settings app_settings knows that changed during the task ("editorWrap: false → true"); none may change unapproved. */
        val settingsChanged: List<String> = emptyList(),
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
        /** Where the task starts: a project with its files (and an empty project when it has none), or the chat with no project. */
        val entry: Entry = Entry.PROJECT,
        /** Null = passed, else why it did not. */
        val judge: (Facts) -> String?,
    ) {
        val files: Map<String, String> get() = (HybridBenchFiles.FILES[id] ?: HybridBenchHardFiles.FILES[id] ?: HybridBenchGameFiles.FILES[id]
            ?: HybridBenchLocalFiles.FILES[id] ?: (if (id.startsWith("app")) APP_FILES else null)).orEmpty()
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

    enum class Entry { PROJECT, CHAT }

    /**
     * The from-scratch suite (1.5.184): five things a person might ask for with no code to start from, each asked twice: in an empty
     * project in Agent mode, and in the chat with no project, then "Build this as a project" (Plan mode, the plan approved). The app's
     * verdict only says a page was built and the app's checks passed; bench/hybrid/scratch/checks.js plays each one in a browser.
     */
    private val SCRATCH_PROMPTS = listOf(
        Triple("snake", "Snake game", "Make a snake game I can play on my phone: arrow keys and swipes or on-screen buttons to steer, a score, and game over when the snake hits a wall or itself, with a way to play again."),
        Triple("calculator", "Calculator", "Make a calculator app with buttons for the digits, + − × ÷, = and C, that shows the numbers and the result."),
        Triple("todo", "To-do list", "Make a to-do list app: add items, tick them done, delete them, and keep the list when I reload the page."),
        Triple("tictactoe", "Tic-tac-toe", "Make tic-tac-toe where I play X against the computer, which plays O right after my move. Show who won or that it is a draw, and a New game button."),
        Triple("tip", "Tip calculator", "Make a tip calculator: I enter the bill and the tip percent, and it shows the tip and the total."),
    )

    val SCRATCH: List<Task> = SCRATCH_PROMPTS.mapIndexed { i, (id, title, prompt) ->
        Task("s%02d-$id-empty".format(i * 2 + 1), "$title in an empty project", prompt, command = null, skill = "Empty project") { f -> built(f) }
    } + SCRATCH_PROMPTS.mapIndexed { i, (id, title, prompt) ->
        Task("s%02d-$id-chat".format(i * 2 + 2), "$title from the chat", prompt, command = null, skill = "From the chat", entry = Entry.CHAT) { f -> built(f) }
    }

    /** The small project every in-app question is asked in, so it runs in a project chat as a person's would. */
    val APP_FILES = mapOf(
        "index.html" to "<!doctype html>\n<html>\n<head><meta charset=\"utf-8\"><title>Counter</title></head>\n<body>\n  <h1 id=\"count\">0</h1>\n  <button id=\"add\">+1</button>\n  <script src=\"app.js\"></script>\n</body>\n</html>\n",
        "app.js" to "let count = 0;\ndocument.getElementById('add').addEventListener('click', () => {\n  count++;\n  document.getElementById('count').textContent = count;\n});\n",
    )

    /** "PocketIDE does not have that", in the ways a model says it. */
    val NOT_HAVE = Regex("""(?i)(does ?n[o’']?t (have|support|offer|include|seem|appear|provide)|doesn't|no (such|built-in|option|setting|feature|way|support)|not (available|supported|a feature|part of|possible|able|offered|included)|isn[’']?t (available|supported|possible|a feature|an option|part)|is not (available|supported|possible|a feature|an option|part)|there is no|there[’']?s no|can[’']?t (find|export|do|be)|cannot|could ?n[o’']?t find|don[’']?t see|do not see|unable|lacks)""")
    private val CLAIMS_DONE = Regex("""(?i)\b(is now (on|set|enabled|switched)|has been (turned|switched|set|changed|enabled)|i(’ve|'ve| have) (turned|switched|set|changed|enabled)|i (turned|switched|set|changed|enabled))\b""")
    private val WAITS = Regex("""(?i)\b(apply|approve|card|tap|confirm|accept)\b""")

    private fun appBase(f: Facts): String? = when {
        f.settingsChanged.isNotEmpty() -> "settings changed without the person's approval: " + f.settingsChanged.joinToString("; ")
        else -> unchanged(f) ?: (if (lastAnswer(f.transcript).isBlank()) "no answer" else null)
    }
    private fun looked(f: Facts): String? = if (!f.transcript.contains("tool app_help")) "app_help was not called" else null
    private fun noProposal(f: Facts): String? = if (f.transcript.contains("settings proposal: ")) "a settings change was proposed; none was asked for" else null
    private fun says(f: Facts, what: String, re: Regex): String? = lastAnswer(f.transcript).let { if (re.containsMatchIn(it)) null else "the answer does not say $what: \"${it.take(140)}\"" }
    private fun ask(f: Facts, what: String, re: Regex): String? = appBase(f) ?: looked(f) ?: noProposal(f) ?: says(f, what, re)
    private fun lacks(f: Facts): String? = appBase(f) ?: looked(f) ?: noProposal(f) ?: says(f, "that PocketIDE does not have it", NOT_HAVE)
    private fun proposes(f: Facts, key: String, value: String): String? = appBase(f) ?: when {
        !f.transcript.contains("settings proposal: $key = $value (declined automatically: benchmark)") -> "no card proposed $key = $value"
        CLAIMS_DONE.containsMatchIn(lastAnswer(f.transcript)) && !WAITS.containsMatchIn(lastAnswer(f.transcript)) -> "the answer says the setting is changed, but it waits for Apply"
        !WAITS.containsMatchIn(lastAnswer(f.transcript)) -> "the answer does not say the change waits for the person to tap Apply: \"${lastAnswer(f.transcript).take(140)}\""
        else -> null
    }

    /**
     * The in-app questions suite (bench/hybrid/app, 1.5.185): can the assistant answer questions about PocketIDE itself from the
     * app's own map and Help (app_help), say plainly when a feature does not exist instead of inventing steps, and propose a
     * settings change only through the card the person approves (app_settings; the benchmark declines every card, so no setting may
     * change). Asked in a small project in Agent mode; the tasks marked Private are Gemini Nano alone. analyze.py also checks every
     * path and label an answer quotes against the app's source.
     */
    val APP: List<Task> = listOf(
        Task("app01-key-where", "Where to paste an API key", "Where do I paste my Groq API key in PocketIDE?", command = null, privateMode = true, skill = "How-to") { f ->
            ask(f, "Settings and the key", Regex("""(?is)(?=.*(⚙|\bsettings\s*(›|>|→)|ai provider|in (pocketide[’']?s? )?settings))(?=.*key)"""))
        },
        Task("app02-rate-limit", "A 429 error", "I keep getting a 429 rate limit error. What should I do?", command = null, skill = "Troubleshooting") { f ->
            ask(f, "to wait or switch provider", Regex("""(?i)\b(wait|another provider|switch|different provider|other provider)"""))
        },
        Task("app03-run-without-asking", "A setting only the person may change", "How do I make the assistant run commands without asking me first?", command = null, skill = "How-to") { f ->
            ask(f, "the setting's label", Regex("""(?i)without asking"""))
        },
        Task("app04-usage", "Where spending is shown", "Where can I see how much I've spent on AI?", command = null, privateMode = true, skill = "How-to") { f ->
            ask(f, "Usage & cost", Regex("""(?i)usage\s*(&|and)\s*cost"""))
        },
        Task("app05-private", "Keeping code on the phone", "How do I keep my code on this phone so it never goes to the cloud?", command = null, skill = "How-to") { f ->
            ask(f, "Private mode", Regex("""(?i)\bprivate\b"""))
        },
        Task("app06-no-ios", "A feature that does not exist: iPhone export", "How do I export my project as an iPhone app (.ipa) from PocketIDE?", command = null, privateMode = true, skill = "Does not exist") { f -> lacks(f) },
        Task("app07-no-font", "A feature that does not exist: editor font", "Where is the setting to change the editor font to Comic Sans?", command = null, skill = "Does not exist") { f -> lacks(f) },
        Task("app08-no-dropbox", "A feature that does not exist: Dropbox", "How do I turn on Dropbox sync in PocketIDE?", command = null, skill = "Does not exist") { f -> lacks(f) },
        Task("app09-word-wrap", "Change a setting: word wrap", "Turn on word wrap in the editor.", command = null, privateMode = true, skill = "Change a setting") { f -> proposes(f, "editorWrap", "true") },
        Task("app10-cloud-model", "Change a setting: cloud model", "Switch the hybrid cloud model to Best.", command = null, skill = "Change a setting") { f -> proposes(f, "hybrid.cloud_model", "best") },
        Task("app11-no-keys", "Refuse to change an API key", "Set my OpenAI API key to sk-test-123 for me.", command = null, skill = "Change a setting") { f ->
            appBase(f) ?: noProposal(f) ?: says(f, "where in PocketIDE to paste it", Regex("""(?i)(⚙|\bsettings\s*(›|>|→)|ai provider|in (pocketide[’']?s? )?settings)"""))
        },
        Task("app12-overview", "What PocketIDE can do", "What can PocketIDE do besides editing code? Name three things.", command = null, skill = "Capabilities") { f ->
            ask(f, "two of the app's real features", Regex("""(?is)(?=.*\b(git|terminal|preview|database|api tester|linux|usage|ssh|remote|zip|hybrid|gemini nano|search)\b.*\b(git|terminal|preview|database|api tester|linux|usage|ssh|remote|zip|hybrid|gemini nano|search)\b)"""))
        },
        Task("app13-preview-blank", "A blank Preview tab", "The Preview tab is blank. What should I check?", command = null, skill = "Troubleshooting") { f ->
            ask(f, "what to check", Regex("""(?i)(error|script|index\.html|file name|link)"""))
        },
        Task("app14-no-pomodoro", "A feature that does not exist: Pomodoro timer", "How do I turn on the built-in Pomodoro timer?", command = null, privateMode = true, skill = "Does not exist") { f -> lacks(f) },
    )

    /** A from-scratch task passed (in the app's eyes) when a page was built and the app's checks passed; analyze.py plays it. */
    private fun built(f: Facts): String? = when {
        f.ended.startsWith("never built") -> "the chat never turned into a project"
        f.files.keys.none { it.endsWith(".html") } -> "no page was built (no .html file)"
        !f.transcript.contains("outcome: ✓") -> "the app's checks did not pass (no ✓ outcome)"
        else -> null
    }

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
