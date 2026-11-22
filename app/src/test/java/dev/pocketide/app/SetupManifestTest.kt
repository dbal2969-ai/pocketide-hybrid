package dev.pocketide.app

import dev.pocketide.app.core.SetupManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupManifestTest {
    private val json = """{"steps":[{"name":"Install","run":"npm install"},{"run":"npm run build"},{"name":"empty","run":""}]}"""

    @Test fun parsesStepsAndRunsOncePerVersion() {
        val steps = SetupManifest.parse(json)
        assertEquals(2, steps.size)
        assertEquals("npm run build", steps[1].name)
        assertNotNull(SetupManifest.pending(json, null))
        assertNull(SetupManifest.pending(json, SetupManifest.hash(json)))
        assertNotNull(SetupManifest.pending(json.replace("build", "compile"), SetupManifest.hash(json)))
        assertNull(SetupManifest.pending("not json", null))
    }

    @Test fun notesForTheModel() {
        val steps = SetupManifest.parse(json)
        val results = listOf(SetupManifest.Result(steps[0], 0, "ok"), SetupManifest.Result(steps[1], 2, "Error: missing script"))
        val note = SetupManifest.contextNote(results)
        assertTrue(note.contains("1 of 2 worked") && note.contains("exit code 2") && note.contains("missing script"))
        assertEquals("Setup: 1 of 2 step(s) worked; failed: npm run build.", SetupManifest.summary(results))
        assertTrue(SetupManifest.unavailableNote(json).contains("/cloud"))
        assertEquals("", SetupManifest.unavailableNote(null))
    }
}
