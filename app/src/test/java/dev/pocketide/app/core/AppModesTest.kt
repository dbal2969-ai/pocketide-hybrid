package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.84: a tool the mode cannot use is named by the app, and the model is told what the modes are. */
class AppModesTest {
    private val ask = "Run look_at_preview with test=all and report the results"

    @Test fun findsToolNamesAsWords() {
        assertEquals(listOf("look_at_preview"), AppModes.named(ask))
        assertEquals(listOf("run_command", "write_file"), AppModes.named("use `write_file` then run_command"))
        assertEquals(emptyList<String>(), AppModes.named("make the map searchable and fix read_files_later"))
    }

    @Test fun lookingIsAllowedInEveryModeWhenThereIsAPage() {
        assertTrue(AppModes.missing(ask, readOnly = true, hasPage = true, usesTools = true).isEmpty())
        assertTrue(AppModes.missing(ask, readOnly = false, hasPage = true, usesTools = true).isEmpty())
    }

    @Test fun noPageNoLook() {
        val m = AppModes.missing(ask, readOnly = false, hasPage = false, usesTools = true)
        assertEquals("look_at_preview", m.single().first)
        assertTrue(AppModes.personNote(m, "Agent").contains("no .html page"))
    }

    @Test fun askModeCannotChangeFiles() {
        val m = AppModes.missing("use write_file to save it", readOnly = true, hasPage = true, usesTools = true)
        val n = AppModes.personNote(m, "Ask")
        assertTrue(n, n.contains("write_file") && n.contains("choose Agent") && n.contains("\"Ask\""))
        assertTrue(AppModes.modelNote(m).contains("do not invent buttons"))
    }

    @Test fun aModelWithoutToolsCannotRunAny() {
        val m = AppModes.missing(ask, readOnly = true, hasPage = true, usesTools = false)
        assertTrue(AppModes.personNote(m, "Ask").contains("supports tool calling"))
    }

    @Test fun theGuideIsInBothPrompts() {
        assertTrue(Prompts.toolsSystem(true).contains("About PocketIDE"))
        assertTrue(Prompts.SYSTEM.contains("no button runs them"))
    }
}
