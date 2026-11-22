package dev.pocketide.app

import dev.pocketide.app.core.Parts
import dev.pocketide.app.core.PartsContract
import dev.pocketide.app.core.Requirements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartsContractTest {
    // the agreement from the world streaming run, written in the checkable form
    private val agreement = """
        game/index.html loads main.js as a module. ui.js owns the preview screen.
        LOADS game/index.html: main.js
        EXPORTS game/content.js: Content
        EXPORTS game/npcspawns.js: ActorManager
        PAGE game/index.html: preview, p-bar, p-note, mini
        DATA game/npcspawns.js: data/spawns.json
    """.trimIndent()

    @Test fun parsesTheCheckableLines() {
        val c = PartsContract.parse(agreement)
        assertEquals(listOf("main.js"), c.loads["game/index.html"])
        assertEquals(listOf("ActorManager"), c.exports["game/npcspawns.js"])
        assertEquals(listOf("preview", "p-bar", "p-note", "mini"), c.pages["game/index.html"])
        assertFalse(c.isEmpty)
        assertTrue(PartsContract.parse("just words").isEmpty)
    }

    @Test fun reviewFindsFilesNoPartWrites() {
        val parts = listOf(Parts.Part("UI", listOf(1), listOf("game/ui.js", "game/main.js")), Parts.Part("World", listOf(2), listOf("game/npcspawns.js", "game/content.js")))
        val probs = PartsContract.review(PartsContract.parse(agreement), { p -> parts.any { Parts.owns(it.files, p) } }, { false })
        assertTrue(probs.toString(), probs.any { it.startsWith("game/index.html") })        // the page belongs to nobody
        assertTrue(probs.toString(), probs.any { it.startsWith("data/spawns.json") })       // nobody writes the spawn data
        assertTrue(PartsContract.review(PartsContract.parse(""), { true }, { true }).single().contains("no LOADS"))
    }

    @Test fun checkComparesTheBuiltFilesWithTheAgreement() {
        val files = mapOf(
            "game/index.html" to "<canvas id=\"mini\"></canvas><script type=\"module\" src=\"main.js\"></script>",
            "game/main.js" to "", "game/content.js" to "export class Content {}", "game/npcspawns.js" to "export class Actors {}")
        val found = PartsContract.check(PartsContract.parse(agreement), { it in files }, { files[it] })
        assertTrue(found.toString(), found.any { it.contains("should export ActorManager") && it.contains("Actors") })
        assertTrue(found.toString(), found.any { it.contains("#preview, #p-bar, #p-note") })
        assertTrue(found.toString(), found.any { it.startsWith("data/spawns.json") })
        assertFalse(found.toString(), found.any { it.contains("should load main.js") })
    }

    // 1.5.122, from a field run: a plain-script page shares its functions through window, and that counts as exporting them
    @Test fun namesSharedThroughWindowOrCommonJsCount() {
        val agr = "LOADS index.html: js/recipes.js\nEXPORTS js/recipes.js: getData, addRow, deleteRecipe\nEXPORTS js/store.js: load, save, Store"
        val files = mapOf("index.html" to "<script src=\"js/recipes.js\"></script>",
            "js/recipes.js" to "(function () {\n  function getData() {}\n  function addRow() {}\n  function deleteRecipe() {}\n  window.getData = getData;\n  window.addRow = addRow;\n  window.deleteRecipe = deleteRecipe;\n})();",
            "js/store.js" to "function load() {}\nfunction save() {}\nObject.assign(globalThis, { Store: {} });\nif (typeof module !== 'undefined') module.exports = { load, save: save };")
        val found = PartsContract.check(PartsContract.parse(agr), { it in files }, { files[it] })
        assertFalse(found.toString(), found.any { it.contains("should export") })
        // a name that is really missing is still reported
        val missing = PartsContract.check(PartsContract.parse("EXPORTS js/recipes.js: getData, importRecipe"), { it in files }, { files[it] })
        assertTrue(missing.toString(), missing.single().contains("should export importRecipe"))
        // a comparison is not an assignment
        assertFalse(dev.pocketide.app.core.GameCheck.sharedOf("if (window.ready == true) go();").contains("ready"))
    }

    @Test fun theVerdictDecides() {
        val pass = PartsContract.verdict("Matches: all\nLeft out: nothing\nDoesn't fit: nothing\nR1: met (game/ui.js)\nR2: met (game/world.js)", 2)
        assertTrue(pass.passed)
        val fail = PartsContract.verdict("Left out: nothing\nDoesn't fit: main.js looks up ActorManager in content.js\nR1: met\nR2: not met (no spawns)", 2)
        assertFalse(fail.passed)
        assertEquals(listOf(2), fail.unmet)
        val s = fail.summary(listOf("UI works", "NPCs spawn"))
        assertTrue(s, s.contains("Requirement 2 is not met: NPCs spawn") && s.contains("Does not fit: main.js looks up ActorManager"))
        assertEquals(listOf(2), PartsContract.verdict("R1: met", 2).unanswered)
        assertTrue(PartsContract.verdict("Left out: nothing\nChanged: nothing", 3).passed)   // no verdict lines: only the fields decide
    }

    @Test fun promptsAskForTheCheckableForm() {
        assertTrue(Parts.requestParallel("t", listOf("a", "b")).contains("LOADS <page or file>"))
        assertTrue(Requirements.check("t", listOf("a")).contains("R1: met"))
        assertTrue(Parts.wholeCheckPrompt("t", listOf("a"), listOf("x.js"), "", listOf("x.js should export Y")).contains("- x.js should export Y"))
    }

    @Test fun stateIsValidJson() {
        val json = PartsContract.stateJson("build it", listOf("a"), listOf(Parts.Part("UI", listOf(1), listOf("ui.js"))), listOf("done"), "LOADS a: b", emptyList(), null, null, "planned", 1L)
        val o = dev.pocketide.app.core.MiniJson.obj(dev.pocketide.app.core.MiniJson.parse(json))!!
        assertEquals("planned", o["stage"]); assertEquals("build it", o["request"])
    }
}
