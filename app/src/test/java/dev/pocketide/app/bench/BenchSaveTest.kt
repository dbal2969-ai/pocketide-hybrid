package dev.pocketide.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BenchSaveTest {
    @Test fun resultsGoToTheChosenFolder() {
        val fallback = Files.createTempDirectory("bench-build").toFile()
        val kept = File(Files.createTempDirectory("bench-kept").toFile(), "runs/kimi")
        assertEquals(kept, BenchRunner.saveDir(fallback, kept.path))
        assertTrue("created", kept.isDirectory)
        assertEquals(fallback, BenchRunner.saveDir(fallback, null))
        assertEquals(fallback, BenchRunner.saveDir(fallback, "  "))
    }

    @Test fun theAccountIdIsNotSaved() {
        val line = "error: https://api.cloudflare.com/client/v4/accounts/0123456789abcdef0123456789abcdef/ai/v1/chat/completions did not answer"
        val r = BenchRunner.redact(line)
        assertFalse(r, r.contains("0123456789abcdef"))
        assertTrue(r, r.contains("/accounts/<id>/ai/v1"))
        assertEquals("no url here", BenchRunner.redact("no url here"))
    }

    @Test fun credentialsAreNotSavedEvenIfAnErrorEchoesThem() {
        val fake = "benchmark-test-not-a-real-key"
        val r = BenchRunner.redact("Authorization: Bearer $fake; $fake", listOf(fake))
        assertFalse(r, r.contains(fake))
        assertEquals("Authorization: Bearer <key>; <key>", r)
    }

    @Test fun anUnfinishedRunSavesItsStepsSoFar() {
        val task = BenchTasks.ALL.first()
        val calls = listOf(dev.pocketide.app.core.ToolCall("1", "read_file", """{"path": "src/cart.js"}""") to "function total() {}")
        val t = BenchRunner.partial(task, calls, 2, 14)
        assertTrue(t, t.startsWith("# ${task.name}") && t.contains("## 1. read_file") && t.contains("function total() {}"))
        assertTrue(t, t.contains("Not finished yet") && t.contains("2 requests and 1 tool calls after 14s"))
    }
}
