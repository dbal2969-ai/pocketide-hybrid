package dev.pocketide.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UnzipTest {
    @Test fun unpacksIntoAFolderNamedLikeTheZip() {
        val dir = File(System.getProperty("java.io.tmpdir"), "unzip" + System.nanoTime()).also { it.mkdirs() }
        val store = ProjectStore(dir, seed = false)
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            z.putNextEntry(ZipEntry("top/a.txt")); z.write("A".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("top/sub/b.txt")); z.write("B".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("../evil.txt")); z.write("X".toByteArray()); z.closeEntry()
        }
        File(dir, "pack.zip").writeBytes(bo.toByteArray())
        assertEquals(2, store.unzip("pack.zip"))
        assertEquals("A", File(dir, "pack/top/a.txt").readText())
        assertEquals("B", File(dir, "pack/top/sub/b.txt").readText())
        assertEquals(false, File(dir.parentFile, "evil.txt").exists())
        assertEquals(-3, store.unzip("missing.zip"))
        dir.deleteRecursively()
    }
}
