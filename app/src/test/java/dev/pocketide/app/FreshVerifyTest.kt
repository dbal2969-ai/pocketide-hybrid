package dev.pocketide.app

import dev.pocketide.app.core.FreshVerify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The fresh-context reviewer's message and reply (1.5.161, Copilot HANDOFF-CALIBRATION S4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FreshVerifyTest {
    @Test fun theDiffShowsChangesNewAndDeletedFiles() {
        val d = FreshVerify.diff(listOf(
            FreshVerify.Change("a.js", "one\ntwo\nthree\nfour\nfive\n", "one\ntwo\nTHREE\nfour\nfive\n"),
            FreshVerify.Change("new.js", null, "fresh\n"),
            FreshVerify.Change("old.js", "gone\n", null),
            FreshVerify.Change("same.js", "x\n", "x\n")))
        assertTrue(d, d.contains("--- a/a.js\n+++ b/a.js\n@@ 1 unchanged lines @@\n two\n-three\n+THREE\n four\n@@ 1 unchanged lines @@\n"))
        assertTrue(d, d.contains("--- /dev/null\n+++ b/new.js\n+fresh\n"))
        assertTrue(d, d.contains("--- a/old.js\n+++ /dev/null\n-gone\n"))
        assertFalse(d, d.contains("same.js"))
    }

    @Test fun secretsAreMaskedEverywhere() {
        val key = "sk-or-v1-" + "a1b2c3d4e5".repeat(5)
        val d = FreshVerify.diff(listOf(FreshVerify.Change(".env", "", "OPENROUTER_API_KEY=$key\n")))
        val q = FreshVerify.question("use $key please", d, "I put $key in .env", "echo $key")
        assertFalse(q, q.contains(key))
    }

    @Test fun theDiffIsCutAt12KB() {
        val big = (1..3000).joinToString("\n") { "line $it of a long generated file" } + "\n"
        val d = FreshVerify.diff(listOf(FreshVerify.Change("big.json", null, big), FreshVerify.Change("after.js", null, "x\n")))
        assertTrue(d.length in FreshVerify.DIFF_CAP..FreshVerify.DIFF_CAP + 100)
        assertTrue(d.endsWith("[the diff goes on; cut at 12 KB]\n"))
    }

    @Test fun theQuestionCarriesOnlyRequestDiffMessageAndOutput() {
        val q = FreshVerify.question("Fix the total.", "--- a/x\n+++ b/x\n-1\n+2\n", "Fixed x.", "$ node test.js (exit 0)\nok")
        assertEquals("Request:\nFix the total.\n\nDiff:\n--- a/x\n+++ b/x\n-1\n+2\n\nFinal message:\nFixed x.\n\nLast test output:\n$ node test.js (exit 0)\nok", q)
        assertFalse(FreshVerify.PROMPT.contains("tool"))
        assertTrue(FreshVerify.PROMPT.contains("at most 120 words"))
    }

    @Test fun repliesAreReadInTheirThreeLines() {
        val f = FreshVerify.parse("Missing: the export does not include tags\nNot needed: none\nNot shown: none")!!
        assertTrue(f.any); assertEquals("the export does not include tags", f.missing); assertEquals("", f.notNeeded)
        val g = FreshVerify.parse("**Missing:** none.\n- **Not needed:** ui/stopList.js adds a favourite star\n- Not shown: N/A")!!
        assertEquals("ui/stopList.js adds a favourite star", g.notNeeded); assertEquals("", g.missing); assertEquals("", g.notShown)
        assertFalse(FreshVerify.parse("Missing: none\nNot needed: none\nNot shown: none")!!.any)
        assertNull(FreshVerify.parse("Looks good to me."))
        assertNull(FreshVerify.parse(null))
        assertTrue(FreshVerify.note(f).startsWith("A reviewer who saw only your request, the diff and your final message found:\nMissing: the export does not include tags\n"))
        assertFalse(FreshVerify.note(f).contains("Not needed"))
    }

    @Test fun theSettingIsOffByDefault() {
        val s = AppSettings(RuntimeEnvironment.getApplication())
        assertFalse(s.verifyFresh)
    }
}
