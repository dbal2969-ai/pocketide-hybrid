package dev.pocketide.app

import dev.pocketide.app.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapacityRetryTest {
    private val cloudflare = """{"errors":[{"message":"AiError: AiError: Capacity temporarily exceeded, please try again. (5244bfbd)","code":3040}],"success":false,"result":{},"messages":[]}"""

    @Test fun cloudflareErrorsAreReadable() {
        val m = SseDecoder.errorMessage(cloudflare)
        assertNotNull(m); assertTrue(m!!, m.contains("Capacity temporarily exceeded"))
        assertEquals("rate", SseDecoder.errorMessage("""{"error":{"message":"rate"}}"""))
        assertNull(SseDecoder.errorMessage("""{"ok":true}"""))
    }

    @Test fun capacityErrorsWaitLongerEachTime() {
        val m = SseDecoder.errorMessage(cloudflare)!!
        assertEquals(8, RateLimit.waitSeconds(429, m, null, 0))
        assertEquals(15, RateLimit.waitSeconds(429, m, null, 1))
        assertEquals(25, RateLimit.waitSeconds(429, m, null, 2))
        assertEquals(25, RateLimit.waitSeconds(503, m, null, 9))
        assertNull("other 503s are shown", RateLimit.waitSeconds(503, "server error", null, 0))
        assertEquals(8, RateLimit.waitSeconds(503, "Upstream provider is temporarily unavailable.", null, 0))
        assertEquals(15, RateLimit.waitSeconds(529, "Overloaded", null, 1))
        assertEquals(8, RateLimit.waitSeconds(502, "<html><title>502 Bad Gateway</title></html>", null, 0))
        // 1.5.111: a bare gateway code is retried too
        assertEquals(8, RateLimit.waitSeconds(502, "error code: 502", null, 0))
        assertEquals(15, RateLimit.waitSeconds(504, "", null, 1))
        assertEquals(8, RateLimit.waitSeconds(500, "AiError: AiError: Internal server error (f9005f4f-44e3-457c-a028-d4d13f6f1e4b)", null, 0))
        assertEquals(15, RateLimit.waitSeconds(503, "AiError: AiError: Unknown internal error (060b12f4)", null, 1))
        assertNull("a 400 is shown", RateLimit.waitSeconds(400, "Internal server error", null, 0))
        assertNull("a billing problem is shown", RateLimit.waitSeconds(402, "Insufficient balance. Please top up your balance to continue.", null, 0))
        assertEquals(15, RateLimit.waitSeconds(429, "rate limit, try again in 14s", null, 0))
    }

    // 1.5.170: OpenRouter's free tier and an upstream provider's 429 ended benchmark runs mid-task
    @Test fun freeTierPerMinuteLimitsAndUpstream429sAreWaitedFor() {
        assertEquals(20, RateLimit.waitSeconds(429, "Rate limit exceeded: free-models-per-min.", null, 0))
        assertEquals(60, RateLimit.waitSeconds(429, "Rate limit exceeded: free-models-per-min.", null, 2))
        assertEquals(8, RateLimit.waitSeconds(429, "Provider returned error", null, 0))
        assertEquals(null, RateLimit.waitSeconds(429, "Rate limit exceeded: free-models-per-day.", null, 0))
    }
}
