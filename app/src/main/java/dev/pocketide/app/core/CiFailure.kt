package dev.pocketide.app.core

/**
 * "Why did this GitHub build fail, and fix it": finds the failed job and step of a workflow run, cuts its log down to the
 * part that matters, and words the request for the assistant (OpenAI's Codex docs and Copilot both offer this hand-off).
 * Pure logic; the Builds tab does the network calls.
 */
object CiFailure {
    data class FailedJob(val id: Long, val name: String, val step: String)

    fun jobsPath(repo: String, runId: Long) = "/repos/$repo/actions/runs/$runId/jobs?per_page=30"
    fun logPath(repo: String, jobId: Long) = "/repos/$repo/actions/jobs/$jobId/logs"

    /** Failed jobs of a run, each with its first failed step, from GET .../runs/{id}/jobs. */
    fun failedJobs(json: String): List<FailedJob> {
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: Exception) { null } ?: return emptyList()
        return (o["jobs"] as? List<*>).orEmpty().mapNotNull { j ->
            val m = j as? Map<*, *> ?: return@mapNotNull null
            if (m["conclusion"] != "failure") return@mapNotNull null
            val step = (m["steps"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.firstOrNull { it["conclusion"] == "failure" }?.get("name") as? String
            FailedJob((m["id"] as? Number)?.toLong() ?: return@mapNotNull null, (m["name"] as? String).orEmpty(), step.orEmpty())
        }
    }

    private val STAMP = Regex("""^\d{4}-\d\d-\d\dT[\d:.]+Z ?""")
    private val NOISE = Regex("""^(##\[(group|endgroup)]|Downloading|Download|Progress|\s*$)""")

    /**
     * The useful end of a job log: timestamps removed, noise dropped, the lines around the first error kept
     * (errors usually come with the 40 lines before them), plus the last lines of the log. At most [max] characters.
     */
    fun excerpt(log: String, max: Int = 6000): String {
        val lines = log.removePrefix("\uFEFF").split("\n").map { STAMP.replace(it.trimEnd('\r'), "") }
        // GitHub marks the failure with ##[error]; plain words like "error" also appear in harmless lines, so they are a fallback
        val generic = Regex("""(?i)\b(error|failed|exception)\b[:\s]""")
        val firstErr = lines.indexOfFirst { it.startsWith("##[error]") }.takeIf { it >= 0 }
            ?: lines.indexOfLast { generic.containsMatchIn(it) && !it.startsWith("##[group]") }
        val keep = ArrayList<String>()
        if (firstErr >= 0) keep.addAll(lines.subList(maxOf(0, firstErr - 40), minOf(lines.size, firstErr + 20)))
        val tail = lines.takeLast(30)
        if (firstErr < 0 || firstErr + 20 < lines.size - 30) { if (keep.isNotEmpty()) keep.add("[…]"); keep.addAll(tail) }
        val text = keep.filterNot { NOISE.containsMatchIn(it) && it != "[…]" }.joinToString("\n").replace("##[error]", "ERROR: ")
        return if (text.length <= max) text else "[…]\n" + text.takeLast(max)
    }

    fun prompt(runTitle: String, workflow: String, job: FailedJob?, excerpt: String): String =
        "The GitHub build \"" + runTitle.ifBlank { workflow } + "\" (workflow $workflow) failed" +
            (if (job != null) " in job \"${job.name}\"" + (if (job.step.isNotEmpty()) " at step \"${job.step}\"" else "") else "") + ".\n" +
            (if (excerpt.isNotBlank()) "The end of its log:\n```\n$excerpt\n```\n" else "The log could not be loaded.\n") +
            "Find the cause in this project and fix it. The build runs on GitHub, not on this phone, so say what will happen on the next push and how to check it."
}
