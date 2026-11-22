package dev.pocketide.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** 1.5.133 (analysis T3): the add-on's PRoot is used only when the add-on is installed, complete, and signed like this app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinuxAddonDirTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val mine = Signature("0a0b0c0d")
    private val theirs = Signature("01020304")

    private fun install(pkg: String, sig: Signature, libDir: File?) {
        val pi = PackageInfo().apply {
            packageName = pkg
            @Suppress("DEPRECATION")
            signatures = arrayOf(sig)
            applicationInfo = ApplicationInfo().apply { packageName = pkg; nativeLibraryDir = libDir?.absolutePath ?: "/nonexistent" }
        }
        shadowOf(ctx.packageManager).installPackage(pi)
    }

    private fun libs(vararg names: String): File = Files.createTempDirectory("addon").toFile().also { d -> names.forEach { File(d, it).writeText("x") } }
    private val all = arrayOf("libproot.so", "libproot-loader.so", "libtalloc.so")

    @Test fun notInstalled() {
        install(ctx.packageName, mine, null)
        assertNull(LinuxAddon.addonDir(ctx))
        assertFalse(LinuxAddon.installed(ctx))
    }

    @Test fun signedLikeThisAppAndComplete() {
        install(ctx.packageName, mine, null)
        val d = libs(*all)
        install(LinuxAddon.PACKAGE, mine, d)
        assertEquals(d.absolutePath, LinuxAddon.addonDir(ctx)?.absolutePath)
    }

    @Test fun signedBySomeoneElse() {
        install(ctx.packageName, mine, null)
        install(LinuxAddon.PACKAGE, theirs, libs(*all))
        assertTrue(LinuxAddon.installed(ctx))
        assertNull(LinuxAddon.addonDir(ctx))
        assertTrue(LinuxAddon.missingNote(ctx).contains("not signed like this app"))
    }

    @Test fun missingAFile() {
        install(ctx.packageName, mine, null)
        install(LinuxAddon.PACKAGE, mine, libs("libproot.so", "libtalloc.so"))
        assertNull(LinuxAddon.addonDir(ctx))
    }
}
