package dev.pocketide.app

import dev.pocketide.app.core.Rereads
import dev.pocketide.app.core.StuckDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckDetectorTest {
    @Test fun sameCallSameResultThreeTimes() {
        val d = StuckDetector()
        d.record("run_command", "{\"command\":\"npm test\"}", "1 failing"); d.record("run_command", "{\"command\":\"npm test\"}", "1 failing")
        assertNull(d.reason)
        d.record("run_command", "{\"command\":\"npm test\"}", "1 failing")
        assertTrue(d.reason!!.contains("3 times"))
    }

    @Test fun sameCallWithADifferentResultIsNotALoop() {
        val d = StuckDetector()
        repeat(5) { d.record("run_command", "{}", "output $it") }
        assertNull(d.reason)
    }

    @Test fun threeErrorsInARow() {
        val d = StuckDetector()
        d.record("edit_file", "a", "Edit not applied to x.js: not found."); d.record("edit_file", "b", "Edit not applied to x.js: nope.")
        assertNull(d.reason)
        d.record("read_file", "c", "contents"); d.record("edit_file", "d", "Edit not applied to y.js: no.")
        assertNull("a good call breaks the streak", d.reason)
        d.record("edit_file", "e", "Refused: x"); d.record("edit_file", "f", "Error: y")
        assertNotNull(d.reason)
    }

    @Test fun alternationOverSixCalls() {
        val d = StuckDetector()
        var n = 0
        repeat(2) { d.record("run_command", "test", "run ${n++}"); d.record("edit_file", "b", "Edited ${n++}") }
        d.record("run_command", "test", "run ${n++}")
        assertNull(d.reason)
        d.record("edit_file", "b", "Edited ${n++}")
        assertTrue(d.reason!!.contains("alternated"))
    }

    @Test fun differentCallsAreNotAnAlternation() {
        val d = StuckDetector()
        repeat(3) { d.record("read_file", "a$it", "A$it"); d.record("search", "b$it", "B$it") }
        assertNull(d.reason)
    }

    @Test fun twelveStaleRounds() {
        val d = StuckDetector()
        d.record("read_file", "a", "same"); d.endRound()
        repeat(11) { d.record("search", "q$it", "same"); d.endRound() }
        assertNull("round 1 was new, so 11 stale so far", d.reason)
        d.record("search", "q99", "same"); d.endRound()
        assertTrue(d.reason!!.contains("12 rounds"))
    }

    @Test fun newInformationOrAChangeResetsTheCount() {
        val d = StuckDetector(staleRounds = 3)
        d.record("a", "1", "x"); d.endRound()
        d.record("a", "2", "x"); d.endRound(); d.record("a", "3", "x"); d.endRound()
        d.record("a", "4", "fresh"); d.endRound()
        assertNull(d.reason)
        d.record("a", "5", "fresh"); d.endRound(); d.record("a", "6", "fresh"); d.endRound()
        assertNull(d.reason)
        d.changed()
        repeat(2) { d.record("a", "7$it", "fresh"); d.endRound() }
        assertNull(d.reason)
    }

    @Test fun errorDetection() {
        assertTrue(StuckDetector.isError("Refused: read-only"))
        assertTrue(StuckDetector.isError("Edit not applied to a: x"))
        assertFalse(StuckDetector.isError("No matches for x"))
        assertFalse(StuckDetector.isError("Edited a.js"))
        assertEquals(3, StuckDetector.SAME_RESULT)
    }

    // 1.5.124: the field run's loop, re-reading a file it already had in new ranges, which no exact-match rule could see
    @Test fun readingFilesItAlreadyHadSixTimesIsALoop() {
        val d = StuckDetector()
        repeat(StuckDetector.REREADS - 1) { d.reread() }
        assertNull(d.reason)
        d.reread()
        assertTrue(d.reason!!, d.reason!!.contains("reads of files it already had"))
    }

    @Test fun aChangedFileStartsTheRereadCountAgain() {
        val d = StuckDetector()
        repeat(StuckDetector.REREADS - 1) { d.reread() }
        d.changed()
        repeat(StuckDetector.REREADS - 1) { d.reread() }
        assertNull(d.reason)
        assertEquals(StuckDetector.REREADS - 1, d.rereads)
    }

    @Test fun commandsThatOnlyPrintAFileAreRecognised() {
        assertEquals(listOf("js/recipes.js"), Rereads.printed("cat -n js/recipes.js | sed -n 1,40p"))
        assertEquals(listOf("a.js"), Rereads.printed("sed -n '120,180p' ./a.js"))
        assertEquals(listOf("a.js"), Rereads.printed("head -n 40 a.js"))
        assertEquals(listOf("a.js", "b.js"), Rereads.printed("cat a.js b.js | tail -20"))
        // anything else is not a re-read: a search, a test run, a write, a pipe into a program, a filter that names a file
        assertEquals(emptyList<String>(), Rereads.printed("grep -n add a.js"))
        assertEquals(emptyList<String>(), Rereads.printed("node test.js"))
        assertEquals(emptyList<String>(), Rereads.printed("cat a.js > b.js"))
        assertEquals(emptyList<String>(), Rereads.printed("cat a.js | node"))
        assertEquals(emptyList<String>(), Rereads.printed("cat a.js | sed -n 1,5p b.js"))
        assertEquals(emptyList<String>(), Rereads.printed("cat \$(ls)"))
    }
}
