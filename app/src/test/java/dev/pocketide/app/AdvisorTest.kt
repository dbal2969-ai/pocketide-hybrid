package dev.pocketide.app

import dev.pocketide.app.core.Advisor
import dev.pocketide.app.core.Advisor.Purpose
import org.junit.Assert.*
import org.junit.Test

class AdvisorTest {
    @Test fun modes() {
        assertEquals(Advisor.OFF, Advisor.parse("nope"))
        assertEquals(Advisor.AUTO, Advisor.parse("auto"))
        assertEquals("", Advisor.executorNote(Advisor.OFF))
        assertTrue(Advisor.executorNote(Advisor.AUTO).contains("may also add"))
        assertFalse(Advisor.executorNote(Advisor.ASK).contains("may also add"))
    }
    @Test fun transcriptReadsBothFormats() {
        val openai = listOf<Map<String, Any?>>(
            mapOf("role" to "user", "content" to "Build a game"),
            mapOf("role" to "assistant", "content" to null, "tool_calls" to listOf(mapOf("id" to "1", "type" to "function", "function" to mapOf("name" to "read_file", "arguments" to "{\"path\":\"a.js\"}")))),
            mapOf("role" to "tool", "tool_call_id" to "1", "content" to "var x = 1;"))
        val t = Advisor.transcript(openai)
        assertTrue(t.contains("User: Build a game")); assertTrue(t.contains("Assistant called: read_file")); assertTrue(t.contains("Tool result: var x = 1;"))
        val anth = listOf<Map<String, Any?>>(
            mapOf("role" to "user", "content" to listOf(mapOf("type" to "text", "text" to "Fix it"))),
            mapOf("role" to "assistant", "content" to listOf(mapOf("type" to "text", "text" to "Looking"), mapOf("type" to "tool_use", "id" to "1", "name" to "search", "input" to mapOf("pattern" to "x")))),
            mapOf("role" to "user", "content" to listOf(mapOf("type" to "tool_result", "tool_use_id" to "1", "content" to "found"))))
        val a = Advisor.transcript(anth)
        assertTrue(a.contains("User: Fix it")); assertTrue(a.contains("Assistant called: search")); assertTrue(a.contains("Tool result: found"))
    }
    @Test fun longTranscriptKeepsTaskAndTail() {
        val msgs = (1..400).map { mapOf<String, Any?>("role" to "user", "content" to "line $it " + "x".repeat(300)) }
        val t = Advisor.transcript(msgs, 8000)
        assertTrue(t.length < 8200); assertTrue(t.contains("line 1 ")); assertTrue(t.contains("line 400 ")); assertTrue(t.contains("left out"))
    }
    @Test fun promptsAndVerdicts() {
        assertTrue(Advisor.prompt("t", "tr", Purpose.FINISH, 120).contains("LGTM"))
        assertTrue(Advisor.prompt("t", "tr", Purpose.START, 120).contains("under 120 words"))
        assertTrue(Advisor.clear("LGTM")); assertTrue(Advisor.clear("**LGTM.**")); assertFalse(Advisor.clear("LGTM but the score never resets, the player can walk through walls"))
        assertTrue(Advisor.heldMessage("do x").contains("do x"))
    }
}
