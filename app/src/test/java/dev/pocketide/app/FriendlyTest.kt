package dev.pocketide.app

import dev.pocketide.app.core.HelpAction
import dev.pocketide.app.core.HelpContent
import dev.pocketide.app.core.ModelCatalog
import dev.pocketide.app.core.ModelFit
import dev.pocketide.app.core.OllamaApi
import dev.pocketide.app.core.Providers
import org.junit.Test
import java.net.InetSocketAddress

class FriendlyTest {
    @Test
    fun friendlyChecks() {
        FriendlySelfTest.runAll()
    }
}

/** Checks for the Models screen's Ollama client, the model catalog and the Help content. */
object FriendlySelfTest {
    private var fails = 0
    private var passes = 0

    private fun ok(c: Boolean, m: String) {
        if (c) passes++ else fails++
        println((if (c) "PASS  " else "FAIL  ") + m)
    }

    private fun eq(a: Any?, b: Any?, m: String) = ok(a == b, if (a == b) m else "$m  got <$a> expected <$b>")

    fun runAll() {
        ollama(); pullStream(); catalog(); help(); fakeServer()
        println("\n$passes passed, $fails failed")
        check(fails == 0) { "$fails friendly checks failed" }
    }

    private fun ollama() {
        eq(OllamaApi.root("http://127.0.0.1:11434/v1"), "http://127.0.0.1:11434", "root: /v1 stripped")
        eq(OllamaApi.root("http://127.0.0.1:11434/v1/"), "http://127.0.0.1:11434", "root: trailing slash")
        eq(OllamaApi.root("http://10.0.0.5:11434/v1/chat/completions"), "http://10.0.0.5:11434", "root: chat path stripped")
        eq(OllamaApi.root("localhost:11434"), "http://localhost:11434", "root: scheme added")
        eq(OllamaApi.root(""), OllamaApi.DEFAULT_ROOT, "root: empty means default")
        eq(OllamaApi.root("http://h:1/api"), "http://h:1", "root: /api stripped")
        ok(OllamaApi.isValidName("qwen2.5-coder:1.5b"), "name: tag accepted")
        ok(OllamaApi.isValidName("phi4-mini"), "name: no tag accepted")
        ok(OllamaApi.isValidName("someone/model:latest"), "name: namespace accepted")
        ok(!OllamaApi.isValidName("Qwen Coder"), "name: spaces refused")
        ok(!OllamaApi.isValidName("../x"), "name: path refused")
        ok(!OllamaApi.isValidName(""), "name: empty refused")
        ok(OllamaApi.sameModel("phi4-mini", "phi4-mini:latest"), "same model: implicit latest")
        ok(!OllamaApi.sameModel("qwen3:1.7b", "qwen3:4b"), "different tags differ")
        eq(OllamaApi.pullBody("a:b"), "{\"model\":\"a:b\",\"stream\":true}", "pull body")
        eq(OllamaApi.deleteBody("a:b"), "{\"model\":\"a:b\"}", "delete body")
        eq(OllamaApi.parseVersion("{\"version\":\"0.17.7\"}"), "0.17.7", "version parsed")
        eq(OllamaApi.parseVersion("not json"), null, "bad version reply is null")
        val tags = """{"models":[{"name":"qwen2.5-coder:1.5b","model":"qwen2.5-coder:1.5b","modified_at":"2026-10-19T17:00:00Z","size":986062089,
            "digest":"abc","details":{"parent_model":"","format":"gguf","family":"qwen2","families":["qwen2"],"parameter_size":"1.5B","quantization_level":"Q4_K_M"}},
            {"name":"gemma3:1b","size":815319791,"details":{"parameter_size":"999.89M","quantization_level":"Q4_K_M"}}]}"""
        val t = OllamaApi.parseTags(tags)
        eq(t.map { it.name }, listOf("gemma3:1b", "qwen2.5-coder:1.5b"), "tags parsed and sorted")
        eq(t[1].sizeBytes to t[1].parameterSize, 986062089L to "1.5B", "tag size and parameters")
        eq(OllamaApi.parseTags("{\"models\":[]}").size, 0, "no models")
        eq(OllamaApi.parseTags("garbage").size, 0, "garbage tags reply is empty, not a crash")
        eq(OllamaApi.formatSize(986_062_089), "986 MB", "size in MB")
        eq(OllamaApi.formatSize(4_700_000_000), "4.7 GB", "size in GB")
    }

    /** A realistic /api/pull stream (two layers, then verify/write/success). */
    val pullLines = listOf(
        """{"status":"pulling manifest"}""",
        """{"status":"pulling 29d8c98fa6b0","digest":"sha256:29d8c98fa6b0","total":986048768}""",
        """{"status":"pulling 29d8c98fa6b0","digest":"sha256:29d8c98fa6b0","total":986048768,"completed":493024384}""",
        """{"status":"pulling 66b9ea09bd5b","digest":"sha256:66b9ea09bd5b","total":68,"completed":68}""",
        """{"status":"pulling 29d8c98fa6b0","digest":"sha256:29d8c98fa6b0","total":986048768,"completed":986048768}""",
        """{"status":"verifying sha256 digest"}""",
        """{"status":"writing manifest"}""",
        """{"status":"success"}"""
    )

    private fun pullStream() {
        val p = OllamaApi.PullProgress()
        eq(p.permille, -1, "progress unknown before any layer")
        p.add(OllamaApi.parsePullLine(pullLines[0])!!)
        eq(p.text(), "Looking up the model…", "manifest phase text")
        p.add(OllamaApi.parsePullLine(pullLines[1])!!)
        p.add(OllamaApi.parsePullLine(pullLines[2])!!)
        p.add(OllamaApi.parsePullLine(pullLines[3])!!)
        ok(p.permille in 499..501, "progress adds up layers (${p.permille})")
        ok(p.text().startsWith("Downloading 493 MB of 986 MB"), "download text: ${p.text()}")
        p.add(OllamaApi.parsePullLine(pullLines[4])!!)
        eq(p.permille, 1000, "complete layer is 100%")
        p.add(OllamaApi.parsePullLine(pullLines[5])!!)
        eq(p.text(), "Checking the download…", "verify phase text")
        p.add(OllamaApi.parsePullLine(pullLines[7])!!)
        ok(p.done && p.text() == "Downloaded", "success line finishes")
        val e = OllamaApi.PullProgress()
        e.add(OllamaApi.parsePullLine("""{"error":"pull model manifest: file does not exist"}""")!!)
        eq(e.error, "pull model manifest: file does not exist", "error line captured")
        eq(OllamaApi.parsePullLine(""), null, "blank line ignored")
        eq(OllamaApi.parsePullLine("{oops"), null, "broken line ignored")
    }

    private fun catalog() {
        val gb = 1_000_000_000L
        val names = ModelCatalog.models.map { it.name }
        eq(names.size, names.toSet().size, "catalog names unique")
        ok(names.all { OllamaApi.isValidName(it) }, "catalog names are valid Ollama names")
        ok(ModelCatalog.models.any { it.recommended && it.coding }, "a recommended coding model exists")
        // S24+ class phone reports about 11 GB
        val s24 = 11 * gb
        eq(ModelCatalog.fit(986_000_000, s24), ModelFit.GOOD, "1.5B runs well on 11 GB")
        eq(ModelCatalog.fit(1_900_000_000, s24), ModelFit.GOOD, "3B runs well on 11 GB")
        eq(ModelCatalog.fit(4_700_000_000, s24), ModelFit.SLOW, "7B is slow on 11 GB")
        eq(ModelCatalog.fit(1_900_000_000, 7_500_000_000), ModelFit.SLOW, "3B is slow on an 8 GB phone (7.5 GB reported)")
        eq(ModelCatalog.fit(4_700_000_000, 6 * gb), ModelFit.TOO_BIG, "7B too big on 6 GB")
        eq(ModelCatalog.fit(398_000_000, 4 * gb), ModelFit.GOOD, "0.5B fine on 4 GB")
        eq(ModelCatalog.fit(3_400_000_000, 32 * gb), ModelFit.SLOW, "over 3 GB is never better than slow")
        eq(ModelCatalog.fit(986_000_000, 0), ModelFit.GOOD, "unknown memory: small model assumed fine")
    }

    private fun help() {
        val ids = HelpContent.sections.map { it.id } + HelpContent.sections.flatMap { s -> s.entries.map { it.id } }
        eq(ids.size, ids.toSet().size, "help ids unique across sections and entries")
        for (p in HelpContent.keyProviders) {
            val e = HelpContent.locate(p)?.second
            ok(e != null && e.steps.size >= 3 && e.url?.startsWith("https://") == true && e.action == HelpAction.SETTINGS && e.providerId == p,
                "key guide for $p has steps, a link and a settings action")
            ok(Providers.all.any { it.id == p && !it.keyless }, "key guide $p matches a provider that needs a key")
        }
        eq(HelpContent.locate(HelpContent.KEYS)?.second, null, "section id locates the section")
        val modelAdvice = HelpContent.section(HelpContent.KEYS)!!.intro
        ok(modelAdvice.contains("Kimi K2.6") && modelAdvice.contains("GPT-OSS 120B") &&
            modelAdvice.contains("Llama 3.3 70B") && modelAdvice.contains("vary by provider") && !modelAdvice.contains("1.5."),
            "model advice reports measured outcomes and limits")
        eq(HelpContent.locate("trouble-429")?.first?.id, HelpContent.TROUBLE, "entry id locates its section")
        eq(HelpContent.locate("nope"), null, "unknown id")
        eq(HelpContent.locate("files-save")?.first?.id, HelpContent.FILES, "files entry locates the files section")
        ok(HelpContent.search("download").any { it.second.id == "files-save" } && HelpContent.search("docx").any { it.second.id == "files-ai" }, "search finds the files help")
        ok(HelpContent.section(HelpContent.FILES)!!.entries.all { it.steps.isNotEmpty() }, "every files entry has steps")
        ok(HelpContent.search("429").any { it.second.id == "trouble-429" }, "search finds 429")
        ok(HelpContent.search("ollama termux").isNotEmpty(), "search with two words")
        eq(HelpContent.search("x").size, 0, "one-letter search ignored")
        eq(HelpContent.forError("Invalid API Key (HTTP 401)", true, "groq"), "groq", "401 on groq opens the groq key guide")
        eq(HelpContent.forError("Request failed (403).", false, "custom"), "trouble-401", "403 on a custom server goes to troubleshooting")
        eq(HelpContent.forError("Rate limit reached for model (HTTP 429)", false, "groq"), "trouble-429", "429 maps to rate limits")
        eq(HelpContent.forError("model 'x' not found (HTTP 404)", false, "ollama"), "trouble-model", "model not found")
        eq(HelpContent.forError("Could not reach http://127.0.0.1:11434/v1/chat/completions. Check that the server is running", false, "ollama"),
            "trouble-local", "local server unreachable")
        eq(HelpContent.forError("Something odd", false, "gemini"), HelpContent.TROUBLE, "anything else goes to troubleshooting")
    }

    /** The client against a small fake Ollama server: version, tags, a streamed pull, delete, and a cancelled pull. */
    private fun fakeServer() {
        val deleted = ArrayList<String>()
        val pullBodies = ArrayList<String>()
        // A tiny socket-based HTTP server: Android unit tests compile against android.jar,
        // which has no com.sun.net.httpserver.
        val server = MiniHttp { method, path, body, r ->
            when (path) {
                "/api/version" -> r.send(200, "{\"version\":\"0.17.7\"}")
                "/api/tags" -> r.send(200, "{\"models\":[{\"name\":\"gemma3:1b\",\"size\":815319791}]}")
                "/api/delete" -> {
                    deleted.add("$method $body")
                    if (body.contains("missing")) r.send(404, "{\"error\":\"model 'missing' not found\"}") else r.send(200, "")
                }
                "/api/pull" -> {
                    pullBodies.add(body)
                    val out = r.stream(200)
                    if (body.contains("nosuch")) {
                        out.write("{\"status\":\"pulling manifest\"}\n{\"error\":\"pull model manifest: file does not exist\"}\n".toByteArray())
                    } else if (body.contains("slow")) {
                        // Ollama sends progress lines continuously while downloading
                        repeat(30) { out.write((pullLines[2] + "\n").toByteArray()); out.flush(); Thread.sleep(100) }
                        out.write((pullLines[7] + "\n").toByteArray())
                    } else for (l in pullLines) { out.write((l + "\n").toByteArray()); out.flush() }
                }
                else -> r.send(404, "not found")
            }
        }
        try {
            val root = "http://127.0.0.1:${server.port}"
            eq(OllamaApi.version("$root/v1".let { OllamaApi.root(it) }), "0.17.7", "fake server: version")
            eq(OllamaApi.version("http://127.0.0.1:1"), null, "nothing listening: version is null")
            eq(OllamaApi.tags(root).map { it.name }, listOf("gemma3:1b"), "fake server: tags")
            val seen = ArrayList<Int>()
            val err = OllamaApi.pull(root, "qwen2.5-coder:1.5b", OllamaApi.PullHandle()) { seen.add(it.permille) }
            eq(err, null, "fake server: pull succeeds")
            ok(seen.contains(1000) && pullBodies.last().contains("\"stream\":true"), "fake server: progress reached 100% and body asked for a stream")
            eq(OllamaApi.pull(root, "nosuch:1b", OllamaApi.PullHandle()) {}, "pull model manifest: file does not exist", "fake server: pull error reported")
            val h = OllamaApi.PullHandle()
            Thread { Thread.sleep(400); h.cancel() }.start()
            val t0 = System.currentTimeMillis()
            eq(OllamaApi.pull(root, "slow:1b", h) {}, "Cancelled", "fake server: cancel stops a pull")
            ok(System.currentTimeMillis() - t0 < 2500, "fake server: cancel is quick")
            OllamaApi.delete(root, "gemma3:1b")
            eq(deleted.first(), "DELETE {\"model\":\"gemma3:1b\"}", "fake server: delete sends DELETE with a body")
            val miss = try { OllamaApi.delete(root, "missing"); null } catch (e: OllamaApi.OllamaError) { e.message }
            eq(miss, "model 'missing' not found", "fake server: delete error message")
        } finally {
            server.stop()
        }
    }
}

/** Minimal HTTP/1.1 server for tests: one request per connection, Content-Length request bodies. */
class MiniHttp(private val handle: (method: String, path: String, body: String, r: Responder) -> Unit) {
    class Responder(private val out: java.io.OutputStream) {
        private fun reason(code: Int) = if (code == 200) "OK" else if (code == 404) "Not Found" else "Error"
        fun send(code: Int, body: String) {
            val b = body.toByteArray()
            out.write("HTTP/1.1 $code ${reason(code)}\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(b); out.flush()
        }
        /** Body ends when the connection closes. */
        fun stream(code: Int): java.io.OutputStream {
            out.write("HTTP/1.1 $code ${reason(code)}\r\nContent-Type: application/x-ndjson\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return out
        }
    }

    private val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    @Volatile private var running = true

    init {
        val t = Thread {
            while (running) {
                val c = try { socket.accept() } catch (e: Exception) { break }
                Thread { serve(c) }.apply { isDaemon = true }.start()
            }
        }
        t.isDaemon = true
        t.start()
    }

    private fun serve(c: java.net.Socket) {
        c.use { sock ->
            try {
                val input = java.io.BufferedInputStream(sock.getInputStream())
                fun line(): String {
                    val sb = StringBuilder()
                    while (true) {
                        val ch = input.read()
                        if (ch < 0 || ch == '\n'.code) break
                        if (ch != '\r'.code) sb.append(ch.toChar())
                    }
                    return sb.toString()
                }
                val request = line().split(" ")
                if (request.size < 2) return
                var length = 0
                while (true) {
                    val h = line()
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    if (i > 0 && h.substring(0, i).trim().equals("Content-Length", true)) length = h.substring(i + 1).trim().toInt()
                }
                val body = ByteArray(length)
                var n = 0
                while (n < length) { val r = input.read(body, n, length - n); if (r < 0) break; n += r }
                handle(request[0], request[1].substringBefore('?'), String(body, 0, n, Charsets.UTF_8), Responder(sock.getOutputStream()))
            } catch (e: Exception) { /* client went away */ }
        }
    }

    fun stop() { running = false; try { socket.close() } catch (e: Exception) { } }
}
