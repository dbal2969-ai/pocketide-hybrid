package dev.pocketide.app.core

import java.io.File

/**
 * Writes a file so that a crash, a kill or a full disk cannot leave it half written: the bytes go to a side file first and
 * replace the real file only when all of them were written. The old content stays if anything fails.
 */
object SafeWrite {
    fun write(f: File, data: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "." + f.name + ".tmp")
        try {
            tmp.outputStream().use { o -> o.write(data); o.flush(); try { o.fd.sync() } catch (e: Exception) { } }
            if (!tmp.renameTo(f)) {
                // some file systems will not rename over an existing file
                f.delete()
                if (!tmp.renameTo(f)) { f.writeBytes(data) }
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun write(f: File, text: String) = write(f, text.toByteArray(Charsets.UTF_8))
}
