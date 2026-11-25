package dev.pocketide.app.core

import dev.pocketide.app.HybridAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local benchmark run 3 (2026-11-25): l05 hard-coded the answer, l12 replaced the line it meant to insert after, l19 exported twice,
 *  and l18's rename was called unfinished because the test names the old name. */
class LocalRun3Test {
    @Test fun twoExportsAndATwiceDeclaredFunctionAreNamed() {
        val before = "let count = 0;\nfunction add() { count++; return count; }\nif (typeof module !== 'undefined') module.exports = { add, get: () => count };\n"
        val after = "let count = 0;\nfunction add() { count++; return count; }\nfunction reset() { count = 0; }\nfunction reset() { count = 0; }\n" +
            "if (typeof module !== 'undefined') module.exports = { add, get, reset };\nif (typeof module !== 'undefined') module.exports = { add, get: () => count };\n"
        val n = Doubles.note("app.js", after, before)
        assertTrue(n, n.contains("sets module.exports 2 times") && n.contains("declares function reset twice"))
        assertEquals("", Doubles.note("app.js", before, before))
        assertEquals("not JavaScript", "", Doubles.note("a.py", after, before))
    }

    @Test fun insertLinesIsAnInsertAfterALine() {
        val (turn, why) = HybridAgent.phoneTurn("""{"reason":"add it","tool":"insert_lines","tool_args":{"path":"controls.js","after":5,"text":"x();"}}""", emptyList())
        assertEquals(why, "edit_lines", turn!!.calls.single().name)
        val a = MiniJson.obj(MiniJson.parse(turn.calls.single().arguments))!!
        assertEquals(6, (a["start"] as Number).toInt()); assertEquals(5, (a["end"] as Number).toInt()); assertEquals("x();", a["text"])
        val r = EditBlocks.applyLines("a\nb\nc\nd\ne\n", 6, 5, "x();")
        assertTrue(r.text!!, r.text!!.startsWith("a\nb\nc\nd\ne\nx();"))
    }

    @Test fun nanoSeesInsertLinesAndTheHardCodingRule() {
        val sys = SmallLocal.system(Tools.smallCatalogue(false))
        assertTrue(sys.contains("- insert_lines:") && sys.contains("use insert_lines with after = N"))
        assertTrue(sys.contains("never return a test's expected value"))
    }

    @Test fun aRenameIsNotUnfinishedBecauseTheTestNamesTheOldName() {
        val files = mapOf("score.js" to "function readScore(s) { return 1; }\nmodule.exports = { readScore };\n",
            "test.js" to "need(typeof s.readScore === 'function' && !s.getScore, 'not getScore');\n")
        val req = "Rename getScore to readScore in score.js and ui.js. node test.js must print ok"
        val (_, n) = RenameCheck.left("getScore", files.keys - Scope.namedChecks(req)) { files[it] }
        assertEquals(0, n)
    }

    @Test fun aCapitalisedDefaultIsNamed() {
        val before = "function delta(d) {\n  switch (d) {\n    default: return [0, 0];\n  }\n}\n"
        val after = before.replace("default:", "Default:")
        assertTrue(Doubles.note("dir.js", after, before).contains("\"Default:\" with a capital letter"))
        assertEquals("", Doubles.note("dir.js", before, before))
    }
}
