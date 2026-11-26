package dev.pocketide.app.core

import dev.pocketide.app.HybridCloud
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.5.197: Gemini Nano's tuning from Remote Config, and the template in the repository that lists every key. */
class RemoteTuningTest {
    @After fun reset() { PyHelper.remoteNote = null; PyHelper.remoteExamples = ""; PyHelper.remoteLong = null; SmallLocal.remoteNote = "" }

    @Test fun defaultsAreTheBuiltInValues() {
        assertEquals(PyHelper.NOTE, PyHelper.note())
        assertEquals(PyHelper.LONG, PyHelper.long())
        assertEquals("", SmallLocal.remoteLine())
    }

    @Test fun remoteValuesChangeWhatNanoSees() {
        PyHelper.remoteNote = "Python: use 4 spaces."
        PyHelper.remoteExamples = "edit_lines start 12 end 12 text \"    return a + b\""
        assertEquals("Python: use 4 spaces.\nWorked examples:\nedit_lines start 12 end 12 text \"    return a + b\"", PyHelper.note())
        PyHelper.remoteLong = 200
        val text = (1..250).joinToString("\n") { if (it % 50 == 1) "def f$it():" else "    x = $it" }
        assertTrue(PyHelper.outline("m.py", text)!!.startsWith("m.py has 250 lines"))
        PyHelper.remoteLong = 7   // out of range: the built-in length
        assertEquals(PyHelper.LONG, PyHelper.long())
        SmallLocal.remoteNote = "Prefer edit_lines."
        assertEquals("\nPrefer edit_lines.", SmallLocal.remoteLine())
        SmallLocal.remoteNote = "x".repeat(3000)
        assertEquals(1501, SmallLocal.remoteLine().length)
    }

    @Test fun theTemplateListsEveryKeyWithTheAppsDefaults() {
        val t = MiniJson.obj(MiniJson.parse(File("../remoteconfig.template.json").readText()))!!
        val params = t["parameters"] as Map<*, *>
        val keys = listOf(HybridCloud.KEY_MODEL, HybridCloud.KEY_LIMIT, HybridCloud.KEY_MODE, HybridCloud.KEY_CLOUD_ON, HybridCloud.KEY_LIMITED_TOKENS,
            HybridCloud.KEY_NANO_NOTE, HybridCloud.KEY_PY_NOTE, HybridCloud.KEY_PY_EXAMPLES, HybridCloud.KEY_PY_DEFAULT, HybridCloud.KEY_PY_LINES) + Tuning.DEFAULTS.keys
        assertEquals(keys.toSet(), params.keys.map { it.toString() }.toSet())
        fun dv(k: String) = ((params[k] as Map<*, *>)["defaultValue"] as Map<*, *>)["value"].toString()
        assertEquals(HybridSettings.DEFAULT_ON_DEVICE_CHARS.toString(), dv(HybridCloud.KEY_LIMIT))
        assertEquals(PyHelper.LONG.toString(), dv(HybridCloud.KEY_PY_LINES))
        assertEquals("false", dv(HybridCloud.KEY_PY_DEFAULT))
        assertEquals("", dv(HybridCloud.KEY_PY_NOTE))
        assertEquals("", dv(HybridCloud.KEY_NANO_NOTE))
        // every Tuning key's template default is its built-in value
        for ((k, v) in Tuning.DEFAULTS) {
            if (v is Double) assertEquals(k, v, dv(k).toDouble(), 1e-6) else assertEquals(k, v.toString(), dv(k))
        }
    }

    /** 1.5.199: Gemini Nano's file reads grow with the on-phone limit (AICore can allow 8,000 tokens). */
    @Test fun nanoReadsGrowWithTheOnPhoneLimit() {
        assertEquals(Tools.MAX_RESULT, ContextFit.nanoResultCap(HybridSettings.DEFAULT_ON_DEVICE_CHARS))   // the default: as before
        assertEquals(13200, ContextFit.nanoResultCap(HybridSettings.NANO_8K_CHARS))
        assertTrue(HybridSettings.warning(HybridSettings(onDeviceMaxChars = 24000))!!.contains("up to 8,000 tokens"))
        assertEquals(null, HybridSettings.warning(HybridSettings()))
    }

    /** 1.5.198: Nano's defaults, thresholds and switches from Remote Config. */
    @Test fun tuningValues() {
        try {
            Tuning.apply(mapOf(Tuning.Keys.NANO_TEMPERATURE to "0.5", Tuning.Keys.NANO_TOP_K to "20", Tuning.Keys.NANO_MAX_OUTPUT to "2048",
                Tuning.Keys.NANO_FULL_MODEL to "true", Tuning.Keys.CLOUD_TIER to "Fast", Tuning.Keys.HANDOFF_FAILURES to "3",
                Tuning.Keys.CLOUD_OUT_MINUTES to "30", Tuning.Keys.PY_SYNTAX to "false", Tuning.Keys.PAGE_WIRING to "false", Tuning.Keys.REPAIR_JSON to "false")::get)
            assertEquals(0.5f, Tuning.nanoTemperature); assertEquals(20, Tuning.nanoTopK); assertEquals(2048, Tuning.nanoMaxOutput)
            assertTrue(Tuning.nanoFullModel); assertEquals("fast", Tuning.cloudTier); assertEquals(3, Tuning.handoffAfterFailures)
            assertEquals(30 * 60_000L, CloudTier.outUntil("RESOURCE_EXHAUSTED: per minute", 0))
            assertTrue(!SyntaxCheck.guarded("a.py") && SyntaxCheck.guarded("a.js"))
            assertTrue(PageRefs.wiring(listOf("index.html", "script.js")) { if (it == "index.html") "<body></body>" else "x" }.isNotEmpty())
            assertTrue(PageRefs.problems(listOf("index.html", "script.js"), { if (it == "index.html") "<body></body>" else "x" }, { 1L }).none { it.contains("loads no script") })
            assertTrue(!SmallLocal.isAction("""{"reason": "a "quoted" word", "tool": "read_file", "tool_args": {"path": "a.js"}}"""))
            // out of range or not a number: the built-in value
            Tuning.apply(mapOf(Tuning.Keys.NANO_TEMPERATURE to "9", Tuning.Keys.NANO_TOP_K to "x", Tuning.Keys.CLOUD_TIER to "turbo")::get)
            assertEquals(HybridSettings.DEFAULT_TEMPERATURE, Tuning.nanoTemperature); assertEquals(HybridSettings.DEFAULT_TOP_K, Tuning.nanoTopK)
            assertEquals("auto", Tuning.cloudTier); assertTrue(Tuning.pythonSyntaxCheck && Tuning.repairBrokenJson)
        } finally { Tuning.reset() }
        assertTrue(SmallLocal.isAction("""{"reason": "a "quoted" word", "tool": "read_file", "tool_args": {"path": "a.js"}}"""))
    }
}
