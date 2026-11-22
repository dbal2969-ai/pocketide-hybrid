package dev.pocketide.app

import dev.pocketide.app.core.*
import org.junit.Assert.*
import org.junit.Test

class StepGateTest {
    private val files = listOf("game/index.html", "game/main.js", "game/save.js")
    private val read: (String) -> String? = { if (it == "game/index.html") "<script src=\"main.js\"></script>" else "x" }
    private fun checks(order: String, changed: List<String>, size: (String) -> Long = { 10L }) = StepGate.appChecks(order, changed, files, read, size)

    @Test fun namedFilesAreFound() {
        assertEquals(listOf("game/save.js", "main.js"), StepGate.named("Wire core/x? no: use game/save.js and main.js, saved to data/icons/<id>.png"))
        assertTrue(StepGate.named("Delete game/savegame.js").isEmpty())
    }
    @Test fun noChangeFailsUnlessCheckOnly() {
        assertTrue(checks("Add the bank panel", emptyList()).any { it.contains("No file was changed") })
        assertTrue(checks("Verify combat is implemented", emptyList()).isEmpty())
    }
    @Test fun missingOrEmptyNamedFileFails() {
        assertTrue(checks("Create game/bank.js with the bank", listOf("game/main.js")).any { it.contains("game/bank.js") })
        assertTrue(checks("Write game/save.js", listOf("game/save.js"), { 0L }).any { it.contains("is empty") })
        assertTrue(checks("Write game/save.js", listOf("game/save.js")).isEmpty())
    }
    @Test fun verdictNeedsPassEvidenceAndNothingMissing() {
        val good = "Evidence: 1. game/save.js line 4 \"localStorage.setItem\"\nMissing: nothing\nVerdict: PASS"
        assertTrue(StepGate.verdict(good).pass)
        assertFalse(StepGate.verdict("Evidence: it works\nMissing: nothing\nVerdict: PASS").pass)
        assertFalse(StepGate.verdict("Evidence: game/save.js NOT FOUND\nMissing: autosave\nVerdict: FAIL").pass)
        assertEquals("autosave", StepGate.verdict("Evidence: game/save.js x\nMissing: autosave\nVerdict: PASS").missing)
        assertFalse(StepGate.verdict("Looks great, all done!").pass)
    }
    @Test fun queueFileRoundTrips() {
        val t = StepGate.encode("Build", listOf("A one", "B two", "C three"), listOf(StepGate.State.VERIFIED, StepGate.State.VERIFYING, StepGate.State.QUEUED))
        assertEquals(listOf(StepGate.State.VERIFIED to "A one", StepGate.State.VERIFYING to "B two", StepGate.State.QUEUED to "C three"), StepGate.decode(t))
    }
}
