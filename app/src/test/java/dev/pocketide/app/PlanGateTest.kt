package dev.pocketide.app

import dev.pocketide.app.core.PlanGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanGateTest {
    @Test fun recognisesThePlanFile() {
        assertTrue(PlanGate.isPlan("PLAN.md"))
        assertTrue(PlanGate.isPlan("game/plan.md"))
        assertFalse(PlanGate.isPlan("docs/notes.md"))
    }

    @Test fun reopensEveryTickedBox() {
        val (text, n) = PlanGate.reopen("# Plan\n- [x] art\n- [ ] wood\n1. [X] goblin\n")
        assertEquals("# Plan\n- [ ] art\n- [ ] wood\n1. [ ] goblin\n", text)
        assertEquals(2, n)
    }

    @Test fun keepsTicksThatWereAlreadyThere() {
        val old = "- [x] art\n- [ ] wood\n- [ ] goblin\n"
        val (text, n) = PlanGate.reopenNew(old, "- [x] Art\n- [x] wood\n- [ ] goblin\n")
        assertEquals("- [x] Art\n- [ ] wood\n- [ ] goblin\n", text)
        assertEquals(1, n)
        assertEquals(2, PlanGate.reopenNew(null, "- [x] a\n- [x] b").second)
    }

    @Test fun countsDoneAndAll() {
        assertEquals(1 to 3, PlanGate.counts("- [x] a\n- [ ] b\n- [ ] c"))
    }
}
