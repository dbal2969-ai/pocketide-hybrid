package dev.pocketide.app.bench

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 1.5.179: the harness checks written files as the app does, so a broken edit is put back in benchmarks too. */
class HarnessSyntaxTest {
    private val hasNode = try { ProcessBuilder("node", "--version").start().waitFor() == 0 } catch (e: Exception) { false }

    @Test fun brokenScriptsAreFound() {
        assumeTrue(hasNode)
        assertNull(harnessSyntax("src/cart.js", "function total(a) {\n  return a + 1;\n}\nmodule.exports = { total };\n"))
        val err = harnessSyntax("src/cart.js", "function total(a) {\n  return a + 1;\n}\n}\n")
        assertNotNull(err)
        assertTrue(err, err!!.contains("SyntaxError"))
        assertNull(harnessSyntax("game/main.js", "import { a } from './a.js';\nexport const b = a + 1;\n"))   // a module parses as one
    }

    @Test fun jsonIsParsed() {
        assertNull(harnessSyntax("package.json", "{\"name\": \"x\"}"))
        assertNotNull(harnessSyntax("package.json", "{\"name\": \"x\",}"))
        assertNull(harnessSyntax("README.md", "anything }"))
    }
}
