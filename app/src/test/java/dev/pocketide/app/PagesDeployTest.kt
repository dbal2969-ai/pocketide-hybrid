package dev.pocketide.app

import dev.pocketide.app.core.GitApi
import dev.pocketide.app.core.GitResponse
import dev.pocketide.app.core.PagesDeploy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PagesDeployTest {
    private class FakeApi(val answers: Map<String, Pair<Int, String>>, val builds: MutableList<String> = mutableListOf("building", "built")) : GitApi {
        val calls = ArrayList<String>()
        override fun call(method: String, path: String, body: String?, raw: Boolean): GitResponse {
            calls.add("$method $path")
            if (path.endsWith("/pages/builds/latest")) {
                val s = if (builds.size > 1) builds.removeAt(0) else builds[0]
                return GitResponse(200, (if (s == "errored") """{"status":"errored","error":{"message":"Page build failed: missing file"}}""" else """{"status":"$s"}""").toByteArray())
            }
            val a = answers["$method $path"] ?: (200 to "{}")
            return GitResponse(a.first, a.second.toByteArray())
        }
    }

    private val repoInfo = "GET /repos/me/site" to (200 to """{"default_branch":"main"}""")

    @Test fun enablesPagesWaitsForBuildAndChecksHealth() {
        val api = FakeApi(mapOf(repoInfo, "GET /repos/me/site/pages" to (404 to "{}"),
            "POST /repos/me/site/pages" to (201 to """{"html_url":"https://me.github.io/site/"}""")))
        val out = PagesDeploy(api, "me/site", { _ -> 200 to "<html><body>hi</body></html>" }, {}).run(listOf("index.html", "app.js")) {}
        assertTrue(out.message, out.ok)
        assertEquals("https://me.github.io/site/", out.url)
        assertTrue("POST /repos/me/site/pages" in api.calls)
    }

    @Test fun reportsBuildErrorsAndBadHealth() {
        val errored = FakeApi(mapOf(repoInfo, "GET /repos/me/site/pages" to (200 to """{"html_url":"https://me.github.io/site/","source":{"branch":"main","path":"/"}}""")), mutableListOf("errored"))
        val out = PagesDeploy(errored, "me/site", { _ -> 200 to "<html>" }, {}).run(listOf("index.html")) {}
        assertFalse(out.ok)
        assertEquals("Page build failed: missing file", out.buildError)

        val down = FakeApi(mapOf(repoInfo, "GET /repos/me/site/pages" to (200 to """{"html_url":"https://me.github.io/site/","source":{"branch":"main","path":"/"}}""")))
        val out2 = PagesDeploy(down, "me/site", { _ -> 404 to "Not found" }, {}).run(listOf("index.html")) {}
        assertFalse(out2.ok)
        assertTrue(out2.message.contains("answered 404"))
    }

    @Test fun sourceAndHealthRules() {
        assertEquals("/", PagesDeploy.sourcePath(listOf("index.html")))
        assertEquals("/docs", PagesDeploy.sourcePath(listOf("docs/index.html", "server.js")))
        assertNull(PagesDeploy.sourcePath(listOf("server.js")))
        assertFalse(PagesDeploy.healthy(200, "plain text"))
        assertFalse(PagesDeploy(FakeApi(emptyMap()), "me/site", { _ -> 200 to "" }, {}).run(listOf("server.js")) {}.ok)
    }
}
