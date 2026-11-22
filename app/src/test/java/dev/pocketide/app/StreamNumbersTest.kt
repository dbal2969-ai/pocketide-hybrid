package dev.pocketide.app

import dev.pocketide.app.core.Providers
import dev.pocketide.app.core.SseDecoder
import dev.pocketide.app.core.StreamText
import dev.pocketide.app.core.ToolStream
import dev.pocketide.app.core.Tools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamNumbersTest {
    // Cloudflare Workers AI sends a token that is only digits as a JSON number
    private val lines = listOf(
        """data: {"choices":[{"delta":{"content":"const port = "}}]}""",
        """data: {"choices":[{"delta":{"content":3000}}]}""",
        """data: {"choices":[{"delta":{"content":";\nctx.font = '"}}]}""",
        """data: {"choices":[{"delta":{"content":16}}]}""",
        """data: {"choices":[{"delta":{"content":"px Arial'; v = "}}]}""",
        """data: {"choices":[{"delta":{"content":1.50}}]}"""
    )

    @Test fun textDecoderKeepsNumberTokens() {
        val d = SseDecoder(false)
        assertEquals("const port = 3000;\nctx.font = '16px Arial'; v = 1.50", lines.joinToString("") { d.feed(it) })
    }

    @Test fun toolDecoderKeepsNumberTokens() {
        val t = ToolStream(false)
        lines.forEach { t.feed(it) }
        assertTrue(t.textSoFar.contains("port = 3000;") && t.textSoFar.contains("'16px Arial'"))
    }

    @Test fun helpers() {
        assertEquals("42", StreamText.of(42.0, "{}"))
        assertEquals("", StreamText.of(null, "{}"))
        assertTrue(Tools.filesAsText("Here:\n```file:index.js\nx\n```"))
        assertFalse(Tools.filesAsText("```js\nconsole.log(1)\n```"))
        assertTrue(Tools.providerSupports("cloudflare") && Tools.providerSupports("ollamacloud"))
        // gateways, paid providers and custom servers use the tool-calling agent (a server that refuses tools falls back to the text format)
        for (id in listOf("haimaker", "deepseek", "xai", "together", "fireworks", "deepinfra", "moonshot", "zai", "nebius", "nvidia", "huggingface", "sambanova")) {
            assertTrue(id, Tools.providerSupports(id)); assertEquals(id, Providers.byId(id).id)
            assertEquals(Providers.byId(id).url, Providers.customUrl(Providers.byId(id).url))   // a full chat address
        }
        assertEquals("https://api.haimaker.ai/v1/chat/completions", Providers.byId("haimaker").url)
        assertTrue(Tools.providerSupports("custom")); assertFalse(Tools.providerSupports("ollama")); assertFalse(Tools.providerSupports("demo"))
        // every provider that takes a key links to where to get one; keyless providers link nowhere
        for ((id, url) in Providers.keyUrls) { assertTrue(id, Providers.all.any { it.id == id }); assertTrue(url, url.startsWith("https://")) }
        for (p in Providers.all) if (p.keyless) assertNull(p.id, Providers.keyUrls[p.id])
    }
}
