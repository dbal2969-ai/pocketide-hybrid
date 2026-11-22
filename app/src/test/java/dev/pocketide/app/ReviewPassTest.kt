package dev.pocketide.app

import dev.pocketide.app.core.ReviewPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPassTest {
    @Test fun verdicts() {
        assertTrue(ReviewPass.wantsFix("VERDICT: FIX\n- index.js:3 port has no value"))
        assertTrue(ReviewPass.wantsFix("**VERDICT: FIX**\n1. game.js:39"))
        assertFalse(ReviewPass.wantsFix("VERDICT: OK"))
        assertFalse(ReviewPass.wantsFix("Looks fine to me."))
        assertEquals("- index.js:3 port has no value", ReviewPass.findings("VERDICT: FIX\n- index.js:3 port has no value"))
        assertTrue(ReviewPass.question("build a game", listOf("a.js", "b.js")).contains("a.js, b.js"))
    }

    @Test fun acceptanceMessage() {
        val m = ReviewPass.acceptance("add a score counter", "Visible: canvas 800x600, text 'Score: 0'", true)
        assertTrue(m.contains("screenshot is attached") && m.contains("add a score counter") && m.contains("Score: 0"))
        assertFalse(ReviewPass.acceptance("x", "r", false).contains("screenshot"))
    }
}
