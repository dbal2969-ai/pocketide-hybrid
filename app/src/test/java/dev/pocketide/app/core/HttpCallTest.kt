package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class HttpCallTest {
    @Test fun addresses() {
        assertEquals("http://localhost:3000/x", HttpCall.normalize("localhost:3000/x"))
        assertEquals("https://api.example.com/v1", HttpCall.normalize("api.example.com/v1"))
        assertEquals("http://127.0.0.1:8080", HttpCall.normalize(" http://127.0.0.1:8080 "))
        assertNull(HttpCall.normalize("ftp://x")); assertNull(HttpCall.normalize("a b"))
        assertTrue(HttpCall.isLocal("http://localhost:3000/") && HttpCall.isLocal("http://127.0.0.2/") && HttpCall.isLocal("http://user@127.0.0.1:9/"))
        assertFalse(HttpCall.isLocal("https://localhost.evil.com/") || HttpCall.isLocal("https://example.com/?u=localhost"))
    }

    @Test fun headersSecretsAndCurl() {
        val h = HttpCall.parseHeaders("Authorization: Bearer {{API_KEY}}\n\nnot a header\nX-Id:  7 ")
        assertEquals(listOf("Authorization" to "Bearer {{API_KEY}}", "X-Id" to "7"), h)
        val r = HttpCall.filled(HttpCall.Request("POST", "http://localhost/{{PATH}}", h, "{\"k\":\"{{API_KEY}}\",\"u\":\"{{NOPE}}\"}"), mapOf("API_KEY" to "sekrit123", "PATH" to "items"))
        assertEquals("http://localhost/items", r.url); assertEquals("Bearer sekrit123", r.headers[0].second); assertEquals("{\"k\":\"sekrit123\",\"u\":\"{{NOPE}}\"}", r.body)
        assertEquals("curl -X POST -H 'Authorization: Bearer {{API_KEY}}' -H 'X-Id: 7' --data '{\"a\":\"it'\\''s\"}' 'http://localhost/x'",
            HttpCall.curl(HttpCall.Request("POST", "http://localhost/x", h, "{\"a\":\"it's\"}")))
    }

    @Test fun jsonIsIndentedAndSecretsMasked() {
        assertEquals("{\n  \"a\": 1,\n  \"b\": [\n    true,\n    \"x\"\n  ],\n  \"c\": {}\n}", HttpCall.prettyJson("{\"a\":1,\"b\":[true,\"x\"],\"c\":{}}"))
        assertNull(HttpCall.prettyJson("<html>"))
        val d = HttpCall.describe(HttpCall.Response(201, "Created", listOf("Content-Type" to "application/json", "X-Other" to "y"), "{\"token\":\"sekrit123\"}", 12, "application/json"), mapOf("API_KEY" to "sekrit123"))
        assertTrue(d.startsWith("HTTP 201 Created (12 ms)\nContent-Type: application/json\n\n{") && d.contains("***API_KEY***") && !d.contains("sekrit123") && !d.contains("X-Other"))
    }

    @Test fun historyKeepsTheNewestWithoutDuplicates() {
        val a = HttpCall.Request("GET", "http://localhost/a", emptyList(), "")
        val b = HttpCall.Request("POST", "http://localhost/b", listOf("A" to "1"), "{}")
        val h = HttpCall.remember(HttpCall.remember(HttpCall.remember(emptyList(), a), b), a)
        assertEquals(listOf("http://localhost/a", "http://localhost/b"), h.map { it.url })
        val back = HttpCall.historyParse(HttpCall.historyJson(h))
        assertEquals("POST", back[1].method); assertEquals(listOf("A" to "1"), back[1].headers); assertEquals("{}", back[1].body)
        assertTrue(HttpCall.historyParse("nonsense").isEmpty())
    }

    /** A one-thread HTTP server that echoes the method, Authorization, Content-Type and body as JSON (DELETE answers 404). */
    private fun echoServer(): ServerSocket {
        val ss = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                s.use { sock ->
                    val ins = java.io.BufferedInputStream(sock.getInputStream())
                    fun line(): String { val b = StringBuilder(); while (true) { val c = ins.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.append(c.toChar()) }; return b.toString() }
                    val method = line().substringBefore(' ')
                    val hs = HashMap<String, String>()
                    while (true) { val l = line(); if (l.isEmpty()) break; hs[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim() }
                    val n = hs["content-length"]?.toIntOrNull() ?: 0
                    val body = String(ByteArray(n).also { var r = 0; while (r < n) { val k = ins.read(it, r, n - r); if (k < 0) break; r += k } })
                    val out = "{\"method\":\"$method\",\"auth\":\"${hs["authorization"]}\",\"type\":\"${hs["content-type"]}\",\"body\":${if (body.isEmpty()) "null" else body}}".toByteArray()
                    val code = if (method == "DELETE") "404 Not Found" else "200 OK"
                    sock.getOutputStream().apply { write("HTTP/1.1 $code\r\nContent-Type: application/json\r\nContent-Length: ${out.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(out); flush() }
                }
            }
        }.also { it.isDaemon = true }.start()
        return ss
    }

    @Test fun sendsToALocalServer() {
        val server = echoServer()
        try {
            val base = "http://127.0.0.1:${server.localPort}/echo"
            val r = HttpCall.send(HttpCall.Request("POST", base, listOf("Authorization" to "Bearer t"), "{\"n\":1}"))
            assertEquals(200, r.code)
            assertTrue(r.body, r.body.contains("\"method\":\"POST\"") && r.body.contains("\"auth\":\"Bearer t\"") && r.body.contains("application/json") && r.body.contains("{\"n\":1}"))
            val del = HttpCall.send(HttpCall.Request("DELETE", base, emptyList(), ""))
            assertEquals(404, del.code); assertTrue(del.body.contains("DELETE"))
            // port 1 has nothing listening (a closed test server's port can be taken by another test at once)
            val down = HttpCall.send(HttpCall.Request("GET", "http://127.0.0.1:1/", emptyList(), ""), 2000)
            assertEquals(0, down.code)
            assertTrue(HttpCall.failure(HttpCall.Request("GET", "http://127.0.0.1:1/", emptyList(), ""), down).contains("Is the server running?"))
        } finally { server.close() }
    }
}
