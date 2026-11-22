package dev.pocketide.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 1.5.117: moving a file onto one that is already there replaces it once the person says so (the file tree asks first). */
class MoveReplaceTest {
    @Test fun aFileCanReplaceTheOneAlreadyThere() {
        val dir = Files.createTempDirectory("move").toFile()
        try {
            val st = ProjectStore(dir, seed = false)
            st.write("main.js", "new"); st.write("game/main.js", "old"); st.write("game/style.css", "css")
            assertFalse("a plain move still refuses", st.move("main.js", "game/main.js"))
            assertTrue(st.replace("main.js", "game/main.js"))
            assertEquals("new", st.read("game/main.js"))
            assertFalse(st.exists("main.js"))
            assertEquals("css", st.read("game/style.css"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun onlyFilesAreReplaced() {
        val dir = Files.createTempDirectory("move").toFile()
        try {
            val st = ProjectStore(dir, seed = false)
            st.write("a.js", "a"); st.write("game/x.js", "x")
            assertFalse("not onto a folder", st.replace("a.js", "game"))
            assertFalse("not a file onto itself", st.replace("a.js", "a.js"))
            assertFalse("not onto a file that is not there", st.replace("a.js", "b.js"))
            assertFalse("not outside the project", st.replace("a.js", "../a.js"))
            assertEquals("a", st.read("a.js")); assertEquals("x", st.read("game/x.js"))
        } finally { dir.deleteRecursively() }
    }
}
