package dev.pocketide.app

import dev.pocketide.app.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTest {
    @Test fun readingRoundsAreRecognised() {
        assertTrue(Speed.readOnlyRound(listOf("read_file", "search")))
        assertFalse(Speed.readOnlyRound(listOf("read_file", "write_file")))
        assertFalse(Speed.readOnlyRound(listOf("run_command")))
        assertFalse(Speed.readOnlyRound(emptyList()))
        assertFalse(Speed.keepCheapTurn(listOf("edit_file")))
    }

    @Test fun routingOnlyWhereItIsSafe() {
        assertTrue(Speed.canRoute(slotAnthropic = false, mainAnthropic = false, supportsTools = true, refusedTools = false))
        assertFalse(Speed.canRoute(slotAnthropic = true, mainAnthropic = false, supportsTools = true, refusedTools = false))
        assertFalse(Speed.canRoute(false, false, supportsTools = false, refusedTools = false))
        assertFalse(Speed.canRoute(false, false, true, refusedTools = true))
        assertFalse("the first round is the main model's", Speed.routeThisRound(true, 0, true, false))
        assertFalse("not after a write", Speed.routeThisRound(true, 3, false, false))
        assertFalse("off", Speed.routeThisRound(false, 3, true, false))
        assertFalse("not while wrapping up", Speed.routeThisRound(true, 3, true, true))
        assertTrue(Speed.routeThisRound(true, 3, true, false))
    }

    @Test fun slowRepliesAndLongChats() {
        assertFalse(Speed.slow(59, false, false))
        assertTrue(Speed.slow(60, false, false))
        assertFalse("any sign of life", Speed.slow(300, true, false))
        assertFalse("a model on the phone", Speed.slow(300, false, true))
        assertFalse(Speed.longChat(1000, 5))
        assertTrue(Speed.longChat(Speed.LONG_CHAT_CHARS, 5))
        assertTrue(Speed.longChat(100, Speed.LONG_CHAT_MESSAGES))
        assertEquals(60, Speed.SLOW_SECS)
    }
}
