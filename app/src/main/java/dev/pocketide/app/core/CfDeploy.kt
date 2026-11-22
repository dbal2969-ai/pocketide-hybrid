package dev.pocketide.app.core

/**
 * Full-stack deploy to Cloudflare Workers (1.5.112): the project's files as static assets, plus a backend when the project has one
 * (worker.js, _worker.js or src/worker.js with `export default { fetch }`), with the project's secrets as the Worker's secrets,
 * published at <name>.<account>.workers.dev (Replit, Lovable and Bolt deploy full apps; /deploy before this did static pages with
 * GitHub Pages only). Follows Cloudflare's direct-upload API: register a manifest, upload the files it asks for, upload the Worker.
 * Pure logic; CfDeployRun sends the requests.
 */
object CfDeploy {
    const val API = "https://api.cloudflare.com/client/v4"
    const val COMPAT = "2025-09-01"
    private val ENTRIES = listOf("_worker.js", "worker.js", "src/worker.js", "worker/index.js")
    private val SKIP_DIRS = setOf(".git", "node_modules", ".pocketide", ".github", ".vscode", "__pycache__", ".bench")
    private val SKIP_FILES = setOf("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "wrangler.toml", "wrangler.json", ".gitignore", ".env")

    fun scriptName(project: String): String =
        project.lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(54).ifEmpty { "app" }.let { if (it.first().isDigit()) "app-$it" else it }

    /** The backend's entry file, when the project has one. */
    fun entry(paths: Collection<String>): String? = ENTRIES.firstOrNull { it in paths }

    /** The entry and every module it imports by a relative path, recursively. */
    fun modules(entry: String, read: (String) -> String?): List<String> {
        val out = LinkedHashSet<String>(); val todo = ArrayDeque(listOf(entry))
        while (todo.isNotEmpty() && out.size < 50) {
            val p = todo.removeFirst(); if (!out.add(p)) continue
            val text = read(p) ?: continue
            for (m in Regex("""(?:import|export)\s[^'"]*?from\s*['"](\.{1,2}/[^'"]+)['"]|import\s*\(\s*['"](\.{1,2}/[^'"]+)['"]\s*\)|import\s*['"](\.{1,2}/[^'"]+)['"]""").findAll(text)) {
                val rel = m.groupValues.drop(1).first { it.isNotEmpty() }
                resolve(p, rel)?.let { todo.add(it) }
            }
        }
        return out.toList()
    }

    fun resolve(from: String, rel: String): String? {
        val parts = ArrayList(from.split('/').dropLast(1))
        for (s in rel.split('/')) when (s) { ".", "" -> {}; ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1); else -> parts.add(s) }
        val p = parts.joinToString("/")
        return if (Regex("""\.(m?js|json|wasm|txt|html)$""").containsMatchIn(p)) p else "$p.js"
    }

    /** Files served as static assets: not the backend's modules, not tooling, hidden folders or the notes at the project's top. */
    fun assets(paths: Collection<String>, backend: Collection<String>): List<String> = paths.filter { p ->
        val segs = p.split('/')
        p !in backend && segs.none { it in SKIP_DIRS || (it.startsWith(".") && it != ".well-known") } && segs.last() !in SKIP_FILES &&
            !(segs.size == 1 && p.endsWith(".md", true) && !p.equals("index.md", true))
    }.sorted()

    fun hash(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(32)

    /** {"manifest": {"/index.html": {"hash": …, "size": …}}} */
    fun manifest(files: Map<String, ByteArray>): String =
        MiniJson.write(mapOf("manifest" to files.entries.sortedBy { it.key }.associate { (p, b) -> "/$p" to mapOf("hash" to hash(b), "size" to b.size) }))

    fun mime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"; "js", "mjs" -> "application/javascript"; "css" -> "text/css"; "json", "webmanifest" -> "application/json"
        "svg" -> "image/svg+xml"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; "ico" -> "image/x-icon"
        "wasm" -> "application/wasm"; "txt", "md" -> "text/plain"; "mp3" -> "audio/mpeg"; "ogg" -> "audio/ogg"; "wav" -> "audio/wav"; "mp4" -> "video/mp4"
        "woff2" -> "font/woff2"; "woff" -> "font/woff"; "ttf" -> "font/ttf"; "glb" -> "model/gltf-binary"; "gltf" -> "model/gltf+json"; "xml" -> "application/xml"
        else -> "application/octet-stream"
    }

    /** The Worker used when the project has no backend: it only serves the files. */
    const val STATIC_WORKER = "export default { async fetch(request, env) { return env.ASSETS.fetch(request); } };\n"

    /** The metadata part of the Worker upload. */
    fun metadata(mainModule: String, assetsJwt: String?, secrets: Map<String, String>, paths: Collection<String>, backend: Boolean = false, reports: Reports? = null): String {
        val config = LinkedHashMap<String, Any?>()
        config["html_handling"] = "auto-trailing-slash"
        when {
            "404.html" in paths -> config["not_found_handling"] = "404-page"
            "index.html" in paths -> config["not_found_handling"] = "single-page-application"
        }
        // 1.5.123: with a backend, /api/… always goes to it first, so a single-page app's index.html never answers an API call
        // (a recipe app's /api/fetch would otherwise get the page back instead of the recipe)
        // 1.5.125: with error reports on, /__pocketide/* (the reports and the reporter script) always reaches the Worker too
        val first = (if (backend) listOf("/api/*") else emptyList()) + (if (reports != null) listOf(CfErrors.ROUTE) else emptyList())
        if (first.isNotEmpty()) config["run_worker_first"] = first
        val assets = LinkedHashMap<String, Any?>()
        if (assetsJwt != null) assets["jwt"] = assetsJwt else assets["keep_assets"] = true
        assets["config"] = config
        // 1.5.125: the read key is one more secret (one binding even when the project's secrets hold it too), and KV when there is one
        val allSecrets = if (reports != null) secrets + (CfErrors.KEY_NAME to reports.key) else secrets
        val kv = reports?.kvId?.let { listOf(mapOf("name" to CfErrors.KV_NAME, "type" to "kv_namespace", "namespace_id" to it)) }.orEmpty()
        return MiniJson.write(mapOf("main_module" to mainModule, "assets" to assets, "compatibility_date" to COMPAT,
            "bindings" to (listOf(mapOf("name" to "ASSETS", "type" to "assets")) + kv + allSecrets.entries.sortedBy { it.key }.map { (k, v) -> mapOf("name" to k, "type" to "secret_text", "text" to v) })))
    }

    /** Error reports for a deploy (1.5.125, CfErrors): the key that reads them, and the KV namespace when the token could make one. */
    class Reports(val key: String, val kvId: String?)

    /** A multipart/form-data body: (field name, file name or null, content type or null, bytes). */
    class Part(val name: String, val filename: String?, val type: String?, val bytes: ByteArray)
    fun multipart(parts: List<Part>, boundary: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        for (p in parts) {
            w("--$boundary\r\nContent-Disposition: form-data; name=\"${p.name}\"" + (p.filename?.let { "; filename=\"$it\"" } ?: "") + "\r\n")
            if (p.type != null) w("Content-Type: ${p.type}\r\n")
            w("\r\n"); out.write(p.bytes); w("\r\n")
        }
        w("--$boundary--\r\n")
        return out.toByteArray()
    }

    /** The parts of one upload batch: each file's base64 content under its hash, typed as it should be served. */
    fun uploadParts(bucket: List<String>, byHash: Map<String, Pair<String, ByteArray>>): List<Part> = bucket.mapNotNull { h ->
        byHash[h]?.let { (path, bytes) -> Part(h, h, mime(path), java.util.Base64.getEncoder().encode(bytes)) }
    }

    /** The answer's "result" object, or the errors as one line. */
    fun result(json: String): Pair<Map<String, Any?>?, String> {
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: Exception) { null } ?: return null to "Cloudflare's answer was not JSON: ${json.take(200)}"
        val errs = MiniJson.list(o["errors"]).orEmpty().mapNotNull { MiniJson.obj(it)?.let { e -> "${e["message"]}" + ((e["code"] as? Number)?.let { c -> " (code ${c.toInt()})" } ?: "") } }
        return if (o["success"] == true) (MiniJson.obj(o["result"]) ?: emptyMap()) to "" else null to errs.joinToString("; ").ifEmpty { "Cloudflare said no: ${json.take(200)}" }
    }

    fun buckets(result: Map<String, Any?>): List<List<String>> = MiniJson.list(result["buckets"]).orEmpty().map { b -> MiniJson.list(b).orEmpty().map { it.toString() } }

    fun url(script: String, subdomain: String) = "https://$script.$subdomain.workers.dev"

    /** Plain words for the usual failures. */
    fun hint(error: String): String = when {
        error.contains("Authentication error", true) || error.contains("10000") -> " The token needs the permission Workers Scripts: Edit (and Account Settings: Read) on this account."
        error.contains("workers.dev subdomain", true) || error.contains("10063") -> " Open Workers & Pages in the Cloudflare dashboard once, so your account gets its workers.dev address."
        error.contains("Uncaught", true) || error.contains("10021") -> " The backend's code failed when Cloudflare loaded it: check worker.js (it must use `export default { fetch(request, env) { … } }`)."
        else -> ""
    }

    const val HELP = "Deploy to Cloudflare puts the whole project online at <name>.<you>.workers.dev: every file as a static page or asset, " +
        "and, when the project has worker.js (or _worker.js, src/worker.js) exporting { fetch }, that as the backend for any address no file matches. " +
        "The project's secrets reach the backend as env.NAME. It needs a Cloudflare API token with Workers Scripts: Edit, and your account id " +
        "(both in the Cloudflare dashboard; the free plan is enough)."
}
