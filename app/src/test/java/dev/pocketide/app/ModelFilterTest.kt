package dev.pocketide.app

import dev.pocketide.app.core.ModelFilter
import dev.pocketide.app.core.ModelFilter.Kind
import dev.pocketide.app.core.ModelPurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFilterTest {
    @Test fun splitsNameWordsFromKinds() {
        val q = ModelFilter.parse("qwen free tools", setOf(Kind.CODING))
        assertEquals(listOf("qwen"), q.terms)
        assertEquals(setOf(Kind.CODING, Kind.FREE, Kind.TOOLS), q.kinds)
        assertTrue(ModelFilter.parse("  ").isEmpty)
    }

    @Test fun matchesPartOfANameWithOrWithoutSeparators() {
        assertTrue(ModelFilter.nameMatches("moonshotai/kimi-k2.6", listOf("kimi")))
        assertTrue(ModelFilter.nameMatches("moonshotai/kimi-k2.6", listOf("kimik2")))
        assertTrue(ModelFilter.nameMatches("deepseek/deepseek-r1-0528", listOf("r1", "0528")))
        assertFalse(ModelFilter.nameMatches("deepseek/deepseek-r1-0528", listOf("qwen")))
    }

    @Test fun learnsThatAModelIsOnlyForListedApps() {
        val msg = "thinkingmachines/inkling:free is only available on agentic harnesses. Try plugging it into a coding agent or productivity app listed on https://openrouter.ai/apps (HTTP 403)"
        assertTrue(dev.pocketide.app.core.ModelFacts.learnFromError("openrouter", "thinkingmachines/inkling:free", msg))
        assertEquals("only for apps OpenRouter lists (403)", dev.pocketide.app.core.ModelFacts.get("openrouter", "thinkingmachines/inkling:free")?.unavailable)
    }

    @Test fun filtersByKind() {
        val coder = ModelPurpose.Info("qwen/qwen3-coder:free", ModelPurpose.Group.CODING, true, false, true)
        assertTrue(ModelFilter.kindMatches(coder, setOf(Kind.CODING, Kind.TOOLS, Kind.FREE), null))
        assertFalse(ModelFilter.kindMatches(coder, setOf(Kind.IMAGES), null))
        assertFalse(ModelFilter.kindMatches(coder, setOf(Kind.WORKS), "not offered (404)"))
        val f = dev.pocketide.app.core.ModelFacts.Facts("openrouter", coder.id, reasoning = true)
        assertTrue(ModelFilter.kindMatches(coder, setOf(Kind.REASONING), null, f))
    }
}
