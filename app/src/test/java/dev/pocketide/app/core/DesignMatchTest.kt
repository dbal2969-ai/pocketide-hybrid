package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignMatchTest {
    @Test fun whenAPictureIsTheDesign() {
        for (t in listOf("Make this", "Build this screen for my app", "recreate the screenshot as a page", "Can you make my landing page look like this?",
                "Code this design in HTML", "Turn my sketch into a page: make it from this sketch", "build it like the picture")) assertTrue(t, DesignMatch.wanted(t, true))
        for (t in listOf("What is wrong with this screen?", "Why does it look like this?", "Fix the bug shown in the screenshot")) assertFalse(t, DesignMatch.wanted(t, true))
        assertFalse(DesignMatch.wanted("Make this", false))
    }

    private fun image(w: Int, h: Int, color: (Int, Int) -> Int) = IntArray(w * h) { i -> color(i % w, i / w) or (0xff shl 24) }

    @Test fun alikePicturesScoreHighAndDifferentAreasAreNamed() {
        val design = image(90, 160) { _, y -> if (y < 20) 0x1e3a8a else 0xffffff }       // a dark blue header on white
        val same = DesignMatch.compare(DesignMatch.grid(design, 90, 160), DesignMatch.grid(design, 90, 160))
        assertEquals(100, same.score); assertTrue(same.areas.isEmpty())
        assertTrue(DesignMatch.report(same).contains("100% alike (very close). No area stands out."))
        val page = image(90, 160) { x, y -> if (y < 20) 0xffffff else if (y > 120 && x > 60) 0x111111 else 0xffffff }   // no header, a dark box bottom right
        val r = DesignMatch.compare(DesignMatch.grid(design, 90, 160), DesignMatch.grid(page, 90, 160))
        assertTrue(r.score in 50..95)
        val names = r.areas.map { it.name }
        assertTrue(names.toString(), names.containsAll(listOf("bottom right")) && names.any { it.startsWith("top") })
        val text = DesignMatch.report(r)
        assertTrue(text, text.contains("bottom right: the design is about #ffffff") && text.contains("(the page is darker)") && text.contains("(the page is lighter)"))
    }
}
