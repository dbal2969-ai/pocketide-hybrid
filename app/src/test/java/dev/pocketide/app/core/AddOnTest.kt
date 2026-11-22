package dev.pocketide.app.core

import dev.pocketide.app.addon.osrs.OsrsAddOn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** 1.5.88: the RuneScape game-cache code is an add-on; the core reaches it only through AddOns. */
class AddOnTest {
    private val withCache = AddOnProject(files = { listOf("cache/main_file_cache.dat2", "index.html") },
        folderWith = { name, _ -> if (name == "main_file_cache.dat2") "cache" else null })
    private val plain = AddOnProject(files = { listOf("index.html", "app.js") }, folderWith = { _, _ -> null })

    @Before fun register() { AddOns.register(OsrsAddOn); AddOns.disabled = emptySet(); AddOns.clear(); Tools.gameFocus = false }
    @After fun reset() { AddOns.disabled = emptySet(); AddOns.clear(); Tools.gameFocus = false }

    @Test fun cacheToolsOnlyForAProjectWithACache() {
        AddOns.activate(withCache, agent = true)
        assertEquals(listOf("game_cache"), AddOns.defs().map { it.name })
        assertTrue(AddOns.handles("game_cache"))
        assertFalse("no server data here", AddOns.handles("game_data"))
        AddOns.activate(plain, agent = true)
        assertTrue(AddOns.defs().isEmpty())
        assertFalse(AddOns.handles("game_cache"))
    }

    @Test fun switchedOffMeansGone() {
        AddOns.disabled = setOf("osrs")
        AddOns.activate(withCache, agent = true)
        assertTrue(AddOns.defs().isEmpty())
        assertEquals("", AddOns.note("make a game set in Lumbridge from the cache", withCache))
        assertEquals(null, AddOns.prepare("make a game set in Lumbridge from the cache", withCache))
    }

    @Test fun notInAgentMode() {
        AddOns.activate(withCache, agent = false)
        assertTrue(AddOns.defs().isEmpty())
    }

    @Test fun whichCallsChangeFiles() {
        AddOns.activate(withCache, agent = true)
        assertTrue(AddOns.changes("game_cache", mapOf("action" to "export")))
        assertFalse(AddOns.changes("game_cache", mapOf("action" to "info")))
        assertFalse(AddOns.changes("read_file", emptyMap()))
    }

    @Test fun notesFollowTheProject() {
        assertTrue(AddOns.note("make a game set in Lumbridge from the cache", withCache).contains("BUILDING A GAME FROM THE PROJECT'S GAME CACHE"))
        assertTrue(AddOns.note("add trees to the game", withCache).contains("THIS PROJECT HOLDS A GAME CACHE"))
        assertEquals("", AddOns.note("make a game set in Lumbridge", plain))
        assertEquals("", AddOns.note("fix the login form", withCache))
    }

    @Test fun cacheToolsAreHeldAndGameToolsOfferedForGames() {
        AddOns.activate(withCache, agent = true)
        assertTrue(Tools.isOnDemand("game_cache"))
        assertTrue(Tools.isOnDemand("game_assets"))
        Tools.gameFocus = true
        assertFalse("a game request gets the generic game tools up front", Tools.isOnDemand("game_assets"))
    }

    @Test fun theCoreDoesNotReferToAddOnCode() {
        val src = listOf(File("src/main/java/dev/pocketide/app"), File("app/src/main/java/dev/pocketide/app")).firstOrNull { it.isDirectory }
        assumeTrue("source tree not found from ${File(".").absolutePath}", src != null)
        val offenders = src!!.walkTopDown().filter { it.isFile && it.extension == "kt" && !it.path.contains("/addon/") }
            .filter { f -> f.readLines().map { it.trim() }.filter { !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*") }.any { it.contains("dev.pocketide.app.addon") && !it.contains("AddOns.register(") } }
            .map { it.relativeTo(src).path }.toList()
        assertEquals("only MainActivity registers add-ons; everything else goes through AddOns", emptyList<String>(), offenders)
    }
}
