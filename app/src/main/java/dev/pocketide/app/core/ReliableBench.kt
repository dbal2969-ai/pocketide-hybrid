package dev.pocketide.app.core

/**
 * The on-device reliability benchmark (1.5.186): Gemini Nano alone, in Private mode, so it costs no cloud quota and can run as often
 * as needed. Built with the method used for the Kaggle Gemma 4 entry: every task is sound (its check fails as given and passes with
 * the reference fix, proven on the phone before the model starts), carries a hidden check the model never sees, belongs to a
 * development split (the local suite, used for tuning) or a held-out split (never tuned on), and is run several times, since one
 * run of a sampling model is luck. Reported: pass@1 (the share of runs passed), pass^k (tasks passed in every run), and per skill,
 * split and track.
 *
 * Three tracks: the local suite's 20 Node tasks (dev), 24 new Node tasks (held-out, bench/hybrid/make_reliable.py), and real
 * Python fixes from Textualize/rich and psf/requests taken from Kaggle's competition tasks, given "located" (the file to fix and the
 * test to run are named; bench/hybrid/make_python.py). The task data is in the app's assets (bench/reliable/).
 */
object ReliableBench {
    /** Runs of each task in the full benchmark; the quick one runs each task once. */
    const val RUNS = 3

    /** Packages the Python track needs in PocketIDE's Linux (Alpine), and the command that checks they are there. */
    const val PY_CHECK = "python3 -c \"import pytest, pygments, markdown_it, urllib3, idna, charset_normalizer, certifi; print('ok')\""
    const val PY_INSTALL = "apk add --no-cache python3 py3-pytest py3-pygments py3-markdown-it-py py3-urllib3 py3-idna py3-charset-normalizer py3-certifi"

    /** The answer of an "answer" task must match; a "fix" task's test and hidden check must pass. */
    private fun judge(kind: String, answer: Regex?, python: Boolean): (HybridBenchTasks.Facts) -> String? = { f ->
        val cloud = HybridBenchTasks.counts(f.transcript).cloud
        when {
            cloud > 0 -> "a step ran in the cloud in Private mode"
            kind == "answer" -> when {
                f.files.filterKeys { !it.startsWith(".") && !it.contains("__pycache__") } != f.original -> "files were changed; the task asked for no change"
                answer != null && !answer.containsMatchIn(HybridBenchTasks.lastAnswer(f.transcript)) ->
                    "the answer is not right: \"" + HybridBenchTasks.lastAnswer(f.transcript).take(140) + "\""
                else -> null
            }
            python && f.commandExit != 0 -> "the tests fail: " + reason(f.commandOut)
            !python && !HybridBenchTasks.commandOk(f) -> "node test.js failed: " + reason(f.commandOut)
            f.hiddenExit != null && f.hiddenExit != 0 -> "the hidden check failed (other behaviour broke): " + reason(f.hiddenOut)
            else -> null
        }
    }

    private fun strMap(v: Any?): Map<String, String> = (MiniJson.obj(v) ?: emptyMap()).mapValues { it.value?.toString().orEmpty() }

    /** The held-out Node tasks from node.json. */
    fun nodeTasks(json: String): List<HybridBenchTasks.Task> = (MiniJson.list(MiniJson.parse(json)) ?: emptyList()).mapNotNull { MiniJson.obj(it) }.map { o ->
        val kind = o["kind"]?.toString() ?: "fix"
        val hidden = o["hidden"]?.toString().orEmpty()
        val files = strMap(o["files"])
        HybridBenchTasks.Task(o["id"].toString(), o["title"].toString(), o["prompt"].toString(),
            command = if (files.containsKey("test.js")) "node test.js" else null, privateMode = true, skill = o["skill"].toString(),
            judge = judge(kind, o["answer"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let { Regex(it) }, false),
            startFiles = files, hidden = if (hidden.isEmpty()) emptyMap() else mapOf("hidden.js" to hidden),
            hiddenCommand = if (hidden.isEmpty()) null else "node hidden.js", solution = strMap(o["solution"]), split = "held-out", track = "Node")
    }

    /**
     * The Python tasks from python.json; [project] gives a task's starting files (its zip in the assets). The prompt names the file
     * and the test, as in the "located" setting, and quotes the issue.
     */
    fun pythonTasks(json: String, project: (String) -> Map<String, String>): List<HybridBenchTasks.Task> =
        (MiniJson.list(MiniJson.parse(json)) ?: emptyList()).mapNotNull { MiniJson.obj(it) }.map { o ->
            val id = o["id"].toString()
            val visible = o["visible"].toString().replace("python3 -m", "python3 -B -m")
            val hiddenCmd = o["hidden"]?.toString().orEmpty().replace("python3 -m", "python3 -B -m")
            // 1.5.187 (reliability run 1125-1815: 0 of 9): the issue text alone ("port bpo-39057") told Nano nothing; the failing test's
            // own code says what must change, as a person would show it
            val code = o["test_code"]?.toString().orEmpty().trim()
            val prompt = "Fix this issue in ${o["fix"]}. Then run `$visible`; it must pass." +
                (if (code.isNotEmpty()) "\n\nThe test that fails now (in ${(MiniJson.list(o["tests"]) ?: emptyList()).joinToString { it.toString() }}):\n```python\n$code\n```" else "") +
                "\n\nThe issue:\n" + o["statement"].toString().trim().take(900)
            HybridBenchTasks.Task(id, "${o["repo"]}: fix ${o["fix"].toString().substringAfterLast('/')}", prompt, command = visible, privateMode = true,
                skill = "Real code (${o["repo"]})", judge = judge("fix", null, true),
                startFiles = project(id), hiddenCommand = hiddenCmd.ifBlank { null }, solution = strMap(o["solution"]),
                split = "held-out", track = "Python")
        }

    /** The local suite as the development split. */
    fun devTasks(hidden: Map<String, String> = emptyMap()): List<HybridBenchTasks.Task> = HybridBenchTasks.LOCAL.map { t ->
        // 1.5.187 (reliability run 1125-1815): the local suite's hidden checks ran only in analyze.py, which caught l13 run 3 passing
        // on the phone with a switch that broke other cases; now the phone runs them too
        val h = hidden[t.id]
        val judge: (HybridBenchTasks.Facts) -> String? = { f -> t.judge(f) ?: hiddenWhy(f) }
        HybridBenchTasks.Task(t.id, t.title, t.prompt, t.command, true, t.skill, t.entry, hidden = if (h == null) emptyMap() else mapOf("hidden.js" to h),
            hiddenCommand = if (h == null) null else "node hidden.js", split = "dev", track = "Node", judge = judge)
    }

    /** Why a run failed its hidden check, or null. */
    fun hiddenWhy(f: HybridBenchTasks.Facts): String? =
        if (f.hiddenExit != null && f.hiddenExit != 0) "the hidden check failed (other behaviour broke): " + reason(f.hiddenOut) else null

    /**
     * The line of a check's output that says what went wrong (1.5.187: the last line was often Node's "Node.js v24.22.3" after a
     * stack trace): a failed check's own message, an Error line, or pytest's summary line.
     */
    fun reason(out: String): String {
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("at ") && !Regex("""^Node\.js v\d""").matches(it) }
        return (lines.lastOrNull { Regex("""\d+ (failed|error)""").containsMatchIn(it) }
            ?: lines.firstOrNull { Regex("""(?i)error|gave |is still|missing|should|need|changed""").containsMatchIn(it) }
            ?: lines.lastOrNull()).orEmpty().take(160)
    }

    /**
     * The same tasks with every step in the cloud (1.5.190, the cloud-only benchmark): Private mode is off, and the checks are the
     * same except that a step on this phone fails the run instead of a step in the cloud. The scores compare directly with Gemini
     * Nano's runs of the same tasks.
     */
    fun forCloud(tasks: List<HybridBenchTasks.Task>): List<HybridBenchTasks.Task> = tasks.map { t ->
        HybridBenchTasks.Task(t.id, t.title, t.prompt, t.command, false, t.skill, t.entry, t.startFiles ?: t.files, t.hidden, t.hiddenCommand,
            t.solution, t.split, t.track) { f -> cloudJudge(t, f) }
    }

    const val ON_PHONE = "a step ran on this phone in the cloud-only run"

    fun cloudJudge(t: HybridBenchTasks.Task, f: HybridBenchTasks.Facts): String? {
        if (HybridBenchTasks.counts(f.transcript).phone > 0) return ON_PHONE
        // the task's own check fails any cloud step (it was written for Private mode); the cloud marks are renamed for it
        return t.judge(HybridBenchTasks.Facts(f.files, f.original, f.commandExit, f.commandOut, f.transcript.replace("☁ Ran in the cloud", "☁ Cloud step"),
            f.ended, f.settingsChanged, f.hiddenExit, f.hiddenOut))
    }

    /** The id ending of a cloud run in the side-by-side suite: "r05-fix-comparison#2@cloud". */
    const val CLOUD = "@cloud"

    fun isCloud(id: String) = id.endsWith(CLOUD)

    /**
     * The side-by-side suite (1.5.190): each run of each task on Gemini Nano (Private mode, as in the on-device suite) and right after
     * it in the cloud, so both see the same phone, the same hour and the same packages.
     */
    fun both(tasks: List<HybridBenchTasks.Task>): List<HybridBenchTasks.Task> = tasks.flatMap { t ->
        val c = forCloud(listOf(t)).single()
        listOf(t, HybridBenchTasks.Task(t.id + CLOUD, t.title, t.prompt, c.command, false, c.skill, c.entry, c.startFiles ?: c.files, c.hidden,
            c.hiddenCommand, c.solution, c.split, c.track, c.judge))
    }

    /**
     * The side-by-side report: each engine's own reliability section, then one table comparing pass@1 by group, and the tasks
     * where one engine passed every run and the other none.
     */
    fun sideBySide(results: List<HybridBenchTasks.Result>): String = buildString {
        val (cloud, nano) = results.partition { isCloud(it.task.id) }
        append("\n# Gemini Nano on this phone\n").append(summary(nano).replace("## Reliability", "## Reliability: Gemini Nano"))
        append("\n# Gemini in the cloud\n").append(summary(cloud).replace("## Reliability", "## Reliability: cloud").replace("(where Nano is unreliable)", "(where the cloud is unreliable)"))
        fun pct(rs: List<HybridBenchTasks.Result>) = if (rs.isEmpty()) "–" else "${rs.count { it.passed } * 100 / rs.size}% (${rs.count { it.passed }} of ${rs.size})"
        append("\n## Nano and the cloud side by side\n\n| Group | Gemini Nano pass@1 | Cloud pass@1 |\n| --- | --- | --- |\n")
        fun row(label: String, sel: (HybridBenchTasks.Result) -> Boolean) { append("| $label | ${pct(nano.filter(sel))} | ${pct(cloud.filter(sel))} |\n") }
        row("All") { true }
        for (k in results.map { it.task.split.ifBlank { "other" } }.distinct()) row("Split: $k") { it.task.split.ifBlank { "other" } == k }
        for (k in results.map { it.task.track.ifBlank { "other" } }.distinct()) row("Track: $k") { it.task.track.ifBlank { "other" } == k }
        for (k in results.map { it.task.skill.ifBlank { "other" } }.distinct()) row(k) { it.task.skill.ifBlank { "other" } == k }
        val n = nano.groupBy { baseId(it.task.id) }; val c = cloud.groupBy { baseId(it.task.id) }
        val onlyCloud = n.keys.filter { k -> n[k]!!.none { it.passed } && c[k]?.all { it.passed } == true }
        val onlyNano = n.keys.filter { k -> n[k]!!.all { it.passed } && c[k]?.none { it.passed } == true }
        append("\nThe cloud passed every run and Nano none: ").append(onlyCloud.joinToString().ifEmpty { "none" }).append('\n')
        append("Nano passed every run and the cloud none: ").append(onlyNano.joinToString().ifEmpty { "none" }).append('\n')
        val secs = { rs: List<HybridBenchTasks.Result> -> rs.map { it.seconds }.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] } }
        append("Median time per run: Nano ").append(secs(nano)).append(" s, cloud ").append(secs(cloud)).append(" s.\n")
    }

    /** "r05-fix-comparison#2" → "r05-fix-comparison": a task's runs share its id before the #. */
    fun baseId(id: String): String = id.substringBefore('#').removeSuffix(CLOUD)

    /** Each task [runs] times, run by run (all tasks once, then again), so a slow phone or a bad hour does not hit one task's runs together. */
    fun repeated(tasks: List<HybridBenchTasks.Task>, runs: Int): List<HybridBenchTasks.Task> = (1..runs).flatMap { r ->
        tasks.map { t -> if (runs == 1) t else HybridBenchTasks.Task("${t.id}#$r", t.title, t.prompt, t.command, t.privateMode, t.skill, t.entry,
            t.startFiles ?: t.files, t.hidden, t.hiddenCommand, t.solution, t.split, t.track, t.judge) }
    }

    /** The report's reliability section: pass@1 and pass^k overall, by split, track and skill, and the tasks that passed only sometimes. */
    fun summary(results: List<HybridBenchTasks.Result>): String = buildString {
        val byTask = results.groupBy { baseId(it.task.id) }
        val runs = byTask.values.maxOfOrNull { it.size } ?: 0
        fun line(label: String, rs: List<HybridBenchTasks.Result>) {
            val tasks = rs.groupBy { baseId(it.task.id) }
            val all = tasks.count { (_, v) -> v.all { it.passed } }
            val pct = if (rs.isEmpty()) 0 else rs.count { it.passed } * 100 / rs.size
            append("| ").append(label).append(" | ").append(tasks.size).append(" | ").append(pct).append("% (").append(rs.count { it.passed }).append(" of ").append(rs.size)
                .append(") | ").append(all).append(" of ").append(tasks.size).append(" |\n")
        }
        append("\n## Reliability\n\n")
        append("pass@1 = the share of runs that passed; pass^").append(runs).append(" = tasks passed in every one of their ").append(runs).append(" runs.\n\n")
        append("| Group | Tasks | pass@1 | pass^").append(runs).append(" |\n| --- | --- | --- | --- |\n")
        line("All", results)
        for ((k, v) in results.groupBy { it.task.split.ifBlank { "other" } }) line("Split: $k", v)
        for ((k, v) in results.groupBy { it.task.track.ifBlank { "other" } }) line("Track: $k", v)
        for ((k, v) in results.groupBy { it.task.skill.ifBlank { "other" } }) line(k, v)
        val flaky = byTask.filter { (_, v) -> v.any { it.passed } && !v.all { it.passed } }
        if (flaky.isNotEmpty()) {
            append("\n**Passed only in some runs** (where Nano is unreliable):\n")
            for ((k, v) in flaky) append("- ").append(k).append(": ").append(v.count { it.passed }).append(" of ").append(v.size).append(" · ")
                .append(v.filter { !it.passed }.joinToString("; ") { it.why.take(90) }).append('\n')
        }
        val damage = results.count { it.why.startsWith("the hidden check failed") }
        val claims = results.count { !it.passed && it.ended == "done" }
        append("\nHidden checks that failed (the visible test passed but other behaviour broke): ").append(damage)
            .append(" · runs that ended \"done\" but failed the check (a finish claimed too early): ").append(claims).append('\n')
    }
}
