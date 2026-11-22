package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.82: report-only requests change no files, and saved play tests are not weakened. */
class ScopeTest {
    @Test fun reportRequestsAreReportOnly() {
        for (t in listOf(
            "Run look_at_preview with test=all",
            "Run the play tests and report the results",
            "Check whether the reload test passes",
            "Test the walking and tell me what happens",
            "Review game/main.js",
            "Verify the total shows 7.00 after adding both expenses",
            "please run the tests"))
            assertTrue(t, Scope.reportOnly(t))
    }

    @Test fun anythingThatAsksForAChangeIsATask() {
        for (t in listOf(
            "Run the play tests and fix anything that fails",
            "Check the minimap and add a world map",
            "Test the converter, then update the README",
            "Run npm install",
            "Show an expanded map when the minimap is tapped",
            "Play a sound when the player levels up",
            "Remove the barrier preventing a player from walking the whole map",
            "Make the HUD match osrs"))
            assertFalse(t, Scope.reportOnly(t))
    }

    private val prior = "# reload keeps entries\ntap \"Add\"\nrun location.reload()\nexpect document.querySelectorAll('li').length === 1\n"

    @Test fun weakeningASavedTestIsRefused() {
        val weaker = "# reload keeps entries\ntap \"Add\"\nrun window.__app.render()\nexpect document.querySelectorAll('li').length === 1\n"
        val r = Scope.testRefusal("tests/play.txt", prior, weaker, "Run look_at_preview with test=all")
        assertNotNull(r)
        assertTrue(r!!.contains("run location.reload()"))
    }

    @Test fun addingTestsIsAllowed() {
        val more = prior + "\n# delete\ntap \"Delete\"\nexpect document.querySelectorAll('li').length === 0\n"
        assertNull(Scope.testRefusal("tests/play.txt", prior, more, "Add a delete button"))
    }

    @Test fun askingToChangeTestsAllowsIt() {
        assertNull(Scope.testRefusal("tests/play.txt", prior, "# reload\ntap \"Add\"\n", "Update the play tests for the new layout"))
    }

    @Test fun otherFilesAndNewTestFilesAreNotGuarded() {
        assertNull(Scope.testRefusal("app.js", "a\nb\n", "a\n", "anything"))
        assertNull(Scope.testRefusal("tests/play.txt", null, "# x\ntap 1 2\n", "anything"))
    }

    @Test fun aTestChangeIsPutToThePerson() {
        val prior = "# start\nexpect window.__game.me.x === 3094\ntap 10 10\n"
        val next = "# start\nexpect window.__game.me.x > 0\ntap 10 10\n"
        assertEquals(listOf("expect window.__game.me.x === 3094"), Scope.gone(prior, next))
        val n = Scope.proposalNote(Scope.gone(prior, next))
        assertTrue(n, n.contains("put to the person") && n.contains("Do not try to write it another way") && n.contains("Carry on"))
    }
}
