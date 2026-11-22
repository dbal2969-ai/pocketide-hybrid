package dev.pocketide.app.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.133 (analysis S6): keys sealed at rest; plain ones still readable; unopenable ones count as missing; records masked. */
class VaultTest {
    /** Stands in for the Android Keystore in tests: reversible, and can be told to fail. */
    class FakeCipher : Vault.Cipher {
        var broken = false
        override fun seal(plain: String) = if (broken) throw IllegalStateException("no keystore") else plain.reversed()
        override fun open(sealed: String) = if (broken) throw IllegalStateException("key lost") else sealed.reversed()
    }

    @After fun clear() { Vault.cipher = null; SecretMask.known = emptyList() }

    @Test fun sealedValuesOpenAgain() {
        Vault.cipher = FakeCipher()
        val s = Vault.seal("sk-secret-1234")
        assertTrue(Vault.isSealed(s)); assertFalse(s.contains("sk-secret"))
        assertEquals("sk-secret-1234", Vault.open(s))
        assertEquals("", Vault.seal(""))
        assertEquals(s, Vault.seal(s))
    }

    @Test fun plainValuesFromOlderVersionsStillWork() {
        Vault.cipher = FakeCipher()
        assertEquals("old-plain-key", Vault.open("old-plain-key"))
    }

    @Test fun aValueThatCannotBeOpenedIsMissingNotACrash() {
        val c = FakeCipher(); Vault.cipher = c
        val s = Vault.seal("ghp_abc")
        c.broken = true
        assertEquals("", Vault.open(s))
        Vault.cipher = null
        assertEquals("", Vault.open(s))
    }

    @Test fun withoutAKeystoreValuesAreKeptRatherThanLost() {
        val c = FakeCipher().also { it.broken = true }; Vault.cipher = c
        assertEquals("my-key", Vault.seal("my-key"))
        Vault.cipher = null
        assertEquals("my-key", Vault.seal("my-key"))
    }

    @Test fun whichSettingsAreSecrets() {
        assertTrue(Vault.isSecretKey("key.openai")); assertTrue(Vault.isSecretKey("remote.pw.demo")); assertTrue(Vault.isSecretKey("web.brave"))
        assertFalse(Vault.isSecretKey("model.openai")); assertFalse(Vault.isSecretKey("provider"))
    }

    @Test fun recordsHideKnownKeysAndTokenShapes() {
        val text = "export KEY=myownkey-123456\ncurl -H 'Authorization: Bearer abcdefghijklmnopqrstuvwxyz012345' x\n" +
            "token ghp_" + "a".repeat(36) + " and github_pat_" + "b".repeat(40) + " and sk-proj-" + "c".repeat(30) + "\nnormal words stay"
        val m = SecretMask.mask(text, listOf("myownkey-123456"))
        assertFalse(m.contains("myownkey-123456")); assertFalse(m.contains("abcdefghijklmnop")); assertFalse(m.contains("ghp_aaaa"))
        assertFalse(m.contains("github_pat_bbbb")); assertFalse(m.contains("sk-proj-cccc"))
        assertTrue(m.contains("Bearer [hidden]")); assertTrue(m.contains("normal words stay"))
    }

    @Test fun moreTokenShapes() {
        val text = listOf("gsk_" + "a".repeat(30), "sk_live_" + "b".repeat(24), "hf_" + "c".repeat(30), "AKIAABCDEFGHIJKLMNOP",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIabc\n-----END RSA PRIVATE KEY-----", "CLOUDFLARE_API_TOKEN=abcdef0123456789abcdef0123456789")
        for (t in text) assertFalse(t, SecretMask.mask("x " + t + " y", emptyList()).contains(t.takeLast(12)))
    }

    @Test fun codeThatNamesAKeyIsNotHidden() {
        val code = "const apiKey = process.env.API_KEY\nlet token = getToken()\npassword: hidden_in_vault"
        assertEquals(code, SecretMask.mask(code, emptyList()))
    }

    // 1.5.135 (Copilot, ANALYSIS-1.5.134)
    @Test fun evenMoreShapes() {
        val hidden = listOf("glpat-" + "a1".repeat(12), "npm_" + "b2".repeat(18), "pypi-" + "c3".repeat(25), "xapp-1-" + "d4".repeat(15),
            "dop_v1_" + "e".repeat(64), "shpat_" + "f".repeat(32), "SG." + "g".repeat(22) + "." + "h".repeat(43), "ya29." + "i5".repeat(15))
        for (t in hidden) assertFalse(t, SecretMask.mask("x " + t + " y", emptyList()).contains(t.takeLast(10)))
        assertEquals("git clone https://me:[hidden]@github.com/a/b", SecretMask.mask("git clone https://me:s3cretpass@github.com/a/b", emptyList()))
        assertEquals("GET /v1?api_key=[hidden]&q=1", SecretMask.mask("GET /v1?api_key=abcd1234efgh&q=1", emptyList()))
        assertEquals("DB_PASSWORD=[hidden]", SecretMask.mask("DB_PASSWORD=hunter2hunter", emptyList()))
        assertEquals("AccountKey=[hidden];", SecretMask.mask("AccountKey=abcdefghijklmnop1234==;", emptyList()))
    }

    @Test fun savedChatsStayValidJson() {
        val json = MiniJson.write(linkedMapOf("id" to "c1", "updated" to 1731400000000L, "entries" to listOf(
            linkedMapOf("text" to "-----BEGIN RSA PRIVATE KEY-----\nMIIabc\n-----END RSA PRIVATE KEY-----", "n" to 2),
            linkedMapOf("text" to "token ghp_" + "a".repeat(30) + " \"quoted\""))))
        val out = SecretMask.maskJson(json, emptyList())
        val back = MiniJson.obj(MiniJson.parse(out))!!
        val entries = MiniJson.list(back["entries"])!!.map { MiniJson.obj(it)!! }
        assertEquals("[hidden]", entries[0]["text"])
        assertEquals("token [hidden] \"quoted\"", entries[1]["text"])
        assertEquals("c1", back["id"])
        assertEquals("1731400000000", MiniJson.write(back["updated"]))
        assertEquals("not json [hidden]", SecretMask.maskJson("not json ghp_" + "b".repeat(30), emptyList()))
    }

    @Test fun shortValuesAreNotMaskedEverywhere() = assertEquals("a b c", SecretMask.mask("a b c", listOf("b")))
}
