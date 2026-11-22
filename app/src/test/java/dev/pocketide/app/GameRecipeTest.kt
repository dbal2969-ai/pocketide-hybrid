package dev.pocketide.app

import dev.pocketide.app.addon.osrs.*

import dev.pocketide.app.core.*
import org.junit.Assert.*
import org.junit.Test

class GameRecipeTest {
    private val vague = "create a game with the basic mechanics of runescape that takes place only in falador and the playable character is combat level 100 wearing the gear of a white knight and you have 20 different hostile randomized runescape native enemies attacking you"

    @Test fun vagueRequestWithCacheGetsRecipe() = assertTrue(GameRecipe.wanted(vague, true))
    @Test fun noCacheNoRecipe() = assertFalse(GameRecipe.wanted(vague, false))
    @Test fun unrelatedProjectsNeverGetIt() {
        assertFalse(GameRecipe.wanted("make a snake game with a score", true))
        assertFalse(GameRecipe.wanted("fix the login page of my shop", true))
        assertFalse(GameRecipe.wanted("write a README for the Falador bakery website", true))
        assertFalse(GameRecipe.wanted("Verify combat and woodcutting are implemented", true))
    }
    @Test fun continuationsKeepTheRecipeWhenAGameExists() {
        assertFalse(GameRecipe.wanted("Continue", true))
        assertTrue(GameRecipe.wanted("Continue", true, true))
        assertFalse(GameRecipe.wanted("Continue", false, true))
        val files = listOf("game/main.js", "game/rules.js", "game/pathfinder.js", "game/index.html")
        assertTrue(GameRecipe.hasGame(files))
        assertFalse(GameRecipe.hasGame(listOf("src/main.js", "src/app.js")))
        assertFalse(GameRecipe.hasGame(listOf("game/main.js", "game/rules.js")))
    }
    @Test fun preflightNeedsAKnownTown() {
        assertEquals("46,52", GameRecipe.preflightSquares(vague))
        assertNull(GameRecipe.preflightSquares("make a game with enemies"))
        assertTrue(GameRecipe.builtNote("Wrote 300 files.\nmore").contains("Wrote 300 files."))
    }
    @Test fun townWithGameWordsCounts() = assertTrue(GameRecipe.wanted("a little game in Lumbridge with enemies", true))

    @Test fun placesResolve() {
        assertEquals("46,52", Places.squaresFor("Falador"))
        assertEquals("51,49", Places.squaresFor("an Al Kharid game"))
        assertEquals("12,12", Places.squaresFor("12,12"))
    }

    @Test fun itemWornModelsAreDecoded() {
        // name "Helm", wear slot 0, hides slot 8, man worn model 2813, woman worn model 2814, end
        val b = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = b.write(v)
        fun u16(v: Int) { b.write(v shr 8); b.write(v and 255) }
        u8(2); b.write("Helm".toByteArray()); u8(0); u8(13); u8(0); u8(14); u8(8); u8(23); u16(2813); u8(0); u8(25); u16(2814); u8(0); u8(0)
        val d = CacheDefs.item(1, b.toByteArray())
        assertFalse(d.partial)
        assertEquals(listOf(2813), d.fields["wornModels"]); assertEquals(listOf(2814), d.fields["wornModelsFemale"])
        assertEquals(0, d.fields["wearSlot"]); assertEquals(8, d.fields["hidesSlot"])
    }

    private fun npc(id: Int, name: String, level: Int, attack: Boolean = true) = CacheDefs.Def(id, linkedMapOf<String, Any?>("name" to name, "combatLevel" to level, "models" to listOf(id),
        "actions" to listOf(null, if (attack) "Attack" else "Talk-to", null, null, null)), false)

    @Test fun sampleIsDifferentRepeatableAndFiltered() {
        val defs = (1..100).map { npc(it, "Mob${it % 40}", it) } + npc(500, "Banker", 5, false) + npc(501, "Ghost", 0)
        val a = CacheTool.attackableSample(defs, "", mapOf("count" to "20", "seed" to "3", "min_level" to "10", "max_level" to "60"))
        val b = CacheTool.attackableSample(defs, "", mapOf("count" to "20", "seed" to "3", "min_level" to "10", "max_level" to "60"))
        val c = CacheTool.attackableSample(defs, "", mapOf("count" to "20", "seed" to "4", "min_level" to "10", "max_level" to "60"))
        assertEquals(a, b); assertNotEquals(a, c)
        val names = Regex("\"name\":\"(\\w+)\"").findAll(a).map { it.groupValues[1] }.toList()
        assertEquals(20, names.size); assertEquals(20, names.toSet().size)
        assertFalse(a.contains("Banker")); assertFalse(a.contains("Ghost"))
    }
}
