package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 1.5.214: the PocketIDEAI bridge in Preview and the "Hybrid AI web app" starter. */
class PreviewAiTest {
    @Test fun onlyTheProjectsOwnPagesMayAsk() {
        assertTrue(PreviewAi.allowed("https://my-game.pocketide.local/index.html"))
        assertTrue(PreviewAi.allowed("https://p1.pocketide.local"))
        assertFalse(PreviewAi.allowed("http://localhost:8080/"))
        assertFalse(PreviewAi.allowed("https://evil.example/?x=.pocketide.local"))
        assertFalse(PreviewAi.allowed("https://pocketide.local.evil.example/"))
        assertFalse(PreviewAi.allowed(null))
        assertNull(PreviewAi.refusal("https://a.pocketide.local/", "hi"))
        assertTrue(PreviewAi.refusal("https://a.pocketide.local/", "x".repeat(PreviewAi.MAX_PROMPT + 1))!!.contains("longer"))
        assertTrue(PreviewAi.refusal("https://a.pocketide.local/", "  ")!!.contains("empty"))
    }

    @Test fun theCloudAllowanceIsPerHour() {
        var now = 0L
        val a = PreviewAi.Allowance { now }
        assertEquals(2, a.cloudLeft(2))
        a.usedCloud(); a.usedCloud()
        assertEquals(0, a.cloudLeft(2))
        now += 3_600_001L
        assertEquals(2, a.cloudLeft(2))
        assertEquals(0, a.cloudLeft(0))
    }

    @Test fun theCallbackIsSafeJavaScript() {
        val js = PreviewAi.callback("a\"b", PreviewAi.reply("it's \"x\"\n</script>", true, 1.5, ""))
        assertTrue(js, js.startsWith("window.__pocketideAI && window.__pocketideAI(\"a\\\"b\", \""))
        val parsed = MiniJson.obj(MiniJson.parse(MiniJson.parse(js.substringAfter(", ").removeSuffix(");") ) as String))!!
        assertEquals("it's \"x\"\n</script>", parsed["text"])
        assertEquals(true, parsed["onDevice"])
    }

    @Test fun theStarterIsListedAndRenders() {
        assertTrue(Templates.all.any { it.id == "ai-web" })
        val files = Templates.render(AiWebTemplate.TEMPLATE, "Ask \"Gemini\"")
        assertTrue(files["index.html"]!!.contains("<title>Ask 'Gemini'</title>"))
        val ai = files["ai.js"]!!
        assertTrue(ai.contains("https://www.gstatic.com/firebasejs/${AiWebTemplate.SDK}/"))
        assertTrue(ai.contains("InferenceMode.PREFER_ON_DEVICE"))
        assertTrue(ai.contains("r.inferenceSource === 'on_device'"))
        assertTrue(ai.contains("window." + PreviewAi.NAME))
        assertFalse(files["firebase-config.js"]!!.contains("apiKey"))   // no project is shipped in the starter
    }

    /** The starter's modules are valid JavaScript (node --check), when Node is installed. */
    @Test fun theStartersJavaScriptParses() {
        val node = try { ProcessBuilder("node", "--version").start().waitFor() == 0 } catch (e: Exception) { false }
        assumeTrue(node)
        val dir = File.createTempFile("aiweb", ""); dir.delete(); dir.mkdirs()
        try {
            for ((p, text) in Templates.render(AiWebTemplate.TEMPLATE, "x")) if (p.endsWith(".js")) {
                val f = File(dir, p.removeSuffix(".js") + ".mjs"); f.writeText(text)
                val pr = ProcessBuilder("node", "--check", f.path).redirectErrorStream(true).start()
                val out = pr.inputStream.readBytes().toString(Charsets.UTF_8)
                assertEquals("$p: $out", 0, pr.waitFor())
            }
        } finally { dir.deleteRecursively() }
    }
}
