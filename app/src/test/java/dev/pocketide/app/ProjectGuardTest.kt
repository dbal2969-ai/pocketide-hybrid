package dev.pocketide.app

import dev.pocketide.app.core.ProjectGuard
import org.junit.Assert.*
import org.junit.Test

class ProjectGuardTest {
    @Test fun heavyFolders() {
        assertTrue(ProjectGuard.isHeavy("node_modules")); assertFalse(ProjectGuard.isHeavy("src"))
    }
    @Test fun noteExplainsMissingIsNotProof() {
        val n = ProjectGuard.listNote(mapOf("node_modules" to 2461), true, 2000)
        assertTrue(n.contains("node_modules/ (2461 files)")); assertTrue(n.contains("first 2000 files")); assertTrue(n.contains("never recreate"))
        assertEquals("", ProjectGuard.listNote(emptyMap(), false, 2000))
    }
    @Test fun browserInstallsAreRefused() {
        assertNotNull(ProjectGuard.installRefusal("cd /work && npm install puppeteer-core"))
        assertNotNull(ProjectGuard.installRefusal("apk add chromium"))
        assertNotNull(ProjectGuard.installRefusal("npx playwright install"))
        assertNull(ProjectGuard.installRefusal("npm install express"))
        assertNull(ProjectGuard.installRefusal("node -c game.js"))
    }
    @Test fun engineReadsAreRefused() {
        assertTrue(ProjectGuard.readsEngine("grep -o version engine/phaser.min.js"))
        assertTrue(ProjectGuard.readsEngine("cat engine/three.module.min.js | head"))
        assertFalse(ProjectGuard.readsEngine("node -c game.js"))
        assertFalse(ProjectGuard.readsEngine("ls engine/"))
    }
}
