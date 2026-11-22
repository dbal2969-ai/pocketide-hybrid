package dev.pocketide.app

import dev.pocketide.app.core.ModelFacts
import dev.pocketide.app.core.Tools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonDumpTest {
    @Test fun recognisesFileObjectsPrintedAsText() {
        assertTrue(Tools.jsonFileDump("""{"source":"js/engine.js","contents":"(function(){})();"}"""))
        assertTrue(Tools.jsonFileDump("```json\n[{\"path\":\"a.py\",\"content\":\"print(1)\"}]\n```"))
        assertTrue(Tools.filesAsText("""{"file":"x.html","code":"<p>"}"""))
    }
    @Test fun leavesNormalAnswersAlone() {
        assertFalse(Tools.jsonFileDump("I updated js/sprites.js to use the new images."))
        assertFalse(Tools.jsonFileDump("""{"ok": true}"""))
        assertFalse(Tools.jsonFileDump("Use {\"path\": \"a.js\"} as the config key."))
    }
    @Test fun unreliableModelsAreMarked() {
        ModelFacts.load(null)
        ModelFacts.markUnreliable("kilo", "cohere/north-mini-code:free", "printed files as text instead of using tools")
        assertNotNull(ModelFacts.get("kilo", "cohere/north-mini-code:free")?.unavailable)
        ModelFacts.clearUnavailable("kilo", "cohere/north-mini-code:free")
        assertEquals(null, ModelFacts.get("kilo", "cohere/north-mini-code:free")?.unavailable)
    }
}
