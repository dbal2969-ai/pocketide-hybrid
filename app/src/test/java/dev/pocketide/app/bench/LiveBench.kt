package dev.pocketide.app.bench

import dev.pocketide.app.ModelGateway
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The benchmark with real models (1.5.86). Skipped unless POCKETIDE_BENCH_MODELS is set, so the normal tests never call a
 * provider. Keys come only from the environment and are never written to a file.
 *
 *   POCKETIDE_BENCH_MODELS="groq:llama-3.3-70b-versatile;cloudflare:@cf/moonshotai/kimi-k2.6"
 *   POCKETIDE_BENCH_KEY_GROQ=...   POCKETIDE_BENCH_KEY_CLOUDFLARE=...
 *   POCKETIDE_BENCH_BASE_CLOUDFLARE=https://api.cloudflare.com/client/v4/accounts/<account id>/ai/v1
 *   POCKETIDE_BENCH_TASKS=fix-from-symptom,report-only   (optional; default all)
 *   POCKETIDE_BENCH_SUITE=hard                            (optional; standard (default), hard, heldout, realproject or all)
 *   POCKETIDE_BENCH_REPEAT=3                              (optional; runs of each task)
 *   POCKETIDE_BENCH_WHOLE_READS=0                         (optional; serve file pieces as asked, as before 1.5.99, to compare)
 *   POCKETIDE_BENCH_EFFORT=low                            (optional; thinking effort none (off, 1.5.182), low, medium or high: ThinkEffort, 1.5.99)
 *   POCKETIDE_BENCH_HELPER=groq:llama-3.1-8b-instant      (optional; the explorer and summary use this model, 1.5.138)
 *   POCKETIDE_BENCH_ASK_CHECK=0                           (optional; without the ask checklist, AskLedger, to compare)
 *   POCKETIDE_BENCH_ROUTE=1                               (optional; reading rounds use the helper model too)
 *   POCKETIDE_BENCH_MAX_TOKENS=2000000                    (optional; no new task starts once this job's runs used this many tokens)
 *   ./gradlew testDebugUnitTest --tests '*LiveBench*'
 *
 * The report is written to app/build/bench/<date>.md, or to POCKETIDE_BENCH_OUT when set (a folder that survives a restart); each run's transcript is saved there before every model request. Node must be installed for the checks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [BenchMemoryPreferences::class])
class LiveBench {
    @Test fun run() {
        val spec = System.getenv("POCKETIDE_BENCH_MODELS").orEmpty().trim()
        assumeTrue("set POCKETIDE_BENCH_MODELS to run the live benchmark", spec.isNotEmpty())
        val only = System.getenv("POCKETIDE_BENCH_TASKS").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val repeat = System.getenv("POCKETIDE_BENCH_REPEAT")?.toIntOrNull()?.coerceIn(1, 10) ?: 1
        // 1.5.179: small local model mode for a cloud-hosted small model (POCKETIDE_BENCH_SMALL=1)
        dev.pocketide.app.core.SmallLocal.forceForBench = System.getenv("POCKETIDE_BENCH_SMALL") == "1"
        val tasks = BenchTasks.select(System.getenv("POCKETIDE_BENCH_SUITE"), only)
        val results = ArrayList<BenchRunner.Result>()
        val dir = File("build/bench").also { it.mkdirs() }
        // 1.5.138: a spend cap per job (Copilot, HANDOFF-RAISE-CEILING WP7): the provider console's limit is the real one, this stops early
        val cap = System.getenv("POCKETIDE_BENCH_MAX_TOKENS")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
        var capped = false
        val out = File(BenchRunner.saveDir(dir), SimpleDateFormat("yyyy-MM-dd-HHmm").format(Date()) + ".md")
        for (entry in spec.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val provider = entry.substringBefore(':').trim()
            val model = entry.substringAfter(':', "").trim()
            val env = provider.uppercase().replace('-', '_')
            val key = dev.pocketide.app.core.KeyClean.clean(System.getenv("POCKETIDE_BENCH_KEY_$env").orEmpty())   // as the app cleans a pasted key
            val base = System.getenv("POCKETIDE_BENCH_BASE_$env").orEmpty()
            for (t in tasks) repeat(repeat) {
                if (cap != null && results.sumOf { it.total } >= cap) {
                    if (!capped) println("BENCH token cap reached: ${results.sumOf { it.total }} of $cap tokens; no more tasks start")
                    capped = true
                    return@repeat
                }
                val runs = File(out.parentFile, out.nameWithoutExtension).also { it.mkdirs() }
                val file = File(runs, "${model.replace(Regex("[^A-Za-z0-9.-]+"), "_")}-${t.name}-${it + 1}.md")
                // saved before every request, so a restart mid-run keeps the steps taken so far
                // 1.5.170: a local model on the runner's CPU is slow, so its per-task limit can be raised (POCKETIDE_BENCH_TIMEOUT, seconds)
                val limit = System.getenv("POCKETIDE_BENCH_TIMEOUT")?.trim()?.toLongOrNull()?.coerceIn(60, 7200) ?: 900
                val r = BenchRunner.run(t, ModelGateway.NETWORK, provider, model, key, base, limit) { so -> file.writeText(BenchRunner.redact(so)) }
                results.add(r)
                println(BenchRunner.redact("BENCH ${r.model} ${r.task}: ${if (r.pass) "pass" else "FAIL"} (${r.requests} requests, ${r.seconds}s, ${r.ending})"))
                out.writeText(BenchRunner.redact("# PocketIDE benchmark\n\n" + BenchRunner.settingsLine() + "\n\n" + BenchRunner.report(results)))   // written after every run, so a long run can be read while it goes
                file.writeText(BenchRunner.redact(r.transcript))
            }
        }
        if (capped) out.appendText("\n**Stopped early:** the token cap ($cap) was reached, so not every task ran.\n")
        println("BENCH report: " + out.absolutePath)
    }
}
