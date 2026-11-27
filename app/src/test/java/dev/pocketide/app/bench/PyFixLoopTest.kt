package dev.pocketide.app.bench

import dev.pocketide.app.AppSettings
import dev.pocketide.app.HybridAgent
import dev.pocketide.app.HybridAi
import dev.pocketide.app.HybridCloud
import dev.pocketide.app.ModelGateway
import dev.pocketide.app.core.MiniJson
import dev.pocketide.app.core.ToolTurn
import dev.pocketide.app.core.Tuning
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 1.5.201: the Python fix path and replace_function in the real agent loop, with scripted Gemini Nano replies and real pytest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PyFixLoopTest {
    private val calc = "\"\"\"Small sums.\"\"\"\n\n\ndef double(x):\n    return x * 2\n\n\ndef add(a, b):\n    total = a - b\n    return total\n"
    private val app = mapOf("calc.py" to calc, "tests/__init__.py" to "",
        "tests/test_calc.py" to "import sys, os\nsys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))\nfrom calc import add\n\n\ndef test_add():\n    assert add(2, 3) == 5\n")
    private val request = "Fix this issue in calc.py. Then run `python3 -B -m pytest -q -p no:cacheprovider tests/test_calc.py`; it must pass.\n\n" +
        "The test that fails now:\n```python\ndef test_add():\n    assert add(2, 3) == 5\n```"
    private val prompts = ArrayList<String>()

    private fun python() = try { ProcessBuilder("python3", "-c", "import pytest").start().waitFor() == 0 } catch (e: Exception) { false }

    @Before fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        HybridAi.appContext = ctx
        HybridCloud.off = true
        ctx.getSharedPreferences("hybrid_ai", 0).edit().clear().putString("mode", HybridAi.Mode.ONLY_ON_DEVICE.name).commit()
        AppSettings(ctx).pythonHelper = true
        Tuning.reset()
    }

    @After fun tearDown() { HybridAgent.testPhone = null; HybridAgent.testCloud = null; HybridAgent.testOnline = null; Tuning.reset(); AppSettings(RuntimeEnvironment.getApplication()).pythonHelper = false }

    private fun nano(vararg replies: String) {
        val q = ArrayDeque(replies.toList())
        HybridAgent.testPhone = { p -> prompts.add(p); q.removeFirstOrNull() ?: """{"reason":"done","tool":"answer","tool_args":{"text":"Fixed add."}}""" }
        if (HybridAgent.testCloud == null) HybridAgent.testCloud = { _, _, _ -> ToolTurn("cloud", emptyList(), "stop") }
    }

    private fun run() = AgentHarness(app, request, ModelGateway.NETWORK, provider = "hybrid", modelName = "", timeoutSeconds = 120).run()

    @Test fun theTestPicksTheRewriteThatPasses() {
        assumeTrue(python())
        nano("add",
            "```python\ndef add(a, b):\n    total = a * b\n    return total\n```",            // wrong: the test still fails
            "Here it is:\n```python\ndef add(a, b):\n    return a + b\n```")
        val o = run()
        val r = o.resultsOf("python_fix")
        assertEquals(r.toString(), 1, r.size)
        assertTrue(r[0], r[0].contains("rewrote def add in calc.py (Gemini Nano on this phone; rewrite 2, 2 tests") && r[0].contains("passes now"))
        assertTrue(o.files["calc.py"], o.files["calc.py"]!!.contains("def add(a, b):\n    return a + b\n") && o.files["calc.py"]!!.contains("def double(x)"))
        // Nano was asked which block, then shown add whole, and never asked to copy search text
        assertTrue(prompts[0].contains("Which one must change") && prompts[0].contains("8: def add(a, b):"))
        assertTrue(prompts[1].contains("```python\ndef add(a, b):\n    total = a - b\n    return total\n```"))
        assertTrue(prompts[2].contains("An earlier rewrite of it did not pass"))
        assertTrue(o.resultsOf("edit_file").isEmpty())
    }

    @Test fun whenNoRewritePassesTheFileIsPutBackAndNanoCarriesOn() {
        assumeTrue(python())
        Tuning.pyFixSamples = 2
        nano("add", "```python\ndef add(a, b):\n    return a * b\n```", "```python\ndef add(a, b):\n    return 0\n```",
            // then Nano's own tools: replace_function by name, no search text
            """{"reason":"fix","tool":"replace_function","tool_args":${MiniJson.write(mapOf("path" to "calc.py", "name" to "add", "code" to "def add(a, b):\n    return a + b"))}}""",
            """{"reason":"check","tool":"run_command","tool_args":{"command":"python3 -B -m pytest -q -p no:cacheprovider tests/test_calc.py"}}""")
        val o = run()
        val r = o.resultsOf("python_fix")
        assertTrue(r.toString(), r.size == 1 && r[0].startsWith("Not written: PocketIDE tried 2 rewrites of add in calc.py"))
        val rf = o.resultsOf("replace_function")
        assertTrue(rf.toString(), rf.size == 1 && rf[0].startsWith("Replaced def add in calc.py."))
        assertTrue(o.files["calc.py"], o.files["calc.py"]!!.contains("def add(a, b):\n    return a + b\n") && o.files["calc.py"]!!.contains("    return x * 2"))
    }

    private var cloudAsked = ArrayList<String>()
    private fun cloudSays(vararg texts: String) {
        val q = ArrayDeque(texts.toList())
        HybridAgent.testCloud = { _, msgs, _ -> cloudAsked.add(msgs.last()["content"].toString()); ToolTurn(q.removeFirstOrNull() ?: "Fixed add.", emptyList(), "stop") }
    }
    private fun hybridMode() {
        RuntimeEnvironment.getApplication().getSharedPreferences("hybrid_ai", 0).edit().putString("mode", HybridAi.Mode.PREFER_ON_DEVICE.name).commit()
        HybridAgent.testOnline = true
    }

    /** 1.5.203: Nano's own rewrite fails; the cloud says what to change; Nano writes it from that plan and the test passes. */
    @Test fun nanoWritesTheCloudsPlan() {
        assumeTrue(python())
        hybridMode(); Tuning.pyFixSamples = 1
        nano("add", "```python\ndef add(a, b):\n    total = a * b\n    return total\n```",
            "```python\ndef add(a, b):\n    total = a + b\n    return total\n```")
        cloudSays("Line 2: change `total = a - b` to `total = a + b`.")
        val o = run()
        val r = o.resultsOf("python_fix")
        assertTrue(r.toString(), r.size == 1 && r[0].contains("Gemini Nano on this phone, from the cloud's plan") && r[0].contains("passes now"))
        assertEquals(1, cloudAsked.size)
        assertTrue(cloudAsked[0], cloudAsked[0].contains("in at most 8 short lines") && cloudAsked[0].contains("assert 6 == 5"))   // shown how Nano's try failed
        assertTrue(prompts.any { it.contains("WHAT TO CHANGE (from a stronger model") && it.contains("total = a + b") })
        assertTrue(o.files["calc.py"]!!.contains("total = a + b"))
    }

    /** 1.5.203: when Nano cannot write it even from the plan, the cloud writes the block and the test decides. */
    @Test fun theCloudWritesTheBlockLast() {
        assumeTrue(python())
        hybridMode(); Tuning.pyFixSamples = 1
        nano("add", "```python\ndef add(a, b):\n    return a * b\n```", "```python\ndef add(a, b):\n    return 0\n```", "```python\ndef add(a, b):\n    return 1\n```")
        cloudSays("Change the minus to a plus.", "```python\ndef add(a, b):\n    return a + b\n```")
        val o = run()
        val r = o.resultsOf("python_fix")
        assertTrue(r.toString(), r.size == 1 && r[0].contains("(the cloud model;") && r[0].contains("passes now"))
        assertEquals(2, cloudAsked.size)
        assertTrue(o.files["calc.py"]!!.contains("    return a + b"))
    }

    /** Private mode: the fix path never asks the cloud. */
    @Test fun privateModeNeverAsksTheCloud() {
        assumeTrue(python())
        Tuning.pyFixSamples = 1
        nano("add", "```python\ndef add(a, b):\n    return a * b\n```")
        cloudSays("```python\ndef add(a, b):\n    return a + b\n```")
        val o = run()
        assertTrue(o.resultsOf("python_fix").first().startsWith("Not written"))
        assertTrue(cloudAsked.toString(), cloudAsked.none { it.contains("You are fixing a bug") })
    }

    /** 1.5.206 (run 1126-2203): a small tool set left over from a request that timed out does not carry into the next one. */
    @Test fun aLeftoverSmallToolSetDoesNotCarryOver() {
        assumeTrue(python())
        dev.pocketide.app.core.Tools.only = setOf("read_file", "run_command"); dev.pocketide.app.core.Tools.onlyNarrowed = true
        try {
            nano("add", "```python\ndef add(a, b):\n    return a + b\n```")
            val o = run()
            val r = o.resultsOf("python_fix")
            assertTrue(r.toString(), r.size == 1 && r[0].contains("passes now"))
        } finally { dev.pocketide.app.core.Tools.only = null; dev.pocketide.app.core.Tools.onlyNarrowed = false }
    }

    @Test fun offWhenRemoteConfigTurnsItOff() {
        Tuning.pyFix = false
        nano("""{"reason":"look","tool":"read_file","tool_args":{"path":"calc.py"}}""")
        val o = run()
        assertTrue(o.resultsOf("python_fix").isEmpty())
    }
}
