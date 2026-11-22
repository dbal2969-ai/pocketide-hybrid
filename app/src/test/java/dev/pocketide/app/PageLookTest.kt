package dev.pocketide.app

import dev.pocketide.app.core.PageLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLookTest {
    @Test fun injectsBeforeAnyPageScript() {
        val html = "<!doctype html><html><head><meta charset=\"utf-8\"><script src=\"a.js\"></script></head><body></body></html>"
        val out = PageLook.inject(html)
        assertTrue(out.indexOf("preserveDrawingBuffer") < out.indexOf("a.js"))
        assertTrue(out.startsWith("<!doctype html><html><head>"))
        assertTrue(PageLook.inject("<p>hi</p>").startsWith("<script>"))
    }

    @Test fun reportsOffScreenElementsAndCanvases() {
        val look = mapOf<String, Any?>(
            "w" to 360.0, "h" to 640.0,
            "off" to listOf(mapOf("tag" to "button", "text" to "Skills", "x" to 400.0, "y" to 8.0, "w" to 84.0, "h" to 36.0)),
            "canvases" to listOf(mapOf("x" to 0.0, "y" to 0.0, "w" to 360.0, "h" to 640.0, "pw" to 360.0, "ph" to 640.0, "data" to "data:image/jpeg;base64,AAAA")),
            "text" to "", "state" to "{\"hp\":10}")
        val s = PageLook.summary(look, listOf("TypeError: x is undefined (game.js:3)"), "Screenshot: attached (480px wide).")
        assertTrue(s.contains("<button> \"Skills\" at 400, 8"))
        assertTrue(s.contains("Canvas 1"))
        assertTrue(s.contains("HUD"))
        assertTrue(s.contains("Game state (render_game_to_text): {\"hp\":10}"))
        assertTrue(s.contains("TypeError"))
        assertEquals(1, PageLook.canvases(look).size)
    }

    @Test fun missingPageAndFlatPictures() {
        assertTrue(PageLook.summary(null, emptyList(), "").contains("Could not read the page"))
        assertTrue(PageLook.flat(intArrayOf(5, 5, 5)))
        assertTrue(!PageLook.flat(intArrayOf(5, 6)))
    }

    @Test fun stepsParse() {
        val p = dev.pocketide.app.core.PageSteps.parse("tap 180 320; wait 500\nkey ArrowRight 400; type hi; hold 10,20 300")
        assertEquals(null, p.error)
        assertEquals(listOf("tap", "wait", "key", "key", "key", "hold"), p.steps.map { it.kind })
        assertEquals(150 + 500 + 450 + 90 + 90 + 350, p.totalMs)
        assertTrue(dev.pocketide.app.core.PageSteps.parse("jump 1 2").error!!.contains("unknown step"))
        assertTrue(dev.pocketide.app.core.PageSteps.parse("tap 5").error!!.contains("needs x and y"))
        assertTrue(dev.pocketide.app.core.PageSteps.parse("wait 10000; wait 10000; wait 10000; wait 10000; wait 10000").error!!.contains("under"))
        assertTrue(dev.pocketide.app.core.PageSteps.script(p.steps).contains("\"ArrowRight\""))
        assertEquals("{\"hp\":3}", dev.pocketide.app.core.PageSteps.text("\"{\\\"hp\\\":3}\""))
    }
}
