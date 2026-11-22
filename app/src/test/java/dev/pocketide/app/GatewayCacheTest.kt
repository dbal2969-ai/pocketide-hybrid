package dev.pocketide.app

import dev.pocketide.app.core.*
import org.junit.Assert.*
import org.junit.Test

class GatewayCacheTest {
    @Test fun claudeThroughACustomServerIsMarked() {
        assertTrue(PromptCache.claudeThroughGateway("anthropic/claude-opus-5-5"))
        assertTrue(PromptCache.claudeThroughGateway("claude-haiku-5-5"))
        assertFalse(PromptCache.claudeThroughGateway("moonshotai/kimi-k2.6"))
        val msgs = listOf(mapOf("role" to "user", "content" to "build it"), mapOf("role" to "assistant", "content" to "ok"), mapOf("role" to "tool", "tool_call_id" to "1", "content" to "done"))
        val b = Tools.body(false, "anthropic/claude-opus-5-5", "SYSTEM", msgs, false, cache = true)
        val json = MiniJson.write(b)
        assertTrue(json, json.contains("\"cache_control\"") && json.contains("SYSTEM"))
        @Suppress("UNCHECKED_CAST") val out = b["messages"] as List<Map<String, Any?>>
        assertEquals(4, out.size)
        assertTrue(out[1]["content"] is List<*>)              // the user message carries the mark
        assertEquals("done", out[3]["content"])               // tool results stay plain text
        val plain = MiniJson.write(Tools.body(false, "moonshotai/kimi-k2.6", "S", msgs, false, cache = true))
        assertFalse(plain.contains("cache_control"))
        assertFalse(MiniJson.write(Tools.body(false, "anthropic/claude-opus-5-5", "S", msgs, false, cache = false)).contains("cache_control"))
    }
}
