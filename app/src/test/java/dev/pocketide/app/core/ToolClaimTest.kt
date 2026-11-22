package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolClaimTest {
    @Test fun aClaimThatAToolIsMissingIsFound() {
        // Llama 3.3 70B, hard suite (1.5.90 candidate), after `node:test` gave exit 127
        assertEquals("node", ToolClaim.missing("However, since Node.js is not installed in this environment, I could not run the tests."))
        assertEquals("node", ToolClaim.missing("Node isn't available on this phone."))
        assertEquals("python3", ToolClaim.missing("Python is not installed here, so I could not run it."))
        assertEquals("npm", ToolClaim.missing("npm and node are not installed."))
        assertEquals("git", ToolClaim.missing("git is not currently installed"))
    }

    @Test fun otherSentencesAreLeftAlone() {
        assertNull(ToolClaim.missing("node is installed and the tests pass."))
        assertNull(ToolClaim.missing("The package is not installed yet; run npm install first."))
        assertNull(ToolClaim.missing("The tests are not available in this project."))
    }

    @Test fun theAskSaysWhereTheToolIs() {
        assertEquals("command -v node", ToolClaim.probe("node"))
        val a = ToolClaim.ask("node", "/usr/bin/node\n")
        assertTrue(a, a.startsWith("node is installed here (`command -v node` gives /usr/bin/node)"))
    }
}
