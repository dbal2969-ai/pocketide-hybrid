package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 1.5.141, Cursor parity P3: the embeddings index behind find_code's search by meaning. */
class SemanticIndexTest {
    /** A stand-in model: words (with a few synonyms folded together) hashed into a small vector, so "meaning" is testable offline. */
    private val SYN = mapOf("hp" to "health", "damage" to "health", "hurt" to "health", "lose" to "health", "pay" to "money", "coins" to "money", "gold" to "money")
    private var calls = 0
    private var texts = 0
    private val fake: (List<String>) -> List<FloatArray> = { t ->
        calls++; texts += t.size
        t.map { s ->
            val v = FloatArray(64)
            for (w in Regex("[a-z]+").findAll(s.lowercase())) { val k = SYN[w.value] ?: w.value; v[(k.hashCode() and 0x7fffffff) % 64] += 1f }
            v
        }
    }

    private val files = mapOf(
        "src/combat.js" to "function hit(player, amount) {\n  player.hp -= amount;\n  if (player.hp <= 0) die(player);\n}\n",
        "src/shop.js" to "function buy(player, item) {\n  if (player.coins < item.price) return false;\n  player.coins -= item.price;\n  return true;\n}\n",
        "src/ui.js" to "function draw(ctx) {\n  ctx.fillRect(0, 0, 10, 10);\n}\n",
        "node_modules/x/index.js" to "module.exports = 1;\n",
    )

    @Test fun findsCodeByMeaningAndSkipsVendorFiles() {
        val idx = SemanticIndex()
        val n = idx.update(files, "test:model", fake)
        assertEquals(3, n)   // one chunk per small file; node_modules is skipped
        val hits = idx.query(fake(listOf("where does the player lose health"))[0], files)
        assertEquals("src/combat.js", hits.first().path)
        assertTrue(hits.first().lines.joinToString { it.second }, hits.first().lines.any { it.second.contains("hp") })
        assertEquals("src/shop.js", idx.query(fake(listOf("spend gold"))[0], files).first().path)
    }

    @Test fun onlyChangedFilesAreEmbeddedAgain() {
        val idx = SemanticIndex()
        idx.update(files, "test:model", fake)
        texts = 0
        assertEquals(0, idx.update(files, "test:model", fake))
        val changed = files + ("src/ui.js" to "function draw(ctx) {\n  ctx.clearRect(0, 0, 10, 10);\n}\n")
        assertEquals(1, idx.update(changed, "test:model", fake))
        assertEquals(1, texts)
        // a deleted file leaves the index; another model starts it again
        assertEquals(0, idx.update(changed - "src/shop.js", "test:model", fake))
        assertEquals(2, idx.size)
        assertEquals(2, idx.update(changed - "src/shop.js", "test:other", fake))
    }

    @Test fun savesAndLoads() {
        val idx = SemanticIndex()
        idx.update(files, "test:model", fake)
        val f = File.createTempFile("semantic", ".bin"); f.deleteOnExit()
        idx.save(f)
        val back = SemanticIndex.load(f)
        assertEquals(idx.size, back.size)
        assertEquals("test:model", back.model)
        assertEquals(0, back.update(files, "test:model", fake))
        assertEquals("src/combat.js", back.query(fake(listOf("health"))[0], files).first().path)
        f.writeText("not an index")
        assertEquals(0, SemanticIndex.load(f).size)
    }

    @Test fun fusionPutsWhatBothFoundFirst() {
        fun h(p: String, s: Int) = RankedSearch.Hit(p, s, s + 39, 1.0, emptyList())
        val words = listOf(h("a.js", 1), h("b.js", 1), h("c.js", 1))
        val meaning = listOf(h("c.js", 1), h("d.js", 21), h("a.js", 21))
        val fused = SemanticIndex.fuse(words, meaning, 4)
        assertEquals(listOf("a.js", "c.js"), fused.take(2).map { it.path })   // both found a.js (overlapping) and c.js
        assertEquals(4, fused.size)
    }

    @Test fun capsTheIndex() {
        val big = (1..600).associate { "f$it.js" to (1..200).joinToString("\n") { i -> "line $i of $it" } }
        val idx = SemanticIndex()
        idx.update(big, "m", fake)
        assertTrue(idx.size.toString(), idx.size in (SemanticIndex.MAX_CHUNKS - 9)..SemanticIndex.MAX_CHUNKS)   // whole files only (9 chunks each)
        // files past the cap are not marked indexed: with room again they are embedded
        val room = big.filterKeys { it.removePrefix("f").removeSuffix(".js").toInt() <= 100 }   // 500 files removed
        idx.update(room, "m", fake)
        val freed = idx.size
        assertTrue(idx.update(room + mapOf("zz.js" to "one\ntwo"), "m", fake) == 1)
        assertEquals(freed + 1, idx.size)
    }

    @Test fun embeddingsAddresses() {
        assertEquals("https://api.openai.com/v1/embeddings", EmbedClient.url(Providers.byId("openai"), ""))
        assertEquals("http://127.0.0.1:11434/v1/embeddings", EmbedClient.url(Providers.byId("ollama"), "http://127.0.0.1:11434/v1"))
        assertNull(EmbedClient.url(Providers.byId("anthropic"), ""))
        assertNull(EmbedClient.url(Providers.byId("cloudflare"), "https://api.cloudflare.com/client/v4/accounts/ACCOUNT_ID/ai/v1"))
        assertEquals("https://api.cloudflare.com/client/v4/accounts/abc/ai/v1/embeddings", EmbedClient.url(Providers.byId("cloudflare"), "https://api.cloudflare.com/client/v4/accounts/abc/ai/v1"))
        assertEquals(Workflow.Slot("cloudflare", "@cf/baai/bge-base-en-v1.5"), EmbedClient.slot(" cloudflare:@cf/baai/bge-base-en-v1.5 "))
        assertNull(EmbedClient.slot("bge"))
    }

    @Test fun callsAnOpenAiStyleEndpoint() {
        val ss = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val seen = java.util.concurrent.LinkedBlockingQueue<String>()
        val t = Thread {
            repeat(2) {
                ss.accept().use { sock ->
                    val inp = sock.getInputStream().bufferedReader()
                    val head = generateSequence { inp.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    val len = head.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    val body = CharArray(len).also { var r = 0; while (r < len) { val n = inp.read(it, r, len - r); if (n < 0) break; r += n } }
                    seen.add(head.joinToString("\n") + "\n\n" + String(body))
                    val bad = head[0].contains("/bad/")
                    // answered out of order, as some servers do: the index field decides
                    val out = if (bad) """{"error":{"message":"no such model"}}""" else """{"data":[{"index":1,"embedding":[0,1]},{"index":0,"embedding":[1,0]}],"model":"m"}"""
                    val status = if (bad) "404 Not Found" else "200 OK"
                    sock.getOutputStream().write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${out.length}\r\nConnection: close\r\n\r\n$out".toByteArray())
                }
            }
        }.also { it.isDaemon = true; it.start() }
        try {
            val port = ss.localPort
            val v = EmbedClient.embed("http://127.0.0.1:$port/v1/embeddings", "k1", "m", listOf("a", "b"))
            val req = seen.take()
            assertTrue(req, req.startsWith("POST /v1/embeddings") && req.contains("Authorization: Bearer k1") && req.contains("\"input\":[\"a\",\"b\"]"))
            assertEquals(listOf(1f, 0f), v[0].toList())
            assertEquals(listOf(0f, 1f), v[1].toList())
            val err = try { EmbedClient.embed("http://127.0.0.1:$port/bad/embeddings", "", "m", listOf("a")); "" } catch (e: Exception) { e.message.orEmpty() }
            assertTrue(err, err.contains("HTTP 404") && err.contains("no such model"))
        } finally { ss.close(); t.join(2000) }
    }

    @Test fun noModelMeansKeywordOnly() {
        assertFalse(EmbedClient.slot("").let { it != null })
    }

    // 1.5.146 (Copilot, ANALYSIS-1.5.145 N1): secret files never reach the embeddings provider, and keys in other files are masked
    @Test fun secretsNeverLeaveThePhone() {
        for (p in listOf(".env", "app/.env.local", "certs/server.pem", "keys/deploy.key", "release.keystore", "home/.ssh/config", "id_ed25519",
                "config/secrets.yml", "credentials.json", "local.properties", "github.properties", ".npmrc", "infra/terraform.tfstate", "secrets"))
            assertTrue(p, SemanticIndex.secretFile(p))
        for (p in listOf("src/tokenizer.js", "src/auth/passwordReset.ts", "src/keyboard.js", "README.md", "src/env.js", "app/build.gradle.kts"))
            assertFalse(p, SemanticIndex.secretFile(p))
        val sent = ArrayList<String>()
        val spy: (List<String>) -> List<FloatArray> = { t -> sent.addAll(t); fake(t) }
        val token = "ghp_" + "a".repeat(36)
        val files = mapOf(
            ".env" to "OPENAI_API_KEY=sk-proj-" + "b".repeat(40) + "\n",
            "src/client.js" to "const auth = 'Bearer abcdefghijklmnopqrstuvwxyz123456';\nconst gh = '$token';\nfetch(url, { headers: { auth } });\n")
        SemanticIndex().update(files, "m", spy)
        val all = sent.joinToString("\n")
        assertFalse(all, all.contains("OPENAI_API_KEY") || all.contains("bbbbbbbb"))
        assertFalse(all, all.contains(token) || all.contains("abcdefghijklmnopqrstuvwxyz123456"))
        assertTrue(all, all.contains("src/client.js") && all.contains(SecretMask.HIDDEN))
    }

    // 1.5.151 (Copilot, ANALYSIS-1.5.150 P1): the names it listed, a plain password in a config file, and a quoted one in code
    @Test fun moreSecretFilesAndPlainPasswords() {
        for (p in listOf("android/key.properties", "keystore.properties", "serviceAccountKey.json", "firebase-adminsdk-abc12.json", "prod.tfvars",
                "AuthKey_ABC.p8", ".git-credentials", "kubeconfig", "vault.kdbx", "office.ovpn", "wp-config.php", "group_vars/vault.yml",
                ".vault_pass", "application-prod.properties", "app.env", "prod.env", ".kube/config", "google-services.json"))
            assertTrue(p, SemanticIndex.secretFile(p))
        for (p in listOf("src/tokenizer.js", "src/environment.js", "data/items.json", "README.md"))
            assertFalse(p, SemanticIndex.secretFile(p))
        assertTrue(SemanticIndex.secretContent("config/app.yml", "db:\n  host: x\n  password: hunter2\n"))
        assertTrue(SemanticIndex.secretContent("settings.json", "{\"apiKey\": \"abc123\"}"))
        assertFalse(SemanticIndex.secretContent("config/app.yml", "db:\n  password: ${'$'}{DB_PASSWORD}\n"))
        assertFalse(SemanticIndex.secretContent("data/items.json", "[{\"name\": \"rope\"}]"))
        assertFalse(SemanticIndex.secretContent("src/login.js", "const password = form.password.value;"))   // code: masked, not skipped
        val code = SemanticIndex.masked("const DB_PASSWORD = \"hunter2\";\nconst token = getToken();\nlogin(user, password);")
        assertFalse(code, code.contains("hunter2"))
        assertTrue(code, code.contains("const DB_PASSWORD = \"${SecretMask.HIDDEN}\";") && code.contains("const token = getToken();"))
    }
}
