package dev.pocketide.app

import dev.pocketide.app.core.*
import org.junit.Assert.*
import org.junit.Test

class SyntaxGateTest {
    @Test fun scriptsPagesAndDataAreGuarded() {
        assertTrue(SyntaxCheck.guarded("game/main.js")); assertTrue(SyntaxCheck.guarded("index.html")); assertTrue(SyntaxCheck.guarded("data/a.json"))
        assertFalse(SyntaxCheck.guarded("style.css")); assertFalse(SyntaxCheck.guarded("notes.md"))
    }
    @Test fun cutOffIsExplained() {
        val r = SyntaxCheck.refused("game/main.js", "line 18, column 1: Unexpected end of input", true)
        assertTrue(r, r.startsWith("NOT SAVED") && r.contains("cut off") && r.contains("put back"))
        assertTrue(SyntaxCheck.refused("a.js", "line 2, column 3: Unexpected token", false).contains("not kept"))
    }
}
