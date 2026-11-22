package dev.pocketide.app

import dev.pocketide.app.core.TreeSitter
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

/** Runs against the real parsers when the built library is given with POCKETIDE_TS_LIB; otherwise the tests are skipped. */
class TreeSitterTest {
    companion object {
        @JvmStatic @BeforeClass fun loadLibrary() {
            val lib = System.getenv("POCKETIDE_TS_LIB")
            if (lib != null && !TreeSitter.ready) { try { System.load(lib); TreeSitter.ready = true } catch (e: Throwable) { } }
        }
    }

    private fun need() = Assume.assumeTrue("set POCKETIDE_TS_LIB to the built library", TreeSitter.ready)

    @Test fun javascript() {
        need()
        assertEquals(listOf("render", "helper", "Scene"), TreeSitter.definitions("a.js", "export function render(s) {}\nconst helper = (a) => a;\nclass Scene {}"))
    }

    @Test fun python() {
        need()
        assertEquals(listOf("Server", "run"), TreeSitter.definitions("server.py", "class Server:\n    def run(self):\n        pass\n"))
    }

    @Test fun java() {
        need()
        assertEquals(listOf("Shape", "Box", "draw"), TreeSitter.definitions("Box.java", "interface Shape {}\nclass Box { void draw() {} }"))
    }

    @Test fun kotlin() {
        need()
        assertEquals(listOf("Scene", "render", "Game"), TreeSitter.definitions("a.kt", "class Scene\nfun render(x: Int) = x\nobject Game"))
    }

    @Test fun unknownLanguagesAreLeftToThePatterns() {
        need()
        assertEquals(null, TreeSitter.definitions("notes.md", "# hi"))
    }
}
