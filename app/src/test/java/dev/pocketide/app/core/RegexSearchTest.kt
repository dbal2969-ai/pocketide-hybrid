package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.76: search is a regex search like grep; the patterns are the ones a real run wrote and got "No matches" for. */
class RegexSearchTest {
    private val files = mapOf(
        "game/world.js" to "export class World {\n  flags(x, z, plane = 0) {\n    const sq = this.squares.get((x >> 6) + ',' + (z >> 6));\n    if (!sq) return 0;\n    return sq.planes[plane][(z & 63) * 64 + (x & 63)];\n  }\n}\n",
        "game/main.js" to "const me = { x: 3199, z: 3206, path: [] };\nworld.ensure(midX, midZ, 4);\nif (me.path.length) step();\n"
    )
    private fun grep(p: String, folder: String = "") = AgentActions.grep(files.keys, { files[it] }, p, folder)

    @Test fun escapedParenthesisMatchesAsRegex() {
        val r = grep("""flags\(""")
        assertEquals(1, r.hits)
        assertTrue(r.text.contains("game/world.js:2"))
    }

    @Test fun escapedDotAndAlternationMatch() {
        assertEquals(1, grep("""me\.path|step\(""").hits)   // both alternatives sit on one line, so one hit
    }

    @Test fun wildcardCallMatches() {
        assertEquals(1, grep("""ensure\(.*,.*,.*\)""").hits)
    }

    @Test fun starSixtyFourPlusMatches() {
        assertEquals(1, grep("""\* 64 \+|64 \+""").hits)
    }

    @Test fun plainTextStillWorksAndIsCaseInsensitive() {
        assertEquals(1, grep("CONST ME").hits)
        assertNull(AgentActions.regexOf("const me"))
    }

    @Test fun codeWithDoubleBarIsPlainText() {
        assertNull(AgentActions.regexOf("a || b"))
    }

    @Test fun badRegexFallsBackToPlainText() {
        assertNull(AgentActions.regexOf("flags("))
        assertEquals(1, grep("flags(").hits)
    }

    @Test fun missSaysNotToGuessAnotherName() {
        val r = grep("regionSquares|setRegion")
        assertEquals(0, r.hits)
        assertTrue(r.text.startsWith("No matches"))
        assertTrue(r.text.contains("do not guess another name"))
        assertTrue(r.text.contains("file_outline"))
    }

    @Test fun aFileAsThePathSearchesThatFile() {
        // 1.5.89 searched nothing for a file path and answered "No matches" (gpt-oss-120b, hard suite: 20 rounds lost)
        val r = grep("squares", "game/world.js")
        assertEquals(1, r.hits)
        assertTrue(r.text.contains("game/world.js:3"))
        assertEquals(0, grep("me.path", "game/world.js").hits)
        assertEquals(1, grep("me.path", "./game/main.js").hits)
        assertEquals(1, grep("flags", "game/").hits)
    }

    @Test fun aPathThatIsNotThereSaysSo() {
        val r = grep("flags", "src/world.js")
        assertEquals(0, r.hits)
        assertTrue(r.text.contains("no file or folder 'src/world.js'"))
        assertFalse(r.text.contains("not in the project: do not guess"))
    }

    @Test fun tooManyHitsAsksToNarrow() {
        val big = mapOf("a.js" to (1..100).joinToString("\n") { "let v$it = 1;" }, "b.js" to (1..5).joinToString("\n") { "let w$it = 2;" })
        val r = AgentActions.grep(big.keys, { big[it] }, "let ")
        assertEquals(105, r.hits)
        assertTrue(r.text.contains("45 more matches"))
        assertTrue(r.text.contains("Narrow the pattern"))
        assertTrue(r.text.contains("a.js 100"))
    }

    @Test fun wholeReadFitsUnderABigCap() {
        val text = (1..800).joinToString("\n") { "line $it of a long file" }
        val r = AgentActions.read(listOf("big.js"), { text }, "big.js", cap = 120_000)
        assertFalse(r.contains(AgentActions.MORE))
        assertTrue(r.endsWith("800: line 800 of a long file"))
    }

    @Test fun smallCapStillPages() {
        val text = (1..800).joinToString("\n") { "line $it of a long file" }
        val r = AgentActions.read(listOf("big.js"), { text }, "big.js", cap = 6000)
        assertTrue(r.contains(AgentActions.MORE))
    }

    @Test fun explicitEndIsHonoured() {
        val text = (1..800).joinToString("\n") { "line $it" }
        val r = AgentActions.read(listOf("big.js"), { text }, "big.js", 10, 500, cap = 120_000)
        assertTrue(r.startsWith("Lines 10-500 of 800"))
        assertTrue(r.contains("500: line 500"))
        assertTrue(r.contains("300 " + AgentActions.MORE))
    }

    @Test fun resultCapFollowsTheWindow() {
        assertEquals(ContextFit.DEFAULT_RESULT_CAP, ContextFit.resultCap(null))
        assertEquals(117_964, ContextFit.resultCap(262_144))
        assertEquals(14_745, ContextFit.resultCap(32_768))
        assertEquals(Tools.MAX_RESULT, ContextFit.resultCap(8_000))
        assertEquals(ContextFit.MAX_RESULT_CAP, ContextFit.resultCap(2_000_000))
    }

    @Test fun historyBudgetGrowsWithTheWindow() {
        assertEquals(HistoryTrim.BUDGET, ContextFit.messageBudget(null, 7000, 28000, 8192))
        assertEquals(ContextFit.MAX_BUDGET, ContextFit.messageBudget(262_144, 7000, 28000, 8192))
        assertEquals(96_000, ContextFit.messageBudget(64_000, 7000, 28000, 8192))   // half the window
    }
}
