package dev.pocketide.app.bench

import dev.pocketide.app.AppSettings
import dev.pocketide.app.ModelCall
import dev.pocketide.app.ModelGateway
import dev.pocketide.app.NativeListener
import dev.pocketide.app.bench.ScriptedModel.Companion.call
import dev.pocketide.app.bench.ScriptedModel.Companion.say
import dev.pocketide.app.bench.ScriptedModel.Companion.tools
import dev.pocketide.app.core.Usage
import dev.pocketide.app.core.UsageMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Token counts per job in the benchmark (1.5.138, Copilot's HANDOFF-RAISE-CEILING WP7): the harness recorded none. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HarnessUsageTest {
    /** A scripted model that reports usage as the network client does, as each request ends: 100 in, 10 out for each. */
    private class Metered(steps: List<(List<Map<String, Any?>>) -> dev.pocketide.app.core.ToolTurn>) : ModelGateway {
        private val inner = ScriptedModel(steps)
        override fun startNative(settings: AppSettings, system: String, messages: List<Map<String, Any?>>, tools: Boolean, listener: NativeListener,
                                 docs: Boolean, loaded: Set<String>?): ModelCall {
            UsageMeter.report("openrouter", "scripted", Usage(input = 100, output = 10, cached = 50))
            return inner.startNative(settings, system, messages, tools, listener, docs, loaded)
        }
    }

    private val app = mapOf("app.js" to "function add(a, b) {\n  return a - b;\n}\nmodule.exports = { add };\n")

    @Test fun eachRequestIsTimedAndCountedUnderItsJob() {
        val o = AgentHarness(app, "Where is add defined? Answer only.", Metered(listOf(
            tools(call("explore", "question" to "Where is add defined?")),   // main
            say("app.js:1 defines add."),                                     // the explorer's own request
            say("add is defined in app.js on line 1."),                      // main again
        )), appTools = true, explore = true).run()
        assertFalse(o.timedOut)
        assertEquals(listOf("main", "explore", "main"), o.log.map { it.role })
        assertTrue(o.log.all { it.usage.input == 100L && it.usage.output == 10L && it.usage.cached == 50L })
        assertTrue(o.log.all { it.millis >= 0 })
        // the run's own tool calls, not the explorer's conversation
        assertEquals(listOf("explore"), o.names)
    }

    @Test fun theReportShowsTokensAndTheTranscriptListsRequests() {
        val req = listOf(AgentHarness.Request("main", 1500, Usage(input = 1000, output = 50, cached = 4000)),
            AgentHarness.Request("explore", 800, Usage(input = 300, output = 20, estimated = true)))
        val r = BenchRunner.Result("t", "p:m", true, 2, 1, 0, 0, 0, 3, "finished", "", log = req)
        val report = BenchRunner.report(listOf(r))
        assertTrue(report, report.contains("| In | Cached | Out | Helper |"))
        assertTrue(report, report.contains("| 1000 | 4000 | 50 | ~320 |"))
        assertTrue(report, report.contains("tokens: 1000 in, 4000 cached, 50 out, 320 helper (some estimated)"))
        val log = BenchRunner.requestLog(req)
        assertTrue(log, log.contains("| 1 | main | 1.5 | 1000 | 4000 | 50 |"))
        assertTrue(log, log.contains("| 2 | explore | 0.8 | ~300 | 0 | ~20 |"))
        assertEquals(5370L, r.total)
    }
}
