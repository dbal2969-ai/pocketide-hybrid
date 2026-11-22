package dev.pocketide.app

import dev.pocketide.app.core.Memory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderRulesTest {
    private val files = mapOf("game/AGENTS.md" to "Use Phaser 3 only.", "web/POCKETIDE.md" to "Tabs, not spaces.", "AGENTS.md" to "root", "game/js/x.js" to "")

    @Test fun includesFolderRules() {
        val s = Memory.folderSections(files.keys, { files[it] }, listOf("", "game/js/x.js"))
        assertTrue(s.indexOf("game/AGENTS.md") < s.indexOf("web/POCKETIDE.md"))
        assertTrue(s.contains("Rules for files under game/"))
        assertTrue(!s.contains("root"))
    }

    @Test fun manyFilesOnlyTheFocusedOnes() {
        val many = (1..6).associate { "f$it/AGENTS.md" to "rule $it" }
        val s = Memory.folderSections(many.keys, { many[it] }, listOf("f3"))
        assertTrue(s.contains("rule 3"))
        assertTrue(!s.contains("rule 4"))
        assertEquals("", Memory.folderSections(listOf("a.js"), { "" }, emptyList()))
    }
}
