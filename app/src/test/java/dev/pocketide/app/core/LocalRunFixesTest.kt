package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local benchmark run 1 (2026-11-25, 12 of 20): each failure, as the app now handles it. */
class LocalRunFixesTest {
    @Test fun changeNothingMakesARequestReadOnly() {
        assertEquals(Intent.Kind.QUESTION, Intent.of("Which file defines MAX_LIVES? Reply with the file name only. Change nothing."))
        assertEquals(Intent.Kind.QUESTION, Intent.of("Explain what parse() in csv.js does. Change nothing."))
        assertEquals(Intent.Kind.REPORT, Intent.of("Run node test.js and tell me whether it passes. Change nothing."))
        assertEquals(Intent.Kind.QUESTION, Intent.of("What does step() return? Don't change anything."))
        assertEquals("…nothing else is a limit, not read-only", Intent.Kind.TASK, Intent.of("Fix add in app.js. Change nothing else."))
        assertEquals(Intent.Kind.TASK, Intent.of("Fix the login bug without changing the API"))
        assertEquals(Intent.Kind.TASK, Intent.of("Add dark mode"))
        assertEquals("a change word inside a question", Intent.Kind.QUESTION, Intent.of("Which file defines add? Reply with the file name only. Change nothing."))
        assertEquals("an order alongside", Intent.Kind.TASK, Intent.of("Change nothing in the tests. Fix add in app.js."))
    }

    @Test fun aSearchForTheStartOfALineReplacesTheWholeLine() {
        val cur = "document.addEventListener('keydown', e => {\n  if (e.key === 'ArrowLeft') move('left');\n  if (e.key === 'ArrowRight') move('right');\n});\n"
        val r = EditBlocks.apply(cur, EditBlock("controls.js", "if (e.key === 'ArrowLeft')", "if (e.key === 'ArrowLeft') move('left');\n  if (e.key === 'ArrowUp') move('up');"))
        assertEquals(cur.replace("move('left');\n", "move('left');\n  if (e.key === 'ArrowUp') move('up');\n"), r.text)
    }

    @Test fun aBlocksFirstLineWithAWholeNewBlockReplacesTheBlock() {
        val cur = "function label(user) {\n  return user.fullname + ' (' + user.age + ')';\n}\nmodule.exports = { label };\n"
        val r = EditBlocks.apply(cur, EditBlock("user.js", "function label(user) {", "function label(user) {\n  return user.name + ' (' + user.age + ')';\n}"))
        assertEquals("function label(user) {\n  return user.name + ' (' + user.age + ')';\n}\nmodule.exports = { label };\n", r.text)
        val cb = "document.addEventListener('keydown', e => {\n  a();\n});\nx();\n"
        val r2 = EditBlocks.apply(cb, EditBlock("c.js", "document.addEventListener('keydown', e => {", "document.addEventListener('keydown', e => {\n  b();\n});"))
        assertEquals("document.addEventListener('keydown', e => {\n  b();\n});\nx();\n", r2.text)
    }

    @Test fun anInsertAtTheTopOfABlockIsNotWidened() {
        val cur = "function a() {\n  x();\n}\n"
        val r = EditBlocks.apply(cur, EditBlock("a.js", "function a() {", "function a() {\n  first();"))
        assertEquals("function a() {\n  first();\n  x();\n}\n", r.text)
    }

    @Test fun editLinesThatWouldDeleteOtherLinesIsCaught() {
        val cur = "function delta(d) {\n  switch (d) {\n    case 'up': return [0, -1];\n    case 'down': return [0, 1];\n    case 'right': return [1, 0];\n    default: return [0, 0];\n  }\n}\n"
        assertNotNull(EditBlocks.linesLost(cur, 3, 5, "    case 'left': return [-1, 0];", "Make delta('left') return [-1, 0]"))
        assertNull("insert", EditBlocks.linesLost(cur, 6, 5, "    case 'left': return [-1, 0];", "Make delta('left') return [-1, 0]"))
        assertNull("asked to replace", EditBlocks.linesLost(cur, 3, 5, "x", "Replace the up, down and right cases"))
        assertNull("one line", EditBlocks.linesLost(cur, 3, 3, "    case 'up': return [0, -2];", "Change up"))
    }

    @Test fun nanoIsToldToExport() = assertTrue(SmallLocal.system(Tools.smallCatalogue(false)).contains("module.exports"))

    /** Local run 2, l08: told only "label gave Unknown Name (30)", Nano guessed twice and never read test.js. */
    @Test fun nanoIsToldToReadAFailingTest() = assertTrue(SmallLocal.system(Tools.smallCatalogue(false)).contains("read the test file to see what it expects"))
}
