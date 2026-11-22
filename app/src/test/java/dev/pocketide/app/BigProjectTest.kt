package dev.pocketide.app

import dev.pocketide.app.core.ActionVerb
import dev.pocketide.app.core.AgentAction
import dev.pocketide.app.core.AgentActions
import dev.pocketide.app.core.ProjectGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class BigProjectTest {
    @Test fun exportKeepsEveryFile() {
        val dir = java.nio.file.Files.createTempDirectory("big").toFile()
        val sprites = java.io.File(dir, "assets/sprites").also { it.mkdirs() }
        for (i in 0 until 5200) java.io.File(sprites, "$i.png").writeBytes(byteArrayOf(1))
        java.io.File(dir, "index.html").writeText("<html></html>")
        val store = ProjectStore(dir, seed = false)
        val out = ByteArrayOutputStream()
        store.exportZip(out)
        var n = 0; var sawIndex = false
        ZipInputStream(out.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; n++; if (e.name == "index.html") sawIndex = true } }
        assertEquals(5201, n)
        assertTrue(sawIndex)
        // the assistant's list keeps code ahead of thousands of sprites, and says what it left out
        val listed = store.list()
        assertTrue("index.html" in listed)
        assertEquals(ProjectStore.LIST_LIMIT, listed.size)
        assertTrue(store.listNote().contains("assets/sprites/ (3201 files)"))
        assertEquals(5201 - ProjectStore.LIST_LIMIT, store.omittedCount())
        dir.deleteRecursively()
    }

    @Test fun pickListedPutsTextFirst() {
        val all = (0 until 10).map { "a/$it.png" } + listOf("b/game.js", "index.html")
        val (keep, left) = ProjectGuard.pickListed(all, 5) { it.endsWith(".png") }
        assertTrue("b/game.js" in keep && "index.html" in keep)
        assertEquals(mapOf("a" to 7), left)
        assertEquals(all.sorted() to emptyMap<String, Int>(), ProjectGuard.pickListed(all, 50) { false })
    }

    @Test fun textPathRoundsAndNudges() {
        val unzip = AgentAction(ActionVerb.RUN, "cd work && unzip -o rsmod-main.zip -d rsmod")
        assertTrue(AgentActions.madeProgress(listOf(unzip to "exit code 0\ninflating...")))
        assertFalse(AgentActions.madeProgress(listOf(unzip to "exit code 2\nerror")))
        assertFalse(AgentActions.madeProgress(listOf(AgentAction(ActionVerb.RUN, "ls -la") to "exit code 0\n.")))
        assertTrue(AgentActions.announcesWork("I'll build the RuneScape clone now. Let me create the project files."))
        assertFalse(AgentActions.announcesWork("Done.\n```file:index.html\n<html>\n```"))
        assertFalse(AgentActions.announcesWork("The game is finished and works."))
        assertTrue(AgentActions.writeNow("make a game").contains("Original request: make a game"))
    }

    @Test fun filesAtTheTopAreAlwaysListed() {
        val all = (1..3000).map { "cache-export/data/f$it.json" } + listOf("game-preview-fix.zip", "index.html")
        val (keep, _) = dev.pocketide.app.core.ProjectGuard.pickListed(all, 2000) { it.endsWith(".zip") }
        org.junit.Assert.assertTrue(keep.contains("game-preview-fix.zip") && keep.contains("index.html"))
        org.junit.Assert.assertEquals(2000, keep.size)
    }

    @Test fun searchReadsOneFileAtATime() {
        val reads = ArrayList<String>()
        val files = mapOf("a.js" to "let hp = 1", "b.js" to "nothing", "c.js" to "hp += 2")
        val r = dev.pocketide.app.core.ProjectSearch.search(files.keys.toList(), { p -> reads.add(p); files[p] }, dev.pocketide.app.core.SearchOptions("hp"))
        org.junit.Assert.assertEquals(2, r.fileCount)
        org.junit.Assert.assertEquals(listOf("a.js", "b.js", "c.js"), reads)
        val plan = dev.pocketide.app.core.ProjectSearch.replaceInProject(files.keys.toList(), { files[it] }, dev.pocketide.app.core.SearchOptions("hp"), "health")
        org.junit.Assert.assertEquals(setOf("a.js", "c.js"), plan.keys)
    }

    @Test fun thePageIsFoundInAProjectTooBigForTheList() {
        val dir = java.nio.file.Files.createTempDirectory("bigpage").toFile()
        try {
            val data = java.io.File(dir, "cache-export/data").also { it.mkdirs() }
            for (i in 1..2500) java.io.File(data, "f$i.json").writeText("{}")
            java.io.File(dir, "game").mkdirs(); java.io.File(dir, "game/index.html").writeText("<p>game</p>")
            val store = ProjectStore(dir, seed = false)
            org.junit.Assert.assertEquals("game/index.html", store.findPage())     // no page at the top: the shallowest one
            java.io.File(dir, "index.html").writeText("<p>top</p>")
            org.junit.Assert.assertEquals("index.html", store.findPage())
            java.io.File(dir, "index.html").delete(); java.io.File(dir, "game/index.html").delete()
            org.junit.Assert.assertNull(store.findPage())
        } finally { dir.deleteRecursively() }
    }

    @Test fun aFailedCheapModelIsRemembered() {
        org.junit.Assert.assertFalse(dev.pocketide.app.core.Speed.cheapFailed("deepseek", "deepseek-v4.1-flash"))
        dev.pocketide.app.core.Speed.markCheapFailed("deepseek", "deepseek-v4.1-flash")
        org.junit.Assert.assertTrue(dev.pocketide.app.core.Speed.cheapFailed("deepseek", "deepseek-v4.1-flash"))
    }

    @Test fun codeNearTheTopIsKeptBeforeDeepData() {
        val all = (1..3000).map { "cache-export/data/f$it.json" } + listOf("game/main.js", "game/index.html", "game/pathfinder.js", "index.html")
        val (keep, left) = dev.pocketide.app.core.ProjectGuard.pickListed(all, 2000) { false }
        org.junit.Assert.assertTrue(keep.containsAll(listOf("game/main.js", "game/index.html", "game/pathfinder.js", "index.html")))
        org.junit.Assert.assertEquals(1004, left["cache-export/data"])
    }
}
