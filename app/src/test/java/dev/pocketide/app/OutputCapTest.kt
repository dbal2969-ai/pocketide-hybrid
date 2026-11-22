package dev.pocketide.app

import dev.pocketide.app.core.OutputLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputCapTest {
    @Test fun aCutOffReplyDoesNotLowerTheLimit() {
        // the stored limit is only a provider's per-minute rule; the default is the model's full reply length
        assertEquals(16000, OutputLimit.maxTokens("some-model"))
    }

    @Test fun cutReplyIsRecognised() {
        assertTrue(OutputLimit.isCut("length"))
        assertTrue(OutputLimit.isCut("max_tokens"))
        assertTrue(!OutputLimit.isCut("stop"))
    }

    @Test fun cloudflareGetsAReplyLimitBeforeItsWindowIsKnown() {
        // Cloudflare stops gpt-oss and Llama at 256 tokens when no max_tokens is sent (1.5.89)
        assertEquals(16000, OutputLimit.whenUnknown("cloudflare", "@cf/openai/gpt-oss-120b"))
        // other providers keep their own default (newer OpenAI models refuse max_tokens)
        for (p in listOf("openai", "groq", "openrouter", "gemini")) assertEquals(p, 0, OutputLimit.whenUnknown(p, "some-model"))
    }

    @Test fun exportsKeepWholeMessages() {
        val long = "x".repeat(20_000)
        val s = dev.pocketide.app.core.ChatSession("c1", "Long", 0L, 0L)
        s.entries.add(dev.pocketide.app.core.ChatEntry("user", long))
        s.entries.add(dev.pocketide.app.core.ChatEntry("assistant", long))
        s.entries.add(dev.pocketide.app.core.ChatEntry("user", long, "auto"))
        val md = dev.pocketide.app.core.ChatExport.markdown(s)
        assertTrue(!md.contains("more characters"))
        assertEquals(3, Regex("x{20000}").findAll(md).count())
    }
}
