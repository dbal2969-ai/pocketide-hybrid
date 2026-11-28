package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.218: edit_lines' message and the repeated insert (reliability run 1127-2155, l12 and r10). */
class LineEditsTest {
    private val listener = "document.addEventListener('touchstart', e => { move('tap'); });"
    private val before = "// Keyboard controls\ndocument.addEventListener('keydown', e => {\n  move(e.key);\n});"

    /** l12: "Edited lines 2-1 … have shifted; read again" was read as a failure, and the insert was sent eight times. */
    @Test fun anInsertIsSaidPlainlyWithTheLinesAsTheyAreNow() {
        val now = EditBlocks.applyLines(before, 2, 1, listener).text!!
        val m = LineEdits.done("controls.js", 2, 1, listener, now)
        assertTrue(m, m.startsWith("Inserted 1 line into controls.js after line 1: it is now line 2. It is saved, so do not insert it again."))
        assertTrue(m, m.contains("1: // Keyboard controls\n2: $listener\n3: document.addEventListener('keydown', e => {\n4:   move(e.key);"))
        assertFalse(m, m.contains("shifted"))
        assertFalse(m, m.contains("2-1"))
    }

    @Test fun replacingAndRemovingAreSaidPlainlyToo() {
        val now = EditBlocks.applyLines(before, 3, 3, "  if (e.repeat) return;\n  move(e.key);").text!!
        val m = LineEdits.done("controls.js", 3, 3, "  if (e.repeat) return;\n  move(e.key);", now)
        assertTrue(m, m.startsWith("Replaced line 3 of controls.js with 2 lines (now lines 3-4). It is saved."))
        assertTrue(m, m.endsWith("1: // Keyboard controls\n2: document.addEventListener('keydown', e => {\n3:   if (e.repeat) return;\n4:   move(e.key);\n5: });"))
        val gone = EditBlocks.applyLines(before, 1, 1, "").text!!
        assertTrue(LineEdits.done("controls.js", 1, 1, "", gone).startsWith("Removed line 1 from controls.js. It is saved."))
        // a long replacement shows its start and its end
        val long = (1..40).joinToString("\n") { "line $it" }
        val shown = LineEdits.done("a.txt", 1, 1, long, long)
        assertTrue(shown, shown.contains("7: line 7\n…\n34: line 34"))
    }

    @Test fun theSameInsertIsNotMadeTwice() {
        val now = EditBlocks.applyLines(before, 2, 1, listener).text!!
        val applied = setOf(LineEdits.insertKey("controls.js", listener))
        // the same lines at another line number, and with other indentation: still the same insert
        val why = LineEdits.repeatedInsert("controls.js", 4, 3, "  $listener", now, applied)
        assertTrue(why, why!!.startsWith("Not inserted: controls.js already has this exact line at line 2, from your earlier edit"))
        // not refused: never inserted; another file; the lines were removed since; a replacement; a short line
        assertNull(LineEdits.repeatedInsert("controls.js", 4, 3, listener, now, emptySet()))
        assertNull(LineEdits.repeatedInsert("other.js", 4, 3, listener, now, applied))
        assertNull(LineEdits.repeatedInsert("controls.js", 2, 1, listener, before, applied))
        assertNull(LineEdits.repeatedInsert("controls.js", 2, 2, listener, now, applied))
        assertNull(LineEdits.repeatedInsert("a.js", 2, 1, "}", "{\n}\n", setOf(LineEdits.insertKey("a.js", "}"))))
        assertEquals(LineEdits.insertKey("a.js", "  x();\n  y();"), LineEdits.insertKey("a.js", "x();\ny();\n"))
    }
}
