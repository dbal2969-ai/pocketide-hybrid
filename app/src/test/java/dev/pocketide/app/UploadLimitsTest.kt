package dev.pocketide.app

import dev.pocketide.app.core.UploadLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UploadLimitsTest {
    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> for ((n, b) in files) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bo.toByteArray()
    }

    @Test fun importsStreamedStripsTopFolderAndSkipsUnsafeNames() {
        val dir = java.nio.file.Files.createTempDirectory("imp").toFile()
        val root = java.io.File(dir, "proj").also { it.mkdirs() }
        val store = ProjectStore(root, seed = false)
        val big = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val n = store.importZip(zip("game-main/index.html" to "<html></html>".toByteArray(), "game-main/assets/big.bin" to big,
            "game-main/../../evil.txt" to "x".toByteArray()).inputStream())
        assertEquals(2, n)
        assertTrue(java.io.File(root, "index.html").isFile)
        assertEquals(big.size.toLong(), java.io.File(root, "assets/big.bin").length())
        assertFalse(java.io.File(dir, "evil.txt").exists())
        assertTrue(dir.listFiles()!!.none { it.name.startsWith(".pocketide-import-") })
        dir.deleteRecursively()
    }

    @Test fun limitsAndNames() {
        assertTrue(UploadLimits.FILE >= 512L * 1024 * 1024 && UploadLimits.ZIP_TOTAL >= 1024L * 1024 * 1024)
        assertEquals("1 GB", UploadLimits.mb(UploadLimits.ZIP_TOTAL))
        assertEquals("512 MB", UploadLimits.mb(UploadLimits.FILE))
        assertNull(UploadLimits.safeEntry("../x"))
        assertNull(UploadLimits.safeEntry("C:/x"))
        assertEquals("a/b.txt", UploadLimits.safeEntry("/a\\b.txt"))
    }
}
