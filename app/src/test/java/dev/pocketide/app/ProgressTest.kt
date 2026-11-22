package dev.pocketide.app

import dev.pocketide.app.core.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTest {
    @Test fun roundTripAndContinuation() {
        val s1 = Progress.next(null, "Build an RPG with skills and enemies", listOf("World map" to "done", "Skills panel" to "doing", "Enemies" to "todo"), "2026-10-22 10:00")
        val back = Progress.decode(Progress.encode(s1))!!
        assertEquals(s1, back)
        // "continue" with mostly the same plan keeps the original request
        val s2 = Progress.next(back, "continue", listOf("World map" to "done", "Skills panel" to "done", "Enemies" to "doing", "Gathering" to "todo"), "2026-10-22 11:00")
        assertEquals("Build an RPG with skills and enemies", s2.request)
        // a different job replaces it
        val s3 = Progress.next(s2, "Make a todo app", listOf("Layout" to "todo", "Storage" to "todo"), "x")
        assertEquals("Make a todo app", s3.request)
        assertEquals("Saved progress: 2 of 4 done", Progress.summary(s2))
    }

    @Test fun contextNoteOnlyWhileUnfinished() {
        val open = Progress.encode(Progress.State("job", listOf(Progress.Item("A", "done"), Progress.Item("B", "todo")), "d"))
        val note = Progress.contextNote(open)
        assertTrue(note.contains("Current step (2 of 2): [to do] B"))
        assertTrue(note.contains("Finished: A"))
        assertFalse(note.contains("[done] A"))
        val done = Progress.encode(Progress.State("job", listOf(Progress.Item("A", "done")), "d"))
        assertEquals("", Progress.contextNote(done))
        assertEquals("", Progress.contextNote("not json"))
        assertEquals("", Progress.contextNote(null))
    }
}
