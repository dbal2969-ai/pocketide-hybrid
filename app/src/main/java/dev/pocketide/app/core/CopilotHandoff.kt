package dev.pocketide.app.core

/**
 * Hands a task to GitHub Copilot's coding agent from the phone: the task becomes an issue in the linked repository and is
 * assigned to Copilot, which works on it in GitHub Actions and opens a draft pull request. The issue is always created; the
 * assignment needs a Copilot plan with the coding agent turned on for the repository, and GitHub drops an assignee it does not
 * accept without an error, so the reply's assignees are checked. When it did not take, the person assigns Copilot on GitHub.
 */
object CopilotHandoff {
    /** Logins tried in order: the bot login community reports confirm on REST, then the plain one. */
    val LOGINS = listOf("copilot-swe-agent[bot]", "copilot-swe-agent")

    class Sent(val number: Int, val url: String, val assigned: Boolean) {
        fun message(): String =
            if (assigned) "Issue #$number was created and assigned to Copilot. It works on it on GitHub and opens a draft pull request; see \"Copilot's pull requests\"."
            else "Issue #$number was created, but GitHub did not accept Copilot as its assignee. Open the issue and assign Copilot there " +
                "(this needs a Copilot plan with the coding agent turned on for this repository, and a token with the repo scope)."
    }

    class Pull(val number: Int, val title: String, val url: String, val draft: Boolean)

    /** The issue title: the first line of the task, shortened. */
    fun title(task: String): String = task.trim().lineSequence().firstOrNull().orEmpty().trim().let { if (it.length > 80) it.take(77).trimEnd() + "…" else it }.ifEmpty { "Task from PocketIDE" }

    /** The issue text: the task, the branch to start from, and a line saying where it came from. */
    fun body(task: String, branch: String?): String =
        task.trim() + "\n\n" + (if (!branch.isNullOrBlank() && branch != "main" && branch != "master") "Start from the `$branch` branch.\n\n" else "") +
            "_Sent from PocketIDE. Follow the repository's AGENTS.md and .github/copilot-instructions.md if they exist._"

    private fun check(r: GitResponse, what: String): GitResponse {
        if (r.code in 200..299) return r
        val msg = (try { MiniJson.obj(MiniJson.parse(r.text))?.get("message") as? String } catch (e: Exception) { null }) ?: "HTTP ${r.code}"
        throw GitHubException(when (r.code) {
            401 -> "GitHub did not accept the token (401). Check it in Git → Setup."
            403 -> "The token may not $what (403: $msg). A classic token needs the repo scope."
            404 -> "GitHub could not find the repository, or the token cannot see it (404)."
            410 -> "Issues are turned off for this repository (410)."
            else -> "Could not $what: $msg"
        }, r.code)
    }

    /** Creates the issue and tries to assign Copilot. */
    fun send(api: GitApi, repo: GitRepo, task: String, branch: String?): Sent {
        val path = "/repos/${repo.owner}/${repo.name}/issues"
        val created = MiniJson.obj(MiniJson.parse(check(api.call("POST", path, MiniJson.write(mapOf("title" to title(task), "body" to body(task, branch)))), "create an issue").text))
            ?: throw GitHubException("GitHub sent an unexpected reply when creating the issue.")
        val number = (created["number"] as? Number)?.toInt() ?: throw GitHubException("GitHub did not say which issue it created.")
        val url = created["html_url"] as? String ?: "https://github.com/${repo.identity}/issues/$number"
        for (login in LOGINS) {
            val r = api.call("POST", "$path/$number/assignees", MiniJson.write(mapOf("assignees" to listOf(login))))
            if (r.code in 200..299 && assignedToCopilot(r.text)) return Sent(number, url, true)
        }
        return Sent(number, url, false)
    }

    /** Whether an issue reply lists Copilot among its assignees. */
    fun assignedToCopilot(issueJson: String): Boolean = try {
        val o = MiniJson.obj(MiniJson.parse(issueJson))
        MiniJson.list(o?.get("assignees")).orEmpty().any { (MiniJson.obj(it)?.get("login") as? String)?.lowercase()?.contains("copilot") == true }
    } catch (e: Exception) { false }

    /** Open pull requests opened by Copilot, newest first. */
    fun pulls(api: GitApi, repo: GitRepo): List<Pull> {
        val text = check(api.call("GET", "/repos/${repo.owner}/${repo.name}/pulls?state=open&per_page=50"), "list pull requests").text
        return MiniJson.list(MiniJson.parse(text)).orEmpty().mapNotNull { MiniJson.obj(it) }
            .filter { (MiniJson.obj(it["user"])?.get("login") as? String)?.lowercase()?.contains("copilot") == true }
            .mapNotNull { o ->
                val n = (o["number"] as? Number)?.toInt() ?: return@mapNotNull null
                Pull(n, o["title"] as? String ?: "", o["html_url"] as? String ?: "https://github.com/${repo.identity}/pull/$n", o["draft"] == true)
            }
    }
}
