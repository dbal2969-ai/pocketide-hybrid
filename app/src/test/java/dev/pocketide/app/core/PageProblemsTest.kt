package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.76: a missing data or media file is a note; missing code and console errors block. */
class PageProblemsTest {
    private val run = listOf(
        "404 Not found: game/data/objects/50_50.json",
        "404 Not found: game/data/ground/g46_51.png",
        "404 Not found: game/data/scene/s46_51.bin.gz",
        "Uncaught TypeError: Cannot read properties of undefined (main.js:12)",
        "404 Not found: game/view3d.js"
    )

    @Test fun dataAndMediaAreNotes() {
        val s = PageProblems.split(run)
        assertEquals(listOf("Uncaught TypeError: Cannot read properties of undefined (main.js:12)", "404 Not found: game/view3d.js"), s.blocking)
        assertEquals(3, s.notes.size)
    }

    @Test fun aFileTheRequestNamesBlocks() {
        val s = PageProblems.split(run, "load the objects from 50_50.json for the Lumbridge square")
        assertTrue(s.blocking.contains("404 Not found: game/data/objects/50_50.json"))
        assertEquals(2, s.notes.size)
    }

    @Test fun theBrowsersEchoOfA404IsNotAnError() {
        assertFalse(PageProblems.blocks("Failed to load resource: the server responded with a status of 404 (Not Found) (50_50.json:0)"))
    }

    @Test fun verdictPassesWithOnlyMissingData() {
        val look = mapOf<String, Any?>("w" to 400, "h" to 800, "canvases" to emptyList<Any?>())
        val v = PageLook.verdict(look, run.take(3))
        assertTrue(v, v.startsWith("VERDICT: PASS"))
        assertTrue(PageLook.verdict(look, run).startsWith("VERDICT: FAIL"))
    }

    @Test fun noteLineListsTheFiles() {
        val n = PageProblems.noteLine(run.take(3))
        assertTrue(n, n.startsWith("Note: 3 requested data files do not exist"))
        assertTrue(n.contains("game/data/objects/50_50.json"))
        assertTrue("a map texture is not a visible gap", n.contains("game/data/ground/g46_51.png"))
        assertEquals("", PageProblems.noteLine(emptyList()))
    }

    @Test fun aMissingItemPictureIsAVisibleGapWithTheWayToMakeIt() {
        // 1.5.98: the tinderbox and essence had no picture; the note said "not a failure" and the model told the person to make them
        val n = PageProblems.noteLine(listOf("404 Not found: game/data/icons/590.png", "404 Not found: game/data/icons/1436.png", "404 Not found: game/data/objects/50_50.json"))
        assertTrue(n, n.startsWith("Missing pictures (shown blank on the page): game/data/icons/590.png, game/data/icons/1436.png."))
        assertTrue(n, n.contains("game_cache action=export kind=icons ids=590,1436"))
        assertTrue(n, n.contains("\nNote: 1 requested data file does not exist"))
        assertFalse("still never blocks", PageProblems.blocks("404 Not found: game/data/icons/590.png"))
    }

    @Test fun aLookAtAZeroSizedPreviewIsNotMeasured() {
        // 1.5.98: with the app in the background the hidden preview was 0x0; every look said elements were off screen
        val look = mapOf<String, Any?>("w" to 0, "h" to 0, "canvases" to emptyList<Any?>(), "off" to listOf(mapOf("tag" to "div", "x" to -115, "y" to 4, "w" to 73, "h" to 13)))
        val v = PageLook.verdict(look, emptyList())
        assertTrue(v, v.startsWith("VERDICT: NOT MEASURED"))
        val s = PageLook.summary(look, emptyList(), "Screenshot: none.")
        assertFalse("no off-screen list from a 0x0 screen", s.contains("outside the screen"))
    }
}
