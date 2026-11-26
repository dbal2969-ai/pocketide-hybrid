package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.192: the total benchmark's progress file and summary. */
class BenchTotalTest {
    @Test fun progressSurvivesTheFile() {
        val s = BenchTotal.State("1126-0900", listOf("hybrid", "hybrid-hard", "hybrid-reliable-both"), index = 2, runs = 3, python = false, active = false)
        assertEquals(s, BenchTotal.decode(BenchTotal.encode(s)))
        assertTrue(s.resumable)
        assertFalse(s.copy(finished = true).resumable)
        assertFalse(s.copy(index = 3).resumable)
        assertNull(BenchTotal.decode("nothing"))
    }

    @Test fun summaryTable() {
        val t = BenchTotal.table(listOf(
            BenchTotal.Row("Hybrid AI benchmark", "hybrid", 7, 8, 40, 12),
            BenchTotal.Row("Reliability benchmark (Gemini Nano and the cloud)", "hybrid-reliable-both", 90, 114, 500, 300, "stopped: every cloud model is out of free quota"),
            BenchTotal.Row("Hybrid AI benchmark (games)", "hybrid-games", 0, 0, 0, 0, ran = false)))
        assertTrue(t, t.contains("| Hybrid AI benchmark | 7 of 8 | 87% | 40 / 12 | hybrid/ |"))
        assertTrue(t, t.contains("| Hybrid AI benchmark (games) | not run yet |"))
        assertTrue(t, t.contains("| **All suites** | **97 of 122** | **79%** | 540 / 312 | |"))
        assertTrue(t, t.contains("- Reliability benchmark (Gemini Nano and the cloud): stopped: every cloud model is out of free quota"))
    }
}
