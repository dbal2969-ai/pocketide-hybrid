package dev.pocketide.app

import dev.pocketide.app.core.ToolTrace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTraceTest {
    @Test fun callShowsRoundToolArgsAndResult() {
        val e = ToolTrace.call(0L, 3, "read_file", "{\"path\":\"data/content.json\"}", "ok sk-abcdefghijklmnop", 12)
        assertTrue(e, e.contains("round 3 · tool read_file · 12ms") && e.contains("{\"path\":\"data/content.json\"}") && e.contains("→ ok sk-"))
        assertFalse(e.contains("abcdefghijklmnop"))
    }

    @Test fun longResultsAreCut() {
        val e = ToolTrace.call(0L, 1, "grep", "{}", "r".repeat(ToolTrace.MAX_RESULT + 10), 1)
        assertTrue(e.contains("10 characters left out"))
    }

    @Test fun blockKeepsTheAssistantsWords() {
        val b = ToolTrace.block("assistant said", "I will edit index.html", 4, 0L)
        assertTrue(b.contains("round 4 · assistant said") && b.contains("I will edit index.html"))
    }

    @Test fun eventIsAMarker() {
        assertTrue(ToolTrace.event("request started: fix ui").startsWith("===== "))
    }
}
