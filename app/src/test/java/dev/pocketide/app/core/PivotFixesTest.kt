package dev.pocketide.app.core

import dev.pocketide.app.ProjectStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 1.5.167: the fixes from the Empty-project-10 field run (2009scape quests): lists, second try, requirements, plan, port note. */
class PivotFixesTest {
    @Test fun rollUpCountsEveryLeftOutFile() {
        val m = LinkedHashMap<String, Int>()
        for (i in 0 until 300) m["big/server/src/main/content/region/r$i/quest"] = 3
        m["game/data/objects"] = 4485
        m["big/dumps/scripts"] = 5378
        val r = ProjectGuard.rollUp(m, 12)
        assertTrue(r.size <= 12)
        assertEquals(m.values.sum(), r.values.sum())
        assertTrue(r.keys.any { "big/server/src/main/content".startsWith(it) || it.startsWith("big") })
    }

    @Test fun noteNamesTheSourceTreeNotOnlyTheBiggestFolders() {
        val m = LinkedHashMap<String, Int>()
        for (i in 0 until 40) m["x/dumps/d$i"] = 500
        for (i in 0 until 600) m["x/server/src/content/q$i"] = 3
        val note = ProjectGuard.listNote(emptyMap(), true, 2000, m)
        assertTrue(note, note.contains("x/server") || note.contains("x/"))
        assertTrue(note.contains("list_files or search on any of these folders"))
    }

    @Test fun bigFolderIsSummarisedBySubfolder() {
        val files = (0 until 500).map { "srv/content/region/a$it/Q.kt" } + "srv/content/README.md"
        val out = AgentActions.list(files, { 10L }, "srv/content")
        assertTrue(out, out.startsWith("501 files under srv/content"))
        assertTrue(out.contains("srv/content/region/ (500 files)"))
        assertTrue(out.contains("srv/content/README.md"))
    }

    @Test fun listUnderWalksAFolderTheCappedListLeftOut() {
        val root = Files.createTempDirectory("lu").toFile()
        try {
            val d = File(root, "proj/server/src/main/content/quest/cook"); d.mkdirs()
            File(d, "CooksAssistant.kt").writeText("class CooksAssistant")
            File(root, "proj/server/node_modules/x").mkdirs(); File(root, "proj/server/node_modules/x/a.js").writeText("x")
            val store = ProjectStore(root, false)
            val got = store.listUnder("proj/server")
            assertEquals(listOf("proj/server/src/main/content/quest/cook/CooksAssistant.kt"), got)
            assertTrue(store.listUnder("nope").isEmpty())
            assertTrue(store.listUnder("").isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun secondTryNeverRestartsASteeredRunAndKeepsLongWork() {
        assertEquals(SecondTry.Mode.NONE, SecondTry.mode(steered = true, filesChanged = 3, seconds = 1700))
        assertEquals(SecondTry.Mode.NONE, SecondTry.mode(steered = true, filesChanged = 0, seconds = 20))
        assertEquals(SecondTry.Mode.KEEP, SecondTry.mode(steered = false, filesChanged = 2, seconds = 1700))
        assertEquals(SecondTry.Mode.FRESH, SecondTry.mode(steered = false, filesChanged = 2, seconds = 90))
        assertEquals(SecondTry.Mode.FRESH, SecondTry.mode(steered = false, filesChanged = 0, seconds = 1700))
        val kept = SecondTry.note("x", "", listOf("game/data/content.json", "game/options.js"))
        assertTrue(kept.contains("Its changes were kept: game/data/content.json, game/options.js"))
        assertFalse(kept.contains("undone"))
        assertTrue(SecondTry.note("x", "").contains("Its changes were undone"))
        assertTrue(SecondTry.startNote("x", true).contains("kept"))
    }

    @Test fun requirementsCopiedFromAnOldPlanAreFlagged() {
        val pts = listOf(
            "Add 2009scape quests to the game [request] [file: 2009scape-project/.../CooksAssistant.kt]",
            "Wire equipment bonuses into combat [file: PLAN.md]",
            "Make prayers affect combat [file: docs/TODO.md]",
            "Magic autocast works [assumed]")
        assertEquals(listOf(1, 2), Requirements.offRequest(pts, "From the 2009scape-project I imported please build player skills and quests"))
        assertTrue(Requirements.offRequest(pts, "Do the rest of the plan").isEmpty())
        assertEquals(listOf(2), Requirements.offRequest(pts, "Do what PLAN.md says and add quests"))
        assertTrue(Requirements.request("x").contains("background, not requests"))
    }

    @Test fun steerPutsThePersonsNewestWordsFirst() {
        val m = Steer.message("Focus on the adding the quests one at a time")
        assertTrue(m.contains("newest words decide"))
        assertTrue(m.contains("do not argue for the old plan"))
        assertTrue(m.contains("do it in this run, not later"))
    }

    @Test fun portSourceFindsTheNamedFolder() {
        val folders = listOf("game", "data", "2009scape-project", "2009scape-project/server", "tools")
        assertEquals("2009scape-project", PortSource.folder("From the 2009scape-project I imported please build player skills and quests", folders))
        assertEquals("2009scape-project", PortSource.folder("merge mechanics from the 2009scape project into this game", folders))
        assertNull(PortSource.folder("make the player run faster", folders))
        assertNull(PortSource.folder("tell me about 2009scape", folders))
        val files = (0 until 50).map { "2009scape-project/server/Server/src/main/content/region/r$it/Q.kt" } + "2009scape-project/server/dumps/a.json"
        val dirs = PortSource.sourceDirs(files, "2009scape-project")
        assertEquals("2009scape-project/server/Server/src/main/content/ (50)", dirs.first())
        val note = PortSource.note("2009scape-project", 12210, dirs)
        assertTrue(note.contains("do not write a simplified version from memory"))
        assertTrue(note.contains("search with path \"2009scape-project\""))
    }
}
