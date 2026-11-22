package dev.pocketide.app

import dev.pocketide.app.core.*
import org.junit.Assert.*
import org.junit.Test

class LayoutReportTest {
    private val look: Map<String, Any?> = mapOf(
        "w" to 400, "h" to 600, "off" to emptyList<Any?>(), "canvases" to emptyList<Any?>(), "text" to "",
        "layout" to mapOf(
            "overlaps" to listOf(listOf("div#a", "Score panel text", "div#b", "Save panel", 30, 20)),
            "covered" to listOf(listOf("button#hid", "Start", "div#veil")),
            "clipped" to listOf(listOf("div#c", "This text", 261, 0)),
            "small" to listOf(listOf("button", "x", 20, 18))))

    @Test fun eachFaultBecomesOneExactLine() {
        val lines = PageLook.layoutLines(look)
        assertEquals(4, lines.size)
        assertEquals("Overlap: <div#a> \"Score panel text\" and <div#b> \"Save panel\" share a 30x20 px area.", lines[0])
        assertEquals("Hidden under: <button#hid> \"Start\" is covered at its centre by <div#veil>.", lines[1])
        assertEquals("Cut off: <div#c> \"This text\" has 261px wider and 0px taller content than its box.", lines[2])
        assertEquals("Small target: <button> \"x\" is 20x18 px (touch targets need about 44).", lines[3])
    }

    @Test fun summaryNamesTheProblemsOrSaysNone() {
        val s = PageLook.summary(look, emptyList(), "")
        assertTrue(s, s.contains("Layout problems (checked by the page itself, exact):"))
        val clean = PageLook.summary(mapOf("w" to 400, "h" to 600, "layout" to mapOf("overlaps" to emptyList<Any?>(), "covered" to emptyList<Any?>(), "clipped" to emptyList<Any?>(), "small" to emptyList<Any?>())), emptyList(), "")
        assertTrue(clean, clean.contains("Layout: no overlaps, hidden controls, cut-off text or small tap targets found."))
    }

    @Test fun missingLayoutIsSilent() {
        assertEquals(emptyList<String>(), PageLook.layoutLines(mapOf("w" to 1)))
    }

    @Test fun aBoxOverTextNamesTheCoveredText() {
        val lines = PageLook.layoutLines(mapOf("layout" to mapOf("overlaps" to listOf(listOf("img#ship", "", "header#hud", "Brig", 24, 16, "text")))))
        assertEquals(listOf("Overlap: <img#ship> covers the text of <header#hud> \"Brig\" (24x16 px of it)."), lines)
    }
}
