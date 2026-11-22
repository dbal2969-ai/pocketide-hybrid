package dev.pocketide.app

import dev.pocketide.app.core.ModelTrack
import dev.pocketide.app.core.Outcome
import dev.pocketide.app.core.Proof
import org.junit.Assert.*
import org.junit.Test

/** 1.5.175, from the free-model survey of 2026-11-17 (Kilo, OpenRouter and OVH free models on the 1.5.174 code). */
class Fixes175Test {
    private val exists = { p: String -> p == "src/duration.js" || p == "test/duration.test.js" }

    @Test fun inlineCodeThatOnlyPrintsIsNotACheckThatCanFail() {
        // Nemotron 3 Ultra's check of parseDuration: prints every rule, asserts nothing
        val printed = "node -e \"\nconst { parseDuration } = require('./src/duration.js');\nconsole.log('1h30m:', parseDuration('1h30m'));\n\""
        assertTrue(Proof.isCheckCommand(printed, exists))
        assertTrue(Proof.printOnly(printed, exists))
        assertTrue(Proof.printOnly("python3 -c \"from src import x; print(x.f(2))\"", exists))
        assertTrue(Proof.printOnly("node <<'EOF'\nconst d = require('./src/duration.js');\nconsole.log(d.parseDuration('5s'));\nEOF", exists))
    }

    @Test fun codeThatCanFailStillCounts() {
        assertFalse(Proof.printOnly("node -e \"const a = require('assert'); a.strictEqual(require('./src/duration.js').parseDuration('1s'), 1000)\"", exists))
        assertFalse(Proof.printOnly("node -e \"if (require('./src/duration.js').parseDuration('1s') !== 1000) throw new Error('bad')\"", exists))
        assertFalse(Proof.printOnly("python3 -c \"import sys; from src import x; sys.exit(0 if x.f(2) == 4 else 1)\"", exists))
        assertFalse(Proof.printOnly("node --test", exists))
        assertFalse(Proof.printOnly("npm test", exists))
        assertFalse(Proof.printOnly("node test/duration.test.js", exists))
        // a runner next to a printing script: the runner can fail
        assertFalse(Proof.printOnly("node --test && node -e \"console.log(require('./src/duration.js'))\"", exists))
    }

    @Test fun aPrintedCheckDoesNotEarnChecked() {
        val printed = "node -e \"\nconsole.log(require('./src/duration.js').parseDuration('1s'))\n\""
        val c = Proof.command(printed, 0, 3, exists)
        assertNull(c.ok)
        assertTrue(c.what.endsWith(Proof.PRINTED))
        assertEquals("`node -e \"…`" + Proof.PRINTED, c.what)   // was `node -e "` on its own
        assertTrue(Proof.onlyPrinted(listOf(c), 2))
        assertTrue(Proof.printedAsk(listOf(c), 2).contains("fails when a result is wrong"))
        // a check that can fail, after it, ends the ask
        assertFalse(Proof.onlyPrinted(listOf(c, Proof.command("node --test", 0, 4, exists)), 2))
        // a failed print-only run is a failed check, as before
        assertEquals(false, Proof.command(printed, 1, 3, exists).ok)
        // the verdict: no passed check after the change is "changed, not checked"
        assertEquals(Outcome.Kind.UNCHECKED, Outcome.of(1, "", "", "", emptyList()).kind)
        assertTrue(Proof.footer(listOf(c), 2).contains("(printed only)"))
    }

    @Test fun flintIsListedAsMeasured() {
        assertNotNull(ModelTrack.measured("recursive/flint-v1.0:free"))
    }
}
