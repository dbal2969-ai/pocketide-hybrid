package dev.pocketide.app

import dev.pocketide.app.core.Outcome
import dev.pocketide.app.core.PlayTests
import dev.pocketide.app.core.TaskTracker
import dev.pocketide.app.core.Verify
import org.junit.Assert.*
import org.junit.Test

/** 1.5.173, from the owner's field chat of 2026-11-16 (Empty-project-10, Haiku 5.5 on OpenRouter). */
class Fixes173Test {
    @Test fun syntaxChecksDoNotRunTheCode() {
        assertFalse(Verify.runsCode("syntax checks"))
        assertTrue(Verify.runsCode("npm test")); assertTrue(Verify.runsCode("Python tests"))
        // with nothing that ran the code, a run with changes is "changed, not checked", not "done and checked"
        assertEquals(Outcome.Kind.UNCHECKED, Outcome.of(3, "", "", "", emptyList()).kind)
    }

    @Test fun aStepMarkedFailedIsKeptApartFromOpenSteps() {
        val t = TaskTracker()
        t.update(listOf("Export" to "done", "Re-draw" to "done", "Re-check in the preview" to "failed"))
        assertTrue(t.unfinished().isEmpty())
        assertEquals(listOf("Re-check in the preview"), t.failed())
        assertEquals(Outcome.Kind.NOT_DONE, Outcome.of(2, "the app's page check", "", "", listOf("1 plan step marked failed (Re-check in the preview)")).kind)
    }

    private val before = "# Lumbridge castle\nrun a()\nuntil b\nexpect c\nrun d()\nuntil e\nexpect f\n# walking\ntap 1 2\nexpect g\n"

    @Test fun aCommentWrittenWithAHashCutsTheTestAndIsPointedOut() {
        val after = "# Lumbridge castle\nrun a()\nuntil b\nexpect c\n# the floor is drawn, not just counted\nwait 1500\nexpect v\nrun d()\nuntil e\nexpect f\n# walking\ntap 1 2\nexpect g\n"
        val n = PlayTests.splits(before, after)
        assertEquals(1, n.size)
        assertTrue(n[0], n[0].contains("\"# the floor is drawn, not just counted\" starts a new test") && n[0].contains("\"Lumbridge castle\" lost its last 3 steps"))
    }

    @Test fun addingATestOrStepsIsNotACut() {
        assertTrue(PlayTests.splits(before, before + "# fishing\ntap 3 4\nexpect h\n").isEmpty())
        assertTrue(PlayTests.splits(before, before.replace("expect f\n", "expect f\nexpect f2\n")).isEmpty())
        assertTrue(PlayTests.splits(before, before.replace("# walking", "# bank\ntap 5 5\nexpect i\n# walking")).isEmpty())
        assertTrue(PlayTests.splits("", before).isEmpty())
    }
}
