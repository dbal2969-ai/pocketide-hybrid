package dev.pocketide.app.core

/**
 * /deploy: publishes a static web project from the person's own GitHub repository with GitHub Pages, waits for the build,
 * then checks that the live page answers (Emergent will not put an app live when its health check fails; Copilot leaves
 * deploying to the person's own CI). Uses only the person's token and repository. Runs on a background thread.
 */
class PagesDeploy(
    private val api: GitApi,
    private val repo: String,
    /** GET a public URL: status code and the start of the body. */
    private val fetch: (String) -> Pair<Int, String>,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {
    data class Outcome(val ok: Boolean, val url: String?, val message: String, val buildError: String? = null)

    companion object {
        /** Where the site's index.html is: "/" or "/docs", the two folders Pages can publish from a branch. Null when neither has one. */
        fun sourcePath(files: Collection<String>): String? = when {
            "index.html" in files -> "/"
            "docs/index.html" in files -> "/docs"
            else -> null
        }

        /** The page answered with a real page. */
        fun healthy(code: Int, body: String): Boolean = code in 200..299 && Regex("(?i)<(html|body|head|title|script|div)").containsMatchIn(body)

        fun fixPrompt(url: String?, why: String): String =
            "Publishing this project with GitHub Pages failed" + (if (url != null) " (site: $url)" else "") + ":\n" + why.take(1500) +
                "\nFind the cause in this project and fix it. Pages serves static files only: index.html must be at the top of the project or in docs/, " +
                "links must be relative, and server code (Node, Python) does not run there."

        private const val BUILD_POLLS = 36
        private const val BUILD_WAIT_MS = 5_000L
        private const val HEALTH_TRIES = 12
        private const val HEALTH_WAIT_MS = 10_000L
    }

    private fun obj(r: GitResponse): Map<String, Any?>? =
        try { MiniJson.obj(MiniJson.parse(String(r.body, Charsets.UTF_8))) } catch (e: Exception) { null }

    private fun apiMessage(r: GitResponse): String = (obj(r)?.get("message") as? String)?.take(200) ?: "HTTP ${r.code}"

    fun run(files: Collection<String>, progress: (String) -> Unit): Outcome {
        val path = sourcePath(files)
            ?: return Outcome(false, null, "There is no index.html at the top of the project or in docs/. GitHub Pages publishes static web pages, so add an index.html first.")
        progress("Checking the repository")
        val info = api.call("GET", "/repos/$repo", null)
        if (info.code == 404) return Outcome(false, null, "GitHub could not find $repo with this token. Check the repository name and that the token can read it.")
        if (info.code !in 200..299) return Outcome(false, null, "GitHub refused to show $repo: ${apiMessage(info)}.")
        val branch = obj(info)?.get("default_branch") as? String ?: "main"
        val source = MiniJson.write(linkedMapOf("source" to linkedMapOf("branch" to branch, "path" to path)))

        progress("Turning on GitHub Pages")
        val pages = api.call("GET", "/repos/$repo/pages", null)
        val site: Map<String, Any?>? = when {
            pages.code == 404 -> {
                val made = api.call("POST", "/repos/$repo/pages", source)
                if (made.code !in 200..299) return Outcome(false, null, "GitHub would not turn on Pages: ${apiMessage(made)}. " +
                    "On a free account Pages needs a public repository, and the token needs the Pages permission (or full repo access).")
                obj(made)
            }
            pages.code in 200..299 -> {
                val cur = obj(pages)
                val curSrc = MiniJson.obj(cur?.get("source"))
                if (curSrc?.get("branch") != branch || curSrc["path"] != path) api.call("PUT", "/repos/$repo/pages", source)
                // ask for a fresh build of the newest commit; harmless if one is already running
                api.call("POST", "/repos/$repo/pages/builds", null)
                cur
            }
            else -> return Outcome(false, null, "GitHub would not show the Pages settings: ${apiMessage(pages)}. The token may need the Pages permission.")
        }
        val url = (site?.get("html_url") as? String)?.ifBlank { null }
            ?: repo.split('/').let { (o, n) -> if (n.equals("$o.github.io", true)) "https://$n/" else "https://$o.github.io/$n/" }

        progress("Waiting for GitHub to build the site")
        var built = false
        for (i in 0 until BUILD_POLLS) {
            val b = api.call("GET", "/repos/$repo/pages/builds/latest", null)
            val o = if (b.code in 200..299) obj(b) else null
            when (o?.get("status")) {
                "built" -> { built = true }
                "errored" -> {
                    val err = (MiniJson.obj(o["error"])?.get("message") as? String).orEmpty().ifBlank { "the build failed without a message" }
                    return Outcome(false, url, "GitHub could not build the site: $err", err)
                }
            }
            if (built) break
            sleep(BUILD_WAIT_MS)
        }
        if (!built) progress("The build is slow; checking the site anyway")

        progress("Checking that the site answers")
        var last = ""
        for (i in 0 until HEALTH_TRIES) {
            val (code, body) = try { fetch(url) } catch (e: Exception) { -1 to (e.message ?: "") }
            if (healthy(code, body)) return Outcome(true, url, "Live at $url")
            last = if (code < 0) "could not connect" else "answered $code"
            sleep(HEALTH_WAIT_MS)
        }
        return Outcome(false, url, "The site was set up at $url but the page $last" + (if (built) "" else " and the build had not finished") +
            ". New sites can take a few minutes; try /deploy again shortly.", if (built) "The page $last after a successful build." else null)
    }
}
