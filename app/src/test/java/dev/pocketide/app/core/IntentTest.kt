package dev.pocketide.app.core

import dev.pocketide.app.core.Intent.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.87: one decision for what a request is, in one order. */
class IntentTest {
    @Test fun eachKind() {
        assertEquals(Kind.QUESTION, Intent.of("Which file sets the tax rate?"))
        assertEquals(Kind.QUESTION, Intent.of("Found a critical indexing bug! world.js uses z*64+x"))
        assertEquals(Kind.REPORT, Intent.of("Run look_at_preview with test=all and report the results"))
        assertEquals(Kind.RESEARCH, Intent.of("Research open-source pathfinding libraries we could use"))
        assertEquals(Kind.TASK, Intent.of("Remove the barrier preventing a player from walking the whole map"))
        assertEquals(Kind.TASK, Intent.of("Run the tests and fix anything that fails"))
    }

    @Test fun aQuestionWinsOverReportAndResearch() {
        // "Can you check …?" is worded as a question and asks for no change
        assertEquals(Kind.QUESTION, Intent.of("Does the reload test pass?"))
    }

    @Test fun onlyTasksHaveNoLabel() {
        for (k in Kind.values()) assertEquals(k == Kind.TASK, k.label.isEmpty())
        assertTrue(Kind.REPORT.label.contains("changes no files"))
    }

    @Test fun theQuestionNoteFollowsTheDecision() {
        assertEquals(Habits.QUESTION_NOTE, Habits.notes("Which file sets the tax rate?"))
        assertTrue("run as a task: no question note", !Habits.notes("Which file sets the tax rate?", question = false).contains("This message is a question"))
        assertTrue(!AgentPrompt.core("Run node test.js and report", true, "", false, false, false, false, Kind.TASK).contains(Scope.REPORT_NOTE.trim().take(40)))
        assertTrue(AgentPrompt.core("Run node test.js and report", true, "", false, false, false, false).contains(Scope.REPORT_NOTE.trim().take(40)))
    }
}
