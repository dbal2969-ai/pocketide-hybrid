package dev.pocketide.app

import android.os.Handler
import android.os.Looper
import dev.pocketide.app.core.Mcp
import dev.pocketide.app.core.McpServer
import dev.pocketide.app.core.McpTool
import dev.pocketide.app.core.Tools
import java.net.HttpURLConnection
import java.net.URL

/** Talks to remote MCP servers over streamable HTTP. All calls block, so run them off the main thread (see [background]). */
object McpClient {
    private val sessions = HashMap<String, String>()
    @Volatile private var nextId = 1
    private val main = Handler(Looper.getMainLooper())

    private fun post(s: McpServer, body: String, wantReply: Boolean = true): String {
        val c = URL(s.url.trim()).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000; c.readTimeout = 45000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json, text/event-stream")
            if (s.token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer " + s.token.trim())
            sessions[s.name]?.let { c.setRequestProperty("Mcp-Session-Id", it) }
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            c.getHeaderField("Mcp-Session-Id")?.let { sessions[s.name] = it }
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code == 401 || code == 403) throw Mcp.McpException("The server refused the sign-in (HTTP $code). Check the token.")
            if (code !in 200..299) throw Mcp.McpException("The server answered HTTP $code.")
            return if (wantReply) text else ""
        } finally { c.disconnect() }
    }

    private fun open(s: McpServer) {
        sessions.remove(s.name)
        Mcp.result(post(s, Mcp.initialize(nextId++)))
        try { post(s, Mcp.notification("notifications/initialized"), false) } catch (e: Exception) { /* some servers answer 202 with no body */ }
    }

    /** Connects and lists the server's tools. Throws with a readable message. */
    fun tools(s: McpServer): List<McpTool> {
        try { open(s) } catch (e: Mcp.McpException) { throw e } catch (e: Exception) { throw Mcp.McpException(describe(e)) }
        return try { Mcp.parseTools(Mcp.safeName(s.name), Mcp.result(post(s, Mcp.listTools(nextId++)))) }
        catch (e: Mcp.McpException) { throw e } catch (e: Exception) { throw Mcp.McpException(describe(e)) }
    }

    /** Runs one tool and returns text for the model. Never throws. */
    fun call(s: McpServer, tool: String, args: Map<String, Any?>): String {
        fun once(): String = Mcp.resultText(Mcp.result(post(s, Mcp.callTool(nextId++, tool, args))))
        return try {
            if (!sessions.containsKey(s.name)) open(s)
            try { once() } catch (e: Mcp.McpException) {
                if (e.message?.contains("HTTP 404") == true) { open(s); once() } else throw e
            }
        } catch (e: Mcp.McpException) { "The server call failed: ${e.message}" }
        catch (e: Exception) { "The server call failed: ${describe(e)}" }
    }

    private fun describe(e: Exception): String =
        if (e is java.net.UnknownHostException || e is java.net.ConnectException) "Could not reach the server. Check the address and your connection."
        else if (e is java.net.SocketTimeoutException) "The server took too long to answer."
        else e.message ?: e.javaClass.simpleName

    fun background(work: () -> Unit) { Thread(work).start() }
    fun onMain(f: () -> Unit) { main.post(f) }

    /** Reconnects to every saved server and publishes the tools to the model. Servers that fail are skipped. */
    fun refresh(settings: AppSettings, done: (List<String>) -> Unit = {}) {
        val servers = settings.mcpServers
        if (servers.isEmpty()) { Tools.extra = emptyList(); done(emptyList()); return }
        background {
            val all = ArrayList<McpTool>(); val errors = ArrayList<String>()
            for (s in servers) {
                try { all.addAll(tools(s)) } catch (e: Exception) { errors.add("${s.name}: ${e.message}") }
            }
            Tools.extra = all
            onMain { done(errors) }
        }
    }

    fun server(settings: AppSettings, tool: McpTool): McpServer? = settings.mcpServers.firstOrNull { Mcp.safeName(it.name) == tool.server }
}
