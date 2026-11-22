package dev.pocketide.app

import dev.pocketide.app.core.ProjectGuard
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveGuardTest {
    private val big = { p: String -> if (p.endsWith("cache.zip")) 130L * 1024 * 1024 else 1024L }

    @Test fun refusesBigArchiveNotNamedInTheRequest() {
        assertNotNull(ProjectGuard.archiveRefusal("unzip cache.zip -d out", "add a quest log", big))
        assertNotNull(ProjectGuard.archiveRefusal("tar xf data/cache.zip", "fix the HUD", big))
    }
    @Test fun allowsNamedSmallAndNonExtractCommands() {
        assertNull(ProjectGuard.archiveRefusal("unzip cache.zip", "please unzip cache.zip and list it", big))
        assertNull(ProjectGuard.archiveRefusal("unzip small.zip", "anything", big))
        assertNull(ProjectGuard.archiveRefusal("unzip -l cache.zip".replace("unzip -l", "ls -la"), "anything", big))
    }
}
