package dev.pocketide.app

import dev.pocketide.app.core.SelfFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.5.133 (analysis E6): the artifact names in this repository's build-apk.yml are the ones self-fix and the add-on download look for. */
class ArtifactNamesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, ".github/workflows/build-apk.yml").isFile }
    private val names = Regex("""(?m)^\s+name:\s*(PocketIDE-[\w.-]+)\s*$""").findAll(File(root, ".github/workflows/build-apk.yml").readText())
        .map { it.groupValues[1] }.toList()
    private fun arts() = names.mapIndexed { i, n -> SelfFix.Artifact(i.toLong() + 1, n, 1_000_000L, false) }

    @Test fun selfFixFindsTheAppArtifact() {
        val apps = names.filter { it.lowercase().endsWith("debug-apk") }
        assertEquals("one app artifact in $names", 1, apps.size)
        assertEquals(apps[0], SelfFix.pickApk(arts())?.name)
    }

    @Test fun theAddonIsThereWhenThisRepositoryBuildsIt() {
        val buildsAddon = File(root, "linux-addon").isDirectory
        assertEquals(buildsAddon, SelfFix.ADDON_ARTIFACT in names)
        if (buildsAddon) assertEquals(SelfFix.ADDON_ARTIFACT, SelfFix.pickAddon(arts())?.name)
    }

    @Test fun aRenamedArtifactIsNotGuessed() {
        assertNull(SelfFix.pickApk(listOf(SelfFix.Artifact(1, "logs", 50_000_000, false), SelfFix.Artifact(2, "something-else", 9, false))))
        assertNotNull(SelfFix.pickApk(listOf(SelfFix.Artifact(3, "PocketIDE-debug-apk", 9, false))))
        assertTrue(names.isNotEmpty())
    }
}
