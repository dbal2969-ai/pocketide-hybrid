package dev.pocketide.app

import dev.pocketide.app.core.ImageHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.129: a model that sees images describes them for one that cannot. */
class ImageHelperTest {
    private val models = mapOf("gemini" to "gemini-3-flash", "openrouter" to "some/text-model", "groq" to "llama")

    @Test fun picksTheFirstSeeingModelWithAKey() {
        val keys = setOf("cloudflare", "openrouter", "gemini")
        val sees = { p: String, m: String -> p == "gemini" || m.contains("vl") }
        assertEquals("gemini" to "gemini-3-flash", ImageHelper.pick("cloudflare", { it in keys }, { models[it] ?: "" }, sees))
    }

    @Test fun skipsProvidersWithoutAKeyOrSight() {
        val sees = { p: String, _: String -> p == "anthropic" }
        assertNull(ImageHelper.pick("cloudflare", { it == "gemini" }, { models[it] ?: "" }, sees))
        assertNull(ImageHelper.pick("gemini", { it == "gemini" }, { models[it] ?: "" }, { _, _ -> true }))   // not the current one
    }

    @Test fun theMessageCarriesTheDescription() {
        val m = ImageHelper.compose("Why is the button cut off?", "gemini-3-flash", "A login form. The Sign in button is cut off at the right edge.", 1)
        assertTrue(m.startsWith("Why is the button cut off?"))
        assertTrue(m.contains("described by gemini-3-flash") && m.contains("cannot see images") && m.endsWith("right edge."))
        assertTrue(ImageHelper.prompt(2).contains("Image 1, Image 2"))
    }
}
