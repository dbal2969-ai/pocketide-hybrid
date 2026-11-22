package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.5.144, Cursor parity P6: the calls into the CodeMirror page, and the bundled page itself. */
class AdvancedEditorJsTest {
    @Test fun languageByExtension() {
        assertEquals("js", AdvancedEditorJs.ext("src/game.js"))
        assertEquals("tsx", AdvancedEditorJs.ext("App.TSX"))
        assertEquals("", AdvancedEditorJs.ext("Makefile"))
        assertEquals("", AdvancedEditorJs.ext("main.kt"))
    }

    @Test fun textGoesThroughJsonSoNothingBreaksTheCall() {
        val nasty = "const s = \"a'b\";\n// \\ backslash   </script>\n"
        val call = AdvancedEditorJs.open(nasty, "a.js", true, false)
        assertTrue(call, call.startsWith("window.PocketCM.open(\"") && call.endsWith(", \"js\", true, false)"))
        val literal = call.removePrefix("window.PocketCM.open(").substringBeforeLast(", \"js\"")
        assertEquals(nasty, MiniJson.parse(literal))
        assertEquals(nasty, AdvancedEditorJs.decode(MiniJson.write(nasty)))
        assertNull(AdvancedEditorJs.decode("null"))
        assertNull(AdvancedEditorJs.decode(null))
    }

    @Test fun onlyKnownCommands() {
        assertEquals("window.PocketCM.run(\"next\")", AdvancedEditorJs.run("next"))
        assertTrue(try { AdvancedEditorJs.run("alert(1)"); false } catch (e: IllegalArgumentException) { true })
    }

    @Test fun thePageAndItsLicencesShip() {
        val dir = listOf(File("src/main/assets/codemirror"), File("app/src/main/assets/codemirror")).first { it.isDirectory }
        val page = File(dir, "page.html").readText()
        assertTrue(page.contains("<script src=\"codemirror.min.js\"></script>"))
        assertTrue(page.contains("id=\"root\""))
        val js = File(dir, "codemirror.min.js").readText()
        assertTrue(js.contains("PocketCM") && js.contains("PocketBridge"))
        assertTrue("the bundle stays small: ${js.length}", js.length < 900_000)
        assertTrue(File(dir, "LICENSES.txt").readText().contains("== codemirror 6.0.1 (MIT)"))
        // nothing in the page reaches the network
        assertTrue(!page.contains("http://") && !page.contains("https://"))
    }
}
