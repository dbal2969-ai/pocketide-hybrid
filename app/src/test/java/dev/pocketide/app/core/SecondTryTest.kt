package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondTryTest {
    private fun r(answer: String = "Fixed: the menu now sits above the panel.", work: Boolean = true, stop: Boolean = false, error: String? = null, cut: String = "", failed: String = "") =
        SecondTry.reason(work, stop, error, cut, failed, answer)

    @Test fun aFinishedRunIsKept() { assertNull(r()) }

    @Test fun aRunCutShortIsTriedAgain() {
        assertTrue(r(cut = "24 rounds used")!!.contains("24 rounds used"))
    }

    @Test fun aFailedLastCheckIsTriedAgain() {
        assertTrue(r(failed = "node --test")!!.contains("node --test"))
    }

    @Test fun anAnswerThatSaysItIsNotDoneIsTriedAgain() {
        for (a in listOf("I haven't changed anything yet, because the cause isn't where I first expected.",
                "Not fixed. The item menu's code isn't in the page's loaded scripts.",
                "I couldn't find where the position is saved.",
                "The bug is still not fixed: the save writes pos but reads position.",
                "I can't yet say what causes it.")) assertNotNull(a, r(answer = a))
    }

    @Test fun ordinaryWordsAreNotAFailure() {
        for (a in listOf("Fixed the bug that made saves not load. Tests pass.", "The menu was not visible because of its z-index; it is fixed now.",
                "Done: Net gives shrimps, a full bag says so, and Bait says you need bait.")) assertNull(a, r(answer = a))
    }

    @Test fun noRetryWhenItWouldNotHelp() {
        assertNull("the person stopped it", r(cut = "x", stop = true))
        assertNull("a provider error", r(cut = "x", error = "HTTP 401"))
        assertNull("a question or report", r(answer = "I couldn't find it.", work = false))
    }

    @Test fun theNoteCarriesTheReasonAndTheLastWords() {
        val n = SecondTry.note("its answer says the job is not done", "Not fixed.\n\nThe page loads only main.js.")
        assertTrue(n.startsWith("\n\n(") && n.endsWith(")"))
        assertTrue(n.contains("its answer says the job is not done") && n.contains("\"Not fixed. The page loads only main.js.\"") && n.contains("may be wrong"))
        assertEquals(false, SecondTry.note("x", "").contains("last words"))
    }
}
