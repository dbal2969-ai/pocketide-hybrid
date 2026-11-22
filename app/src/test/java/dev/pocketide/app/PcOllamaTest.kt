package dev.pocketide.app

import dev.pocketide.app.core.ModelFacts
import dev.pocketide.app.core.PcOllama
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.180: Ollama on the person's computer over Wi-Fi. */
class PcOllamaTest {
    @Test fun whatThePersonTypesBecomesTheAddress() {
        assertEquals("http://192.168.1.20:11434/v1", PcOllama.baseFor("192.168.1.20"))
        assertEquals("http://192.168.1.20:11434/v1", PcOllama.baseFor(" 192.168.1.20:11434 "))
        assertEquals("http://192.168.1.20:8080/v1", PcOllama.baseFor("http://192.168.1.20:8080/"))
        assertEquals("http://192.168.1.20:11434/v1", PcOllama.baseFor("http://192.168.1.20:11434/v1"))
        assertEquals("http://100.64.0.5:11434/v1", PcOllama.baseFor("100.64.0.5"))          // Tailscale
        assertEquals("http://desktop.local:11434/v1", PcOllama.baseFor("desktop.local"))
        assertEquals("https://gpu.example.com:443/v1", PcOllama.baseFor("https://gpu.example.com:443"))
        for (bad in listOf("", "  ", "192.168.1", "300.1.1.1", "192.168.1.20:99999", "my pc", "http://x/y/z", ".local", "192.168.1.20:abc"))
            assertNull(bad, PcOllama.baseFor(bad))
    }

    @Test fun aComputerIsNotThisPhone() {
        assertTrue(PcOllama.isRemote("http://192.168.1.20:11434/v1"))
        assertTrue(PcOllama.isRemote("http://100.64.0.5:11434/v1"))
        assertFalse(PcOllama.isRemote("http://127.0.0.1:11434/v1"))
        assertFalse(PcOllama.isRemote("http://localhost:11434/v1"))
        assertFalse(PcOllama.isRemote(""))
    }

    /** The phone's Ollama is set up with 8K, the computer's with 32K; neither lists its window, so the app sizes requests to these. */
    @Test fun theWindowFollowsWhereOllamaRuns() {
        assertEquals(8192, ModelFacts.context("ollama", "pc-test-unlisted", "http://127.0.0.1:11434/v1"))
        assertEquals(8192, ModelFacts.context("ollama", "pc-test-unlisted"))
        assertEquals(PcOllama.CONTEXT, ModelFacts.context("ollama", "pc-test-unlisted", "http://192.168.1.20:11434/v1"))
        assertEquals(PcOllama.CONTEXT, ModelFacts.context("custom", "pc-test-unlisted", "http://192.168.1.20:11434/v1"))
        assertNull(ModelFacts.context("custom", "pc-test-unlisted", "https://api.example.com/v1"))
    }

    @Test fun everyGuideSetsTheSameSettings() {
        assertEquals(setOf("Windows", "Mac", "Linux"), PcOllama.GUIDES.map { it.system }.toSet())
        for (g in PcOllama.GUIDES) for ((k, v) in PcOllama.SETTINGS)
            assertTrue("${g.system} $k", g.commands.any { it.contains(k) && it.contains(v) })
        assertEquals("0.0.0.0:11434", PcOllama.SETTINGS["OLLAMA_HOST"])     // Ollama answers only the computer itself by default
        assertTrue(PcOllama.whyNoAnswer("http://192.168.1.20:11434/v1").contains("OLLAMA_HOST"))
    }
}
