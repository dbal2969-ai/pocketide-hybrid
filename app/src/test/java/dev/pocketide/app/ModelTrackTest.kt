package dev.pocketide.app

import dev.pocketide.app.core.ModelFacts
import dev.pocketide.app.core.ModelRecommend
import dev.pocketide.app.core.ModelRecommend.Task
import dev.pocketide.app.core.ModelTrack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 1.5.128: suggestions count what was measured and what happened on this phone, not only what providers say. The owner was
 * suggested thinkingmachines/inkling:free (OpenRouter: free, agentic, tool-using) over Kimi K2.6 again and again, and it failed.
 */
class ModelTrackTest {
    private val now = 1_800_000_000_000L
    private val kimi = ModelFacts.Facts("cloudflare", "@cf/moonshotai/kimi-k2.6")   // Cloudflare says little about it
    private val inkling = ModelFacts.Facts("openrouter", "thinkingmachines/inkling:free", "An agentic coding model with tool use and long context",
        context = 256_000, maxOutput = 32_000, tools = true, free = true)
    private val usable = setOf("cloudflare", "openrouter")

    @Before fun clean() = ModelTrack.clearForTest()
    @After fun after() = ModelTrack.clearForTest()

    @Test fun anUnprovenModelIsNotSuggestedOverAMeasuredOne() {
        assertTrue(ModelTrack.proven("cloudflare", kimi.id))
        assertFalse(ModelTrack.proven("openrouter", inkling.id))
        assertNull(ModelRecommend.advice(Task.BUILD, "cloudflare", kimi.id, listOf(kimi, inkling), usable, now = now))
    }

    @Test fun twoFailuresHereAndNeverFinishedEndsTheSuggestions() {
        val list = listOf(inkling)
        assertNotNull(ModelRecommend.score(inkling, Task.BUILD))
        ModelTrack.outcome("openrouter", inkling.id, finished = false)
        assertNotNull(ModelRecommend.score(inkling, Task.BUILD))
        ModelTrack.outcome("openrouter", inkling.id, finished = false)
        assertNull(ModelRecommend.score(inkling, Task.BUILD))
        assertTrue(ModelRecommend.best(Task.BUILD, list, usable).isEmpty())
    }

    @Test fun finishingHereProvesAModelAndFailuresCostPoints() {
        repeat(2) { ModelTrack.outcome("openrouter", inkling.id, finished = true) }
        assertTrue(ModelTrack.proven("openrouter", inkling.id))
        val before = ModelRecommend.score(inkling, Task.BUILD)!!
        ModelTrack.outcome("openrouter", inkling.id, finished = false)
        assertEquals(before - 3, ModelRecommend.score(inkling, Task.BUILD))
        assertTrue(ModelTrack.evidence("openrouter", inkling.id).contains("2 finished, 1 unfinished"))
    }

    @Test fun aSuggestionIsMadeAtMostOnceAWeekAndNeverAfterDismissal() {
        val unknown = ModelFacts.Facts("groq", "some-small-model")   // current model: unproven
        val list = listOf(unknown, inkling)
        assertEquals(inkling.id, ModelRecommend.advice(Task.BUILD, "groq", unknown.id, list, setOf("groq", "openrouter"), now = now)?.facts?.id)
        ModelTrack.suggested("openrouter", inkling.id, now)
        assertNull(ModelRecommend.advice(Task.BUILD, "groq", unknown.id, list, setOf("groq", "openrouter"), now = now + 3600_000))
        assertNotNull(ModelRecommend.advice(Task.BUILD, "groq", unknown.id, list, setOf("groq", "openrouter"), now = now + ModelTrack.WEEK_MS + 1))
        ModelTrack.dismiss("openrouter", inkling.id)
        assertNull(ModelRecommend.advice(Task.BUILD, "groq", unknown.id, list, setOf("groq", "openrouter"), now = now + 2 * ModelTrack.WEEK_MS))
    }

    @Test fun measuredModelsMatchOnAnyProvider() {
        assertNotNull(ModelTrack.measured("anthropic/claude-haiku-5-5"))
        assertNotNull(ModelTrack.measured("z-ai/glm-5.3-flash"))
        assertNull(ModelTrack.measured("thinkingmachines/inkling:free"))
    }

    @Test fun theRecordSurvivesARestart() {
        ModelTrack.outcome("openrouter", inkling.id, finished = false)
        ModelTrack.dismiss("groq", "x")
        val saved = ModelTrack.encode()
        ModelTrack.clearForTest()
        ModelTrack.load(saved)
        assertEquals(1, ModelTrack.get("openrouter", inkling.id).failed)
        assertTrue(ModelTrack.get("groq", "x").dismissed)
    }
}
