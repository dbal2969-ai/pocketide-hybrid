package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightShiftTest {
    @Test fun schedulesAreReadInPhoneTimeAndRunInUtc() {
        val ny = -300   // New York in winter
        val p = NightShift.parse("at 3am: run the tests and fix what broke", ny)!!
        assertEquals("run the tests and fix what broke", p.task)
        assertEquals(8, p.hour); assertTrue(p.minute in 7..46); assertEquals("*", p.days)
        assertTrue(p.label.startsWith("every day at 3:"))
        val w = NightShift.parse("weekdays at 22:30 update the dependencies", ny)!!
        assertEquals("30 3 * * 2-6", NightShift.cron(w))     // 22:30 Mon–Fri in New York is 03:30 Tue–Sat in UTC
        val m = NightShift.parse("mondays: tidy up", 60)!!       // Berlin, 3am default
        assertEquals(2, m.hour); assertEquals("1", m.days)
        assertEquals("0", NightShift.parse("on monday at 1am: x", 120)!!.days)    // 1am Monday at UTC+2 is Sunday 23:00 UTC
        assertEquals(12, NightShift.parse("at 12pm: x", 0)!!.hour); assertEquals(0, NightShift.parse("at 12:15am: x", 0)!!.hour)
        assertNull(NightShift.parse("at 3am", 0)); assertNull(NightShift.parse("at 25:00 x", 0)); assertNull(NightShift.parse("at 13pm x", 0))
    }

    @Test fun theWorkflowRunsTheAgentOnItsOwnIssue() {
        val p = NightShift.parse("at 3am: run the tests", 0)!!
        val w = NightShift.workflow(p)
        assertTrue(w.contains("- cron: \"" + NightShift.cron(p) + "\"") && w.contains("workflow_dispatch:"))
        assertTrue(w.contains("open('.github/pocketide/nightly.md'") && w.contains("ISSUE_BODY<<POCKETIDE_EOF") && w.contains("run: python3 .github/pocketide/agent.py"))
        assertTrue(w.contains("\${{ secrets.POCKETIDE_API_KEY }}") && !w.contains("\${'$'}"))
        assertEquals("every day at 3:" + "%02d".format(p.minute), NightShift.describe(w))
        assertNull(NightShift.describe(null))
    }
}
