package dev.pocketide.app

import dev.pocketide.app.core.ContextFit
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextMeterTest {
    @Test fun showsHowFullTheWindowIs() {
        assertEquals(" · context 50% of 32K", ContextFit.usage(3 * 16_384, 32_768))
        assertEquals(" · context 1% of 262K", ContextFit.usage(10_000, 262_144))
        assertEquals("", ContextFit.usage(10_000, null))
        assertEquals(" · context 0% of 512", ContextFit.usage(0, 512))
    }
}
