package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RootfsSwapTest {
    private fun tree(): Triple<File, File, File> {
        val base = Files.createTempDirectory("linux").toFile()
        val rootfs = File(base, "rootfs").apply { mkdirs(); File(this, "installed.txt").writeText("old packages") }
        val staging = File(base, "rootfs.new").apply { mkdirs(); File(this, "fresh.txt").writeText("new") }
        return Triple(base, rootfs, staging)
    }

    @Test fun theNewOneReplacesTheOld() {
        val (base, rootfs, staging) = tree()
        RootfsSwap.swap(staging, rootfs, { it.deleteRecursively() })
        assertTrue(File(rootfs, "fresh.txt").isFile)
        assertFalse(File(rootfs, "installed.txt").exists())
        assertFalse(File(base, "rootfs.old").exists())
        assertFalse(staging.exists())
    }

    @Test fun aFailedMoveKeepsTheOldLinux() {
        val (_, rootfs, staging) = tree()
        val failing: (File, File) -> Boolean = { a, b -> if (a == staging) false else a.renameTo(b) }
        val e = runCatching { RootfsSwap.swap(staging, rootfs, { it.deleteRecursively() }, failing) }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(e!!.message!!.contains("still there"))
        assertEquals("old packages", File(rootfs, "installed.txt").readText())
    }

    @Test fun aFirstInstallNeedsNoOldOne() {
        val (_, rootfs, staging) = tree()
        rootfs.deleteRecursively()
        RootfsSwap.swap(staging, rootfs, { it.deleteRecursively() })
        assertTrue(File(rootfs, "fresh.txt").isFile)
    }

    @Test fun spaceIsCheckedFirst() {
        val need = RootfsSwap.neededBytes(29)
        assertNull(RootfsSwap.spaceNote(need, need + 1))
        assertTrue(RootfsSwap.spaceNote(need, 10L * 1024 * 1024)!!.contains("nothing was changed"))
    }
}

class RootfsRunningTest {
    @Test fun programsUsingTheLinuxAreFound() {
        val proc = java.nio.file.Files.createTempDirectory("proc").toFile()
        val rootfs = File("/data/user/0/dev.pocketide.gpl/files/linux/rootfs")
        File(proc, "101").apply { mkdirs(); File(this, "cmdline").writeBytes("libproot.so\u0000-r\u0000${rootfs.absolutePath}\u0000/bin/sh".toByteArray()) }
        File(proc, "102").apply { mkdirs(); File(this, "cmdline").writeBytes("app_process\u0000x".toByteArray()) }
        File(proc, "self").mkdirs()
        org.junit.Assert.assertEquals(1, RootfsSwap.runningIn(rootfs, proc))
        File(proc, "101").deleteRecursively()
        org.junit.Assert.assertEquals(0, RootfsSwap.runningIn(rootfs, proc))
    }
}
