package dev.pocketide.app

import dev.pocketide.app.core.Engine
import dev.pocketide.app.core.LocalModels
import dev.pocketide.app.core.ModelFacts
import dev.pocketide.app.core.Providers
import dev.pocketide.app.core.SmallLocal
import dev.pocketide.app.core.Starter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.182: the built-in engine (llama-server inside the app). */
class EngineTest {
    @Test fun theEngineIsALocalProviderWithThePhoneSettings() {
        val p = Providers.byId(Engine.PROVIDER)
        assertEquals(Engine.PROVIDER, p.id)
        assertTrue(p.keyless && p.custom)
        assertEquals(Engine.BASE, p.defaultBase)
        assertTrue(LocalModels.isLocal(Engine.PROVIDER, Engine.BASE))
        assertEquals(8192, ModelFacts.context(Engine.PROVIDER, "qwen3.5-4b", Engine.BASE))      // sized like Ollama on the phone
        assertTrue("small mode for a 4B", SmallLocal.applies(Engine.PROVIDER, Engine.BASE, "qwen3.5-4b"))
        assertNull("no privacy note: nothing leaves the phone", Starter.keylessNote(Engine.PROVIDER, false))
        assertTrue(Engine.byId(p.model) != null)
    }

    @Test fun theCommandLineUsesThePhoneSettings() {
        val a = Engine.args("/lib/libllama_server.so", "/files/engine-models/Qwen3.5-4B-Q4_K_M.gguf", 8)
        val s = a.joinToString(" ")
        for (part in listOf("-c 8192", "-ctk q8_0", "-ctv q8_0", "-fa on", "--jinja", "--host 127.0.0.1", "--port ${Engine.PORT}", "-t 4", "--no-webui", "-np 1"))
            assertTrue(part, s.contains(part))
        assertEquals("/lib/libllama_server.so", a[0])
        assertEquals(2, Engine.threads(2)); assertEquals(4, Engine.threads(8)); assertEquals(6, Engine.threads(16))
    }

    @Test fun everyModelIsAFixedFileWithAChecksum() {
        assertEquals(Engine.MODELS.size, Engine.MODELS.map { it.id }.toSet().size)
        for (m in Engine.MODELS) {
            assertTrue(m.id, m.url.startsWith("https://huggingface.co/") && m.url.endsWith(".gguf"))
            assertTrue(m.id, Regex("[0-9a-f]{64}").matches(m.sha256))
            assertTrue(m.id, m.bytes > 100_000_000L && m.sizeB > 0)
        }
        assertEquals("granite-4.2-8b", Engine.suggest(12_000_000_000L).id)
        assertEquals("qwen3.5-4b", Engine.suggest(8_000_000_000L).id)
        assertEquals("qwen3.5-2b", Engine.suggest(6_000_000_000L).id)
        assertEquals("qwen3.5-0.8b", Engine.suggest(2_000_000_000L).id)
    }
}
