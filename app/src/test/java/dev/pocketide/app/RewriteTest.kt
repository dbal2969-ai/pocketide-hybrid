package dev.pocketide.app

import dev.pocketide.app.core.Rewrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RewriteTest {
    private val big = (1..822).joinToString("\n") { "line $it" }
    private val small = (1..43).joinToString("\n") { "line $it" }

    @Test fun aNewFileIsAlwaysAllowed() {
        assertNull(Rewrite.refusal("game/main.js", null, small, "make the game"))
    }

    @Test fun theWholeGameEntryPointCannotBeReplacedWithAShortFile() {
        val r = Rewrite.refusal("game/main.js", big, small, "fix the touch controls")
        assertTrue(r!!, r.contains("822 lines") && r.contains("edit_file"))
    }

    @Test fun aLongFileCannotBeWrittenWholeEvenWithTheSameLength() {
        assertTrue(Rewrite.refusal("game/world.js", big, big, "fix the touch controls") != null)
    }

    @Test fun aShortFileMayBeEdited() {
        val short = (1..30).joinToString("\n")
        assertNull(Rewrite.refusal("game/util.js", short, short + "\nmore", "add a helper"))
    }

    @Test fun aSmallFileThatHalvesIsRefused() {
        val was = (1..40).joinToString("\n")
        val now = (1..15).joinToString("\n")
        assertTrue(Rewrite.refusal("game/util.js", was, now, "tidy") != null)
    }

    @Test fun thePersonsOwnRewriteRequestIsAllowed() {
        assertNull(Rewrite.refusal("game/main.js", big, small, "Rewrite main.js from scratch"))
    }

    @Test fun aPlanThatDropsStepsIsRefused() {
        val plan = "# Plan\n- [x] Reproduce the touch bug\n- [ ] Fix the touch bug\n- [ ] Verify with look_at_preview\n"
        val narrowed = "# World Plan\n- [ ] Add skill training\n"
        val r = Rewrite.planRefusal("PLAN.md", plan, narrowed, "continue the touch fix")
        assertTrue(r!!, r.contains("drop") && r.contains("Fix the touch bug".lowercase()))
    }

    @Test fun ticksAndAddedStepsAreNotDrops() {
        val plan = "- [ ] Reproduce the touch bug\n- [ ] Fix the touch bug\n"
        val ticked = "- [x] Reproduce the touch bug\n- [ ] Fix the touch bug\n- [ ] Verify the fix\n"
        assertNull(Rewrite.planRefusal("PLAN.md", plan, ticked, "continue"))
        assertTrue(Rewrite.droppedSteps(plan, ticked).isEmpty())
    }

    @Test fun onlyPlanFilesGetThePlanRule() {
        assertNull(Rewrite.planRefusal("notes.md", "- [ ] a step\n", "", "go"))
    }

    @Test fun aPlanRewriteTheyAskedForIsAllowed() {
        assertNull(Rewrite.planRefusal("PLAN.md", "- [ ] old step\n", "- [ ] new\n", "rewrite the plan"))
    }

    @Test fun linesCountsTheSameAsTheEditors() {
        assertEquals(0, Rewrite.lines(""))
        assertEquals(3, Rewrite.lines("a\nb\nc"))
        assertFalse(Rewrite.asked("fix the minimap"))
    }
}
