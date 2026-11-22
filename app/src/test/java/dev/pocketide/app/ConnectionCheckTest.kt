package dev.pocketide.app

import dev.pocketide.app.core.ConnectionCheck
import dev.pocketide.app.core.Providers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCheckTest {
    private val ollama = Providers.byId("ollamacloud")
    private val openrouter = Providers.byId("openrouter")

    @Test fun verdicts() {
        assertTrue(ConnectionCheck.works(200) && ConnectionCheck.works(429))
        assertFalse(ConnectionCheck.works(401) || ConnectionCheck.works(404))
        assertTrue(ConnectionCheck.chatVerdict(ollama, 200, "{}", "gpt-oss:120b", "k").startsWith("Connected to Ollama Cloud"))
        // the replies the providers really gave without a key (probed 2026-10-23)
        val unauthorized = """{"error":{"message":"Unauthorized","type":"api_error","param":null,"code":null}}"""
        assertTrue(ConnectionCheck.chatVerdict(ollama, 401, unauthorized, "gpt-oss:120b", "abc").contains("not a device key"))
        assertTrue(ConnectionCheck.chatVerdict(ollama, 403, "", "gpt-oss:120b", "ssh-ed25519 AAAAC3Nza").contains("device public key"))
        val gone = """{"error":{"message":"No endpoints found for qwen/qwen3-coder:free.","code":404}}"""
        assertTrue(ConnectionCheck.chatVerdict(openrouter, 404, gone, "qwen/qwen3-coder:free", "k").contains("does not know the model"))
        assertTrue(ConnectionCheck.chatVerdict(openrouter, 429, "{}", "m", "k").contains("limiting requests"))
        assertTrue(ConnectionCheck.chatVerdict(Providers.byId("cloudflare"), 405, "", "m", "k").contains("account ID"))
        assertTrue(ConnectionCheck.notListed(openrouter, "qwen/qwen3-coder:free", "poolside/laguna-s-2.1:free").contains("Suggested: poolside/laguna-s-2.1:free"))
    }

    // 1.5.137: the owner's Z.ai test: glm-4.6v-flash (free) was busy (429) and the app called it "may have been retired"
    @Test fun aModelThatTookTheRequestIsNotCalledRetired() {
        val zai = Providers.byId("zai")
        val m = ConnectionCheck.notListed(zai, "glm-4.6v-flash", "glm-5.3-flash", answered = true)
        assertFalse(m.contains("retired")); assertFalse(m.contains("Suggested")); assertTrue(m.contains("still served"))
        assertTrue(ConnectionCheck.works(429))
        assertTrue(ConnectionCheck.notListed(zai, "glm-old", "glm-5.3-flash", answered = false).contains("may have been retired"))
    }

    @Test fun defaultsAreCurrent() {
        // checked against the providers' public model lists on 2026-10-23
        assertTrue(Providers.byId("openrouter").model == "poolside/laguna-s-2.1:free")
    }
}
