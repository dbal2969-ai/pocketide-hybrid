package dev.pocketide.app.core

/**
 * The API tester (1.5.112): send a request to a server the project runs on the phone or to any web address, and read the answer
 * (Postman, VS Code's REST Client). The person uses it from the Tools menu; the assistant has http_request for servers on the phone
 * itself, so it can check an API it just wrote. {{NAME}} in the address, headers or body is filled from the project's secrets,
 * and secret values are masked out of what is shown. Pure logic: the sending is in ApiScreen and NativeAgent.
 */
object HttpCall {
    val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
    const val MAX_BODY = 200_000
    const val MAX_SHOWN = 12_000

    class Request(val method: String, val url: String, val headers: List<Pair<String, String>>, val body: String)
    class Response(val code: Int, val message: String, val headers: List<Pair<String, String>>, val body: String, val millis: Long, val type: String)

    /** "Name: value" lines; blank lines and lines without a colon are skipped. */
    fun parseHeaders(text: String): List<Pair<String, String>> = text.lines().mapNotNull { l ->
        val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).trim().takeIf { Regex("""^[A-Za-z0-9!#$%&'*+.^_`|~-]+$""").matches(it) }?.let { it to l.substring(i + 1).trim() }
    }

    /** {{NAME}} replaced by the secret NAME; unknown names are left as they are, so the problem is visible in the request. */
    fun fill(text: String, secrets: Map<String, String>): String =
        Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}""").replace(text) { m -> secrets[m.groupValues[1]] ?: m.value }

    fun filled(r: Request, secrets: Map<String, String>): Request =
        Request(r.method, fill(r.url, secrets), r.headers.map { (k, v) -> k to fill(v, secrets) }, fill(r.body, secrets))

    /** An address with http(s):// added when it was left out; "localhost:3000/x" becomes http://, anything else https://. */
    fun normalize(url: String): String? {
        val u = url.trim()
        if (u.isEmpty() || u.contains(' ')) return null
        if (Regex("""^https?://""", RegexOption.IGNORE_CASE).containsMatchIn(u)) return u
        if (Regex("""^[a-z][a-z0-9+.-]*://""", RegexOption.IGNORE_CASE).containsMatchIn(u)) return null
        return (if (isLocal("http://$u")) "http://" else "https://") + u
    }

    fun host(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        .let { if (it.startsWith("[")) it.substringBefore(']') + "]" else it.substringBefore(':') }.lowercase()

    /** A server on this phone (the project's own): the assistant may call it without asking. */
    fun isLocal(url: String): Boolean = host(url) in setOf("localhost", "127.0.0.1", "0.0.0.0", "[::1]") || host(url).startsWith("127.")

    /** The body shown: JSON indented, long text cut, secret values masked. */
    fun shownBody(body: String, type: String, secrets: Map<String, String>, max: Int = MAX_SHOWN): String {
        val pretty = if (type.contains("json") || body.trimStart().let { it.startsWith("{") || it.startsWith("[") }) prettyJson(body) ?: body else body
        val cut = if (pretty.length > max) pretty.take(max) + "\n… (${pretty.length - max} more characters)" else pretty
        return Secrets.mask(cut, secrets)
    }

    /** The answer as text: status line, time, the headers that matter, then the body. */
    fun describe(r: Response, secrets: Map<String, String>, max: Int = MAX_SHOWN): String {
        val keep = setOf("content-type", "content-length", "location", "set-cookie", "access-control-allow-origin", "cache-control", "www-authenticate", "retry-after")
        val hs = r.headers.filter { it.first.lowercase() in keep }.joinToString("\n") { "${it.first}: ${it.second}" }
        return Secrets.mask("HTTP ${r.code} ${r.message} (${r.millis} ms)" + (if (hs.isEmpty()) "" else "\n$hs"), secrets) +
            "\n\n" + (if (r.body.isEmpty()) "(empty body)" else shownBody(r.body, r.type, secrets, max))
    }

    /** The request as a curl command (secrets left as {{NAME}}), to copy into a terminal or a README. */
    fun curl(r: Request): String {
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val sb = StringBuilder("curl")
        if (r.method != "GET") sb.append(" -X ").append(r.method)
        for ((k, v) in r.headers) sb.append(" -H ").append(q("$k: $v"))
        if (r.body.isNotEmpty() && r.method !in setOf("GET", "HEAD")) sb.append(" --data ").append(q(r.body))
        return sb.append(' ').append(q(r.url)).toString()
    }

    /** JSON indented two spaces, or null when [text] is not JSON. */
    fun prettyJson(text: String): String? {
        val v = try { MiniJson.parse(text.trim()) } catch (e: Exception) { return null } ?: return null
        val sb = StringBuilder()
        fun w(x: Any?, ind: String) {
            when (x) {
                is Map<*, *> -> if (x.isEmpty()) sb.append("{}") else { sb.append("{\n"); x.entries.forEachIndexed { i, (k, vv) -> sb.append("$ind  ").append(MiniJson.write(k.toString())).append(": "); w(vv, "$ind  "); if (i < x.size - 1) sb.append(','); sb.append('\n') }; sb.append("$ind}") }
                is List<*> -> if (x.isEmpty()) sb.append("[]") else { sb.append("[\n"); x.forEachIndexed { i, vv -> sb.append("$ind  "); w(vv, "$ind  "); if (i < x.size - 1) sb.append(','); sb.append('\n') }; sb.append("$ind]") }
                is Double -> sb.append(if (x == Math.floor(x) && !x.isInfinite() && Math.abs(x) < 1e15) x.toLong().toString() else x.toString())
                else -> sb.append(MiniJson.write(x))
            }
        }
        w(v, "")
        return sb.toString()
    }

    /**
     * Sends [r] (already filled) and reads the answer. Redirects are not followed, so a 302 is shown as it is. Blocking: call it off
     * the main thread. Errors come back as code 0 with the reason as the message.
     */
    fun send(r: Request, timeoutMs: Int = 20_000): Response {
        val t0 = System.currentTimeMillis()
        return try {
            val c = java.net.URL(r.url).openConnection() as java.net.HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
                var override = false
                try { c.requestMethod = r.method } catch (e: java.net.ProtocolException) { c.requestMethod = "POST"; override = true }
                for ((k, v) in r.headers) c.setRequestProperty(k, v)
                if (override) c.setRequestProperty("X-HTTP-Method-Override", r.method)
                if (r.body.isNotEmpty() && r.method !in setOf("GET", "HEAD")) {
                    if (r.headers.none { it.first.equals("Content-Type", true) })
                        c.setRequestProperty("Content-Type", if (r.body.trimStart().let { it.startsWith("{") || it.startsWith("[") }) "application/json" else "text/plain; charset=utf-8")
                    c.doOutput = true
                    c.outputStream.use { it.write(r.body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = (if (code >= 400) c.errorStream else c.inputStream)
                val bytes = java.io.ByteArrayOutputStream()
                stream?.use { ins -> val buf = ByteArray(16384); while (bytes.size() < MAX_BODY) { val n = ins.read(buf); if (n < 0) break; bytes.write(buf, 0, n) } }
                val headers = c.headerFields.entries.filter { it.key != null }.flatMap { (k, vs) -> vs.map { k to it } }
                Response(code, c.responseMessage.orEmpty(), headers, bytes.toString("UTF-8"), System.currentTimeMillis() - t0, (c.contentType ?: "").lowercase())
            } finally { c.disconnect() }
        } catch (e: Exception) {
            Response(0, (e.javaClass.simpleName + ": " + (e.message ?: "")).trim(), emptyList(), "", System.currentTimeMillis() - t0, "")
        }
    }

    /** What the assistant gets for a failed send: the reason, and the usual cause for a local server. */
    fun failure(r: Request, resp: Response): String = "The request to ${r.url} did not get an answer: ${resp.message}." +
        (if (isLocal(r.url)) " Is the server running? Start it with run_command (in the background, e.g. `node server.js &`) and wait a moment, then try again." else "")

    /** Saved requests, newest first, at most [KEEP]: method, address, headers text and body (unfilled, so no secret is stored). */
    const val KEEP = 30
    fun historyJson(items: List<Request>): String = MiniJson.write(items.take(KEEP).map { mapOf("method" to it.method, "url" to it.url, "headers" to it.headers.joinToString("\n") { (k, v) -> "$k: $v" }, "body" to it.body) })
    fun historyParse(text: String?): List<Request> = try {
        MiniJson.list(MiniJson.parse(text ?: return emptyList())).orEmpty().mapNotNull { e -> MiniJson.obj(e)?.let { o ->
            val m = (o["method"] as? String)?.uppercase()?.takeIf { it in METHODS } ?: return@let null
            Request(m, (o["url"] as? String).orEmpty(), parseHeaders((o["headers"] as? String).orEmpty()), (o["body"] as? String).orEmpty())
        } }
    } catch (e: Exception) { emptyList() }
    fun remember(history: List<Request>, r: Request): List<Request> =
        (listOf(r) + history.filterNot { it.method == r.method && it.url == r.url && it.body == r.body }).take(KEEP)
}
