package dev.pocketide.app

import dev.pocketide.app.core.Hunks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HunksTest {
    private val old = "a\nb\nc\nd\ne\nf\ng\nh\n"
    private val new = "a\nB\nc\nd\ne\nf\nG1\nG2\nh\nextra\n"

    @Test fun splitsIntoParts() {
        val hs = Hunks.split(old, new)!!
        assertEquals(3, hs.size)
        assertEquals(listOf("b"), hs[0].removed); assertEquals(listOf("B"), hs[0].added)
        assertEquals("Lines 7-8 (-1 +2)", hs[1].label)
        assertEquals(listOf("extra"), hs[2].added)
    }

    @Test fun undoPartsInAnyOrder() {
        val hs = Hunks.split(old, new)!!
        val t1 = Hunks.revert(new, hs[1])!!
        assertEquals("a\nB\nc\nd\ne\nf\ng\nh\nextra\n", t1)
        val t2 = Hunks.revert(t1, hs[0])!!
        val t3 = Hunks.revert(t2, hs[2])!!
        assertEquals(old, t3)
    }

    @Test fun changedSinceIsRefused() {
        val hs = Hunks.split(old, new)!!
        assertNull(Hunks.revert("totally different\n", hs[0]))
    }
}
