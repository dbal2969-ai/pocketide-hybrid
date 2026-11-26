package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.188: interrupted benchmark runs keep their finished tasks and resume with the interrupted task rotated to the back. */
class BenchResumeTest {
    private val ids = listOf("l01#1", "r02#1", "requests-1#1", "l01#2")
    private val s0 = BenchResume.State("hybrid-reliable", "1125-2230", runs = 2, order = ids,
        before = mapOf("provider" to "openai", "project" to "My game: v2", "mode" to "plan"))

    @Test fun stateSurvivesTheFile() {
        val s = s0.copy(done = listOf("l01#1"), current = "r02#1", interrupted = mapOf("r02#1" to 1),
            unsound = mapOf("rich-3" to "the reference fix fails here: 1 failed"),
            deselect = mapOf("rich-4" to listOf("tests/test_a.py::test_x[1,2]", "tests/test_b.py::test_y")), pythonNote = "line one\nline two")
        val back = BenchResume.decode(BenchResume.encode(s))
        assertEquals(s, back)
        assertNull(BenchResume.decode("nothing useful"))
    }

    @Test fun interruptionRotatesTheRunningTaskToTheBack() {
        val running = s0.copy(done = listOf("l01#1"), current = "r02#1")
        val s = BenchResume.interrupt(running)
        assertFalse(s.active); assertTrue(s.resumable)
        assertEquals(1, s.interrupted["r02#1"])
        assertEquals(listOf("requests-1#1", "l01#2", "r02#1"), BenchResume.remaining(s))
        assertTrue(BenchResume.givenUp(s).isEmpty())
        assertTrue(BenchResume.interruptedNote(s, 4, "PocketIDE was closed").contains("after 1 of 4 tasks") )
        assertTrue(BenchResume.interruptedNote(s, 4, "x").contains("r02#1 (1×)"))
    }

    @Test fun aTaskInterruptedTwiceIsGivenUp() {
        var s = BenchResume.interrupt(s0.copy(current = "l01#1"))
        s = BenchResume.interrupt(s.copy(active = true, current = "l01#1"))
        assertEquals(listOf("l01#1"), BenchResume.givenUp(s))
        assertEquals(listOf("r02#1", "requests-1#1", "l01#2"), BenchResume.remaining(s))
        // a run interrupted between tasks charges no task
        val between = BenchResume.interrupt(s0.copy(done = listOf("l01#1"), current = "l01#1"))
        assertTrue(between.interrupted.isEmpty())
        // a finished run has nothing to resume
        assertFalse(s0.copy(active = false, done = ids).resumable)
        assertFalse(s0.copy(active = false, finished = true).resumable)
    }

    @Test fun savedResults() {
        val r = BenchResume.parseResult(BenchResume.resultText(BenchResume.Saved(false, "node test.js failed:\nlargest gave 5", 42, "done")))!!
        assertFalse(r.passed); assertEquals("node test.js failed:\nlargest gave 5", r.why); assertEquals(42L, r.seconds); assertEquals("done", r.ended)
        assertTrue(BenchResume.fromCheck("$ node test.js\nexit 0\nok\n\nResult: passed\n")!!.passed)
        assertEquals("the answer is not right", BenchResume.fromCheck("Result: the answer is not right\n")!!.why)
        assertEquals("pocketide-hybrid-reliable-benchmark-1125-2230-partial-17of141.zip", BenchResume.partialName("hybrid-reliable", false, "1125-2230", 17, 141))
    }
}
