package dev.pocketide.app.core

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** One GitHub commit, for the History tab. */
class CommitRow(val sha: String, val message: String, val author: String, val time: Long) {
    val short: String get() = sha.take(7)
}

/** How a build run is shown in the Builds tab. */
enum class RunState(val mark: String, val word: String) { PASSED("✓", "Passed"), FAILED("✗", "Failed"), RUNNING("●", "Running"), CANCELLED("–", "Cancelled"), OTHER("•", "Finished") }

/** The pure parts of the repository page's History and Builds tabs: reading GitHub's answers and wording them. */
object RepoActivity {
    fun commitsPath(repo: String, branch: String, perPage: Int = 20): String = "/repos/$repo/commits?sha=$branch&per_page=$perPage"

    private fun iso(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            f.parse(s)?.time ?: 0L
        } catch (e: Exception) { 0L }
    }

    /** Parses GitHub's time stamp ("2026-10-21T16:20:00Z") to milliseconds; 0 when it cannot be read. */
    fun parseTime(s: String?): Long = iso(s)

    /** The commits in the answer of GET /repos/{repo}/commits, newest first. */
    fun parseCommits(json: String): List<CommitRow> {
        val list = try { MiniJson.list(MiniJson.parse(json)) } catch (e: MiniJson.JsonError) { null } ?: return emptyList()
        return list.mapNotNull { x ->
            val o = MiniJson.obj(x) ?: return@mapNotNull null
            val sha = o["sha"] as? String ?: return@mapNotNull null
            val c = MiniJson.obj(o["commit"]) ?: return@mapNotNull null
            val msg = (c["message"] as? String ?: "").lineSequence().firstOrNull().orEmpty().trim()
            val author = MiniJson.obj(c["author"])
            CommitRow(sha, msg.ifEmpty { "(no message)" }, author?.get("name") as? String ?: "", iso(author?.get("date") as? String))
        }
    }

    fun state(r: SelfFix.Run): RunState = when {
        r.status != "completed" -> RunState.RUNNING
        r.conclusion == "success" -> RunState.PASSED
        r.conclusion == "failure" || r.conclusion == "timed_out" -> RunState.FAILED
        r.conclusion == "cancelled" || r.conclusion == "skipped" -> RunState.CANCELLED
        else -> RunState.OTHER
    }

    /** The GitHub page of a run or commit, to open in the browser. */
    fun runUrl(repo: String, id: Long) = "https://github.com/$repo/actions/runs/$id"
    fun commitUrl(repo: String, sha: String) = "https://github.com/$repo/commit/$sha"

    /** "3 files" / "1 file" for a snapshot row. */
    fun files(n: Int) = "$n file${if (n == 1) "" else "s"}"

    /** Why the GitHub tabs are empty, in words a person can act on. Null when the repository is linked. */
    fun notLinked(repo: String, token: String): String? = when {
        repo.isBlank() -> "This project is not linked to GitHub yet. Open Git and enter the repository to see its history and builds here."
        token.isBlank() -> "Add a GitHub token in Git to see this repository's history and builds here. Public repositories may work without one but are limited."
        else -> null
    }

    /** What to say when GitHub refused or failed. */
    fun apiError(code: Int): String = when (code) {
        401, 403 -> "GitHub refused the request ($code). Check the token has access to this repository (Contents and Actions: Read)."
        404 -> "GitHub could not find that repository or branch. Check the name in Git."
        else -> "GitHub answered $code. Try again in a moment."
    }
}
