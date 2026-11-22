package dev.pocketide.app

import dev.pocketide.app.core.Steer
import dev.pocketide.app.core.ToolCall
import dev.pocketide.app.core.Tools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteerTest {
    private val calls = listOf(ToolCall("1", "read_file", "{}"), ToolCall("2", "edit_file", "{}"), ToolCall("3", "run_command", "{}"))

    @Test fun everyCallLeftInTheRoundGetsAResult() {
        val r = Steer.skipRest(calls, 1)
        assertEquals(listOf("2", "3"), r.map { it.first.id })
        assertTrue(r.all { it.second == Tools.SKIPPED_FOR_STEER })
    }

    @Test fun nothingLeftMeansNothingSkipped() {
        assertTrue(Steer.skipRest(calls, 3).isEmpty())
        assertEquals(3, Steer.skipRest(calls, -2).size)
    }

    @Test fun skippedResultTellsTheModelToReadTheMessage() {
        assertTrue(Tools.SKIPPED_FOR_STEER.startsWith("Skipped"))
        assertTrue(dev.pocketide.app.core.StuckDetector.isError(Tools.SKIPPED_FOR_STEER))
    }

    @Test fun readLabelShowsTheWaitOnlyWhenItWasLong() {
        assertEquals("read by the assistant", Steer.readLabel(400))
        assertEquals("read by the assistant after 12s", Steer.readLabel(12_900))
    }

    // 1.5.125: the field run put the owner's messages off "for the next iteration"
    @Test fun aMessageDuringTheRunJoinsThisJob() {
        val m = Steer.message("recipes need instructions")
        assertTrue(m.contains("\"recipes need instructions\""))
        assertTrue(m.contains("do it in this run, not later"))
    }
}
