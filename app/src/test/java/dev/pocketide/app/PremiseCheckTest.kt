package dev.pocketide.app

import dev.pocketide.app.core.PremiseCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.164: the premise check's search terms, its fact block and its answer. */
class PremiseCheckTest {
    @Test fun termsComeFromWhatIsSaidToHaveGone() {
        assertEquals(listOf("Export CSV"), PremiseCheck.terms("The 'Export CSV' button has disappeared from the toolbar."))
        assertEquals(listOf("rest timer", "rest", "timer"), PremiseCheck.terms("The rest timer that beeped no longer goes off."))
        assertEquals(listOf("star"), PremiseCheck.terms("The star for marking a favourite stop has gone."))
        assertTrue(PremiseCheck.terms("The dark mode switch I had in settings is gone, bring it back").contains("dark mode"))
        assertEquals(emptyList<String>(), PremiseCheck.terms("Add a dark mode switch to the settings."))
    }

    @Test fun theProbeReportsOnlyWhatIsFoundNowhere() {
        val files = mapOf("index.html" to "<button id=\"save\">Save</button>", "app.js" to "function save() {}")
        val note = PremiseCheck.probe("The 'Export CSV' button has disappeared.", files.keys.toList()) { files[it] }!!
        assertTrue(note, note.contains("\"Export CSV\": found in no file") && note.contains("searched the project before you started (2 files)"))
        assertNull(PremiseCheck.probe("The 'Save' button has disappeared.", files.keys.toList()) { files[it] })
        assertNull(PremiseCheck.probe("Add an Export CSV button.", files.keys.toList()) { files[it] })
    }

    @Test fun aFindingIsAnAnswer() {
        assertEquals("I could not find this in the project.\n\nSearched for \"metric\": no matches.\n\nDid you mean another app?",
            PremiseCheck.answer("not_found", "Searched for \"metric\": no matches.", "Did you mean another app?"))
        assertTrue(PremiseCheck.NOTE.contains("correct and complete answer"))
    }

    // Copilot's PR #27, T1.5 (N1)
    @Test fun theProbeIsCappedAndCountsEachTerm() {
        val big = "x".repeat(PremiseCheck.MAX_BYTES) + " Export CSV"
        val files = mapOf("huge.js" to big, "app.js" to "const dark = true;")
        // the name past the cap is not read, so it counts as not found; a term in a small file is found
        val note = PremiseCheck.probe("The 'Export CSV' button and the 'dark' switch have disappeared.", files.keys.toList()) { files[it] }!!
        assertTrue(note, note.contains("\"Export CSV\": found in no file") && note.contains("\"dark\": in 1 file"))
        val many = (1..PremiseCheck.MAX_FILES + 50).map { "f$it.js" }
        assertTrue(PremiseCheck.probe("The 'Gone' tab has disappeared.", many) { "nothing" }!!.contains("(${PremiseCheck.MAX_FILES} files)"))
    }
}
