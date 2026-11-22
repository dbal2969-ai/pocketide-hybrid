package dev.pocketide.app

import dev.pocketide.app.core.AgentActions
import dev.pocketide.app.core.EditBlock
import dev.pocketide.app.core.EditBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditToleranceTest {
    private val file = "function a() {\n  const x = 1;\n\n  return x;\n}\n\nfunction b() {\n  return 2;\n}"

    @Test fun copiedLineNumbersAreDropped() {
        val r = EditBlocks.apply(file, EditBlock("f.js", "2:   const x = 1;", "2:   const x = 5;"))
        assertTrue(r.text!!.contains("const x = 5;"))
        assertTrue(!r.text!!.contains("2:"))
    }

    @Test fun realNumberedTextIsLeftAlone() {
        val yaml = "a:\n12: twelve\n"
        val r = EditBlocks.apply(yaml, EditBlock("f.yml", "12: twelve", "12: TWELVE"))
        assertEquals("a:\n12: TWELVE\n", r.text)
    }

    @Test fun innerSpacesAndBlankLinesTolerated() {
        val r = EditBlocks.apply(file, EditBlock("f.js", "const  x =  1;", "const x = 9;"))
        assertTrue(r.text!!.contains("const x = 9;"))
        val r2 = EditBlocks.apply(file, EditBlock("f.js", "  const x = 1;\n  return x;", "  return 7;"))
        assertTrue(r2.text!!.contains("function a() {\n  return 7;\n}"))
    }

    @Test fun failureShowsClosestLines() {
        val r = EditBlocks.apply(file, EditBlock("f.js", "function b() {\n  return 3;\n}", "x"))
        assertNull(r.text)
        assertTrue(r.error!!, r.error!!.contains("lines 7-"))
        assertTrue(r.error!!.contains("7: function b() {"))
    }

    @Test fun lineEdits() {
        assertEquals("one\nTWO\nthree", EditBlocks.applyLines("one\ntwo\nthree", 2, 2, "TWO").text)
        assertEquals("one\nnew\ntwo", EditBlocks.applyLines("one\ntwo", 2, 1, "new").text)
        assertEquals("one\ntwo\nend", EditBlocks.applyLines("one\ntwo", 3, 2, "end").text)
        assertEquals("one", EditBlocks.applyLines("one\ntwo", 2, 2, "").text)
        assertNull(EditBlocks.applyLines("one", 5, 5, "x").text)
    }

    @Test fun rangedReads() {
        val big = (1..1000).joinToString("\n") { "line $it" }
        val out = AgentActions.read(listOf("big.txt"), { big }, "big.txt", 400, 402)
        assertTrue(out.startsWith("Lines 400-402 of 1000:"))
        assertTrue(out.contains("401: line 401"))
        assertTrue(out.contains("start=403"))
        val whole = AgentActions.read(listOf("big.txt"), { big }, "big.txt")
        assertTrue(whole.contains("read_file with start="))
    }
}
