package dev.pocketide.app

import dev.pocketide.app.core.MiniJson
import dev.pocketide.app.core.Tools
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandToolsTest {
    private fun names(tools: List<Map<String, Any?>>) = tools.map { MiniJson.obj(it["function"])!!["name"] as String }

    @Test fun fullListWhenNothingIsHeld() {
        val all = names(Tools.openAiTools(true))
        assertFalse(all.contains("use_tool"))
        assertTrue(all.contains("doc_edit") && all.contains("read_file"))
    }

    @Test fun rarelyUsedToolsAreReplacedByOneEntry() {
        val held = names(Tools.openAiTools(true, emptySet()))
        assertTrue(held.contains("use_tool"))
        for (n in listOf("doc_edit", "doc_outline", "project_audit")) assertFalse(n, held.contains(n))
        for (n in listOf("read_file", "edit_file", "search", "run_command", "update_plan")) assertTrue(n, held.contains(n))
        assertTrue(Tools.toolChars(true, emptySet()) < Tools.toolChars(true))
    }

    @Test fun aLoadedToolIsSentInFull() {
        val held = names(Tools.openAiTools(true, setOf("doc_edit")))
        assertTrue(held.contains("doc_edit"))
        assertFalse(held.contains("doc_outline"))
    }

    @Test fun everyHeldToolIsLoadedAndTheEntryGoes() {
        val all = names(Tools.openAiTools(true, Tools.heldNames(true).toSet()))
        assertFalse(all.contains("use_tool"))
    }

    @Test fun describeOnlyKnownHeldTools() {
        assertNotNull(Tools.describe("doc_edit", true))
        assertNull(Tools.describe("doc_edit", false))
        assertNull(Tools.describe("read_file", true))
        assertNull(Tools.describe("nope", true))
    }
}
