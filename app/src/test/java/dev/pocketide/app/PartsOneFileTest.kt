package dev.pocketide.app

import dev.pocketide.app.core.Parts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.129: a job that all goes into one file is not split into parts (they could only run one after another). */
class PartsOneFileTest {
    private val snakeReqs = listOf("R1 index.html holds the game", "R2 arrow keys", "R3 swipe controls", "R4 score", "R5 restart button",
        "R6 game over", "R7 food", "R8 speed")

    @Test fun theEmulatorSnakeGame() {
        val req = "Make a snake game in one HTML file with arrow-key and swipe controls, a score, and a restart button."
        assertTrue(Parts.oneFile(req, snakeReqs))
        assertFalse(Parts.shouldSplit(8, enabled = true, oneFile = Parts.oneFile(req, snakeReqs)))
    }

    @Test fun phrasesForOneFile() {
        for (r in listOf("Put it all in a single file", "a single-file app", "one file please", "build it in one .html", "one JavaScript file"))
            assertTrue(r, Parts.oneFile(r, emptyList()))
    }

    @Test fun onlyOneFileNamed() {
        assertTrue(Parts.oneFile("Fix the bugs", listOf("R1 in game.js fix the jump", "R2 game.js: score resets", "R3 src/game.js speed")))
    }

    @Test fun severalFilesStillSplit() {
        val reqs = listOf("R1 index.html layout", "R2 style.css colours", "R3 app.js logic", "R4 app.js storage", "R5 api.js calls", "R6 tests")
        assertFalse(Parts.oneFile("Build a todo app", reqs))
        assertTrue(Parts.shouldSplit(6, enabled = true, oneFile = Parts.oneFile("Build a todo app", reqs)))
        assertFalse(Parts.oneFile("Build a todo app with a server and one database", listOf("R1 login", "R2 lists")))
    }
}
