package dev.pocketide.app

import dev.pocketide.app.core.CloudFollow
import dev.pocketide.app.core.GitApi
import dev.pocketide.app.core.GitResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudFollowTest {
    private fun run(id: Int, status: String, conclusion: String, title: String = "Add a score", created: String = "2026-10-22T21:00:00Z") =
        """{"id":$id,"display_title":"$title","status":"$status","conclusion":${if (conclusion.isEmpty()) "null" else "\"$conclusion\""},"created_at":"$created","html_url":"https://github.com/me/app/actions/runs/$id"}"""

    private class Fake(val runs: MutableList<String>, val extra: Map<String, String>) : GitApi {
        override fun call(method: String, path: String, body: String?, raw: Boolean): GitResponse {
            val b = when {
                path.contains("/workflows/pocketide-agent.yml/runs") -> """{"workflow_runs":[${if (runs.size > 1) runs.removeAt(0) else runs[0]}]}"""
                else -> extra.entries.firstOrNull { path.contains(it.key) }?.value ?: "[]"
            }
            return GitResponse(200, b.toByteArray())
        }
    }

    @Test fun reportsThePullRequest() {
        val api = Fake(mutableListOf(run(1, "in_progress", "") + "," + run(2, "completed", "cancelled"), run(1, "completed", "success")),
            mapOf("/pulls" to """[{"number":7,"html_url":"https://github.com/me/app/pull/7"}]"""))
        val out = CloudFollow(api, "me/app", 3, "Add a score", "2026-10-22T20:58:00Z", { null }, {}, polls = 5, waitMs = 0).follow {}
        assertEquals(CloudFollow.Kind.PULL_REQUEST, out.kind)
        assertEquals("https://github.com/me/app/pull/7", out.url)
    }

    @Test fun failureComesWithAFixPrompt() {
        val api = Fake(mutableListOf(run(1, "completed", "failure")),
            mapOf("/jobs" to """{"jobs":[{"id":9,"name":"agent","conclusion":"failure","steps":[{"name":"Run agent","conclusion":"failure"}]}]}"""))
        val out = CloudFollow(api, "me/app", 3, "Add a score", "2026-10-22T20:58:00Z", { "##[error]Model key missing" }, {}, polls = 3, waitMs = 0).follow {}
        assertEquals(CloudFollow.Kind.FAILED, out.kind)
        assertTrue(out.fixPrompt!!.contains("Model key missing"))
    }

    @Test fun ignoresOtherIssuesAndOldRuns() {
        val runs = CloudFollow.parseRuns("""{"workflow_runs":[${run(1, "completed", "success", "Other")},${run(2, "completed", "success", created = "2026-10-22T10:00:00Z")}]}""")
        assertTrue(CloudFollow.mine(runs, "Add a score", "2026-10-22T20:58:00Z").isEmpty())
        val out = CloudFollow(Fake(mutableListOf(run(1, "completed", "success", "Other")), emptyMap()), "me/app", 3, "Add a score", "2026-10-22T20:58:00Z", { null }, {}, polls = 2, waitMs = 0).follow {}
        assertEquals(CloudFollow.Kind.TIMED_OUT, out.kind)
        assertEquals(20, CloudFollow.isoAgo(0L).length)
    }
}
