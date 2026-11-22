package dev.pocketide.app

import dev.pocketide.app.core.MissingFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissingFileTest {
    private val project = listOf("README.md", "game/world.js", "game/map.js", "game/player.js", "test/world.test.js")

    @Test fun aGuessedNamePointsAtTheRealFiles() {
        // Llama 3.3 guessed "map.txt" for a map kept in game/map.js, then created a junk map.txt (LiveBench, 1.5.89)
        val close = MissingFile.closest("map.txt", project)
        assertEquals("game/map.js", close.first())
        val note = MissingFile.note("map.txt", project)
        assertTrue(note.startsWith("No text file named map.txt."))
        assertTrue(note.contains("game/map.js"))
        assertTrue(note.contains("write_file only for a genuinely new file"))
    }

    @Test fun sameNameInAnotherFolderComesFirst() {
        assertEquals("game/world.js", MissingFile.closest("src/world.js", project).first())
    }

    @Test fun nothingAlikeInASmallProjectShowsEverything() {
        assertEquals(project.sorted(), MissingFile.closest("zzz.py", project))
    }

    @Test fun anEmptyProjectSaysToCreateIt() {
        assertTrue(MissingFile.note("app.py", emptyList()).contains("Use write_file to create it"))
    }

    @Test fun aNameLikeNothingInABigProjectSaysToFindTheCodeFirst() {
        // Llama 3.3 70B guessed utils.py in a JavaScript project, was told to use write_file, and created it
        val big = (1..20).map { "src/models/m$it.js" } + "src/routes/posts.js"
        val note = MissingFile.note("utils.py", big)
        assertTrue(note.contains("The project has no .py files."))
        assertTrue(note.contains("search for a name from it"))
        assertTrue(!note.contains("Use write_file to create it"))
        assertTrue(!MissingFile.note("src/models/zzz.js", big).contains("no .js files"))
    }

    @Test fun theListIsShort() {
        val many = (1..40).map { "src/map$it.js" }
        assertEquals(8, MissingFile.closest("map.js", many).size)
    }
}
