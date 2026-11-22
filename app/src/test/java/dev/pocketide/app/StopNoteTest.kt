package dev.pocketide.app

import dev.pocketide.app.core.Rounds
import dev.pocketide.app.core.StopNote
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopNoteTest {
    @Test fun keepsThePlanTheStepsAndWhatWasFound() {
        val t = StopNote.text(
            said = listOf("The touch handler in main.js listens for pointerdown only on the canvas.", "  "),
            steps = listOf("Looked at the page", "Searched 'touchstart'", "Read game/main.js"),
            changed = emptyList(),
            plan = listOf("Reproduce the touch controls issue" to "doing", "Fix the touch control bug" to "todo"))
        assertTrue(t.startsWith(StopNote.HEAD))
        assertTrue(t.contains("1. [doing] Reproduce the touch controls issue"))
        assertTrue(t.contains("2. [todo] Fix the touch control bug"))
        assertTrue(t.contains("No files were changed."))
        assertTrue(t.contains("Read game/main.js"))
        assertTrue(t.contains("- The touch handler in main.js listens for pointerdown only on the canvas."))
        assertFalse("blank commentary is left out", t.contains("-   "))
    }

    @Test fun listsChangedFilesAndKeepsItShort() {
        val t = StopNote.text(List(20) { "finding $it " + "x".repeat(600) }, List(60) { "step $it" }, listOf("game/main.js"), emptyList())
        assertTrue(t.contains("Files changed: game/main.js."))
        assertFalse("only the last 6 findings", t.contains("finding 13 "))
        assertTrue(t.contains("finding 19 "))
        assertFalse("only the last 25 steps", t.contains("step 34;"))
        assertTrue(t.contains("step 59"))
        assertFalse(t.contains("Plan:"))
        assertTrue(t.length < 6000)
    }

    @Test fun saysWhyItStoppedAndWhatLastPassed() {
        val t = StopNote.text(emptyList(), listOf("Edited game/world.js"), listOf("game/world.js"), emptyList(),
            reason = "the finish budget of 3 checks was spent; the last one was: the page check found 2 error(s).", lastPassed = "looked at the page (round 14)")
        assertTrue(t.contains("Why it stopped: the finish budget of 3 checks was spent; the last one was: the page check found 2 error(s)."))
        assertTrue(t.contains("Last check that passed: looked at the page (round 14)."))
        val none = StopNote.text(emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(none.contains("No check passed in this run."))
        assertFalse(none.contains("Why it stopped"))
    }

    @Test fun oneFinishBudgetInEveryMode() {
        assertTrue(Rounds.finishBudget(Rounds.DEFAULT) == 3)
        assertTrue(Rounds.finishBudget(Rounds.AUTONOMOUS) == 3)
    }

    // 1.5.125: a run that changed nothing ends with the question it needs answered, not just "stopped"
    @Test fun aLimitWithNothingChangedAsksForTheChoice() {
        val none = StopNote.wrapAsk("8 rounds used", changedNothing = true)
        assertTrue(none.contains("(8 rounds used)") && none.contains("Do not call more tools"))
        assertTrue(none.contains("end with one question that names the options"))
        assertFalse(none.contains("'Done:'"))
        val some = StopNote.wrapAsk("8 rounds used", changedNothing = false)
        assertTrue(some.contains("'Done:'") && some.contains("'Left:'"))
        assertFalse(some.contains("question"))
    }
}
