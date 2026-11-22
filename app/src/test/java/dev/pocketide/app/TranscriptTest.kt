package dev.pocketide.app

import dev.pocketide.app.core.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {
    @Test fun shortOutputIsKeptWhole() {
        assertEquals("a\nb", Transcript.cap("a\nb"))
    }

    @Test fun longOutputKeepsStartAndEnd() {
        val out = "H".repeat(60) + "M".repeat(100) + "T".repeat(40)
        val c = Transcript.cap(out, max = 100)
        assertTrue(c.startsWith("H".repeat(60)))
        assertTrue(c.endsWith("T".repeat(40)))
        assertTrue(c.contains("100 characters left out"))
    }

    @Test fun entryHasCommandExitAndOutputAndMasksKeys() {
        val e = Transcript.entry(0L, "npm test", 1, "fail sk-abcdefghijklmnop\n", 3)
        assertTrue(e.contains("exit 1 · 3s") && e.contains("\$ npm test") && e.contains("fail sk-"))
        assertFalse(e.contains("abcdefghijklmnop"))
        assertTrue(Transcript.entry(0L, "true", 0, "", 0).contains("(no output)"))
    }
}
