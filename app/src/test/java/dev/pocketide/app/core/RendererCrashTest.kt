package dev.pocketide.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererCrashTest {
    @Test fun theFirstCrashReloads() = assertTrue(RendererCrash.Restarts().reload(1_000))

    @Test fun aSecondCrashWithinAMinuteDoesNot() {
        val r = RendererCrash.Restarts()
        assertTrue(r.reload(1_000)); assertFalse(r.reload(30_000))
    }

    @Test fun aCrashLaterReloadsAgain() {
        val r = RendererCrash.Restarts()
        assertTrue(r.reload(1_000)); assertTrue(r.reload(120_000))
    }

    @Test fun theCheckReportSaysThereIsNoScreenshotAndKeepsErrors() {
        val t = RendererCrash.lookReport(listOf("TypeError: x is undefined"))
        assertTrue(t.contains("no report or screenshot") && t.contains("TypeError"))
    }
}
