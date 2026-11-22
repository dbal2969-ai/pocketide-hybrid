package dev.pocketide.app

import dev.pocketide.app.core.Memory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryReminderTest {
    @Test
    fun theRememberRulesAreRepeated() {
        val f = "# Project instructions\n\n## About this project\n- \n\n## Conventions\n- \n\n## Remember\nRules: tick a step only after VERDICT: PASS.\nTake values from the cache."
        val r = Memory.reminder(f)
        assertTrue(r.contains("tick a step only after VERDICT: PASS"))
        assertTrue(r.contains("Take values from the cache"))
        assertTrue(!r.contains("Conventions"))
    }

    @Test
    fun anEmptyOrMissingSectionAddsNothing() {
        assertEquals("", Memory.reminder(null))
        assertEquals("", Memory.reminder("# Project instructions\n\n## Remember\n"))
        assertEquals("", Memory.reminder("# Project instructions\n\n## Remember\n- \n"))
        assertEquals("", Memory.reminder("# Only conventions\n- use tabs"))
    }

    @Test
    fun theSectionStopsAtTheNextHeading() {
        val r = Memory.reminder("## Remember\n- a rule\n\n## Later\n- not this")
        assertTrue(r.contains("a rule"))
        assertTrue(!r.contains("not this"))
    }
}
