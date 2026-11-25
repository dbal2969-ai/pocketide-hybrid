package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.185 (from-scratch run, the to-do list from the chat): Gemini Nano took the Chat home for a project and tried to write files. */
class ChatHomeNanoTest {
    @Test fun noWriteToolsAndTheNoProjectNote() {
        val c = Tools.smallCatalogue(false, false, true)
        for (t in listOf("- write_file", "- edit_file", "- edit_lines", "- insert_lines", "- run_command")) assertFalse(t, c.contains(t))
        assertTrue(c.contains("- read_file") && c.contains("- answer"))
        assertTrue(Tools.smallCatalogue(false).contains("- write_file"))
        assertTrue(ChatHome.NOTE.trimStart().startsWith(ChatHome.LABEL_NOTE))
        assertTrue(SmallLocal.next(SmallLocal.State(false, true, false, false, false, chatHome = true)).contains("Build this as a project"))
    }

    @Test fun onlyFilesTheChatNamesAreSentAsContext() {
        val files = listOf(ProjectFile("index.html", "<html>", false), ProjectFile("game.js", "iso()", false), ProjectFile("notes/recipe.txt", "x", false))
        assertEquals(emptyList<String>(), ChatHome.named(files, "Make a to-do list app").map { it.path })
        assertEquals(listOf("notes/recipe.txt"), ChatHome.named(files, "Summarise recipe.txt please").map { it.path })
        assertEquals(listOf("game.js"), ChatHome.named(files, "what does game.js do").map { it.path })
    }
}
