package dev.pocketide.app

import dev.pocketide.app.core.Hooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishHooksTest {
    @Test fun parsesBeforeFinish() {
        val c = Hooks.parse("""{ "protect": ["a/**"], "beforeFinish": ["npm test", {"run": "node --check main.js"}, "", 5, "a", "b"] }""")
        assertEquals(listOf("npm test", "node --check main.js", "a"), c.beforeFinish)
        assertEquals(listOf("a/**"), c.protect)
        assertTrue(Hooks.parse("""{"protect":[]}""").beforeFinish.isEmpty())
        assertTrue(Hooks.parse(null).beforeFinish.isEmpty())
    }

    @Test fun requestNamesEachFailure() {
        val r = Hooks.finishRequest(listOf(Triple("npm test", 1, "FAIL src/app.test.js\n  expected 2 got 3")))
        assertTrue(r, r.contains("`npm test` exited with 1") && r.contains("expected 2 got 3") && r.contains("Do not change the checks"))
    }

    @Test fun theAssistantCannotEditTheHooksFile() {
        assertTrue(Hooks.protectedBy(Hooks.parse("{}"), Hooks.FILE) != null)
    }
}
