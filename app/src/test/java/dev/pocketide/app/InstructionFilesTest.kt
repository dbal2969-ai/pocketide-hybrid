package dev.pocketide.app

import dev.pocketide.app.core.Memory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionFilesTest {
    private val files = mapOf(
        "CLAUDE.md" to "Use tabs.",
        "GEMINI.md" to "Prefer small functions.",
        ".github/instructions/ts.instructions.md" to "---\napplyTo: \"src/**/*.ts,*.tsx\"\n---\nUse strict TypeScript.",
        ".github/instructions/all.instructions.md" to "---\napplyTo: \"**\"\n---\nWrite tests.",
        ".github/instructions/py.instructions.md" to "---\napplyTo: \"**/*.py\"\n---\nUse type hints."
    )

    @Test fun readsClaudeAndGeminiFiles() {
        val s = Memory.sections { files[it] }
        assertTrue(s.contains("Use tabs.") && s.contains("Prefer small functions."))
    }

    @Test fun scopedInstructionsFollowApplyTo() {
        assertEquals(listOf("src/**/*.ts", "*.tsx"), Memory.applyTo(files.getValue(".github/instructions/ts.instructions.md")))
        val s = Memory.scopedSections(files.keys, { files[it] }, listOf("", "src/app/main.ts"))
        assertTrue(s.contains("Use strict TypeScript."))
        assertTrue(s.contains("Write tests."))
        assertFalse(s.contains("Use type hints."))
        assertFalse(s.contains("applyTo:"))
    }
}
