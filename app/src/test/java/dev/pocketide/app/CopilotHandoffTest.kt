package dev.pocketide.app

import dev.pocketide.app.core.CopilotHandoff
import dev.pocketide.app.core.GitApi
import dev.pocketide.app.core.GitHubException
import dev.pocketide.app.core.GitRepo
import dev.pocketide.app.core.GitResponse
import dev.pocketide.app.core.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CopilotHandoffTest {
    /** Accepts Copilot only under [accepts]; other logins are dropped silently, as GitHub does. */
    private class Stub(val accepts: String?, val createCode: Int = 201) : GitApi {
        val calls = ArrayList<Triple<String, String, String?>>()
        override fun call(method: String, path: String, body: String?, raw: Boolean): GitResponse {
            calls.add(Triple(method, path, body))
            fun ok(v: Any?, code: Int = 200) = GitResponse(code, MiniJson.write(v).toByteArray())
            return when {
                method == "POST" && path.endsWith("/issues") ->
                    if (createCode != 201) GitResponse(createCode, """{"message":"Bad credentials"}""".toByteArray())
                    else ok(mapOf("number" to 7, "html_url" to "https://github.com/me/repo/issues/7"), 201)
                method == "POST" && path.endsWith("/issues/7/assignees") -> {
                    val login = MiniJson.list(MiniJson.obj(MiniJson.parse(body!!))!!["assignees"])!!.first() as String
                    ok(mapOf("number" to 7, "assignees" to if (login == accepts) listOf(mapOf("login" to "Copilot")) else emptyList()), 201)
                }
                method == "GET" && path.contains("/pulls") -> ok(listOf(
                    mapOf("number" to 9, "title" to "[WIP] Add dark mode", "html_url" to "u9", "draft" to true, "user" to mapOf("login" to "Copilot")),
                    mapOf("number" to 8, "title" to "Mine", "html_url" to "u8", "draft" to false, "user" to mapOf("login" to "me"))))
                else -> GitResponse(404, "{}".toByteArray())
            }
        }
    }
    private val repo = GitRepo("me", "repo")

    @Test fun createsAndAssigns() {
        val api = Stub(accepts = "copilot-swe-agent[bot]")
        val sent = CopilotHandoff.send(api, repo, "Add a dark mode toggle\nKeep the colours in theme.css.", "feature-x")
        assertTrue(sent.assigned); assertEquals(7, sent.number); assertEquals("https://github.com/me/repo/issues/7", sent.url)
        val issue = MiniJson.obj(MiniJson.parse(api.calls[0].third!!))!!
        assertEquals("Add a dark mode toggle", issue["title"])
        assertTrue((issue["body"] as String).contains("Keep the colours") && (issue["body"] as String).contains("`feature-x`"))
        assertEquals(2, api.calls.size)
    }

    @Test fun triesTheOtherLoginThenReportsHonestly() {
        assertTrue(CopilotHandoff.send(Stub(accepts = "copilot-swe-agent"), repo, "x", "main").assigned)
        val none = CopilotHandoff.send(Stub(accepts = null), repo, "x", "main")
        assertFalse(none.assigned)
        assertTrue(none.message().contains("Issue #7 was created") && none.message().contains("assign Copilot there"))
    }

    @Test fun errorsAreExplained() {
        try { CopilotHandoff.send(Stub(null, createCode = 401), repo, "x", null); fail() } catch (e: GitHubException) { assertTrue(e.message!!.contains("token")) }
    }

    @Test fun onlyCopilotPulls() {
        val pulls = CopilotHandoff.pulls(Stub(null), repo)
        assertEquals(listOf(9), pulls.map { it.number }); assertTrue(pulls[0].draft)
    }

    @Test fun titles() {
        assertEquals("Task from PocketIDE", CopilotHandoff.title("  "))
        assertTrue(CopilotHandoff.title("a".repeat(200)).length <= 80)
        assertFalse("main needs no branch line", CopilotHandoff.body("x", "main").contains("branch"))
    }
}
