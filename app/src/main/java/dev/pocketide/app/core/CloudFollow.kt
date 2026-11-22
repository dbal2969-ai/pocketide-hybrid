package dev.pocketide.app.core

/**
 * Follows a /cloud job after its issue is created, so the result comes back to the phone (GitHub Mobile shows agent
 * sessions and their pull requests; Cursor's phone app follows its cloud agents). It watches the PocketIDE agent workflow
 * runs for the issue and reports the pull request, the agent's comment when there is no pull request, or the failure with
 * the end of its log, ready to hand to the assistant. Runs on a background thread.
 */
class CloudFollow(
    private val api: GitApi,
    private val repo: String,
    private val issue: Int,
    private val issueTitle: String,
    /** ISO time (UTC) just before the issue was created; older runs are ignored. */
    private val since: String,
    /** The text of a job log (GitHub answers with a redirect to storage, which must not get the token). */
    private val log: (Long) -> String?,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val polls: Int = 120,
    private val waitMs: Long = 30_000L,
    /** When only one issue was sent, a run with another name started after [since] is taken as ours (older workflow files have no run-name). */
    private val anyTitle: Boolean = false
) {
    enum class Kind { PULL_REQUEST, NO_CHANGES, FAILED, TIMED_OUT }

    data class Outcome(val kind: Kind, val message: String, val url: String? = null, val fixPrompt: String? = null)

    data class Run(val id: Long, val title: String, val status: String, val conclusion: String, val created: String, val url: String)

    companion object {
        fun runsPath(repo: String) = "/repos/$repo/actions/workflows/pocketide-agent.yml/runs?event=issues&per_page=20"
        fun pullPath(repo: String, issue: Int) = "/repos/$repo/pulls?state=all&head=" + repo.substringBefore('/') + ":pocketide/issue-$issue"
        fun commentsPath(repo: String, issue: Int) = "/repos/$repo/issues/$issue/comments?per_page=50"

        fun parseRuns(json: String): List<Run> {
            val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: Exception) { null } ?: return emptyList()
            return (o["workflow_runs"] as? List<*>).orEmpty().mapNotNull { x ->
                val m = x as? Map<*, *> ?: return@mapNotNull null
                Run((m["id"] as? Number)?.toLong() ?: return@mapNotNull null, (m["display_title"] as? String).orEmpty(), (m["status"] as? String).orEmpty(),
                    (m["conclusion"] as? String).orEmpty(), (m["created_at"] as? String).orEmpty(), (m["html_url"] as? String).orEmpty())
            }
        }

        /** The runs for this issue: same title, started after [since]. Cancelled runs are left out (the opened and labeled events both start one, and one cancels the other). */
        fun mine(runs: List<Run>, title: String, since: String): List<Run> =
            runs.filter { it.title == title && it.created >= since && it.conclusion != "cancelled" && it.conclusion != "skipped" }

        fun parsePull(json: String): Pair<Int, String>? {
            val l = try { MiniJson.parse(json) as? List<*> } catch (e: Exception) { null } ?: return null
            val m = l.firstOrNull() as? Map<*, *> ?: return null
            return ((m["number"] as? Number)?.toInt() ?: return null) to (m["html_url"] as? String).orEmpty()
        }

        fun lastComment(json: String): String? {
            val l = try { MiniJson.parse(json) as? List<*> } catch (e: Exception) { null } ?: return null
            return (l.lastOrNull() as? Map<*, *>)?.get("body") as? String
        }

        /** ISO time [minutesBack] minutes before now, in GitHub's format. */
        fun isoAgo(nowMs: Long, minutesBack: Int = 10): String {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return f.format(java.util.Date(nowMs - minutesBack * 60_000L))
        }
    }

    private fun text(r: GitResponse) = String(r.body, Charsets.UTF_8)

    fun follow(progress: (String) -> Unit): Outcome {
        var started = false
        for (i in 0 until polls) {
            val r = api.call("GET", runsPath(repo), null)
            val all = if (r.code in 200..299) parseRuns(text(r)) else emptyList()
            val runs = mine(all, issueTitle, since).ifEmpty { if (anyTitle) mine(all.map { it.copy(title = issueTitle) }, issueTitle, since) else emptyList() }
            when {
                runs.isEmpty() -> progress(if (started) "Waiting for GitHub" else "Waiting for the agent to start")
                runs.any { it.status != "completed" } -> { started = true; progress("The agent is working on GitHub") }
                else -> return finished(runs)
            }
            sleep(waitMs)
        }
        return Outcome(Kind.TIMED_OUT, "Stopped following issue #$issue after a long wait. It may still be running; check Actions on GitHub.", "https://github.com/$repo/actions")
    }

    private fun finished(runs: List<Run>): Outcome {
        val failed = runs.firstOrNull { it.conclusion == "failure" || it.conclusion == "timed_out" }
        if (failed == null) {
            val pr = api.call("GET", pullPath(repo, issue), null).let { if (it.code in 200..299) parsePull(text(it)) else null }
            if (pr != null) return Outcome(Kind.PULL_REQUEST, "The agent finished issue #$issue and opened pull request #${pr.first}. Read the changes before merging.", pr.second)
            val c = api.call("GET", commentsPath(repo, issue), null).let { if (it.code in 200..299) lastComment(text(it)) else null }
            return Outcome(Kind.NO_CHANGES, "The agent finished issue #$issue without a pull request" + (c?.let { ":\n" + it.take(600) } ?: "."),
                "https://github.com/$repo/issues/$issue")
        }
        val jobs = api.call("GET", CiFailure.jobsPath(repo, failed.id), null).let { if (it.code in 200..299) CiFailure.failedJobs(text(it)) else emptyList() }
        val job = jobs.firstOrNull()
        val excerpt = job?.let { j -> log(j.id)?.let { CiFailure.excerpt(it) } }.orEmpty()
        return Outcome(Kind.FAILED, "The agent's run for issue #$issue failed" + (job?.let { " in \"${it.name}\"" + (if (it.step.isNotEmpty()) " at \"${it.step}\"" else "") } ?: "") + ".",
            failed.url.ifEmpty { null }, CiFailure.prompt(issueTitle, "PocketIDE agent", job, excerpt))
    }
}

/** A job log from the GitHub API: the redirect to storage is followed by hand so the token is not sent there. */
object GitLog {
    fun fetch(token: String, path: String): String? {
        val first = java.net.URI("https://api.github.com$path").toURL().openConnection() as java.net.HttpURLConnection
        try {
            first.instanceFollowRedirects = false
            first.connectTimeout = 20_000; first.readTimeout = 60_000
            first.setRequestProperty("Accept", "application/vnd.github+json")
            first.setRequestProperty("User-Agent", "PocketIDE")
            if (token.isNotEmpty()) first.setRequestProperty("Authorization", "Bearer $token")
            val code = first.responseCode
            if (code in 200..299) return first.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            val where = first.getHeaderField("Location") ?: return null
            if (!where.startsWith("https://")) return null
            val second = java.net.URI(where).toURL().openConnection() as java.net.HttpURLConnection
            try {
                second.connectTimeout = 20_000; second.readTimeout = 60_000
                if (second.responseCode !in 200..299) return null
                val bytes = second.inputStream.use { it.readBytes() }
                return String(if (bytes.size > 3_000_000) bytes.copyOfRange(bytes.size - 3_000_000, bytes.size) else bytes, Charsets.UTF_8)
            } finally { second.disconnect() }
        } finally { first.disconnect() }
    }
}
