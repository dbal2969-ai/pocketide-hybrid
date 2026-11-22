package dev.pocketide.app

import dev.pocketide.app.core.WebResearch
import java.net.HttpURLConnection
import java.net.URL

/** Fetches web pages and search results for the assistant. Blocking: call from a background thread. */
object WebClient {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36 PocketIDE"
    private const val MAX_BYTES = 1_500_000

    class Page(val url: String, val type: String, val body: String)

    /** Resolves the name and rejects it if any address is loopback, private, link-local or otherwise not public (catches 127.1 and names that point inward). */
    private fun publicAddress(host: String): Boolean = try {
        java.net.InetAddress.getAllByName(host).all { a ->
            val b = a.address
            val cgnat = b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
            val ula = b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC
            !(a.isAnyLocalAddress || a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isMulticastAddress || cgnat || ula)
        }
    } catch (e: Exception) { false }

    /** GET with manual redirects so every hop is checked against the blocked-address rules. Throws with a readable message. */
    fun get(start: String, headers: Map<String, String> = emptyMap()): Page {
        var url = start
        for (hop in 0..5) {
            if (WebResearch.blockedHost(WebResearch.hostOf(url)) || !url.startsWith("https://")) throw RuntimeException("That address is not allowed (only public https sites can be read).")
            if (!publicAddress(WebResearch.hostOf(url))) throw RuntimeException("That address is not allowed (it points at this phone or a private network).")
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 15000; c.readTimeout = 25000
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,text/plain,*/*;q=0.5")
                c.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
                if (WebResearch.hostOf(url) == WebResearch.hostOf(start)) for ((k, v) in headers) c.setRequestProperty(k, v)  // never send the key to another site after a redirect
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw RuntimeException("The site redirected without saying where.")
                    url = URL(URL(url), loc).toString()
                    continue
                }
                val type = (c.contentType ?: "").substringBefore(';').trim().lowercase()
                if (code !in 200..299) throw RuntimeException("The site answered HTTP $code" + (if (code == 403 || code == 429) " (it may block automated readers)." else "."))
                if (type.isNotEmpty() && !(type.startsWith("text/") || type.contains("json") || type.contains("xml") || type.contains("javascript") || type.contains("typescript") || type.contains("markdown")))
                    throw RuntimeException("That address is a $type file, not a page the assistant can read as text.")
                val bytes = java.io.ByteArrayOutputStream()
                c.inputStream.use { ins ->
                    val buf = ByteArray(16384)
                    while (bytes.size() < MAX_BYTES) { val n = ins.read(buf); if (n < 0) break; bytes.write(buf, 0, n) }
                }
                val charset = Regex("""charset=([\w-]+)""", RegexOption.IGNORE_CASE).find(c.contentType ?: "")?.groupValues?.get(1)
                val text = try { bytes.toString(charset ?: "UTF-8") } catch (e: Exception) { bytes.toString("UTF-8") }
                return Page(url, type, text)
            } finally { c.disconnect() }
        }
        throw RuntimeException("Too many redirects.")
    }

    /** Text for the model: the page's readable text, or the raw text for json/plain. */
    fun fetch(rawUrl: String): String {
        val url = WebResearch.normalizeUrl(rawUrl) ?: return "That is not a usable web address. Give a full public address such as https://example.com/page."
        return try {
            val p = get(url)
            val isHtml = p.type.contains("html") || p.type.isEmpty() && p.body.trimStart().startsWith("<")
            val text = if (isHtml) WebResearch.htmlToText(p.body) else p.body.take(WebResearch.MAX_PAGE_CHARS)
            WebResearch.pageResult(p.url, if (isHtml) WebResearch.title(p.body) else "", text)
        } catch (e: Exception) { describe(e) }
    }

    fun search(query: String, braveKey: String): String {
        val q = query.trim()
        if (q.isEmpty()) return "Give something to search for."
        return try {
            val hits = if (braveKey.isNotBlank()) WebResearch.parseBrave(get(WebResearch.braveUrl(q), mapOf("X-Subscription-Token" to braveKey.trim(), "Accept" to "application/json")).body)
            else WebResearch.parseDuck(get(WebResearch.duckUrl(q)).body)
            if (hits.isEmpty() && braveKey.isBlank()) WebResearch.NO_RESULTS_HELP else WebResearch.formatHits(q, hits)
        } catch (e: Exception) { describe(e) + if (braveKey.isBlank()) " " + WebResearch.NO_RESULTS_HELP else "" }
    }

    private fun describe(e: Exception): String = "Could not read it: " + when (e) {
        is java.net.UnknownHostException -> "no connection, or the site does not exist."
        is java.net.SocketTimeoutException -> "the site took too long to answer."
        else -> e.message ?: e.javaClass.simpleName
    }
}
