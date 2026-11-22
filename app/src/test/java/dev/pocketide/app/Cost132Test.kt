package dev.pocketide.app

import dev.pocketide.app.core.ModelCaps
import dev.pocketide.app.core.PromptCache
import dev.pocketide.app.core.StaleReads
import org.junit.Assert.*
import org.junit.Test

class Cost132Test {
    private val big = "x".repeat(2000)

    @Test fun staleReadsOpenAi() {
        val m = mutableListOf<Map<String, Any?>>(
            mapOf("role" to "user", "content" to "go"),
            mapOf("role" to "assistant", "content" to null, "tool_calls" to listOf(mapOf("id" to "a", "type" to "function", "function" to mapOf("name" to "read_file", "arguments" to "{\"path\":\"app.js\"}")))),
            mapOf("role" to "tool", "tool_call_id" to "a", "content" to big),
            mapOf("role" to "assistant", "content" to null, "tool_calls" to listOf(mapOf("id" to "b", "type" to "function", "function" to mapOf("name" to "write_file", "arguments" to "{\"path\":\"app.js\",\"content\":\"new\"}")))),
            mapOf("role" to "tool", "tool_call_id" to "b", "content" to "Wrote app.js"))
        assertEquals(1, StaleReads.clear(m))
        assertTrue((m[2]["content"] as String).startsWith("[Earlier contents of app.js"))
        assertEquals("Wrote app.js", m[4]["content"])
        assertEquals(0, StaleReads.clear(m))
    }

    @Test fun staleReadsAnthropicAndKeepsFreshReads() {
        val m = mutableListOf<Map<String, Any?>>(
            mapOf("role" to "user", "content" to "go"),
            mapOf("role" to "assistant", "content" to listOf(mapOf("type" to "tool_use", "id" to "a", "name" to "read_file", "input" to mapOf("path" to "a.js")), mapOf("type" to "tool_use", "id" to "c", "name" to "read_file", "input" to mapOf("path" to "b.js")))),
            mapOf("role" to "user", "content" to listOf(mapOf("type" to "tool_result", "tool_use_id" to "a", "content" to big), mapOf("type" to "tool_result", "tool_use_id" to "c", "content" to big))),
            mapOf("role" to "assistant", "content" to listOf(mapOf("type" to "tool_use", "id" to "b", "name" to "edit_file", "input" to mapOf("path" to "a.js")))),
            mapOf("role" to "user", "content" to listOf(mapOf("type" to "tool_result", "tool_use_id" to "b", "content" to "Edited"))))
        assertEquals(1, StaleReads.clear(m))
        val r = m[2]["content"] as List<*>
        assertTrue(((r[0] as Map<*, *>)["content"] as String).contains("left out"))
        assertEquals(big, (r[1] as Map<*, *>)["content"])
    }

    @Test fun smallSavingsAreLeftAlone() {
        val m = mutableListOf<Map<String, Any?>>(
            mapOf("role" to "assistant", "content" to listOf(mapOf("type" to "tool_use", "id" to "a", "name" to "read_file", "input" to mapOf("path" to "a.js")))),
            mapOf("role" to "user", "content" to listOf(mapOf("type" to "tool_result", "tool_use_id" to "a", "content" to "y".repeat(500)))),
            mapOf("role" to "assistant", "content" to listOf(mapOf("type" to "tool_use", "id" to "b", "name" to "write_file", "input" to mapOf("path" to "a.js")))))
        assertEquals(0, StaleReads.clear(m))
    }

    @Test fun modelCapsParseAndRoundTrip() {
        val json = """{"data":[{"id":"claude-x","max_tokens":64000,"capabilities":{"image_input":{"supported":true},"pdf_input":{"supported":false}}},{"id":"claude-y","max_tokens":4096}],"has_more":false}"""
        val p = ModelCaps.parse(json)
        assertEquals(64000, p.getValue("claude-x").maxTokens)
        assertEquals(true, p.getValue("claude-x").image); assertEquals(false, p.getValue("claude-x").pdf)
        assertNull(p.getValue("claude-y").image)
        var saved = ""
        ModelCaps.onChange = { saved = it }
        ModelCaps.merge(p)
        assertEquals(64000, ModelCaps.maxTokens("claude-x"))
        ModelCaps.load(saved)
        assertEquals(4096, ModelCaps.maxTokens("claude-y")); assertEquals(true, ModelCaps.image("claude-x"))
        ModelCaps.onChange = null
        ModelCaps.load("")
    }

    @Test fun cacheLifetime() {
        PromptCache.ttl = "5m"
        assertNull((PromptCache.systemBlocks("s")[0]["cache_control"] as Map<*, *>)["ttl"])
        PromptCache.ttl = "1h"
        assertEquals("1h", (PromptCache.systemBlocks("s")[0]["cache_control"] as Map<*, *>)["ttl"])
        PromptCache.ttl = "5m"
    }
}
