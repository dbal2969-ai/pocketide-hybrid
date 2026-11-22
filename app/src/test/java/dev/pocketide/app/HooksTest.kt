package dev.pocketide.app

import dev.pocketide.app.core.Hooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HooksTest {
    private val cfg = Hooks.parse("""{"protect":["engine/**","*.png"],"afterWrite":[{"match":"*.js","run":"node --check {file}"},{"run":"echo hi"}]}""")

    @Test fun globs() {
        assertTrue(Hooks.matches("engine/**", "engine/a/b.js"))
        assertFalse(Hooks.matches("engine/*", "engine/a/b.js"))
        assertTrue(Hooks.matches("*.png", "assets/x/hero.png"))
        assertTrue(Hooks.matches("src/**/*.kt", "src/a.kt"))
        assertFalse(Hooks.matches("src/*.kt", "other/a.kt"))
    }

    @Test fun protection() {
        assertNotNull(Hooks.protectedBy(cfg, "engine/core.js"))
        assertNotNull(Hooks.protectedBy(cfg, ".git/config"))
        assertNotNull(Hooks.protectedBy(cfg, "release.jks"))
        assertNull(Hooks.protectedBy(cfg, "game/main.js"))
        assertNotNull(Hooks.protectedBy(cfg, Hooks.FILE))
    }

    @Test fun afterWriteCommands() {
        assertEquals(listOf("node --check 'game/it's.js'".replace("it's", "a"), "echo hi"), Hooks.after(cfg, "game/a.js"))
        assertEquals(listOf("echo hi"), Hooks.after(cfg, "x.css"))
        assertTrue(Hooks.after(cfg, "it's.js")[0].contains("'it'\\''s.js'"))
    }

    @Test fun badJson() {
        assertNotNull(Hooks.parse("{oops").error)
        assertTrue(Hooks.parse(null).protect.isEmpty())
    }
}
