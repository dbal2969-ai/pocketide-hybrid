package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEditTest {
    @Test fun aRenameOnOneLineIsSeen() {
        assertEquals("playerHp" to "playerHealth", NextEdit.renamed("let playerHp = 10;", "let playerHealth = 10;"))
        assertEquals("hp" to "health", NextEdit.renamed("  hp = hp - dmg;", "  health = health - dmg;"))
        assertNull(NextEdit.renamed("let a = 10;", "let a = 12;"))                 // a value, not a name
        assertNull(NextEdit.renamed("f(a, b)", "f(c, d)"))                          // two names changed
        assertNull(NextEdit.renamed("x = 1", "x = 1"))
        assertNull(NextEdit.renamed("let x = 1", "const x = 1"))                    // a keyword
        assertNull(NextEdit.renamed("a.b", "a . b"))                                // the spacing changed
    }

    @Test fun onlyCodeIsRenamed() {
        val text = "let hp = 10;\n// hp is shown\nsay('hp: ' + hp);\nconst hpMax = hp * 2; /* hp */\n"
        val places = NextEdit.places(text, "hp")
        assertEquals(3, places.size)
        val line0 = 0..text.indexOf('\n')
        assertEquals("let hp = 10;\n// hp is shown\nsay('hp: ' + health);\nconst hpMax = health * 2; /* hp */\n", NextEdit.apply(text, "hp", "health", places, line0))
        assertEquals(1, NextEdit.places("def f():\n    # hp here\n    return hp\n", "hp").size)
        assertEquals(Triple(1, "b = 2", "c = 2"), NextEdit.changedLine("a\nb = 2\nz", "a\nc = 2\nz"))
        assertNull(NextEdit.changedLine("a\nb", "a\nb\nc"))
    }

    // ---- the model case (1.5.125) ----

    private val before = "function area(w) {\n  return w * w;\n}\n\nconst a = area(3);\nconsole.log(a);\n"
    private val after = before.replace("function area(w) {", "function area(w, h) {")
    private fun ok(v: NextEdit.Verdict) = v as? NextEdit.Verdict.Ok ?: throw AssertionError("refused: $v")
    private fun why(v: NextEdit.Verdict) = (v as? NextEdit.Verdict.No)?.why

    @Test fun theRecentEditIsTheChangedLinesOnly() {
        val r = NextEdit.recentEdit(before, after)!!
        assertEquals(0, r.firstLine)
        assertEquals("function area(w, h) {", after.substring(r.start, r.end))
        assertEquals("@@ line 1 @@\n-function area(w) {\n+function area(w, h) {", r.diff)
        // a line added between two others: only the new line counts, not its neighbours
        val added = NextEdit.recentEdit("a\nc", "a\nb\nc")!!
        assertEquals("b", "a\nb\nc".substring(added.start, added.end))
        assertEquals("@@ line 2 @@\n+b", added.diff)
        // a deleted line leaves an empty region, so nothing is fenced off
        val gone = NextEdit.recentEdit("a\nb\nc", "a\nc")!!
        assertEquals(gone.start, gone.end)
        assertNull(NextEdit.recentEdit("same", "same"))
    }

    @Test fun theBaseIsTheOldestPauseWhoseChangeIsSmall() {
        val big = (1..40).joinToString("\n") { "line $it" }
        val pasted = big + "\nx"
        // the oldest snapshot differs by a 40-line paste: too big, so the newer one is used
        val r = NextEdit.pickBase(listOf("", big), pasted)!!
        assertEquals("@@ line 41 @@\n+x", r.diff)
        // two quick edits are seen together from the older snapshot
        val mid = before.replace("area(w)", "area(w, h")
        assertEquals(NextEdit.recentEdit(before, after), NextEdit.pickBase(listOf(before, mid), after))
        assertNull(NextEdit.pickBase(listOf(after), after))
    }

    @Test fun thePromptHoldsTheFileTheDiffAndTheCursor() {
        val r = NextEdit.recentEdit(before, after)!!
        val p = NextEdit.prompt("geo.js", after, r, after.indexOf("h)") + 1)
        assertTrue(p.startsWith("File: geo.js\n\n" + after))
        assertTrue(p.contains("-function area(w) {\n+function area(w, h) {"))
        assertTrue(p.contains("The cursor is on line 1: function area(w, h) {"))
        assertTrue(NextEdit.SYSTEM.contains("<<<<<<< SEARCH") && NextEdit.SYSTEM.contains("NONE"))
        // a big file is sent as a window around the edit, cut at whole lines
        val bigText = (1..3000).joinToString("\n") { "const v$it = $it;" }
        val edited = bigText.replace("const v2000 = 2000;", "const v2000 = 2001;")
        val br = NextEdit.recentEdit(bigText, edited)!!
        val bp = NextEdit.prompt("big.js", edited, br, br.start)
        assertTrue(bp.contains("(part of the file, from line "))
        assertTrue(bp.contains("const v2000 = 2001;"))
        assertFalse(bp.contains("const v1 = 1;\n"))
        assertTrue(bp.length < NextEdit.FILE_CHARS + 1000)
    }

    @Test fun aGoodReplyIsParsed() {
        val reply = "<<<<<<< SEARCH\nconst a = area(3);\n=======\nconst a = area(3, 4);\n>>>>>>> REPLACE"
        assertEquals(NextEdit.Suggestion("const a = area(3);", "const a = area(3, 4);"), NextEdit.parse(reply))
    }

    @Test fun aFencedReplyWithWordsAroundIsParsed() {
        val reply = "<think>the call needs h</think>Here is the next edit:\n```js\n<<<<<<< SEARCH\nconst a = area(3);\n=======\nconst a = area(3, 4);\n>>>>>>> REPLACE\n```\n"
        assertEquals(NextEdit.Suggestion("const a = area(3);", "const a = area(3, 4);"), NextEdit.parse(reply))
    }

    @Test fun emptyNoneBrokenAndDoubleRepliesGiveNothing() {
        assertNull(NextEdit.parse(""))
        assertNull(NextEdit.parse("   \n"))
        assertNull(NextEdit.parse("NONE"))
        assertNull(NextEdit.parse("You should also update the call on line 5."))
        assertNull(NextEdit.parse("<<<<<<< SEARCH\nconst a = area(3);\n=======\nconst a = area(3, 4);\n"))         // no REPLACE line
        assertNull(NextEdit.parse("<<<<<<< SEARCH\nx\n>>>>>>> REPLACE"))                                         // no divider
        val one = "<<<<<<< SEARCH\na\n=======\nb\n>>>>>>> REPLACE\n"
        assertNull(NextEdit.parse(one + one))                                                                    // two edits: unsure
        // an empty SEARCH is parsed but refused by the check
        val empty = NextEdit.parse("<<<<<<< SEARCH\n=======\nx\n>>>>>>> REPLACE")!!
        assertEquals("empty", why(NextEdit.validate(after, empty, null)))
    }

    @Test fun aGoodSuggestionPassesAndApplies() {
        val r = NextEdit.recentEdit(before, after)!!
        val v = ok(NextEdit.validate(after, NextEdit.Suggestion("const a = area(3);", "const a = area(3, 4);"), r))
        assertEquals(4, v.line)
        assertEquals(after.replace("area(3)", "area(3, 4)"), NextEdit.applyTo(after, v))
        assertEquals("Next edit, line 5: const a = area(3); → const a = area(3, 4);", NextEdit.describe(v))
    }

    @Test fun ambiguousOldTextIsRefused() {
        val text = "f(1);\nf(1);\n"
        assertEquals("not unique", why(NextEdit.validate(text, NextEdit.Suggestion("f(1);", "f(1, 2);"), null)))
        assertEquals("not found", why(NextEdit.validate(text, NextEdit.Suggestion("g(1);", "g(1, 2);"), null)))
    }

    @Test fun oldTextInsideTheJustEditedLinesIsRefused() {
        val r = NextEdit.recentEdit(before, after)!!
        assertEquals("inside the edit just made", why(NextEdit.validate(after, NextEdit.Suggestion("function area(w, h) {", "function area(w, h = 1) {"), r)))
        // a block that only starts on the edited line is refused too
        assertEquals("inside the edit just made", why(NextEdit.validate(after, NextEdit.Suggestion("h) {\n  return w * w;", "h) {\n  return w * h;"), r)))
        // the next line is fine
        ok(NextEdit.validate(after, NextEdit.Suggestion("  return w * w;", "  return w * h;"), r))
    }

    @Test fun oversizeAndNoChangeAreRefused() {
        val lines = (1..12).joinToString("\n") { "x$it();" }
        assertEquals("too big", why(NextEdit.validate(lines, NextEdit.Suggestion(lines, lines.replace("x", "y")), null)))
        val longLine = "const s = '" + "a".repeat(700) + "';"
        assertEquals("too big", why(NextEdit.validate(longLine, NextEdit.Suggestion(longLine, longLine + " "), null)))
        assertEquals("no change", why(NextEdit.validate("a\n", NextEdit.Suggestion("a", "a"), null)))
        assertEquals("markers", why(NextEdit.validate("a\n", NextEdit.Suggestion("a", "b\n======="), null)))
    }

    @Test fun onByDefaultOnlyOffMeteredNetworks() {
        assertTrue(NextEdit.enabled(null, metered = false))
        assertFalse(NextEdit.enabled(null, metered = true))
        assertTrue(NextEdit.enabled(true, metered = true))     // the person's choice wins
        assertFalse(NextEdit.enabled(false, metered = false))
        assertNotNull(NextEdit.SYSTEM)
    }
}
