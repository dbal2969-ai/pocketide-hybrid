package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.5.193: fixes from reliability run 1126-0816 (117 of 171, Nano Full alone; 18 of 38 Python fails ended rereading a file). */
class ReliableRun3Test {
    private val highlighter = File("src/test/resources/nano/rich_3067/highlighter.py").readText()
    // Nano's search in rich_3067 run 1: the file's line uses double quotes and a backtick, Nano wrote single quotes
    private val nanoSearch = "r'(?P<url>(file|https|http|ws|wss)://[-0-9a-zA-Z\$_+!'(),.?/;:&=%#]*)'"

    @Test fun aSearchWithOtherQuotesGetsTheClosestLine() {
        val r = EditBlocks.apply(highlighter, EditBlock("rich/highlighter.py", nanoSearch, "x"))
        assertNull(r.text)
        assertTrue(r.error, r.error!!.contains("The closest text is at lines"))
        assertTrue(r.error, r.error.contains("101: " + highlighter.lines()[100]))
    }

    @Test fun theExcerptIsAroundTheMissedLine() {
        val ex = EditBlocks.excerpt(highlighter, nanoSearch)!!
        assertTrue(ex, ex.lines().any { it.startsWith("101: ") && it.contains("P<url>") })
        assertEquals(25, ex.lines().size)
        assertNull(EditBlocks.excerpt(highlighter, "nothing like any line in this file at all zzz"))
    }

    @Test fun similarity() {
        assertEquals(1.0, EditBlocks.similarity("a = 'x'", "a  =  \"x\""), 1e-9)
        assertTrue(EditBlocks.similarity("return total + tax", "return total - tax") > 0.6)
        assertTrue(EditBlocks.similarity("return total + tax", "import os") < 0.3)
        assertEquals(1, EditBlocks.similarLine(listOf("x = 1", "name = 'Ana'", "y = 2"), "name = \"Ana\""))
        assertNull(EditBlocks.similarLine(listOf("x = 1"), "abc"))
    }
}
