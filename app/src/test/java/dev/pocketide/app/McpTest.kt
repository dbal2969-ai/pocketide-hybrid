package dev.pocketide.app

import dev.pocketide.app.core.Mcp
import dev.pocketide.app.core.McpServer
import org.junit.Test

object McpSelfTest {
    fun run(): List<String> {
        val f = ArrayList<String>()
        fun check(ok: Boolean, m: String) { if (!ok) f.add(m) }
        check(Mcp.qualify("GitHub!", "search issues") == "mcp__github__search_issues", "qualify: " + Mcp.qualify("GitHub!", "search issues"))
        check(Mcp.split("mcp__github__search_issues") == ("github" to "search_issues"), "split")
        check(Mcp.split("read_file") == null, "split plain")
        check(Mcp.qualify("a", "x".repeat(100)).length == 64, "qualify cap")
        check(Mcp.validUrl("https://mcp.example.com/mcp"), "https ok")
        check(Mcp.validUrl("http://localhost:3000/mcp"), "localhost ok")
        check(!Mcp.validUrl("http://evil.example.com/mcp"), "http remote refused")
        check(!Mcp.validUrl("ftp://x"), "ftp refused")
        val s = McpServer("Docs", "https://a.b/mcp", "tok")
        check(Mcp.decode(Mcp.encode(listOf(s))) == listOf(s), "roundtrip")
        check(Mcp.decode("garbage").isEmpty(), "bad json")
        check(Mcp.problem(McpServer("docs", "https://c.d", ""), listOf(s)) != null, "duplicate")
        check(Mcp.problem(McpServer("new", "https://c.d", ""), listOf(s)) == null, "fine")
        val sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"find\",\"description\":\"Find\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}}]}}\n\n"
        val tools = Mcp.parseTools("docs", Mcp.result(sse))
        check(tools.size == 1 && tools[0].qualified == "mcp__docs__find", "sse tools")
        val err = try { Mcp.result("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"message\":\"nope\"}}"); "" } catch (e: Mcp.McpException) { e.message ?: "" }
        check(err == "nope", "error message: $err")
        val txt = Mcp.resultText(Mcp.result("{\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"isError\":true}}"))
        check(txt.contains("hello") && txt.contains("reported an error") && txt.contains("not instructions"), "result text")
        check(Mcp.resultText(mapOf("content" to listOf(mapOf("type" to "text", "text" to "x".repeat(9000))))).length < 6300, "cap")
        check(Mcp.request(3, "tools/list", emptyMap()).contains("\"method\":\"tools/list\""), "request body")
        check(Mcp.label("mcp__docs__find") == "docs: find", "label")
        return f
    }
}

class McpTest {
    @Test fun mcp() { val f = McpSelfTest.run(); if (f.isNotEmpty()) throw AssertionError(f.joinToString("; ")) }
}
