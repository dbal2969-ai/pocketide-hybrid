package dev.pocketide.app.core

/**
 * /bench (1.5.125, roadmap phase 6): starts the live benchmark (.github/workflows/benchmark.yml) on the linked repository from
 * the phone, follows the run, reads its `summary` artifact and posts it on the branch's pull request. This is step 5 and 6 of
 * "PocketIDE updates itself" (ROADMAP-HANDOFF.md section 4): the agent's branch is compared with main before the owner merges.
 *
 * Every run spends the owner's Actions minutes and provider quota, so the budget of the roadmap's section 1 is a hard cap here,
 * not a suggestion: named tasks only (a whole suite only when typed as `suite <name>` and confirmed), at most [MAX_TASKS] tasks,
 * one run of each (two or three only with an explicit x2/x3 and a confirm), one model. Pure logic and plain HTTP through [GitApi];
 * no Android, so the whole flow is tested against a fake GitHub server.
 */
object BenchRun {
    const val WORKFLOW = "benchmark.yml"
    const val MAX_TASKS = 8
    const val MAX_REPEAT = 3
    const val MARK = "<!-- pocketide-bench -->"

    /** The task sets benchmark.yml offers (its `suite` choice). */
    val SUITES = listOf("standard", "hard", "heldout", "realproject", "polyglot", "all")

    /** About how many tasks each set holds at 1.5.125 (the tasks live in the test sources, which the app does not carry). */
    private val SUITE_SIZE = mapOf("standard" to 7, "hard" to 14, "heldout" to 7, "realproject" to 14, "all" to 42)

    /** The providers benchmark.yml has a key secret (or a keyless route) for; any other provider would fail at its key check. */
    val PROVIDERS = listOf("cloudflare", "groq", "openrouter", "gemini", "cerebras", "llm7", "kilo", "nvidia", "ollamacloud", "haimaker", "ovh")

    // the same shapes the workflow and tools/bench/summarize.py accept, so a bad name is refused here and costs no run
    private val TASK = Regex("[a-z0-9][a-z0-9-]{0,59}")
    private val BRANCH = Regex("[A-Za-z0-9._/-]{1,100}")
    private val REPEAT = Regex("[xX×]([0-9]+)")

    const val USAGE = "/bench <task,task…> [on <branch>] [with <provider:model>] [x2|x3]\n" +
        "e.g. /bench rp-loot,rp-bury on pocketide/issue-12\n" +
        "A branch runs it twice, on the default branch and on yours, and compares them (an A/B). " +
        "A whole task set: /bench suite <standard|hard|heldout|realproject|polyglot|all> (asks first)."

    data class Request(val tasks: List<String>, val suite: String?, val branch: String?, val model: String?, val repeat: Int) {
        /** What makes this run bigger than the everyday budget; each is confirmed in a dialog before anything is sent. */
        val bigger: List<String> get() = listOfNotNull(
            suite?.let { "the whole \"$it\" task set" + (SUITE_SIZE[it]?.let { n -> " (about $n tasks)" } ?: " (one task per exercise, many)") },
            if (repeat > 1) "$repeat runs of each task" else null)

        /** Task runs this costs: tasks × runs of each × refs; null when a whole set is asked for and its size is not known. */
        fun taskRuns(): Int? {
            val n = if (suite != null) SUITE_SIZE[suite] ?: return null else tasks.size
            return n * repeat * (if (branch != null) 2 else 1)
        }

        /** "4 task runs: 2 tasks × 2 branches (main and x), 1 run each, model groq:…", shown before anything is sent. */
        fun estimate(defaultBranch: String = "main", defaultModel: String = "the workflow's default model"): String {
            val what = if (suite != null) "the $suite set" else "${tasks.size} task" + (if (tasks.size == 1) "" else "s")
            val refs = if (branch != null) " × 2 branches ($defaultBranch and $branch)" else ""
            val total = taskRuns()?.let { "$it task run" + (if (it == 1) "" else "s") } ?: "Many task runs"
            return "$total: $what$refs, $repeat run" + (if (repeat == 1) "" else "s") + " each, with ${model ?: defaultModel}."
        }
    }

    sealed class Parsed {
        data class Ok(val request: Request) : Parsed()
        data class Refused(val message: String) : Parsed()
    }

    /**
     * Reads "/bench rp-loot,rp-bury on pocketide/issue-12 with groq:llama-3.3-70b-versatile x3". [appModel] is the app's current
     * provider and model as "provider:model" (see [modelFor]); it is used when no `with` is given, and null leaves the workflow's own default.
     */
    fun parse(arg: String, appModel: String? = null): Parsed {
        val words = arg.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return Parsed.Refused("Name the tasks to run.\n$USAGE")
        val tasks = ArrayList<String>()
        var branch: String? = null; var model: String? = null; var suite: String? = null; var repeat = 1
        var i = 0
        fun next(): String? = words.getOrNull(++i)?.takeIf { it.lowercase() !in setOf("on", "with", "suite") }
        while (i < words.size) {
            val w = words[i]
            val r = REPEAT.matchEntire(w)
            when {
                w.equals("on", true) -> branch = next() ?: return Parsed.Refused("Say which branch after \"on\".\n$USAGE")
                w.equals("with", true) -> model = next() ?: return Parsed.Refused("Say which model after \"with\", as provider:model.\n$USAGE")
                w.equals("suite", true) -> suite = next()?.lowercase() ?: return Parsed.Refused("Say which task set after \"suite\": " + SUITES.joinToString() + ".")
                r != null -> repeat = r.groupValues[1].toIntOrNull() ?: 99
                else -> tasks.addAll(w.split(',').map { it.trim() }.filter { it.isNotEmpty() })
            }
            i++
        }
        val named = tasks.distinct()
        if (suite != null && named.isNotEmpty()) return Parsed.Refused("Give either task names or a suite, not both (named tasks replace the suite on GitHub).")
        if (suite == null && named.isEmpty()) return Parsed.Refused("Name the tasks to run: a whole task set from the phone needs \"suite <name>\" and a confirm.\n$USAGE")
        if (suite != null && suite !in SUITES) return Parsed.Refused("\"$suite\" is not a task set. The sets are: " + SUITES.joinToString() + ".")
        if (named.size > MAX_TASKS) return Parsed.Refused("That is ${named.size} tasks; /bench runs at most $MAX_TASKS at a time to keep within the benchmark budget. Pick the ones the change is about.")
        named.firstOrNull { !TASK.matches(it) }?.let { return Parsed.Refused("\"$it\" is not a task name (lower-case letters, digits and dashes, as in rp-loot).") }
        if (repeat !in 1..MAX_REPEAT) return Parsed.Refused("Runs of each task: 1, 2 or 3 (x2, x3). The benchmark allows no more.")
        if (branch != null && (!BRANCH.matches(branch!!) || branch!!.contains(".."))) return Parsed.Refused("\"$branch\" is not a branch name.")
        val m = model ?: appModel
        if (m != null) {
            if (m.contains(';') || m.contains(',')) return Parsed.Refused("One model at a time from the phone.")
            val provider = m.substringBefore(':', "").lowercase()
            if (model != null && (provider.isEmpty() || m.substringAfter(':').isBlank())) return Parsed.Refused("Give the model as provider:model, e.g. groq:llama-3.3-70b-versatile.")
            if (model != null && provider !in PROVIDERS) return Parsed.Refused("The benchmark has no key for \"$provider\". It knows: " + PROVIDERS.joinToString() + ".")
        }
        return Parsed.Ok(Request(named, suite, branch, m?.takeIf { it.substringBefore(':', "").lowercase() in PROVIDERS }, repeat))
    }

    /** The app's provider and model in the benchmark's provider:model form, or null when the benchmark has no key for that provider. */
    fun modelFor(providerId: String, model: String): String? =
        if (providerId.lowercase() in PROVIDERS && model.isNotBlank()) providerId.lowercase() + ":" + model.trim() else null

    fun dispatchPath(repo: String) = "/repos/$repo/actions/workflows/$WORKFLOW/dispatches"
    fun runsPath(repo: String) = "/repos/$repo/actions/workflows/$WORKFLOW/runs?event=workflow_dispatch&per_page=20"
    fun refPath(repo: String, branch: String) = "/repos/$repo/git/ref/heads/$branch"
    fun pullPath(repo: String, branch: String) = "/repos/$repo/pulls?state=open&head=" + repo.substringBefore('/') + ":" + branch
    fun commentPath(repo: String, pr: Int) = "/repos/$repo/issues/$pr/comments"
    fun runUrl(repo: String, id: Long) = "https://github.com/$repo/actions/runs/$id"

    /**
     * The JSON for POST …/dispatches. Workflow inputs are always strings. `refs` is only sent for an A/B (default branch first, so it
     * reads as the baseline); without a branch the workflow runs on the ref it was started on, the default branch. `suite` is only sent
     * for a whole set, because named tasks replace the suite. `models` is left out to use the workflow's own default.
     */
    fun dispatchBody(r: Request, defaultBranch: String): String {
        val inputs = LinkedHashMap<String, String>()
        if (r.suite != null) inputs["suite"] = r.suite else inputs["tasks"] = r.tasks.joinToString(",")
        inputs["repeat"] = r.repeat.coerceIn(1, MAX_REPEAT).toString()
        if (r.branch != null && r.branch != defaultBranch) inputs["refs"] = "$defaultBranch,${r.branch}"
        r.model?.let { inputs["models"] = it }
        return MiniJson.write(mapOf("ref" to defaultBranch, "inputs" to inputs))
    }

    /** What a refused dispatch means, in plain words: 403 names the permission the token is missing. */
    fun dispatchError(code: Int, body: String, repo: String): String {
        val said = (try { MiniJson.obj(MiniJson.parse(body)) } catch (e: Exception) { null })?.get("message") as? String
        return when (code) {
            401 -> "GitHub did not accept the token. Make a new one in Git settings."
            403 -> "The GitHub token may not start workflows in $repo. Give it the Actions: Read and write permission (fine-grained token), " +
                "or the workflow scope (classic token), then try again." + (said?.let { " GitHub said: $it" } ?: "")
            404 -> "$repo has no $WORKFLOW workflow on its default branch (or the token cannot see the repository). /bench needs " +
                ".github/workflows/$WORKFLOW from PocketIDE's own source."
            422 -> "GitHub refused to start the benchmark: " + (said ?: "invalid request") + "."
            else -> "GitHub said $code" + (said?.let { ": $it" } ?: "") + "."
        }
    }

    /**
     * The run our dispatch started: a workflow_dispatch run of benchmark.yml that was not in the list before we sent it and was created
     * after [since]. GitHub's dispatch answer carries no run id, so this is how the phone finds it; the oldest new one is ours.
     */
    fun pickRun(runs: List<CloudFollow.Run>, before: Set<Long>, since: String): CloudFollow.Run? =
        runs.filter { it.id !in before && it.created >= since }.minByOrNull { it.created }

    /** One row of the summary's totals table, and of its per-task table (task is blank for totals). */
    data class Row(val model: String, val ref: String, val task: String, val passed: Int, val runs: Int)

    data class Summary(val totals: List<Row>, val tasks: List<Row>, val markdown: String)

    /**
     * Reads summary.md as tools/bench/summarize.py writes it: a per-task table (Model | Ref | Task | Passed | …) and a totals table
     * (Model | Ref | Passed | …). Refs there are artifact labels, with '/' made '_'; [refs] maps them back to the branch names.
     */
    fun parseSummary(md: String, refs: List<String> = emptyList()): Summary {
        val totals = ArrayList<Row>(); val tasks = ArrayList<Row>()
        var head: List<String>? = null
        fun ref(label: String) = refs.firstOrNull { it.replace('/', '_') == label } ?: label
        for (line in md.lines()) {
            val t = line.trim()
            if (!t.startsWith("|")) { head = null; continue }
            val cells = t.trim('|').split('|').map { it.trim() }
            if (cells.firstOrNull() == "Model") { head = cells; continue }
            val h = head ?: continue
            if (cells.all { it.matches(Regex("-+")) } || cells.size != h.size) continue
            val row = h.zip(cells).toMap()
            val pass = Regex("(\\d+)/(\\d+)").matchEntire(row["Passed"].orEmpty()) ?: continue
            val r = Row(row["Model"].orEmpty(), ref(row["Ref"].orEmpty()), row["Task"].orEmpty(), pass.groupValues[1].toInt(), pass.groupValues[2].toInt())
            if ("Task" in h) tasks.add(r) else totals.add(r)
        }
        return Summary(totals, tasks, md)
    }

    /** The chat note: pass counts per ref, then each task where the refs differ (or every task when there is one ref). */
    fun note(s: Summary, runUrl: String): String = buildString {
        if (s.totals.isEmpty()) { append("The benchmark finished but its summary has no results (no task ran). See the run: $runUrl"); return@buildString }
        append("Benchmark results\n")
        for (t in s.totals) append("• ").append(t.ref.ifBlank { "this run" }).append(": ").append(t.passed).append('/').append(t.runs).append(" passed (").append(t.model).append(")\n")
        val byTask = s.tasks.groupBy { it.model + "\u0000" + it.task }
        val refs = s.tasks.map { it.ref }.distinct()
        val shown = byTask.values.filter { rows -> refs.size == 1 || rows.map { it.passed * 1000 / maxOf(1, it.runs) }.distinct().size > 1 }
        if (shown.isNotEmpty()) {
            append(if (refs.size > 1) "Tasks that differ:\n" else "Tasks:\n")
            for (rows in shown) append("• ").append(rows[0].task).append(": ").append(rows.joinToString(", ") { it.ref.ifBlank { "this run" } + " " + it.passed + "/" + it.runs }).append('\n')
        } else if (refs.size > 1) append("Every task passed the same number of times on each branch.\n")
        append(runUrl)
    }.trimEnd()

    /** The pull-request comment: marked as PocketIDE's, with what was run and the whole summary table. */
    fun comment(s: Summary, r: Request, runUrl: String, defaultBranch: String): String = buildString {
        append(MARK).append('\n')
        append("### Benchmark from PocketIDE\n\n")
        append("Started with `/bench` from the PocketIDE app. ")
        append(if (r.suite != null) "Task set `${r.suite}`" else "Tasks `" + r.tasks.joinToString(",") + "`")
        append(", ").append(r.repeat).append(if (r.repeat == 1) " run" else " runs").append(" each")
        if (r.branch != null) append(", `$defaultBranch` compared with `${r.branch}`")
        append(", model `").append(r.model ?: "workflow default").append("`. [The run]($runUrl).\n\n")
        append(s.markdown.trim().removePrefix("# Benchmark summary").trim()).append("\n\n")
        append("_Posted by PocketIDE. It does not merge: the owner reads the diff and this result, then decides._")
    }

    /** Reads summary.md (or the first Markdown file) out of the artifact zip GitHub sends. */
    fun summaryFromZip(zip: ByteArray): String? {
        java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
            var fallback: String? = null
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.substringAfterLast('/')
                val text = String(z.readBytes().let { if (it.size > 2_000_000) it.copyOf(2_000_000) else it }, Charsets.UTF_8)
                if (name == "summary.md") return text
                if (fallback == null && name.endsWith(".md")) fallback = text
            }
            return fallback
        }
    }
}

/**
 * The network half of /bench, blocking, for a background thread: checks the repository and branch, dispatches the workflow,
 * finds the run, follows it like [CloudFollow], then reads the summary and comments on the pull request.
 */
class BenchFollow(
    private val api: GitApi,
    private val repo: String,
    /** The text of a job log, for the failing job's tail (GitLog.fetch on the phone; the redirect must not get the token). */
    private val log: (Long) -> String?,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /** 240 polls of 30 s: two hours, enough for a few tasks on two branches; a longer run is left to the Actions page. */
    private val polls: Int = 240,
    private val waitMs: Long = 30_000L,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    class Started(val run: CloudFollow.Run?, val defaultBranch: String, val error: String? = null)

    class Outcome(val ok: Boolean, val message: String, val url: String?, val summary: BenchRun.Summary? = null,
                  val pullRequest: Int? = null, val commentError: String? = null, val fixPrompt: String? = null)

    private fun text(r: GitResponse) = String(r.body, Charsets.UTF_8)

    /** Sends the dispatch and waits (up to a few polls) for its run to appear. */
    fun start(r: BenchRun.Request, progress: (String) -> Unit = {}): Started {
        progress("Checking $repo")
        val info = api.call("GET", "/repos/$repo", null)
        if (info.code == 404) return Started(null, "", "GitHub could not find $repo, or the token cannot see it. Check the repository in Git settings.")
        if (info.code == 401) return Started(null, "", BenchRun.dispatchError(401, "", repo))
        val default = (try { MiniJson.obj(MiniJson.parse(text(info))) } catch (e: Exception) { null })?.get("default_branch") as? String ?: "main"
        if (r.branch != null && r.branch != default && api.call("GET", BenchRun.refPath(repo, r.branch), null).code == 404)
            return Started(null, default, "There is no branch \"${r.branch}\" on GitHub in $repo. Push it first, or check the name.")
        val before = api.call("GET", BenchRun.runsPath(repo), null).let { if (it.code in 200..299) CloudFollow.parseRuns(text(it)) else emptyList() }.map { it.id }.toSet()
        // two minutes back: the phone's clock and GitHub's may differ a little; runs that existed before are excluded by id anyway
        val since = CloudFollow.isoAgo(now(), 2)
        progress("Starting the benchmark")
        val d = api.call("POST", BenchRun.dispatchPath(repo), BenchRun.dispatchBody(r, default))
        if (d.code !in 200..299) return Started(null, default, BenchRun.dispatchError(d.code, text(d), repo))
        for (i in 0 until 10) {
            val runs = api.call("GET", BenchRun.runsPath(repo), null).let { if (it.code in 200..299) CloudFollow.parseRuns(text(it)) else emptyList() }
            BenchRun.pickRun(runs, before, since)?.let { return Started(it, default) }
            progress("Waiting for GitHub to start the run")
            sleep(minOf(waitMs, 6_000L))
        }
        return Started(null, default)
    }

    /** Follows the run to its end, then reads the summary and, for a branch with an open pull request, comments it there. */
    fun follow(r: BenchRun.Request, started: Started, progress: (String) -> Unit = {}): Outcome {
        val first = started.run ?: return Outcome(false, "The benchmark was sent, but its run did not show up yet. Watch it on the Actions page.", "https://github.com/$repo/actions/workflows/${BenchRun.WORKFLOW}")
        var run: CloudFollow.Run = first
        var done = false
        for (i in 0 until polls) {
            val cur = api.call("GET", "/repos/$repo/actions/runs/${run.id}", null)
            if (cur.code in 200..299) {
                val o = try { MiniJson.obj(MiniJson.parse(text(cur))) } catch (e: Exception) { null }
                if (o != null) run = run.copy(status = (o["status"] as? String).orEmpty(), conclusion = (o["conclusion"] as? String).orEmpty())
            }
            if (run.status == "completed") { done = true; break }
            progress(if (run.status == "queued" || run.status.isEmpty()) "Queued on GitHub" else "Running on GitHub")
            sleep(waitMs)
        }
        val url = run.url.ifEmpty { BenchRun.runUrl(repo, run.id) }
        if (!done) return Outcome(false, "Stopped following the benchmark after a long wait. It may still be running.", url)
        progress("Reading the results")
        // the summary job runs even when a benchmark job failed, so look for it first and add the failure after
        val summary = readSummary(run.id, listOfNotNull(started.defaultBranch, r.branch))
        var failure: String? = null; var fix: String? = null
        if (run.conclusion != "success") {
            val jobs = api.call("GET", CiFailure.jobsPath(repo, run.id), null).let { if (it.code in 200..299) CiFailure.failedJobs(text(it)) else emptyList() }
            val job = jobs.firstOrNull()
            val tail = job?.let { j -> log(j.id)?.let { CiFailure.excerpt(it, 1500) } }.orEmpty()
            failure = "The run ended as \"${run.conclusion.ifEmpty { "unknown" }}\"" + (job?.let { " in \"${it.name}\"" + (if (it.step.isNotEmpty()) " at \"${it.step}\"" else "") } ?: "") + "." +
                (if (tail.isNotBlank()) "\nThe end of its log:\n$tail" else "")
            if (job != null) fix = CiFailure.prompt(run.title, "Benchmark (live models)", job, tail)
        }
        if (summary == null) return Outcome(false, (failure ?: "The benchmark finished") + "\nIt left no summary to read.", url, fixPrompt = fix)
        var pr: Int? = null; var commentError: String? = null
        if (r.branch != null) {
            pr = api.call("GET", BenchRun.pullPath(repo, r.branch), null).let { if (it.code in 200..299) CloudFollow.parsePull(text(it))?.first else null }
            if (pr != null) {
                val c = api.call("POST", BenchRun.commentPath(repo, pr), MiniJson.write(mapOf("body" to BenchRun.comment(summary, r, url, started.defaultBranch))))
                if (c.code !in 200..299) commentError = if (c.code == 403) "The result was not posted on pull request #$pr: the token needs Pull requests (or Issues): Read and write."
                    else "The result was not posted on pull request #$pr (GitHub said ${c.code})."
            }
        }
        val msg = BenchRun.note(summary, url) +
            (if (pr != null && commentError == null) "\nPosted on pull request #$pr." else "") +
            (commentError?.let { "\n$it" } ?: "") + (failure?.let { "\n\n$it" } ?: "")
        return Outcome(failure == null, msg, url, summary, pr, commentError, fix)
    }

    private fun readSummary(runId: Long, refs: List<String>): BenchRun.Summary? {
        val arts = api.call("GET", SelfFix.artifactsPath(repo, runId), null)
        if (arts.code !in 200..299) return null
        val a = SelfFix.parseArtifacts(text(arts)).firstOrNull { it.name == "summary" && !it.expired } ?: return null
        val zip = api.call("GET", SelfFix.downloadPath(repo, a.id), null, raw = true)
        if (zip.code !in 200..299) return null
        val md = try { BenchRun.summaryFromZip(zip.body) } catch (e: Exception) { null } ?: return null
        return BenchRun.parseSummary(md, refs)
    }
}
