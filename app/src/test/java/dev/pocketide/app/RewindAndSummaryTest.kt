package dev.pocketide.app

import dev.pocketide.app.core.Checkpoints
import dev.pocketide.app.core.WorkSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RewindAndSummaryTest {
    @Test fun checkpointRemembersTheChat() {
        val dir = java.nio.file.Files.createTempDirectory("cp").toFile()
        val c = Checkpoints(dir)
        c.save("AI turn", mapOf("a.js" to "x".toByteArray()), 1000L, chat = "chat-42:6")
        c.save("Old turn", mapOf("b.js" to null), 900L)
        val list = c.list()
        assertEquals("chat-42" to 6, list[0].chatMark)
        assertNull(list[1].chatMark)
        dir.deleteRecursively()
    }

    @Test fun summaryFactsFromBothFormats() {
        val anthropic = listOf(
            mapOf("role" to "user", "content" to "make a game"),
            mapOf("role" to "assistant", "content" to listOf(
                mapOf("type" to "text", "text" to "Writing files"),
                mapOf("type" to "tool_use", "id" to "1", "name" to "write_file", "input" to mapOf("path" to "game.js", "content" to "x")),
                mapOf("type" to "tool_use", "id" to "2", "name" to "run_command", "input" to mapOf("command" to "node --check game.js")))))
        val openai = listOf(mapOf("role" to "assistant", "content" to null, "tool_calls" to listOf(
            mapOf("id" to "1", "type" to "function", "function" to mapOf("name" to "edit_file", "arguments" to """{"path":"index.html"}""")))))
        val f = WorkSummary.facts(anthropic + openai)
        assertTrue(f.contains("game.js, index.html"))
        assertTrue(f.contains("node --check game.js"))
        val t = WorkSummary.transcript(anthropic)
        assertTrue(t.startsWith("user: make a game") && t.contains("[called write_file"))
        assertTrue(WorkSummary.note(f, "Built the board.").contains("Built the board."))
    }
}
