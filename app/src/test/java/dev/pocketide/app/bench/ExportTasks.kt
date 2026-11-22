package dev.pocketide.app.bench

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Writes benchmark tasks out for another harness (1.5.165, Copilot's PR #27 T5: Aider on PocketIDE's own tasks). Off unless
 * POCKETIDE_BENCH_EXPORT names a folder; POCKETIDE_BENCH_SUITE and POCKETIDE_BENCH_TASKS choose the tasks as for LiveBench. For each
 * task: <folder>/<task>/files/... (the project as delivered), request.txt and check.sh (run in the project; exit 0 = pass; it reads the
 * other harness's final reply from .bench/answer.txt).
 */
class ExportTasks {
    @Test fun export() {
        val out = System.getenv("POCKETIDE_BENCH_EXPORT").orEmpty().trim()
        assumeTrue("set POCKETIDE_BENCH_EXPORT to a folder to export the tasks", out.isNotEmpty())
        val only = System.getenv("POCKETIDE_BENCH_TASKS").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val tasks = BenchTasks.select(System.getenv("POCKETIDE_BENCH_SUITE"), only)
        val root = File(out).also { it.mkdirs() }
        for (t in tasks) {
            val dir = File(root, t.name)
            dir.deleteRecursively()
            for ((p, text) in t.files) File(dir, "files/$p").also { it.parentFile.mkdirs() }.writeText(text)
            File(dir, "request.txt").writeText(t.request)
            File(dir, "check.sh").writeText(t.check + "\n")
        }
        File(root, "tasks.txt").writeText(tasks.joinToString("\n") { it.name } + "\n")
        println("BENCH exported ${tasks.size} tasks to ${root.absolutePath}")
    }
}
