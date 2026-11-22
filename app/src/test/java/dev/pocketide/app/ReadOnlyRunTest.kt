package dev.pocketide.app

import dev.pocketide.app.core.ReadOnlyRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.127: a refused change is labelled as refused, never as if it happened ("Wrote index.html (read-only)"). */
class ReadOnlyRunTest {
    @Test fun refusedChangesSayTheyWereNotDone() {
        assertEquals("Not done (read-only): write index.html", ReadOnlyRun.label("write_file", "index.html"))
        assertEquals("Not done (read-only): edit src/a.js", ReadOnlyRun.label("multi_edit", "src/a.js"))
        assertEquals("Not done (read-only): run npm test", ReadOnlyRun.label("run_command", "npm test\nmore"))
        assertEquals("Not done (read-only): delete", ReadOnlyRun.label("delete_file", null))
        assertEquals("Not done (read-only): game assets", ReadOnlyRun.label("game_assets", " "))
        assertFalse(ReadOnlyRun.label("write_file", "x").startsWith("Wrote"))
    }

    @Test fun theRefusalDoesNotCallEveryRunAReview() {
        assertFalse(ReadOnlyRun.REFUSAL.contains("review"))
        assertTrue(ReadOnlyRun.REFUSAL.contains("in your reply"))
    }
}
