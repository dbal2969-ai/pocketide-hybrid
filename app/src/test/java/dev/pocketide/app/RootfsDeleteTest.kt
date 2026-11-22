package dev.pocketide.app

import dev.pocketide.app.core.RootfsExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class RootfsDeleteTest {
    @Test fun deletesReadOnlyFoldersAndReportsNothingLeft() {
        val root = java.nio.file.Files.createTempDirectory("rootfs").toFile()
        val usr = File(root, "usr/lib").also { it.mkdirs() }
        File(usr, "libc.so").writeText("x")
        File(root, "etc").mkdirs(); File(root, "etc/hostname").writeText("phone")
        usr.setWritable(false, false); File(root, "usr").setWritable(false, false)
        assertEquals(0, RootfsExtractor.deleteTree(root))
        assertFalse(root.exists())
        assertEquals(0, RootfsExtractor.deleteTree(File(root, "missing")))
    }
}
