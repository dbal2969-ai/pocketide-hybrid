package dev.pocketide.app

import dev.pocketide.app.core.SelfFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.128: GPL builds carry the Linux add-on next to the app; self-fix must never install it as the app, and the add-on download must find it. */
class LinuxAddonArtifactTest {
    private val app = SelfFix.Artifact(1, "PocketIDE-GPL-debug-apk", 30_000_000, false)
    private val addon = SelfFix.Artifact(2, SelfFix.ADDON_ARTIFACT, 400_000, false)

    @Test fun selfFixTakesTheAppNotTheAddon() {
        assertEquals(app.id, SelfFix.pickApk(listOf(addon, app))?.id)
        assertNull(SelfFix.pickApk(listOf(addon)))
    }

    @Test fun theSourceBundleIsNeverTheAppOrTheAddon() {
        val src = SelfFix.Artifact(4, SelfFix.ADDON_ARTIFACT + "-source", 9_000_000, false)
        assertNull(SelfFix.pickApk(listOf(addon, src)))
        assertEquals(addon.id, SelfFix.pickAddon(listOf(src, addon))?.id)
    }

    @Test fun thePublicDownloadIsTheLatestRelease() {
        assertEquals("https://github.com/dbal2969-ai/pocketide-linux-addon/releases/latest/download/PocketIDE-Linux-addon.apk", LinuxAddon.PUBLIC_URL)
    }

    @Test fun theAddonIsFoundByName() {
        assertEquals(addon.id, SelfFix.pickAddon(listOf(app, addon))?.id)
        assertNull(SelfFix.pickAddon(listOf(app)))
        assertNull(SelfFix.pickAddon(listOf(SelfFix.Artifact(3, SelfFix.ADDON_ARTIFACT, 400_000, expired = true))))
    }

    @Test fun addonBuildsComeFromTheApkWorkflowOnMain() {
        val p = SelfFix.buildRunsPath("o/r")
        assertTrue(p.contains("/workflows/build-apk.yml/runs") && p.contains("branch=main") && p.contains("status=success"))
    }
}
