package dev.pocketide.app

import dev.pocketide.app.core.PageLook
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFlatTest {
    private fun look(vararg canvases: Map<String, Any?>) = mapOf<String, Any?>("canvases" to canvases.toList())

    @Test fun aLargeFlatCanvasIsFlagged() {
        val v = PageLook.verdict(look(mapOf("pw" to 412, "ph" to 915, "data" to "x".repeat(900))), emptyList())
        assertTrue(v, v.startsWith("VERDICT: FAIL") && v.contains("draws nothing"))
    }

    @Test fun aDrawnCanvasIsNotFlagged() {
        val v = PageLook.verdict(look(mapOf("pw" to 412, "ph" to 915, "data" to "x".repeat(60_000))), emptyList())
        assertFalse(v, v.contains("draws nothing"))
    }

    @Test fun oneDrawnCanvasIsEnoughToPass() {
        val v = PageLook.verdict(look(mapOf("pw" to 412, "ph" to 915, "data" to "x".repeat(900)),
            mapOf("pw" to 412, "ph" to 915, "data" to "x".repeat(60_000))), emptyList())
        assertFalse(v, v.contains("draws nothing"))
    }

    @Test fun aSmallFlatIconIsNotTheWholePage() {
        val v = PageLook.verdict(look(mapOf("pw" to 32, "ph" to 32, "data" to "x".repeat(300))), emptyList())
        assertFalse(v, v.contains("draws nothing"))
    }

    @Test fun aPageWithNoCanvasIsNotFlatCanvas() {
        val v = PageLook.verdict(mapOf("canvases" to emptyList<Any?>()), emptyList())
        assertFalse(v, v.contains("draws nothing"))
    }

    // 1.5.153: the harbour suite's chart (three small dots on a 328x180 canvas) compressed to under 6,000 bytes and was called flat
    @Test fun aFewSmallMarksAreDrawingWhenThePixelsWereRead() {
        val v = PageLook.verdict(look(mapOf("pw" to 328, "ph" to 180, "data" to "x".repeat(900), "flat" to false)), emptyList())
        assertFalse(v, v.contains("draws nothing"))
    }

    @Test fun pixelsReadAsOneColourAreFlatWhateverTheSize() {
        val v = PageLook.verdict(look(mapOf("pw" to 412, "ph" to 915, "data" to "x".repeat(60_000), "flat" to true)), emptyList())
        assertTrue(v, v.contains("draws nothing"))
    }

    @Test fun anUnreadCanvasFallsBackToItsSize() {
        val v = PageLook.verdict(look(mapOf("pw" to 412, "ph" to 915, "data" to "x".repeat(900), "flat" to null)), emptyList())
        assertTrue(v, v.contains("draws nothing"))
    }
}
