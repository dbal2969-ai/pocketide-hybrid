package dev.pocketide.app.bench

import dev.pocketide.app.ModelGateway
import java.io.File

/** Runs benchmark tasks through the agent harness and scores them (1.5.86). */
object BenchRunner {
    class Result(
        val task: String, val model: String, val pass: Boolean, val requests: Int, val calls: Int,
        val failedEdits: Int, val emptySearches: Int, val refused: Int, val seconds: Long, val ending: String, val checkOutput: String,
        /** Each tool call with its result, then the final answer: what to read when a run fails. */
        val transcript: String = "",
        val reads: Int = 0,
        /** For a two-try task: whether the first attempt passed (null for other tasks). */
        val firstPass: Boolean? = null,
        /** Every model request of every attempt, with its job, time and tokens (1.5.138). */
        val log: List<AgentHarness.Request> = emptyList(),
        /** Tool calls that could not run as sent, both tries (1.5.164: tool-call health per model and provider). */
        val badCalls: Int = 0,
    ) {
        /** Tokens by job: "main" (the model under test), "explore", "summary", "route", "advisor", "askcount" (1.5.139). */
        val byRole: Map<String, dev.pocketide.app.core.Usage> get() = log.groupBy { it.role }.mapValues { (_, rs) -> rs.fold(dev.pocketide.app.core.Usage()) { a, r -> a + r.usage } }
        val main: dev.pocketide.app.core.Usage get() = byRole["main"] ?: dev.pocketide.app.core.Usage()
        /** Tokens of every other job together (the helper model's, or the main model's own helper calls when no helper is set). */
        val helper: dev.pocketide.app.core.Usage get() = byRole.filterKeys { it != "main" }.values.fold(dev.pocketide.app.core.Usage()) { a, u -> a + u }
        val total: Long get() = main.total + helper.total
    }

    fun run(task: BenchTasks.Task, gateway: ModelGateway, provider: String, model: String, key: String = "", base: String = "", timeoutSeconds: Long = 900,
            /** Gets the transcript so far before every model request, so an interrupted run still leaves its steps on disk. */
            progress: ((String) -> Unit)? = null): Result {
        val started = System.currentTimeMillis()
        fun attempt(extra: String, before: String) = AgentHarness(task.files, task.request, gateway, rounds = task.rounds, provider = provider, modelName = model, apiKey = key, base = base,
            timeoutSeconds = timeoutSeconds, appTools = System.getenv("POCKETIDE_BENCH_APP_TOOLS")?.trim() != "0",
            advisor = System.getenv("POCKETIDE_BENCH_ADVISOR").orEmpty().trim(), extra = extra, chatHome = task.chatHome, steer = task.steer,
            onProgress = progress?.let { p -> { calls, n, msgs -> p(before + partial(task, calls, n, (System.currentTimeMillis() - started) / 1000, msgs)) } }).run()
        var o = attempt("", "")
        // a second try, as the phone makes one (Settings → Second try): the first attempt's files are dropped and the task starts again
        var first = ""
        var firstRequests = 0
        var firstBad = 0
        val earlier = ArrayList<AgentHarness.Request>()
        val retry = if (System.getenv("POCKETIDE_BENCH_SECOND_TRY")?.trim() == "1" && !o.timedOut) o.result?.let { r ->
            dev.pocketide.app.core.SecondTry.reason(!task.readOnly && !dev.pocketide.app.core.Habits.isQuestion(task.request) && !dev.pocketide.app.core.Scope.reportOnly(task.request),
                r.personStopped, r.error, r.cutShort, r.failedCheck, r.text)
        } else null
        if (retry != null) {
            val answer1 = o.result?.text.orEmpty()
            first = transcript(task, o.calls, answer1, "second try: $retry", "(not checked: a second try followed)", o.messages, o.result?.log.orEmpty()) + "\n\n# Second try\n\n"
            firstRequests = o.requests
            earlier += o.log
            o.root.deleteRecursively()
            o = attempt(dev.pocketide.app.core.SecondTry.note(retry, answer1), first)
        }
        // 1.5.125: escalation, as the phone does it (Escalate): an unfinished run carries on with the strong model on the files as left
        val esc = System.getenv("POCKETIDE_BENCH_ESCALATE").orEmpty().trim()
        val escalated = if (esc.contains(':') && !o.timedOut) o.result?.let { r ->
            dev.pocketide.app.core.SecondTry.reason(!task.readOnly && !dev.pocketide.app.core.Habits.isQuestion(task.request) && !dev.pocketide.app.core.Scope.reportOnly(task.request),
                r.personStopped, r.error, r.cutShort, r.failedCheck, r.text)
        } else null
        if (escalated != null) {
            val ep = esc.substringBefore(':').trim(); val em = esc.substringAfter(':').trim()
            val env = ep.uppercase().replace('-', '_')
            val r1 = o.result!!
            val left = o.root.walkTopDown().filter { it.isFile && !it.path.contains("/.bench/") }.associate { it.relativeTo(o.root).path to it.readText() }
            first += transcript(task, o.calls, r1.text, "escalated: $escalated", "(not checked: the strong model carried on)", o.messages, r1.log) + "\n\n# Carried on with $esc\n\n"
            firstRequests += o.requests
            firstBad += o.badCalls
            earlier += o.log
            o.root.deleteRecursively()
            o = AgentHarness(left, task.request, gateway, rounds = task.rounds, provider = ep, modelName = em,
                apiKey = System.getenv("POCKETIDE_BENCH_KEY_$env").orEmpty(), base = System.getenv("POCKETIDE_BENCH_BASE_$env").orEmpty(),
                timeoutSeconds = timeoutSeconds, appTools = System.getenv("POCKETIDE_BENCH_APP_TOOLS")?.trim() != "0",
                extra = dev.pocketide.app.core.Escalate.note(escalated, model, r1.progress.ifBlank { r1.text })).run()
        }
        var answer = o.result?.let { it.text + (if (it.error != null) "\n" + it.error else "") }.orEmpty()
        File(o.root, ".bench").mkdirs(); File(o.root, ".bench/answer.txt").writeText(answer)
        var (code, out) = AgentHarness.shell(o.root, task.check)
        // Aider's second attempt: the failed tests' output, the files as the first attempt left them (without the hidden tests)
        var firstPass: Boolean? = null
        if (task.twoTries) {
            firstPass = code == 0 && !o.timedOut
            if (!firstPass && !o.timedOut && o.result?.error == null) {
                val left = o.root.walkTopDown().filter { it.isFile && !it.path.contains("/.bench/") && !it.path.contains("/__pycache__/") && !it.name.endsWith(".pyc") }
                    .associate { it.relativeTo(o.root).path to it.readText() }.filterKeys { it !in task.hidden }
                first += transcript(task, o.calls, answer, "first attempt failed the tests", out, o.messages, o.result?.log.orEmpty()) + "\n\n# Second attempt (test output shown)\n\n"
                firstRequests += o.requests
                earlier += o.log
                firstBad += o.badCalls
                o.root.deleteRecursively()
                o = AgentHarness(left, task.request, gateway, rounds = task.rounds, provider = provider, modelName = model, apiKey = key, base = base,
                    timeoutSeconds = timeoutSeconds, appTools = System.getenv("POCKETIDE_BENCH_APP_TOOLS")?.trim() != "0",
                    extra = secondAttempt(task, out), repair = true,
                    // 1.5.163: as Aider, the second try carries on the first try's conversation (its last answer included)
                    history = if (o.messages.isEmpty()) emptyList() else o.messages + listOf(dev.pocketide.app.core.Tools.assistantText(answer.ifBlank { "Done." }))).run()
                answer = o.result?.let { it.text + (if (it.error != null) "\n" + it.error else "") }.orEmpty()
                File(o.root, ".bench").mkdirs(); File(o.root, ".bench/answer.txt").writeText(answer)
                AgentHarness.shell(o.root, task.check).let { code = it.first; out = it.second }
            }
        }
        // a read-only task must leave every file exactly as it was
        val unchanged = !task.readOnly || task.files.all { (p, t) -> File(o.root, p).let { it.isFile && it.readText() == t } } &&
            o.root.walkTopDown().filter { it.isFile && !it.path.contains("/.bench/") }.count() == task.files.size
        val ending = when {
            o.timedOut -> "timed out"
            o.result == null -> "no result"
            o.result.error != null -> "error: " + o.result.error.take(120)
            o.result.stopped -> "stopped"
            !o.result.ok -> "not finished"
            // 1.5.170: the app's verdict (Outcome): "finished (checked)" or "finished (not checked)"; plain "finished" for questions and reports
            else -> o.result.outcome?.ending ?: "finished"
        } + (if (o.calls.any { it.first.name == "report_finding" }) ", reported a finding" else "")   // Copilot N2 (PR #27): over-use shows
        return Result(task.name, "$provider:$model", code == 0 && unchanged && !o.timedOut, firstRequests + o.requests, o.calls.size,
            o.calls.count { it.second.startsWith("Edit not applied") || it.second.startsWith("Patch not applied") },
            o.calls.count { it.second.startsWith("No matches") },
            o.calls.count { it.second.startsWith("Refused") || it.second.startsWith("Not done") || it.second.startsWith("Not written") },
            (System.currentTimeMillis() - started) / 1000, (if (retry != null) "2nd try, " else "") + (if (escalated != null) "escalated, " else "") + ending, out.trim().take(300) + (if (!unchanged) " [changed files in a read-only task]" else ""),
            first + transcript(task, o.calls, answer, ending, out, o.messages, o.result?.log.orEmpty()) + requestLog(earlier + o.log) +
                (if (o.meaningLog.isEmpty()) "" else "\n## Search by meaning (${o.meaningLog.size})\n\n" + o.meaningLog.joinToString("\n") { "- $it" } + "\n"),
            o.calls.count { it.first.name == "read_file" }, firstPass, earlier + o.log, firstBad + o.badCalls)
    }

    private fun tok(n: Long, estimated: Boolean) = (if (estimated && n > 0) "~" else "") + n

    /** The model requests of a run in order: job, seconds and tokens, so a slow model can be told from slow tools (1.5.138). */
    fun requestLog(log: List<AgentHarness.Request>): String {
        if (log.isEmpty()) return ""
        val sb = StringBuilder("\n## Model requests (${log.size})\n\n| # | Job | Seconds | In | Cached | Out |\n|---|---|---|---|---|---|\n")
        log.forEachIndexed { i, r ->
            val u = r.usage
            sb.append("| ${i + 1} | ${r.role} | ${"%.1f".format(r.millis / 1000.0)} | ${tok(u.input + u.cacheWrite, u.estimated)} | ${tok(u.cached, u.estimated)} | ${tok(u.output, u.estimated)} |\n")
        }
        return sb.toString()
    }

    /** Aider's words for the second attempt, with the failing tests' output. */
    /** The second try's message (1.5.164): Aider's wording after the whole output, with the tests declared correct and out of reach. */
    fun secondAttempt(task: BenchTasks.Task, testOutput: String): String =
        dev.pocketide.app.core.RepairTurn.message(task.files.keys, testOutput.trim().lines().takeLast(OUTPUT_LINES).joinToString("\n"))

    /**
     * Lines of test output the second try sees (1.5.163; 50 before). Aider sends the whole output; a jest failure block is about 20
     * lines, so 50 showed two of bowling's seven failures and the second try fixed what it was shown.
     */
    const val OUTPUT_LINES = 400

    /** A run that has not ended yet: its steps so far, marked so a reader knows the answer and check are missing. */
    fun partial(task: BenchTasks.Task, calls: List<Pair<dev.pocketide.app.core.ToolCall, String>>, requests: Int, seconds: Long,
                messages: List<Map<String, Any?>> = emptyList()): String =
        steps(task, calls, messages).append("## Not finished yet\n\n$requests requests and ${calls.size} tool calls after ${seconds}s. If the run was cut off " +
            "(a restart, a killed build), these are the steps it took; there is no answer or check.\n").toString()

    // The steps in order: each tool call with its result, and (from the conversation) what the model wrote between calls and
    // what the app told it, such as an ask to call a tool it wrote as text. Without the conversation, only the calls.
    private fun steps(task: BenchTasks.Task, calls: List<Pair<dev.pocketide.app.core.ToolCall, String>>, messages: List<Map<String, Any?>> = emptyList()): StringBuilder {
        val sb = StringBuilder("# ${task.name}\n\nRequest: ${task.request}\n\n")
        fun call(i: Int, c: dev.pocketide.app.core.ToolCall, r: String) =
            sb.append("## ${i + 1}. ${c.name}\n\n```\n${c.arguments.take(1500)}\n```\n\n```\n${r.take(2000)}\n```\n\n")
        if (messages.isEmpty()) { calls.forEachIndexed { i, (c, r) -> call(i, c, r) }; return sb }
        val byId = AgentHarness.pairs(messages).associateBy { it.first.id }
        var n = 0
        messages.forEachIndexed { at, m ->
            val text = textOf(m["content"]).trim()
            when (m["role"]) {
                "user" -> if (at > 0 && text.isNotEmpty()) sb.append("### App to model\n\n${text.take(1500)}\n\n")
                "assistant" -> if (text.isNotEmpty()) sb.append("### Model said\n\n${text.take(1500)}\n\n")
                "tool" -> (m["tool_call_id"] as? String)?.let { byId[it] }?.let { (c, r) -> call(n++, c, r) }
            }
        }
        return sb
    }

    private fun textOf(v: Any?): String = when (v) {
        is String -> v
        is List<*> -> v.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("\n")
        else -> ""
    }

    private fun transcript(task: BenchTasks.Task, calls: List<Pair<dev.pocketide.app.core.ToolCall, String>>, answer: String, ending: String, check: String,
                           messages: List<Map<String, Any?>> = emptyList(), log: List<dev.pocketide.app.core.WorkLog.Item> = emptyList()): String {
        val sb = steps(task, calls, messages)
        // the model's thinking is not part of the conversation it is sent, so it comes from the work log (1.5.95)
        val thoughts = log.filter { it.kind == dev.pocketide.app.core.WorkLog.Kind.THINK }
        if (thoughts.isNotEmpty()) {
            sb.append("## The model's thinking (${thoughts.size}, in order)\n\n")
            thoughts.forEachIndexed { i, t -> sb.append("### ${i + 1}. ${dev.pocketide.app.core.WorkLog.thoughtTitle(t)}\n\n${dev.pocketide.app.core.WorkLog.clip(t.text, 4000)}\n\n") }
        }
        sb.append("## Answer ($ending)\n\n$answer\n\n## Check (`${task.check}`)\n\n```\n${check.take(2000)}\n```\n")
        return sb.toString()
    }

    /** A Markdown report: one row per run, then a line per model. */
    /**
     * 1.5.161 (Copilot HANDOFF-CALIBRATION S5): the app version and every setting a run can change, written under the report's title
     * so tools/bench/summarize.py can put them on each row (they were only in the workflow run's title).
     */
    fun settingsLine(env: (String) -> String? = System::getenv, version: String = appVersion()): String {
        fun flag(name: String) = if (env("POCKETIDE_BENCH_$name")?.trim() == "1") "1" else "0"
        val helper = env("POCKETIDE_BENCH_HELPER")?.trim().orEmpty().ifEmpty { "main" }
        val tools = if (env("POCKETIDE_BENCH_APP_TOOLS")?.trim() == "0") "0" else "1"
        return "Settings: version=${version.substringBefore('-')} ask=${flag("ASK_CHECK")} cause=${flag("CAUSE_FIRST")} scope=${flag("SCOPE")} " +
            "variety=${flag("VARIETY")} look=${flag("LOOK")} verify=${flag("VERIFY")} premise=${flag("PREMISE")} explore=${flag("EXPLORE")} helper=$helper app_tools=$tools"
    }

    /** The versionName in app/build.gradle.kts (unit tests run in app/); "?" when it can't be read. */
    fun appVersion(): String = listOf(java.io.File("build.gradle.kts"), java.io.File("app/build.gradle.kts")).firstOrNull { it.isFile }
        ?.readText()?.let { Regex("""versionName\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1) } ?: "?"

    /** A run the provider refused or failed before any tool ran (HTTP 4xx/5xx, a bad key): not a score of the model (S5). */
    fun providerError(r: Result): Boolean = r.ending.substringAfter("2nd try, ").startsWith("error:") && r.calls == 0

    fun report(results: List<Result>): String {
        // 1.5.138: tokens per run (Copilot, HANDOFF-RAISE-CEILING WP7). "In" is the main model's prompt tokens not read from a cache,
        // "Cached" those read from one, "Out" its output; "Helper" is every other job's tokens. "~" = estimated from characters
        // (the provider reported none).
        val sb = StringBuilder("| Model | Task | Pass | Requests | Tool calls | Failed edits | Empty searches | Refused | Seconds | Ending | Read file calls | In | Cached | Out | Helper | Bad calls |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
        for (r in results) sb.append("| ${r.model} | ${r.task} | ${if (r.pass) "yes" else "**no**"} | ${r.requests} | ${r.calls} | ${r.failedEdits} | ${r.emptySearches} | ${r.refused} | ${r.seconds} | ${r.ending} | ${r.reads} | " +
            "${tok(r.main.input + r.main.cacheWrite, r.main.estimated)} | ${tok(r.main.cached, r.main.estimated)} | ${tok(r.main.output, r.main.estimated)} | ${tok(r.helper.total, r.helper.estimated)} | ${r.badCalls} |\n")
        sb.append("\n")
        for ((m, rs) in results.groupBy { it.model }) {
            sb.append("- **$m**: ${rs.count { it.pass }} of ${rs.size} passed" +
                rs.filter { it.firstPass != null }.let { two -> if (two.isEmpty()) "" else " (two-try tasks: ${two.count { it.firstPass == true }} of ${two.size} on the first try, ${two.count { it.pass }} with the second)" } +
                ", median ${rs.map { it.requests }.sorted()[rs.size / 2]} requests, ${rs.sumOf { it.seconds }} s in all, " +
                "tokens: ${rs.sumOf { it.main.input + it.main.cacheWrite }} in, ${rs.sumOf { it.main.cached }} cached, ${rs.sumOf { it.main.output }} out, " +
                "${rs.sumOf { it.helper.total }} helper" + (if (rs.any { it.main.estimated || it.helper.estimated }) " (some estimated)" else "") + ".\n")
        }
        results.count { providerError(it) }.takeIf { it > 0 }?.let { sb.append("\n$it run(s) ended in a provider error before any tool ran; tools/bench/summarize.py leaves them out of pass rates.\n") }
        val failed = results.filter { !it.pass }
        if (failed.isNotEmpty()) {
            sb.append("\nFailures:\n")
            for (r in failed) sb.append("- ${r.model} / ${r.task}: ${r.ending}. Check said: ${r.checkOutput.ifBlank { "(nothing)" }.replace("\n", " ")}\n")
        }
        return sb.toString()
    }

    /**
     * Where reports and transcripts go: POCKETIDE_BENCH_OUT when set (a folder that outlives the build, such as one under
     * /workspaces in a Codespace, where /tmp is emptied by a restart), else [fallback]. A run's transcript is rewritten there before every model request, and the report after every run.
     */
    fun saveDir(fallback: File, env: String? = System.getenv("POCKETIDE_BENCH_OUT")): File =
        (env?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it) } ?: fallback).also { it.mkdirs() }

    private val ACCOUNT = Regex("""(/accounts/)[^/\s]+(/)""")

    /** Removes credentials and the account id from text that is saved or printed. */
    fun redact(text: String, secrets: Collection<String> = System.getenv().filterKeys { it.startsWith("POCKETIDE_BENCH_KEY_") }.values): String {
        var clean = text
        for (secret in secrets.flatMap { listOf(it, dev.pocketide.app.core.KeyClean.clean(it)) }.filter { it.isNotEmpty() }.sortedByDescending { it.length }) {
            clean = clean.replace(secret, "<key>")
        }
        return ACCOUNT.replace(clean, "$1<id>$2")
    }
}
