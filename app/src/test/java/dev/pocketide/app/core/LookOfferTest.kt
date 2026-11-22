package dev.pocketide.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookOfferTest {
    @Test fun offeredWhenAPageExists() = assertTrue(LookOffer.offer(true, false, "rename a variable"))
    @Test fun offeredForANewProject() = assertTrue(LookOffer.offer(false, true, "make something"))
    @Test fun offeredWhenTheRequestIsAboutAPage() {
        assertTrue(LookOffer.offer(false, false, "Make a snake game in one HTML file"))
        assertTrue(LookOffer.offer(false, false, "add a landing page"))
    }
    @Test fun notOfferedForOtherWork() {
        assertFalse(LookOffer.offer(false, false, "add a CLI flag to main.py"))
        assertFalse(LookOffer.offer(false, false, "fix the unit tests in pager.kt"))
    }
}
