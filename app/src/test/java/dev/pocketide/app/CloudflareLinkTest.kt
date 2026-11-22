package dev.pocketide.app

import dev.pocketide.app.core.Providers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareLinkTest {
    private val id = "0123456789abcdef0123456789ABCDEF"

    @Test fun buildsAddressFromId() {
        assertEquals("https://api.cloudflare.com/client/v4/accounts/$id/ai/v1", Providers.cloudflareBase("  $id \n"))
        assertNull(Providers.cloudflareBase("ACCOUNT_ID"))
        assertNull(Providers.cloudflareBase("https://dash.cloudflare.com/$id"))
        assertNull(Providers.cloudflareBase(""))
    }

    @Test fun readsIdBackFromSavedAddress() {
        assertEquals(id, Providers.cloudflareAccount(Providers.cloudflareBase(id)!!))
        assertEquals("", Providers.cloudflareAccount("https://api.cloudflare.com/client/v4/accounts/ACCOUNT_ID/ai/v1"))
    }

    @Test fun keepsTextModelsFromSearch() {
        val json = """{"result":[{"name":"@cf/meta/llama-3.3-70b-instruct-fp8-fast","task":{"name":"Text Generation"}},{"name":"@cf/baai/bge-base-en-v1.5","task":{"name":"Text Embeddings"}},{"name":"other"}],"success":true}"""
        assertEquals(listOf("@cf/meta/llama-3.3-70b-instruct-fp8-fast"), Providers.parseCloudflareModels(json))
    }

    @Test fun errorsNeverShowTheAccountId() {
        assertEquals("api.cloudflare.com", Providers.serverName(Providers.cloudflareBase(id)!!))
        assertEquals("127.0.0.1:11434", Providers.serverName("http://127.0.0.1:11434/v1"))
        assertEquals("gpu.example.com", Providers.serverName("https://user:secret@gpu.example.com/v1"))
        assertEquals("the server", Providers.serverName("not a url"))
        val cf = Providers.all.first { it.id == "cloudflare" }
        assertTrue(Providers.hosted(cf))
        assertEquals("Cloudflare Workers AI", Providers.shortName(cf))
        assertFalse(Providers.hosted(Providers.all.first { it.id == "ollama" }))
        assertFalse(Providers.hosted(Providers.all.first { it.id == "custom" }))
    }
}
