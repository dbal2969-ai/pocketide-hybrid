package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWriterTest {
    @Test fun savesTheNewestVersionMaskedAndInOrder() {
        val dir = java.nio.file.Files.createTempDirectory("chats").toFile()
        val f = java.io.File(dir, "c1.json")
        var after = 0
        for (i in 1..20) ChatWriter.save(f, MiniJson.write(linkedMapOf("n" to i, "text" to "key ghp_" + "a".repeat(30)))) { after++ }
        ChatWriter.flush()
        val back = MiniJson.obj(MiniJson.parse(f.readText()))!!
        assertEquals("20", MiniJson.write(back["n"]))
        assertEquals("key [hidden]", back["text"])
        assertTrue(after in 1..20)
        assertFalse(java.io.File(dir, ".c1.json.tmp").exists())
    }

    /** A long chat (about 2 MB of messages and command output) masks in well under a second on a computer. */
    @Test fun aLongChatMasksQuickly() {
        val entries = (1..400).map { i -> linkedMapOf("role" to "tool", "text" to ("line $i of output with path src/app$i.js and a value x=$i\n").repeat(80)) }
        val json = MiniJson.write(linkedMapOf("id" to "long", "entries" to entries))
        val t0 = System.nanoTime()
        val out = SecretMask.maskJson(json, emptyList())
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("maskJson: ${json.length / 1024} KB in $ms ms")
        assertEquals(json.length, out.length)
        assertTrue("took $ms ms", ms < 5000)
    }
}
