package dev.pocketide.app

import dev.pocketide.app.core.Checkpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class RoundCheckpointsTest {
    @Test fun rewindToAMiddleRound() {
        val dir = File(System.getProperty("java.io.tmpdir"), "cp-" + System.nanoTime())
        try {
            val c = Checkpoints(dir, maxCount = Checkpoints.KEEP)
            val files = HashMap<String, String>()
            files["a.js"] = "a0"
            // round 1 changes a.js, round 2 creates b.js and changes a.js again, then the tail changes b.js
            c.save("Build · round 1", mapOf("a.js" to "a0".toByteArray()), now = 1000); files["a.js"] = "a1"
            c.save("Build · round 2", mapOf("a.js" to "a1".toByteArray(), "b.js" to null), now = 2000); files["a.js"] = "a2"; files["b.js"] = "b2"
            c.save("Build · last changes", mapOf("b.js" to "b2".toByteArray()), now = 3000); files["b.js"] = "b3"
            val round2 = c.list().first { it.label.endsWith("round 2") }
            c.restore(round2.id, { p, b -> files[p] = String(b); true }, { p -> files.remove(p) })
            assertEquals("round 1's work stays", "a1", files["a.js"])
            assertNull("the file round 2 created is gone", files["b.js"])
            c.restore(c.list().first { it.label.endsWith("round 1") }.id, { p, b -> files[p] = String(b); true }, { p -> files.remove(p) })
            assertEquals("the first round undoes the whole request", "a0", files["a.js"])
        } finally { dir.deleteRecursively() }
    }

    @Test fun labels() {
        assertEquals("Build the game · round 4: main.js, ui.js and 2 more", Checkpoints.roundLabel("Build the game", 4, listOf("src/main.js", "src/ui.js", "a", "b")))
        assertEquals("X · round 1: a.js", Checkpoints.roundLabel("X", 1, listOf("a.js")))
    }
}
