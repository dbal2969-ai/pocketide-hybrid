package dev.pocketide.app.core

/**
 * Sends a CfDeploy deploy (1.5.112): the account's workers.dev name, the asset manifest, the files Cloudflare asks for, the Worker
 * with its secrets, the workers.dev route, then a check that the address answers. Blocking; [api] is changeable for tests.
 */
class CfDeployRun(private val token: String, private val account: String, private val api: String = CfDeploy.API,
                  private val waitMs: Long = 5000, private val urlFor: (String, String) -> String = CfDeploy::url) {
    /** [storage]: where the site keeps error reports ("kv" or "memory"), or null when the deploy had none (1.5.125). */
    class Outcome(val ok: Boolean, val url: String?, val message: String, val storage: String? = null)

    private fun call(method: String, path: String, body: ByteArray? = null, type: String? = null, bearer: String = token): Pair<Int, String> {
        val c = java.net.URL(if (path.startsWith("http")) path else api + path).openConnection() as java.net.HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 20_000; c.readTimeout = 90_000
            c.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", type ?: "application/json"); c.outputStream.use { it.write(body) } }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return code to text
        } finally { c.disconnect() }
    }

    private fun ok(r: Pair<Int, String>, what: String): Map<String, Any?> {
        val (res, err) = CfDeploy.result(r.second)
        if (res == null) throw IllegalStateException("$what: $err" + CfDeploy.hint(err))
        return res
    }

    /** Removes a deployed Worker and its address (the test deploy cleans up with it). */
    fun delete(project: String): String? = try {
        val (res, err) = CfDeploy.result(call("DELETE", "/accounts/$account/workers/scripts/${CfDeploy.scriptName(project)}?force=true").second)
        if (res == null) err else null
    } catch (e: Exception) { e.message }

    /**
     * The KV namespace for this Worker's error reports: found by its title, else made. Null when Cloudflare says no, which with
     * the usual token (no Workers KV Storage: Edit) is the normal case; the Worker then keeps reports in memory (1.5.125).
     */
    private fun errorStore(script: String): String? = try {
        val title = CfErrors.kvTitle(script)
        CfErrors.namespaceId(call("GET", "/accounts/$account/storage/kv/namespaces?per_page=100").second, title)
            ?: CfErrors.createdId(call("POST", "/accounts/$account/storage/kv/namespaces", MiniJson.write(mapOf("title" to title)).toByteArray()).second)
    } catch (e: Exception) { null }

    /** [reportKey]: when set, the published pages send their errors to the Worker, which hands them to whoever has the key (1.5.125). */
    fun deploy(project: String, files: Map<String, ByteArray>, read: (String) -> String?, secrets: Map<String, String>, reportKey: String? = null, progress: (String) -> Unit = {}): Outcome = try {
        if (!Regex("^[0-9a-f]{32}$").matches(account)) throw IllegalArgumentException("The account id is the 32-character code on the dashboard's Workers & Pages page.")
        val script = CfDeploy.scriptName(project)
        val acc = "/accounts/$account"
        progress("Finding your workers.dev address")
        val sub = ok(call("GET", "$acc/workers/subdomain"), "Reading your workers.dev address")["subdomain"] as? String
            ?: throw IllegalStateException("Your account has no workers.dev address yet." + CfDeploy.hint("workers.dev subdomain"))
        val entry = CfDeploy.entry(files.keys)
        val backend = entry?.let { CfDeploy.modules(it, read) }.orEmpty()
        val assetPaths = CfDeploy.assets(files.keys, backend)
        if (assetPaths.isEmpty() && entry == null) throw IllegalStateException("Nothing to publish: the project has no web files.")
        val reports = reportKey?.let { progress("Setting up storage for error reports"); CfDeploy.Reports(it, errorStore(script)) }
        // the reporter script and the tag in each page go into the upload only; the project's files stay as they are
        val assets = assetPaths.associateWith { files.getValue(it) }.let { if (reports != null) CfErrors.withReporter(it) else it }
        progress("Sending the list of ${assets.size} files")
        val session = ok(call("POST", "$acc/workers/scripts/$script/assets-upload-session", CfDeploy.manifest(assets).toByteArray()), "Starting the upload")
        var jwt = session["jwt"] as? String ?: throw IllegalStateException("Cloudflare gave no upload token.")
        val buckets = CfDeploy.buckets(session)
        val byHash = assets.entries.associate { (p, b) -> CfDeploy.hash(b) to (p to b) }
        for ((i, bucket) in buckets.withIndex()) {
            progress("Uploading files (${i + 1} of ${buckets.size})")
            val boundary = "pocketide" + System.nanoTime()
            val res = ok(call("POST", "$acc/workers/assets/upload?base64=true", CfDeploy.multipart(CfDeploy.uploadParts(bucket, byHash), boundary), "multipart/form-data; boundary=$boundary", bearer = jwt), "Uploading files")
            (res["jwt"] as? String)?.let { jwt = it }
        }
        progress(if (entry != null) "Uploading the backend ($entry)" else "Uploading the Worker")
        // with reports and a backend, a generated entry module answers /__pocketide/* and imports the project's own entry unchanged
        val main = if (entry != null && reports != null) CfErrors.ENTRY else entry ?: "index.js"
        val modules = if (entry != null) backend.mapNotNull { p -> files[p]?.let { CfDeploy.Part(p, p, if (p.endsWith(".js") || p.endsWith(".mjs")) "application/javascript+module" else CfDeploy.mime(p), it) } } +
                (if (reports != null) listOf(CfDeploy.Part(main, main, "application/javascript+module", CfErrors.wrapper(entry).toByteArray())) else emptyList())
            else listOf(CfDeploy.Part(main, main, "application/javascript+module", (if (reports != null) CfErrors.staticWorker() else CfDeploy.STATIC_WORKER).toByteArray()))
        val boundary = "pocketide" + System.nanoTime()
        val parts = listOf(CfDeploy.Part("metadata", null, "application/json", CfDeploy.metadata(main, jwt, secrets, files.keys, backend = entry != null, reports = reports).toByteArray())) + modules
        ok(call("PUT", "$acc/workers/scripts/$script", CfDeploy.multipart(parts, boundary), "multipart/form-data; boundary=$boundary"), "Uploading the Worker")
        progress("Turning on the workers.dev address")
        ok(call("POST", "$acc/workers/scripts/$script/subdomain", MiniJson.write(mapOf("enabled" to true, "previews_enabled" to false)).toByteArray()), "Turning on the address")
        val url = urlFor(script, sub)
        progress("Checking that $url answers")
        var last = 0
        for (i in 0 until 8) {
            last = try { val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection; c.connectTimeout = 10_000; c.readTimeout = 15_000; val code = c.responseCode; c.disconnect(); code } catch (e: Exception) { 0 }
            if (last in 200..399) break
            Thread.sleep(waitMs)
        }
        // counts are the project's own files and secrets, not the reporter or the read key
        val own = secrets.keys.count { it != CfErrors.KEY_NAME }
        val storage = reports?.let { if (it.kvId != null) "kv" else "memory" }
        val note = reports?.let { CfErrors.storageNote(it.kvId != null) }.orEmpty()
        if (last in 200..399) Outcome(true, url, "Live at $url (${assetPaths.size} files" + (if (entry != null) " and the backend from $entry" else "") + (if (own > 0) ", $own secret${if (own == 1) "" else "s"}" else "") + ")." + note, storage)
        else Outcome(false, url, "Deployed, but $url answered " + (if (last == 0) "nothing yet" else "HTTP $last") + ". A new workers.dev address can take a minute; open it again soon." +
            (if (last >= 500 && entry != null) " A 5xx from the backend means worker.js failed on that request." else "") + note, storage)
    } catch (e: Exception) { Outcome(false, null, e.message ?: e.javaClass.simpleName) }

    companion object {
        /** GET (or DELETE) the published site's error reports with the project's key (1.5.125). (HTTP code, body); 0 when unreachable. */
        fun siteErrors(site: String, key: String, method: String = "GET"): Pair<Int, String> = try {
            val c = java.net.URL(site.trimEnd('/') + CfErrors.ERRORS_PATH).openConnection() as java.net.HttpURLConnection
            try {
                c.requestMethod = method; c.connectTimeout = 15_000; c.readTimeout = 20_000
                c.setRequestProperty(CfErrors.HEADER, key); c.setRequestProperty("Accept", "application/json")
                val code = c.responseCode
                code to ((if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty())
            } finally { c.disconnect() }
        } catch (e: Exception) { 0 to (e.message ?: "") }
    }
}
