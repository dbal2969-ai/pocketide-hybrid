package dev.pocketide.app

import dev.pocketide.app.core.ModelFacts
import dev.pocketide.app.core.ModelRecommend
import dev.pocketide.app.core.ModelRecommend.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRecommendTest {
    private val openRouter = """{"data":[
      {"id":"qwen/qwen3-coder:free","description":"Qwen3-Coder is optimized for agentic coding tasks such as function calling, tool use, and long-context reasoning over repositories.",
       "context_length":262144,"supported_parameters":["tools","tool_choice"],"architecture":{"input_modalities":["text"]},"pricing":{"prompt":"0","completion":"0"},"top_provider":{"max_completion_tokens":65536}},
      {"id":"xingchen-agi/xing4.0-29b-a4b:free","description":"A compact assistant model.","context_length":32768,"supported_parameters":["temperature"],
       "architecture":{"input_modalities":["text"]},"pricing":{"prompt":"0","completion":"0"}},
      {"id":"google/gemini-2.5-flash","description":"Fast multimodal model.","context_length":1048576,"supported_parameters":["tools"],
       "architecture":{"input_modalities":["text","image"]},"pricing":{"prompt":"0.0000003","completion":"0.0000025"}},
      {"id":"openai/whisper-1","description":"speech"}]}"""

    @Test fun readsProviderFormats() {
        val f = ModelFacts.parse("openrouter", openRouter)
        assertEquals(3, f.size)                       // whisper left out
        val coder = f.first { it.id == "qwen/qwen3-coder:free" }
        assertEquals(true, coder.tools); assertEquals(262144, coder.context); assertEquals(65536, coder.maxOutput); assertEquals(true, coder.free)
        val cf = ModelFacts.parse("cloudflare", """{"result":[{"id":"9c1b-uuid","name":"@cf/meta/llama-3.3-70b-instruct-fp8-fast","description":"Llama 3.3 70B quantized","properties":[{"property_id":"context_window","value":"24000"},{"property_id":"function_calling","value":"true"}]}]}""")
        assertEquals("@cf/meta/llama-3.3-70b-instruct-fp8-fast", cf[0].id); assertEquals(24000, cf[0].context); assertEquals(true, cf[0].tools)
        val mistral = ModelFacts.parse("mistral", """{"data":[{"id":"devstral-medium","description":"Agentic coding","max_context_length":131072,"capabilities":{"function_calling":true,"vision":false}}]}""")
        assertEquals(true, mistral[0].tools); assertEquals(131072, mistral[0].context)
        val gemini = ModelFacts.parse("gemini", """{"models":[{"name":"models/gemini-2.5-pro","description":"Thinking model","inputTokenLimit":1048576,"outputTokenLimit":65536,"supportedGenerationMethods":["generateContent"]},{"name":"models/embedding-001","supportedGenerationMethods":["embedContent"]}]}""")
        assertEquals(listOf("gemini-2.5-pro"), gemini.map { it.id })
        assertTrue(ModelFacts.summary(coder).startsWith("tool calling · 262k context"))
    }

    @Test fun recommendsTheCoderForBuildingNotTheSmallModel() {
        val f = ModelFacts.parse("openrouter", openRouter)
        val task = ModelRecommend.task("Unzip these files and build a local RuneScape clone web game with touch controls using the sprites", false, 40)
        assertEquals(Task.BUILD, task)
        val rec = ModelRecommend.advice(task, "openrouter", "xingchen-agi/xing4.0-29b-a4b:free", f, setOf("openrouter"))
        assertNotNull(rec)
        assertEquals("qwen/qwen3-coder:free", rec!!.facts.id)
        // already on the best model: no advice; a provider without a key is never suggested
        assertNull(ModelRecommend.advice(task, "openrouter", "qwen/qwen3-coder:free", f, setOf("openrouter")))
        assertNull(ModelRecommend.advice(task, "groq", "llama-3.1-8b-instant", f, setOf("groq")))
        // pictures need a model that reads images
        assertEquals("google/gemini-2.5-flash", ModelRecommend.best(Task.IMAGES, f, setOf("openrouter")).first().facts.id)
        assertEquals(Task.QUESTION, ModelRecommend.task("What does this function do?", false, 10))
    }

    @Test fun factsSurviveSaving() {
        val f = ModelFacts.parse("openrouter", openRouter)
        ModelFacts.load(null); ModelFacts.remember(f)
        val saved = ModelFacts.encode()
        ModelFacts.load(saved)
        assertEquals(f.toSet(), ModelFacts.all().toSet())
    }
}
