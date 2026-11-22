package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMissTest {
    @Test fun threeMissesInARowBlockSearch() {
        val s = ReadStreak()
        repeat(3) { s.searched("search", "No matches for 'regionSquares'") }
        assertTrue(s.missBlocked("search"))
        assertTrue(s.missBlocked("find_code"))
        assertFalse(s.missBlocked("read_file"))
    }

    @Test fun aHitResetsTheCount() {
        val s = ReadStreak()
        s.searched("search", "No matches for 'a'"); s.searched("search", "No matches for 'b'")
        s.searched("search", "src/main.js:12: function walk()")
        assertEquals(0, s.misses)
        assertFalse(s.missBlocked("search"))
    }

    @Test fun refusalDoesNotResetTheCount() {
        val s = ReadStreak()
        repeat(3) { s.searched("search", "No matches") }
        s.searched("search", ReadStreak.MISSED)
        assertTrue(s.missBlocked("search"))
    }

    @Test fun editClearsTheCount() {
        val s = ReadStreak()
        repeat(3) { s.searched("search", "No matches") }
        s.changed()
        assertFalse(s.missBlocked("search"))
    }

    // 1.5.110: the refusal says to read a file and then search for a name seen there, so a read lets searching start again
    @Test fun aReadAfterTheRefusalLetsSearchingAgain() {
        val s = ReadStreak()
        repeat(3) { s.searched("search", "No matches") }
        s.searched("read_file", "1: const defaults = require('../config/defaults.json');")
        assertFalse(s.missBlocked("search"))
        repeat(3) { s.searched("search", "No matches") }
        s.searched("file_outline", "src/config.js: merge (4), load (8)")
        assertFalse(s.missBlocked("search"))
    }

    @Test fun aFailedReadDoesNot() {
        val s = ReadStreak()
        repeat(3) { s.searched("search", "No matches") }
        s.searched("read_file", "Error: src/settings.js does not exist")
        assertTrue(s.missBlocked("search"))
    }
}
