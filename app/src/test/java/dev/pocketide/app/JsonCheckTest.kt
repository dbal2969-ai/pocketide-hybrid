package dev.pocketide.app

import dev.pocketide.app.core.JsonCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonCheckTest {
    @Test fun validJsonIsSilent() { assertEquals("", JsonCheck.note("data/content.json", "{\"quests\":[]}")) }

    @Test fun brokenJsonGetsAWarning() {
        val n = JsonCheck.note("data/content.json", "{\"quests\": [")
        assertTrue(n, n.contains("WARNING: data/content.json is not valid JSON"))
    }

    @Test fun otherFilesAreNotChecked() { assertEquals("", JsonCheck.note("main.js", "{{{")) }
}
