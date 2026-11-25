package dev.pocketide.app

import dev.pocketide.app.core.HybridBenchTasks
import dev.pocketide.app.core.HybridBenchTasks.Facts
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The game benchmark (bench/hybrid/games): complete, the app's copy matches the repository, and judged for the right reasons. */
class HybridBenchGamesTest {
    private val games = HybridBenchTasks.GAMES
    private fun task(id: String) = games.single { it.id == id }
    private val bench = listOf(File("../bench/hybrid/games"), File("bench/hybrid/games")).firstOrNull { it.isDirectory }

    @Test fun everyTaskHasItsFilesAndAPlayTest() {
        check(games.size == 5)
        check((games + HybridBenchTasks.HARD + HybridBenchTasks.ALL).map { it.id }.toSet().size == games.size + HybridBenchTasks.HARD.size + HybridBenchTasks.ALL.size)
        for (t in games) { check(t.files.containsKey("tests/play.txt") && t.files.containsKey("index.html")) { t.id }; check(t.command == null) }
    }

    @Test fun theAppHasTheSameFilesAsTheRepository() {
        assumeTrue(bench != null)
        for (t in games) {
            val dir = File(bench, "tasks/${t.id}")
            val disk = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }
            check(disk == t.files) { "${t.id}: HybridBenchGameFiles is out of date" }
        }
    }

    @Test fun judges() {
        val t = task("gm02-fix-keyboard")
        val o = t.files
        val fixed = o + ("game.js" to o["game.js"]!!.replace("canvas.addEventListener", "document.addEventListener"))
        check(t.judge(Facts(fixed, o, null, "", "outcome: ✓ Done and checked: the app's page check.", "done")) == null)
        check(t.judge(Facts(o, o, null, "", "outcome: ✓", "done")) == "game.js was not changed")
        check(t.judge(Facts(fixed, o, null, "", "outcome: ⚠ Changed, not checked", "done"))!!.contains("did not pass"))
        check(t.judge(Facts(fixed + ("tests/play.txt" to "# nothing\nexpect true\n"), o, null, "", "outcome: ✓", "done")) == "the play tests were changed")
        val snake = task("gm01-snake-from-spec")
        check(snake.judge(Facts(snake.files + ("game.js" to "// no game"), snake.files, null, "", "outcome: ✓", "done")) == "game.js does not set window.game")
    }

    /** "Hybrid helps my strong model": the strong model must be another provider's API with a key, and never Cloudflare. */
    @Test fun theHelperSetupNeedsARealStrongModelThatIsNotCloudflare() {
        check(HybridBenchTasks.strongProblem(null, false)!!.contains("Pick a strong model"))
        check(HybridBenchTasks.strongProblem(dev.pocketide.app.core.Workflow.Slot("hybrid", ""), true)!!.contains("another provider"))
        check(HybridBenchTasks.strongProblem(dev.pocketide.app.core.Workflow.Slot("cloudflare", "@cf/x"), true)!!.contains("Cloudflare is not used"))
        check(HybridBenchTasks.strongProblem(dev.pocketide.app.core.Workflow.Slot("openai", "gpt-x"), false)!!.contains("no API key"))
        check(HybridBenchTasks.strongProblem(dev.pocketide.app.core.Workflow.Slot("openai", "gpt-x"), true) == null)
    }
}
