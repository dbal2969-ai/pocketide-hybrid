package dev.pocketide.app

import dev.pocketide.app.core.ContextFit
import dev.pocketide.app.core.HistoryTrim
import dev.pocketide.app.core.Tools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextFitTest {
    @Test fun aSmallWindowGetsASmallerHistoryAndReply() {
        // the case from the phone: a 32K server, a big system prompt and the tool list
        val b = ContextFit.messageBudget(32768, systemChars = 20_000, toolChars = 25_000, replyCap = 16000)
        assertTrue("history must leave room: $b", b < HistoryTrim.BUDGET)
        val total = (b + 20_000 + 25_000) / ContextFit.CHARS_PER_TOKEN + ContextFit.replyTokens(32768, 16000)
        assertTrue("everything fits in 32K: $total", total <= 32768)
        assertEquals(8192, ContextFit.replyTokens(32768, 16000))
        assertEquals(16000, ContextFit.replyTokens(200_000, 16000))
        assertEquals("unknown window: unchanged", HistoryTrim.BUDGET, ContextFit.messageBudget(null, 20_000, 25_000, 16000))
        assertEquals("never below the floor", ContextFit.MIN_BUDGET, ContextFit.messageBudget(16384, 60_000, 25_000, 16000))
        assertEquals("an 8K window: a quarter of it", 8192 * ContextFit.CHARS_PER_TOKEN / 4, ContextFit.messageBudget(8192, 60_000, 25_000, 16000))
    }

    @Test fun anOllamaServerIsSizedToTheWindowTheAppGivesIt() {
        // Granite 4.2 8B on real Ollama (1.5.179 R1): 8 of 13 real-project tasks ended on this error, which was not recognised
        val ollama = "request (11831 tokens) exceeds the available context size (8192 tokens), try increasing it"
        assertTrue(ContextFit.isTooLong(ollama))
        assertEquals(8192, ContextFit.limitFrom(ollama))
        assertFalse(Tools.isRejection(400, ollama))
        // Ollama's OpenAI API lists no window: a local server gets the one the app starts it with, a cloud model stays unknown
        assertEquals(8192, dev.pocketide.app.core.ModelFacts.context("ollama", "granite4.2:8b-test-unlisted"))
        assertEquals(null, dev.pocketide.app.core.ModelFacts.context("openrouter", "nobody/unlisted-test-model"))
        // in small mode (prompt about 5,000 characters, short tool list) the whole request fits the 8K window
        val b = ContextFit.messageBudget(8192, systemChars = 5_000, toolChars = 2_000, replyCap = 16000)
        val total = (b + 5_000 + 2_000) / ContextFit.CHARS_PER_TOKEN + ContextFit.replyTokens(8192, 16000)
        assertTrue("fits 8K: $total", total <= 8192)
    }

    @Test fun tooLongErrorsAreRecognisedAndAreNotToolRefusals() {
        val vllm = "Input length (34701) exceeds model's maximum context length (32768)."
        assertTrue(ContextFit.isTooLong(vllm))
        assertEquals(32768, ContextFit.limitFrom(vllm))
        assertEquals(8192, ContextFit.limitFrom("This model's maximum context length is 8192 tokens. However, you requested 9000 tokens"))
        assertFalse(Tools.isRejection(400, vllm))
        // a request whose error mentions tools only because it was too long is still about length
        assertFalse(Tools.isRejection(400, "maximum context length is 8192 tokens (messages + tools)"))
        assertTrue(Tools.isRejection(400, "this model does not support tools"))
        assertFalse(ContextFit.isTooLong("invalid api key"))
    }

    @Test fun theReplyLimitShrinksWhenThePromptNearlyFillsTheWindow() {
        // Cloudflare, Llama 3.3 at 1.5.89: "maximum context length is 24000 tokens. However, you requested 6000 output tokens and
        // your prompt contains at least 18001 input tokens"
        val msg = "This model's maximum context length is 24000 tokens. However, you requested 6000 output tokens and your prompt contains at least 18001 input tokens"
        assertTrue(ContextFit.isTooLong(msg))
        assertEquals(24000, ContextFit.limitFrom(msg))
        val reply = ContextFit.replyFor(6000, 24000, promptChars = 54_003)
        assertTrue("prompt and reply must fit: $reply", 54_003 / ContextFit.CHARS_PER_TOKEN + reply <= 24000)
        assertEquals(6000, ContextFit.replyFor(6000, 24000, promptChars = 30_000))
        assertEquals(ContextFit.MIN_REPLY, ContextFit.replyFor(6000, 24000, promptChars = 80_000))
        // no limit sent, or the window unknown: unchanged
        assertEquals(0, ContextFit.replyFor(0, 24000, promptChars = 54_003))
        assertEquals(6000, ContextFit.replyFor(6000, null, promptChars = 54_003))
    }
}
