package dev.pocketide.app.core

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A real deploy to Cloudflare, only when POCKETIDE_DEPLOY_TOKEN_CLOUDFLARE and an account id (POCKETIDE_CF_ACCOUNT, or the one in
 * POCKETIDE_BENCH_BASE_CLOUDFLARE) are set: a page, a backend route that reads a secret, then the Worker is deleted again.
 */
class CfDeployLiveTest {
    @Test fun deploysAndRemovesATestSite() {
        val token = System.getenv("POCKETIDE_DEPLOY_TOKEN_CLOUDFLARE").orEmpty().trim()
        val account = System.getenv("POCKETIDE_CF_ACCOUNT").orEmpty().ifEmpty { Regex("""/accounts/([0-9a-f]{32})/""").find(System.getenv("POCKETIDE_BENCH_BASE_CLOUDFLARE").orEmpty())?.groupValues?.get(1).orEmpty() }
        assumeTrue("set POCKETIDE_DEPLOY_TOKEN_CLOUDFLARE and an account id", token.isNotEmpty() && account.isNotEmpty())
        val name = "pocketide-deploy-test-" + (System.currentTimeMillis() / 1000 % 100000)
        val files = mapOf(
            "index.html" to "<!doctype html><title>t</title><h1>PocketIDE deploy test</h1><script src=\"app.js\"></script>",
            "app.js" to "console.log('ok')",
            "worker.js" to "import { greet } from './lib/greet.js';\nexport default { async fetch(req, env) { const u = new URL(req.url); if (u.pathname === '/api/hi') return Response.json({ hi: greet(), secretSet: !!env.TEST_SECRET }); return env.ASSETS.fetch(req); } };\n",
            "lib/greet.js" to "export function greet() { return 'from the backend'; }\n")
        val run = CfDeployRun(token, account)
        try {
            val out = run.deploy(name, files.mapValues { it.value.toByteArray() }, { files[it] }, mapOf("TEST_SECRET" to "s3cret-value")) { println("DEPLOY: $it") }
            println("DEPLOY RESULT: ok=${out.ok} url=${out.url} ${out.message}")
            assertTrue(out.message, out.url != null)
            fun get(u: String): Pair<Int, String> = try { val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection; c.connectTimeout = 15000; c.readTimeout = 15000
                val code = c.responseCode; code to ((if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()) } catch (e: Exception) { 0 to (e.message ?: "") }
            var page = 0 to ""; var api = 0 to ""
            for (i in 0 until 12) { page = get(out.url!! + "/"); api = get(out.url + "/api/hi"); if (page.first == 200 && api.first == 200) break; Thread.sleep(5000) }
            println("PAGE ${page.first}: ${page.second.take(200)}\nAPI ${api.first}: ${api.second.take(200)}")
            assertTrue(page.second, page.first == 200 && page.second.contains("PocketIDE deploy test"))
            assertTrue(api.second, api.first == 200 && api.second.contains("from the backend") && api.second.contains("\"secretSet\":true"))
            assertTrue("the secret's value must not be served", !api.second.contains("s3cret-value"))
            val worker = get(out.url + "/worker.js")
            assertTrue("the backend's code must not be served as a file: ${worker.second.take(100)}", !worker.second.contains("env.TEST_SECRET"))
        } finally {
            println("DELETE: " + (run.delete(name) ?: "removed"))
        }
    }
}
