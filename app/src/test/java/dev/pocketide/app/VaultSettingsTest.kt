package dev.pocketide.app

import android.content.Context
import dev.pocketide.app.core.SecretMask
import dev.pocketide.app.core.Vault
import dev.pocketide.app.core.VaultTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 1.5.133: keys stored as plain text by older versions are sealed when the app starts, and still read the same. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VaultSettingsTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    @After fun clear() { Vault.cipher = null; SecretMask.known = emptyList() }

    @Test fun plainKeysAreSealedOnStartAndReadTheSame() {
        val sp = ctx.getSharedPreferences("pocketide", Context.MODE_PRIVATE)
        sp.edit().putString("key.openai", "sk-plain-from-before-123").putString("model.openai", "gpt-x").commit()
        Vault.cipher = VaultTest.FakeCipher()
        val s = AppSettings(ctx)
        assertTrue(Vault.isSealed(sp.getString("key.openai", "")!!))
        assertEquals("gpt-x", sp.getString("model.openai", ""))
        assertEquals("sk-plain-from-before-123", s.key("openai"))
        assertTrue("sk-plain-from-before-123" in SecretMask.known)
        s.setKey("anthropic", "sk-ant-new-key-456789")
        assertTrue(Vault.isSealed(sp.getString("key.anthropic", "")!!))
        assertEquals("sk-ant-new-key-456789", s.key("anthropic"))
    }
}
