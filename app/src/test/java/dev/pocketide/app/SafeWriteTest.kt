package dev.pocketide.app

import dev.pocketide.app.core.SafeWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class SafeWriteTest {
    @Test fun replacesContentAndLeavesNoSideFile() {
        val d = File(System.getProperty("java.io.tmpdir"), "safewrite" + System.nanoTime()).also { it.mkdirs() }
        val f = File(d, "sub/a.txt")
        SafeWrite.write(f, "one")
        SafeWrite.write(f, "two ünï")
        assertEquals("two ünï", f.readText())
        assertEquals(listOf("a.txt"), f.parentFile.list()!!.toList())
        assertFalse(File(f.parentFile, ".a.txt.tmp").exists())
        d.deleteRecursively()
    }
}
