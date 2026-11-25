package dev.pocketide.app.core

import dev.pocketide.app.HybridAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's touch-controls run (2026-11-25): how Gemini Nano's edits failed, and what the app does about each. */
class NanoEditsTest {
    private val game = "const canvas = document.getElementById('canvas');\nconst ctx = canvas ? canvas.getContext('2d') : null;\n\nconst GRID_SIZE = 20;\n"

    @Test fun aRepeatedDeclarationAfterTheSearchIsNotAddedTwice() {
        val search = "const canvas = document.getElementById('canvas');"
        val replace = search + "\nconst ctx = canvas ? canvas.getContext('2d') : null;\n\n// touch\nlet touchStartX = 0;"
        val r = EditBlocks.apply(game, EditBlock("game.js", search, replace))
        assertEquals(1, Regex("const ctx").findAll(r.text!!).count())
        assertTrue(r.text!!, r.text!!.contains("let touchStartX = 0;\nconst ctx"))
    }

    @Test fun ordinaryReplacementsAreUntouched() {
        val r = EditBlocks.apply(game, EditBlock("game.js", "const GRID_SIZE = 20;", "const GRID_SIZE = 24;"))
        assertEquals(game.replace("20;", "24;"), r.text)
    }

    @Test fun dotsInTheSearchAreExplained() {
        val r = EditBlocks.apply(game, EditBlock("game.js", "document.addEventListener('keydown', function(e) { ... })", "x"))
        assertTrue(r.error!!, r.error!!.contains("stands for skipped code") && r.error!!.contains("edit_lines"))
    }

    @Test fun nanoIsToldHowToInsert() {
        val c = Tools.smallCatalogue(false)
        assertTrue(c, c.contains("end = start - 1"))
        assertTrue(SmallLocal.system(c).contains("To ADD new code"))
    }

    @Test fun toolsNamedInOtherWordsAreRecognised() {
        assertEquals("read_file", HybridAgent.toolFromArgs("membaca_file", setOf("path")))
        assertEquals("read_file", HybridAgent.toolFromArgs("讀取文件", setOf("path")))
        assertEquals("edit_file", HybridAgent.toolFromArgs("bearbeiten", setOf("path", "search", "replace")))
        assertEquals("run_command", HybridAgent.toolFromArgs("ejecutar", setOf("command")))
        assertEquals("read_file", HybridAgent.toolFromArgs("readFile", emptySet()))
        assertNull(HybridAgent.toolFromArgs("dance", setOf("tempo")))
    }
}
