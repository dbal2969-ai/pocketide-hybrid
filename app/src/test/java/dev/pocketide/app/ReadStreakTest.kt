package dev.pocketide.app

import dev.pocketide.app.core.ReadStreak
import dev.pocketide.app.core.Requirements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadStreakTest {
    @Test fun refusesSearchesAfterThreeMissesUntilAChange() {
        val s = ReadStreak()
        repeat(ReadStreak.MISS_AT) { assertFalse(s.missBlocked("search")); s.searched("search", "No matches for x") }
        assertTrue(s.missBlocked("search"))
        assertTrue(s.missBlocked("find_code"))
        assertFalse("reads stay allowed", s.missBlocked("read_file"))
        s.searched("search", "Refused: ...")
        assertEquals("a refusal does not reset", ReadStreak.MISS_AT, s.misses)
        s.changed()
        assertFalse(s.missBlocked("search"))
    }

    @Test fun aHitResetsTheMissCount() {
        val s = ReadStreak()
        s.searched("search", "No matches"); s.searched("search", "No matches")
        s.searched("search", "a.js:3: hit")
        assertEquals(0, s.misses)
    }

    @Test fun theStuckRequestAsksForChanges() {
        assertFalse(Requirements.asksForResearch("Research and obtain missing game logic and mechanics to fix the user menus to show and function exactly as osrs do."))
    }
}
