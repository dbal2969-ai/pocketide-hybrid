package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.218: the benchmark's record of the screen and the phone's state (run 1127-2155 stopped twice with no record of why). */
class BenchEventsTest {
    private val snap = BenchEvents.Snap(interactive = false, thermal = 3, batteryPct = 31, tempC = 44.2, charging = false, powerSave = false,
        keepOnFlag = true, screenLock = true, focus = false, sinceTouchS = 1560)

    @Test fun aLineSaysWhatHappenedAndThePhonesState() {
        val l = BenchEvents.line("11-27 22:21:40", BenchEvents.LEFT, snap)
        assertEquals("11-27 22:21:40  PocketIDE left the screen | screen on no, heat severe, battery 31% 44.2°C, keep-on flag yes, " +
            "screen lock held yes, window focus no, last touch 26 min ago", l)
        assertEquals("11-27 22:21:40  run started", BenchEvents.line("11-27 22:21:40", "run started", null))
        assertEquals("unknown", BenchEvents.thermalName(-1))
        assertEquals("1 h 05 min", BenchEvents.span(3900))
    }

    /** Run 1127-2155: off from 22:21 to 01:06, across midnight. */
    @Test fun theReportListsLongSpansOffScreen() {
        val events = listOf(
            BenchEvents.line("11-27 21:55:29", "run started", snap.copy(interactive = true, thermal = 0, tempC = 33.0, focus = true, sinceTouchS = 2)),
            BenchEvents.line("11-27 22:21:30", "task 45 of 171: requests_7427#1", snap.copy(interactive = true)),
            BenchEvents.line("11-27 22:21:38", "the screen turned off", snap),
            BenchEvents.line("11-27 22:21:40", BenchEvents.LEFT, snap),
            BenchEvents.line("11-28 01:06:02", BenchEvents.BACK, snap.copy(interactive = true, thermal = 0)),
            BenchEvents.line("11-28 01:20:00", BenchEvents.LEFT, snap),      // 20 seconds: not listed
            BenchEvents.line("11-28 01:20:20", BenchEvents.BACK, snap),
            BenchEvents.line("11-28 01:34:10", BenchEvents.LEFT, snap),      // never came back before the record ends
            BenchEvents.line("11-28 02:50:04", "run stopped", null),
        ).joinToString("\n")
        val p = BenchEvents.pauses(events)
        assertEquals(p.toString(), 2, p.size)
        assertTrue(p[0], p[0].startsWith("22:21:40 to 01:06:02 (2 h 44 min). When it left: screen on no, heat severe, battery 31% 44.2°C"))
        assertTrue(p[0], p[0].endsWith("Just before: 22:21:38 the screen turned off."))
        assertTrue(p[1], p[1].startsWith("01:34:10 to the end of the record (1 h 15 min)."))
        assertTrue(BenchEvents.section(events).contains("## Times PocketIDE was off screen"))
        assertEquals("", BenchEvents.section(BenchEvents.line("11-27 21:55:29", "run started", null)))
        assertEquals(emptyList<String>(), BenchEvents.pauses("not a record\n\n"))
    }

    @Test fun theRunWaitsWhenThePhoneIsHot() {
        assertTrue(BenchEvents.tooHot(3, 40.0))
        assertTrue(BenchEvents.tooHot(-1, 46.5))
        assertFalse(BenchEvents.tooHot(2, 44.0))
        assertFalse(BenchEvents.tooHot(-1, 30.0))
        assertTrue(BenchEvents.cooled(1, 41.0))
        assertFalse(BenchEvents.cooled(3, 30.0))
        assertFalse(BenchEvents.cooled(0, 44.0))
        // 1.5.219: thermal headroom moves before the status does
        assertTrue(BenchEvents.tooHot(1, 38.0, headroom = 0.96))
        assertFalse(BenchEvents.tooHot(1, 38.0, headroom = 0.90))
        assertFalse(BenchEvents.cooled(1, 38.0, headroom = 0.85))
        assertTrue(BenchEvents.cooled(1, 38.0, headroom = 0.70))
        assertTrue(BenchEvents.snapText(snap.copy(headroom = 0.8234)).contains("heat severe (headroom 0.82), battery 31%"))
    }
}
