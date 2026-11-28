package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.219: the Gemini Nano release is chosen from what AICore says the phone has. */
class NanoPickTest {
    @Test fun theFirstReadyReleaseIsUsed() {
        val fast = NanoPick.order(full = false)
        assertEquals(listOf("preview_fast", "preview", "stable"), fast)
        // the owner's Galaxy S24+: only the fast preview
        assertEquals("preview_fast", NanoPick.choose(fast, mapOf("preview_fast" to "AVAILABLE", "preview" to "UNAVAILABLE", "stable" to "UNAVAILABLE")))
        // a phone that is not in the AICore preview: only the stable model
        assertEquals("stable", NanoPick.choose(fast, mapOf("preview_fast" to "UNAVAILABLE", "preview" to "UNAVAILABLE", "stable" to "AVAILABLE")))
        // "Full" ticked, but only Fast is on the phone
        assertEquals("preview_fast", NanoPick.choose(NanoPick.order(full = true), mapOf("preview" to "DOWNLOADABLE", "preview_fast" to "AVAILABLE", "stable" to "UNAVAILABLE")))
        // nothing ready: the one that can be downloaded, so the Download button works; nothing at all: the first
        assertEquals("stable", NanoPick.choose(fast, mapOf("preview_fast" to "UNAVAILABLE", "preview" to "UNAVAILABLE", "stable" to "DOWNLOADABLE")))
        assertEquals("preview_fast", NanoPick.choose(fast, emptyMap()))
        assertEquals("preview, fast: available; preview, full: unavailable; stable: not asked",
            NanoPick.describe(fast, mapOf("preview_fast" to "AVAILABLE", "preview" to "UNAVAILABLE")))
    }

    @Test fun aBusyModelIsWorthAShortWait() {
        assertTrue(NanoPick.busy("GenAiException: ErrorCode.BUSY: too many requests"))
        assertFalse(NanoPick.busy("PER_APP_BATTERY_USE_QUOTA_EXCEEDED"))
        assertFalse(NanoPick.busy("BACKGROUND_USE_BLOCKED"))
        assertFalse(NanoPick.busy("the model is busybody"))
    }

    /** Run 1127-2155: six identical rewrites at the same temperature. Each rewrite after the first gets its own seed. */
    @Test fun everyRewriteAfterTheFirstHasItsOwnSeed() {
        assertNull(NanoPick.seed(0, 0))
        val seeds = (0..2).flatMap { n -> (1..5).map { k -> NanoPick.seed(n, k)!! } }
        assertEquals(seeds.size, seeds.toSet().size)
        assertTrue(seeds.all { it > 0 })
    }
}
