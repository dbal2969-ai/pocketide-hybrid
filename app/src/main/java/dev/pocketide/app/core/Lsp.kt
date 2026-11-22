package dev.pocketide.app.core

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A small Language Server Protocol client (3.17, stdio transport): enough to ask a language server for the diagnostics of one
 * file after an edit, so errors reach the assistant before its next round. It reads the server's replies on its own thread, so a
 * server that never answers only costs the timeout.
 */
object Lsp {
    fun frame(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        return "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII) + body
    }

    /** The next message from the server, or null at the end of its output. */
    fun read(input: InputStream): String? {
        var length = -1
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull() ?: -1
        }
        if (length < 0) return null
        val buf = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = input.read(buf, got, length - got)
            if (n < 0) return null
            got += n
        }
        return String(buf, Charsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    /** The language identifier a server expects for a file, or null when no server is known for it. */
    fun languageId(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "js", "mjs", "cjs" -> "javascript"
        "jsx" -> "javascriptreact"
        "ts" -> "typescript"
        "tsx" -> "typescriptreact"
        "json" -> "json"
        "py" -> "python"
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "html" -> "html"
        "css" -> "css"
        else -> null
    }

    /**
     * What this client accepts. typescript-language-server sends errors only when publishDiagnostics is declared here: with empty
     * capabilities (before 1.5.73) it never sent any, and every check waited out its time limit.
     */
    val CLIENT_CAPABILITIES: Map<String, Any?> = mapOf("textDocument" to mapOf(
        "synchronization" to emptyMap<String, Any?>(),
        "publishDiagnostics" to emptyMap<String, Any?>(),
        "definition" to emptyMap<String, Any?>(),
        "references" to emptyMap<String, Any?>()))

    /** The answer to a request the server sends the client: workspace/configuration gets one empty setting per item, anything else null. */
    fun serverReply(req: Map<String, Any?>): Map<String, Any?> {
        val result: Any? = if (req["method"] == "workspace/configuration")
            MiniJson.list(MiniJson.obj(req["params"])?.get("items")).orEmpty().map { null } else null
        return mapOf("jsonrpc" to "2.0", "id" to req["id"], "result" to result)
    }

    /** The file URI a server uses for [path] under [root]. */
    fun uriOf(root: String, path: String): String = "file://" + root.trimEnd('/') + "/" + path

    /**
     * One short session with a server: initialize, open [path] with [text], then [body] with the file's URI, a sender and a reader
     * of the next message (null at the end of the output or after [timeoutMs]); shutdown and exit afterwards. Null when the file
     * has no known language or the server does not answer.
     */
    private fun <T> session(input: InputStream, output: OutputStream, root: String, path: String, text: String, timeoutMs: Long,
                            body: (String, (Map<String, Any?>) -> Unit, () -> Map<String, Any?>?) -> T?): T? {
        val lang = languageId(path) ?: return null
        val uri = uriOf(root, path)
        val queue = LinkedBlockingQueue<String>()
        val reader = Thread {
            try { while (true) queue.put(read(input) ?: break) } catch (e: Exception) { }
            queue.put("")  // the end of the output
        }
        reader.isDaemon = true
        reader.start()
        val deadline = System.currentTimeMillis() + timeoutMs
        val send: (Map<String, Any?>) -> Unit = { obj -> output.write(frame(MiniJson.write(obj))); output.flush() }
        val next: () -> Map<String, Any?>? = next@{
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return@next null
                val m = queue.poll(left, TimeUnit.MILLISECONDS) ?: return@next null
                if (m.isEmpty()) return@next null
                val o = try { MiniJson.obj(MiniJson.parse(m)) } catch (e: Exception) { null } ?: return@next null
                // a request from the server (pyright asks for its settings and waits): answer it, then keep reading
                if (o["method"] != null && o["id"] != null) { send(serverReply(o)); continue }
                return@next o
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        try {
            send(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "initialize",
                "params" to mapOf("processId" to null, "rootUri" to "file://" + root.trimEnd('/'), "capabilities" to CLIENT_CAPABILITIES)))
            while (true) {
                val m = next() ?: return null
                if ((m["id"] as? Number)?.toInt() == 1) break
            }
            send(mapOf("jsonrpc" to "2.0", "method" to "initialized", "params" to emptyMap<String, Any>()))
            send(mapOf("jsonrpc" to "2.0", "method" to "textDocument/didOpen", "params" to mapOf(
                "textDocument" to mapOf("uri" to uri, "languageId" to lang, "version" to 1, "text" to text))))
            return body(uri, send, next)
        } finally {
            try {
                send(mapOf("jsonrpc" to "2.0", "id" to 2, "method" to "shutdown"))
                send(mapOf("jsonrpc" to "2.0", "method" to "exit"))
            } catch (e: Exception) { }
        }
    }

    /**
     * Opens [path] (with [text]) on a running server and returns its errors and warnings as "line N: message", or null when the
     * server does not answer within [timeoutMs]. The server's streams are the process's output (read) and input (written).
     */
    fun diagnose(input: InputStream, output: OutputStream, root: String, path: String, text: String, timeoutMs: Long): List<String>? =
        session(input, output, root, path, text, timeoutMs) { uri, _, next ->
            var found: List<String>? = null
            while (found == null) {
                val m = next() ?: break
                if (m["method"] != "textDocument/publishDiagnostics") continue
                val p = MiniJson.obj(m["params"]) ?: continue
                if (p["uri"] != uri) continue
                found = MiniJson.list(p["diagnostics"]).orEmpty().mapNotNull { d ->
                    val o = MiniJson.obj(d) ?: return@mapNotNull null
                    val sev = (o["severity"] as? Number)?.toInt() ?: 1
                    if (sev > 2) return@mapNotNull null  // hints and information are not sent to the assistant
                    val line = (MiniJson.obj(MiniJson.obj(o["range"])?.get("start"))?.get("line") as? Number)?.toInt() ?: 0
                    "line ${line + 1}: " + (o["message"] as? String).orEmpty().lineSequence().first()
                }
            }
            found
        }

    /** A place in the project: a path and a 1-based line. */
    data class Location(val path: String, val line: Int)

    /** Where [name] first appears in [text] as a whole word: 0-based line and character, or null. */
    fun locate(text: String, name: String): Pair<Int, Int>? {
        if (name.isBlank()) return null
        val re = Regex("(?<![\\w$])" + Regex.escape(name) + "(?![\\w$])")
        text.split('\n').forEachIndexed { i, l -> re.find(l)?.let { return i to it.range.first } }
        return null
    }

    /** A file URI from the server as a project path: file:///work/game/main.js under root /work is game/main.js. */
    fun relative(root: String, uri: String): String {
        val p = java.net.URLDecoder.decode(uri.removePrefix("file://").replace("+", "%2B"), "UTF-8")
        val r = root.trimEnd('/') + "/"
        return if (p.startsWith(r)) p.removePrefix(r) else p
    }

    /**
     * Asks the server for the definition ([method] "definition") or every use ("references", declaration included) of the symbol
     * at [line] and [character] (0-based) in [path]. Null when the server does not answer; empty when it found nothing.
     */
    fun locations(input: InputStream, output: OutputStream, root: String, path: String, text: String, method: String,
                  line: Int, character: Int, timeoutMs: Long): List<Location>? =
        session(input, output, root, path, text, timeoutMs) { uri, send, next ->
            val params = LinkedHashMap<String, Any?>()
            params["textDocument"] = mapOf("uri" to uri)
            params["position"] = mapOf("line" to line, "character" to character)
            if (method == "references") params["context"] = mapOf("includeDeclaration" to true)
            send(mapOf("jsonrpc" to "2.0", "id" to 3, "method" to "textDocument/$method", "params" to params))
            var found: List<Location>? = null
            while (found == null) {
                val m = next() ?: break
                if ((m["id"] as? Number)?.toInt() != 3) continue
                if (m["error"] != null) { found = emptyList(); break }
                val res = m["result"]
                val items: List<*> = when (res) { null -> emptyList<Any>(); is List<*> -> res; else -> listOf(res) }
                found = items.mapNotNull { item ->
                    val o = MiniJson.obj(item) ?: return@mapNotNull null
                    val u = (o["uri"] ?: o["targetUri"]) as? String ?: return@mapNotNull null
                    val range = MiniJson.obj(o["range"] ?: o["targetSelectionRange"] ?: o["targetRange"])
                    val ln = (MiniJson.obj(range?.get("start"))?.get("line") as? Number)?.toInt() ?: 0
                    Location(relative(root, u), ln + 1)
                }.distinct()
            }
            found
        }

    /** The locations as the assistant reads them, each with its line of code. */
    fun formatLocations(name: String, method: String, locs: List<Location>, lineText: (String, Int) -> String?): String {
        if (locs.isEmpty()) return "The language server found no ${if (method == "definition") "definition" else "uses"} of $name."
        val head = if (method == "definition") "Definition of $name:" else "$name is defined or used in ${locs.size} place(s):"
        return head + "\n" + locs.take(60).joinToString("\n") { l -> "- ${l.path}:${l.line}: " + (lineText(l.path, l.line)?.trim()?.take(160) ?: "") } +
            if (locs.size > 60) "\n… and ${locs.size - 60} more." else ""
    }
}
