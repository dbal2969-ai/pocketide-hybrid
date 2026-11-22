package dev.pocketide.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.5.155 (emulator check for Copilot's R8): the app's look is always dark and never reads the system's night mode, so a night-mode
 * switch (by hand, or automatic at sunset) must not recreate the screen; it did, and threw an open file back to the home page.
 */
class ManifestConfigTest {
    @Test fun nightModeAndRotationDoNotRecreateTheScreens() {
        val m = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        val acts = Regex("""<activity\b[^>]*>""").findAll(m).map { it.value }.toList()
        assertTrue(acts.size >= 2)
        for (a in acts.filter { it.contains("MainActivity") || it.contains("TerminalActivity") }) {
            val changes = Regex("""android:configChanges="([^"]*)"""").find(a)?.groupValues?.get(1).orEmpty().split('|')
            for (c in listOf("orientation", "screenSize", "uiMode")) assertTrue("$c in $a", c in changes)
        }
    }
}
