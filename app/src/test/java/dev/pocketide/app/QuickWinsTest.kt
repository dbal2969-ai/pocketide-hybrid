package dev.pocketide.app

import dev.pocketide.app.core.McpPresets
import dev.pocketide.app.core.ToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickWinsTest {
    @Test fun newPresetsAreOffered() {
        val names = McpPresets.ALL.map { it.name }
        assertTrue(names.containsAll(listOf("deepwiki", "perplexity", "ref")))
        assertTrue(McpPresets.ALL.first { it.name == "deepwiki" }.let { !it.needsToken && it.url.startsWith("https://") })
    }

    @Test fun aKeyCanGoInTheAddress() {
        assertEquals("https://api.ref.tools/mcp?apiKey=a%2Bb" to "", McpPresets.withToken("https://api.ref.tools/mcp?apiKey={token}", " a+b "))
        assertEquals("https://x/mcp" to "k", McpPresets.withToken("https://x/mcp", "k"))
        assertEquals("https://x/mcp?apiKey={token}" to "", McpPresets.withToken("https://x/mcp?apiKey={token}", ""))
    }

    @Test fun newToolsInstallOnBothLinuxBases() {
        for (id in listOf("yq", "gh", "ast-grep")) {
            val t = ToolCatalog.byId(id)
            assertNotNull(id, t)
            assertNotNull("$id on Alpine", ToolCatalog.packages(t!!, "alpine"))
            assertNotNull("$id on Ubuntu", ToolCatalog.packages(t, "ubuntu"))
        }
        val ub = ToolCatalog.installCommand(listOf(ToolCatalog.byId("yq")!!), "ubuntu")
        assertTrue(ub, ub.contains("mikefarah/yq"))
    }

    @Test fun searchFindsAlternatives() {
        val files = mapOf("game/data/objects.json" to "{\"name\":\"Ladder\",\"actions\":[\"Climb-up\"]}\n{\"name\":\"Staircase\"}", "game/main.js" to "if (a || b) go()", "docs/x.md" to "ladder notes")
        val r = dev.pocketide.app.core.AgentActions.grep(files.keys, { files[it] }, "ladder|stair|trapdoor", "game")
        assertEquals(2, r.hits)   // Ladder and Staircase in game/, not docs/
        assertEquals(1, dev.pocketide.app.core.AgentActions.grep(files.keys, { files[it] }, "a || b").hits)   // code with || is searched as written
        val none = dev.pocketide.app.core.AgentActions.grep(files.keys, { files[it] }, "zebra|yak")
        assertTrue(none.text, none.text.startsWith("No matches") && none.text.contains("do not guess another name"))
    }

    @Test fun aFlatSpawnListBecomesNpcRows() {
        val list = listOf(
            mapOf("name" to "Goblin", "id" to 3029, "p" to 0, "x" to 3250, "y" to 3230, "standingAnimation" to 6181),
            mapOf("name" to "Goblin", "id" to 3029, "p" to 0, "x" to 3252, "y" to 3233),
            mapOf("name" to "Dagannoth", "id" to 979, "p" to 0, "x" to 2520, "y" to 3740))
        val rows = dev.pocketide.app.addon.osrs.CacheTool.spawnRows(list, dev.pocketide.app.addon.osrs.CacheTool.area("3072,3072,3263,3263"))
        assertEquals(1, rows.size)                       // the dagannoth is on Waterbirth Island, outside the area
        assertEquals(3029, rows[0]["id"])
        assertEquals(listOf(listOf(3250, 3230, 0, 1), listOf(3252, 3233, 0, 1)), rows[0]["spawns"])
        assertEquals(2, dev.pocketide.app.addon.osrs.CacheTool.spawnRows(list, null).size)
        assertTrue(dev.pocketide.app.addon.osrs.GameRecipe.CONTINUE_NOTE.contains("type 9 is a diagonal wall"))
        assertTrue(dev.pocketide.app.addon.osrs.GameRecipe.NOTE.contains("NPC POSITIONS"))
        assertTrue(dev.pocketide.app.core.Requirements.IMAGE_ONLY.contains("do not assume"))
    }
}
