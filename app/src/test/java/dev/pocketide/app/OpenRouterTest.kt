package dev.pocketide.app

import dev.pocketide.app.core.MiniJson
import dev.pocketide.app.core.OpenRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterTest {
    @Test
    fun pkce() {
        // RFC 7636 appendix B test vector
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OpenRouter.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = OpenRouter.newVerifier()
        assertEquals(43, v.length)
        assertTrue(v.matches(Regex("[A-Za-z0-9_-]+")))
        assertTrue(OpenRouter.newVerifier() != v)
    }

    @Test
    fun authUrls() {
        val u = OpenRouter.authUrl("abc", OpenRouter.callbackUrl(51423), "st8")
        assertTrue(u, u.startsWith("https://openrouter.ai/auth?callback_url=http%3A%2F%2Flocalhost%3A51423%2Fcallback&"))
        assertTrue(u.contains("code_challenge=abc&code_challenge_method=S256") && u.contains("key_label=PocketIDE") && u.endsWith("&state=st8"))
        val h = OpenRouter.authUrl("abc", null, "ignored")
        assertFalse("headless mode has no callback or state", h.contains("callback_url") || h.contains("state="))
        assertTrue(h.contains("code_challenge=abc"))
    }

    @Test
    fun exchangeAndCallback() {
        val body = MiniJson.obj(MiniJson.parse(OpenRouter.exchangeBody(" c0de \n", "ver")))!!
        assertEquals(mapOf("code" to "c0de", "code_verifier" to "ver", "code_challenge_method" to "S256"), body)
        assertEquals("sk-or-v1-x", OpenRouter.parseKey("""{"key":"sk-or-v1-x","user_id":"u"}"""))
        assertNull(OpenRouter.parseKey("""{"error":"no"}""")); assertNull(OpenRouter.parseKey("not json"))
        val cb = OpenRouter.parseCallback("GET /callback?code=a%2Bb&state=st8 HTTP/1.1")!!
        assertEquals("a+b", cb.code); assertEquals("st8", cb.state); assertNull(cb.error)
        assertEquals("access_denied", OpenRouter.parseCallback("GET /callback?error=access_denied HTTP/1.1")!!.error)
        assertNull("other paths are not the callback", OpenRouter.parseCallback("GET /favicon.ico HTTP/1.1"))
        assertNull(OpenRouter.parseCallback(""))
        assertEquals("xyz", OpenRouter.pastedCode("  xyz "))
        assertEquals("q1", OpenRouter.pastedCode("http://localhost:5/callback?code=q1&state=s"))
        assertNull(OpenRouter.pastedCode("two words"))
        assertTrue(OpenRouter.exchangeError(403, """{"error":{"message":"Authorization code expired"}}""").contains("expired"))
    }

    @Test
    fun keyInfo() {
        val json = """{"data":{"label":"sk-or-v1-au7...890","limit":100,"limit_remaining":74.5,"limit_reset":"monthly","usage":25.5,"usage_daily":1.25,"usage_weekly":5,"usage_monthly":25.5,
            "is_free_tier":false,"free_model_daily_requests":{"limit":1000,"remaining":988,"used":12}}}"""
        val k = OpenRouter.parseKeyInfo(json)!!
        assertEquals(74.5, k.remaining!!, 0.0); assertEquals(12, k.freeUsed)
        val s = k.summary()
        assertTrue(s, s.contains("$1.25 today") && s.contains("$25.50 this month") && s.contains("$74.50 left of its $100.00 limit (resets monthly)") && s.contains("12 of 1000"))
        val free = OpenRouter.parseKeyInfo("""{"data":{"label":"k","limit":null,"limit_remaining":null,"usage":0,"usage_daily":0,"usage_monthly":0,"is_free_tier":true}}""")!!
        assertTrue(free.summary(), free.summary().contains("only free models") && !free.summary().contains("limit"))
        assertNull(OpenRouter.parseKeyInfo("""{"error":"x"}"""))
        assertEquals("$0.0040", OpenRouter.usd(0.004))
    }

    @Test
    fun routing() {
        assertEquals(listOf("b/2", "c/3"), OpenRouter.parseFallbacks("b/2, a/1 ,\nc/3, b/2,", "a/1"))
        assertEquals(3, OpenRouter.parseFallbacks("1,2,3,4,5", "x").size)
        assertEquals(emptyMap<String, Any?>(), OpenRouter.routing("a/1", false, emptyList(), OpenRouter.Route.DEFAULT))
        val r = OpenRouter.routing("a/1", true, listOf("b/2"), OpenRouter.Route.PRICE)
        assertEquals(listOf("a/1", "b/2"), r["models"])
        assertEquals(mapOf("require_parameters" to true, "sort" to "price"), r["provider"])
        assertEquals(mapOf("data_collection" to "deny"), OpenRouter.routing("a", false, emptyList(), OpenRouter.Route.PRIVATE)["provider"])
        assertEquals(mapOf("sort" to "throughput"), OpenRouter.routing("a", false, emptyList(), OpenRouter.Route.SPEED)["provider"])
    }
}
