package dev.pocketide.app

import dev.pocketide.app.core.PageLook
import org.junit.Assert.assertTrue
import org.junit.Test

class PageVerdictTest {
    @Test fun failsWhenThePageDoesNotLoad() {
        assertTrue(PageLook.verdict(null, emptyList()).startsWith("VERDICT: FAIL"))
    }

    @Test fun failsOnConsoleErrors() {
        assertTrue(PageLook.verdict(emptyMap(), listOf("ReferenceError: x is not defined")).startsWith("VERDICT: FAIL - 1 console error"))
    }

    @Test fun passesOnACleanPage() {
        assertTrue(PageLook.verdict(emptyMap(), emptyList()).startsWith("VERDICT: PASS"))
    }
}
