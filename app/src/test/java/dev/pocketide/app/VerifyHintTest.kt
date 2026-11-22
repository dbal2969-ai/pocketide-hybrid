package dev.pocketide.app

import dev.pocketide.app.core.VerifyHint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.127: a changed web page is checked with look_at_preview first. */
class VerifyHintTest {
    @Test fun webFiles() {
        for (p in listOf("index.html", "game/main.js", "style.css", "src/App.tsx", "a.mjs")) assertTrue(p, VerifyHint.isWeb(p))
        for (p in listOf("main.py", "notes.md", "build.gradle.kts", "html.txt")) assertFalse(p, VerifyHint.isWeb(p))
    }

    @Test fun thePageCheckComesFirstForAPage() {
        val page = VerifyHint.ask(web = true, canLook = true)
        assertTrue(page.indexOf("look_at_preview") < page.indexOf("run_command"))
        val code = VerifyHint.ask(web = false, canLook = true)
        assertTrue(code.indexOf("run_command") < code.indexOf("look_at_preview"))
        assertFalse(VerifyHint.ask(web = true, canLook = false).contains("look_at_preview"))
    }
}
