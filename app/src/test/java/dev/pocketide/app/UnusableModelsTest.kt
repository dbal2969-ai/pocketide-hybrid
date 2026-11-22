package dev.pocketide.app

import dev.pocketide.app.core.KnownPrices
import dev.pocketide.app.core.ModelFacts
import dev.pocketide.app.core.ModelPurpose
import dev.pocketide.app.core.ModelRecommend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnusableModelsTest {
    @Test fun learnsFromTheErrorsInTheReport() {
        ModelFacts.load(null)
        assertTrue(ModelFacts.learnFromError("nvidia", "bigcode/starcoder2-15b", "Request failed (404)."))
        assertTrue(ModelFacts.learnFromError("kilo", "openai/gpt-4-turbo", "You need to sign in to use this model. (HTTP 401)"))
        assertTrue(ModelFacts.learnFromError("nvidia", "nvidia/riva-translate-4b-instruct-v2", "This model's maximum context length is 8192 tokens. However, you requested 0 output tokens"))
        assertFalse(ModelFacts.learnFromError("openai", "gpt-5.1", "You have no credits remaining. (HTTP 429)"))   // the account, not the model
        assertEquals("not offered for chat (404)", ModelFacts.get("nvidia", "bigcode/starcoder2-15b")!!.unavailable)
        assertEquals("needs sign-in", ModelFacts.get("kilo", "openai/gpt-4-turbo")!!.unavailable)
        assertEquals(8192, ModelFacts.get("nvidia", "nvidia/riva-translate-4b-instruct-v2")!!.context)
        assertNull(ModelRecommend.score(ModelFacts.get("nvidia", "bigcode/starcoder2-15b")!!, ModelRecommend.Task.BUILD))
        ModelFacts.clearUnavailable("nvidia", "bigcode/starcoder2-15b")
        assertNull(ModelFacts.get("nvidia", "bigcode/starcoder2-15b")!!.unavailable)
        // survives saving
        val saved = ModelFacts.encode(); ModelFacts.load(saved)
        assertEquals("needs sign-in", ModelFacts.get("kilo", "openai/gpt-4-turbo")!!.unavailable)
        assertEquals(ModelPurpose.Group.NOT_CHAT, ModelPurpose.group("nvidia/riva-translate-4b-instruct-v2"))
    }

    @Test fun pricesFromTheProvidersList() {
        ModelFacts.load(null)
        ModelFacts.remember(ModelFacts.parse("openrouter", """{"data":[{"id":"openai/gpt-5.6-terra-pro","pricing":{"prompt":"0.000015","completion":"0.00012"},"supported_parameters":["tools"]}]}"""))
        val p = KnownPrices.find("openrouter", "openai/gpt-5.6-terra-pro")
        assertNotNull(p)
        assertEquals(15.0, p!!.inPerM, 0.0001); assertEquals(120.0, p.outPerM, 0.0001)
        assertEquals(2.0, KnownPrices.find("anthropic", "claude-sonnet-5-5")!!.inPerM, 0.0)
    }
}
