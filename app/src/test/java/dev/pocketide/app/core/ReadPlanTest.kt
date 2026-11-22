package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.99: files that fit are sent whole; the thinking-effort field is optional and never stops a run. */
class ReadPlanTest {
    @Test fun aFileThatFitsIsSentWhole() {
        val text = (1..900).joinToString("\n") { "const line$it = $it;" }
        assertTrue(ReadPlan.whole(text, 120_000))
        assertFalse("too big for the reply", ReadPlan.whole(text, 10_000))
        assertFalse("too many lines to take in at once", ReadPlan.whole((1..2500).joinToString("\n") { "x" }, 1_000_000))
        assertTrue(ReadPlan.wholeNote("game/main.js", text, 300, 450).startsWith("The whole of game/main.js (900 lines) is below (you asked for lines 300-450)"))
        assertTrue(ReadPlan.seen("game/main.js", 300, null).contains("Lines 300-599 are in that read"))
    }

    @Test fun thinkingEffortIsSentOnlyWhenChosenAndDroppedWhenRefused() {
        ThinkEffort.clearForTests()
        assertEquals(emptyMap<String, Any?>(), ThinkEffort.extra("", false, "cloudflare", "@cf/moonshotai/kimi-k2.6"))
        assertEquals(mapOf("reasoning_effort" to "low"), ThinkEffort.extra("Low", false, "cloudflare", "@cf/moonshotai/kimi-k2.6"))
        assertEquals(mapOf("reasoning_effort" to "none"), ThinkEffort.extra("none", false, "ollama", "qwen3.5:4b"))   // 1.5.182: thinking off
        assertEquals("Anthropic has its own", emptyMap<String, Any?>(), ThinkEffort.extra("low", true, "anthropic", "claude"))
        assertTrue(ThinkEffort.rejects(400, "Unrecognized request argument supplied: reasoning_effort"))
        assertFalse(ThinkEffort.rejects(429, "reasoning_effort rate limit"))
        assertFalse(ThinkEffort.rejects(400, "messages: too long"))
        ThinkEffort.reject("groq", "llama")
        assertEquals(emptyMap<String, Any?>(), ThinkEffort.extra("low", false, "groq", "llama"))
        assertEquals(mapOf("reasoning_effort" to "high"), ThinkEffort.extra("high", false, "groq", "openai/gpt-oss-120b"))
        ThinkEffort.clearForTests()
    }
}
