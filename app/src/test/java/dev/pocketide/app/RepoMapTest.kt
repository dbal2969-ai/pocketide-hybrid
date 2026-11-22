package dev.pocketide.app

import dev.pocketide.app.core.RepoMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoMapTest {
    private val files = mapOf(
        "src/render.js" to "export function render(scene) {\n  return draw(scene);\n}\nfunction draw(s) { return s; }\n",
        "src/app.js" to "import { render } from './render';\nconst a = () => render(1);\nrender(2); render(3);\nfunction start() { render(4); }\n",
        "src/util.js" to "export const helper = () => 1;\n",
        "server.py" to "class Server:\n    def run(self):\n        pass\n",
        "node_modules/x/index.js" to "function hidden() {}\n",
        "notes.md" to "# not code\n",
    )

    @Test fun mostUsedFileComesFirst() {
        val map = RepoMap.build(files)
        val lines = map.lines().filter { it.startsWith("- ") }
        assertTrue(map, lines.first().startsWith("- src/render.js: "))
        assertTrue(map.contains("render") && map.contains("draw"))
    }

    @Test fun skipsDependenciesAndNonCode() {
        val map = RepoMap.build(files)
        assertFalse(map.contains("node_modules"))
        assertFalse(map.contains("notes.md"))
    }

    @Test fun pythonAndHelpersAreNamed() {
        assertEquals(setOf("Server", "run"), RepoMap.names("server.py", files["server.py"]!!).toSet())
        assertTrue(RepoMap.build(files).contains("- server.py: "))
    }

    @Test fun budgetCapsTheOutput() {
        val full = RepoMap.build(files).lines().count { it.startsWith("- ") }
        val small = RepoMap.build(files, tokenBudget = 30).lines().count { it.startsWith("- ") }
        assertTrue("a small budget shows fewer files ($small of $full)", small in 1 until full)
        assertEquals("", RepoMap.build(mapOf("a.md" to "nothing here")))
    }

    // 1.5.115: a game's map data under game/data came before game/main.js and used the whole budget
    @Test fun codeIsGatheredBeforeData() {
        val files = LinkedHashMap<String, String>()
        for (i in 0 until 60) files["game/data/maps/m$i.json"] = "[" + "1,".repeat(40_000) + "1]"
        files["game/data/items.json"] = "{\"bread\": 1}"
        files["game/main.js"] = "function startGame() { loadMaps(); }\n"
        files["game/ui.js"] = "export function drawInventory() {}\n"
        val got = RepoMap.gather(files.keys.sorted()) { files[it] }
        assert(got.containsKey("game/main.js") && got.containsKey("game/ui.js")) { got.keys.toString() }
        assert(got.containsKey("game/data/items.json")) { "small data files still count" }
        assert(got.keys.none { it.startsWith("game/data/maps/") }) { "big data files are not read" }
        assert(got.keys.indexOf("game/main.js") < got.keys.indexOf("game/data/items.json"))
    }

    @Test fun gatherStaysWithinItsBudget() {
        val big = "x".repeat(RepoMap.MAX_FILE_CHARS)
        val files = (1..40).associate { "f$it.js" to big }
        val got = RepoMap.gather(files.keys) { files[it] }
        assertEquals(20, got.size)  // 20 files of 200,000 characters fill the 4,000,000 budget
        assertTrue(got.values.sumOf { it.length } <= RepoMap.MAX_TOTAL_CHARS)
    }

    @Test fun hotFilesAreTheOnesChangedMostOften() {
        val sets = listOf(listOf("game/main.js", "game/ui.js"), listOf("game/main.js"), listOf("game/world.js", "game/ui.js"), listOf("game/main.js", "README.md"))
        assertEquals(listOf("game/main.js", "game/ui.js"), RepoMap.hot(sets))
        assertTrue(RepoMap.hot(listOf(listOf("a.js"))).isEmpty())
        val notes = RepoMap.notes(listOf("game/main.js"), "npm test")
        assertTrue(notes, notes.contains("Changed most often lately: game/main.js") && notes.contains("Test command: npm test"))
        assertEquals("", RepoMap.notes(emptyList(), null))
    }
}
