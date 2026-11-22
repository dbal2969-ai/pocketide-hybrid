package dev.pocketide.app

import dev.pocketide.app.core.Sops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SopsTest {
    @Test fun projectOverridesBuiltIn() {
        val mine = Sops.parse(".pocketide/sops/debug.sop.md", "# My debugging\n1. Look.\n2. Fix.")!!
        assertEquals("My debugging", mine.title)
        assertEquals("1. Look.\n2. Fix.", mine.body)
        val all = Sops.all(listOf(mine))
        assertFalse(Sops.find(all, "debug")!!.builtIn)
        assertEquals(Sops.BUILT_IN.size, all.size)
        assertNotNull(Sops.find(all, "GAME-QA"))
    }

    @Test fun parsingAndPrompt() {
        assertNull(Sops.parse("notes.md", "x"))
        assertNull(Sops.parse("a.sop.md", "  "))
        assertEquals("my-sop", Sops.parse("my sop.sop.md", "do it")!!.name)
        val p = Sops.prompt(Sops.BUILT_IN[0], "the login crash")
        assertTrue(p.contains("MUST") && p.endsWith("Task: the login crash"))
        assertTrue(Sops.listing(Sops.all(emptyList())).contains("• debug"))
    }
}
