package dev.pocketide.app.bench

import dev.pocketide.app.core.PageSteps
import dev.pocketide.app.core.PlayTests
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Writes the app's own play-test scripts (PageSteps.script) for every game benchmark task, so bench/hybrid/check_games.js can play
 * them in a real browser before and after the reference fix. Runs only with POCKETIDE_DUMP_PLAY=<folder>.
 * Every test must also parse (a step the app cannot read would make the task unfair).
 */
class GamePlayScriptDump {
    private val bench = listOf(File("../bench/hybrid/games"), File("bench/hybrid/games")).firstOrNull { it.isDirectory }

    @Test fun everyGamePlayTestParses() {
        assumeTrue(bench != null)
        for (task in File(bench, "tasks").listFiles()!!.sorted()) {
            val tests = PlayTests.parse(File(task, PlayTests.FILE).readText())
            check(tests.size >= 3) { "${task.name}: ${tests.size} tests" }
            for (t in tests) { val p = PageSteps.parse(t.steps); check(p.error == null) { "${task.name} / ${t.name}: ${p.error}" } }
        }
    }

    /** Every suite's task folders that have play tests (the standard, hard and game suites), for analyze.py. */
    private fun playTasks(): List<File> = listOf("tasks", "hard/tasks", "games/tasks", "local/tasks").flatMap { sub ->
        (File(bench!!.parentFile, sub).listFiles() ?: emptyArray()).filter { File(it, PlayTests.FILE).isFile }
    }.sorted()

    @Test fun dump() {
        val out = System.getenv("POCKETIDE_DUMP_PLAY")?.let(::File)
        assumeTrue(bench != null && out != null)
        for (task in playTasks()) {
            val dir = File(out, task.name).apply { mkdirs() }
            PlayTests.parse(File(task, PlayTests.FILE).readText()).forEachIndexed { i, t ->
                File(dir, "%02d.js".format(i)).writeText("// ${t.name}\n" + PageSteps.script(PageSteps.parse(t.steps).steps))
            }
        }
    }
}
