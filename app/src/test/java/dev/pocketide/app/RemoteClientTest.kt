package dev.pocketide.app

import dev.pocketide.app.core.RemoteDev
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Against a real SSH server, only when POCKETIDE_SSH_TEST is set to "user@host:port" and POCKETIDE_SSH_KEYS to the server's
 * authorized_keys file (the test adds the phone's key to it). Skipped otherwise.
 */
class RemoteClientTest {
    @Test fun syncRunAndBringBack() {
        val addr = System.getenv("POCKETIDE_SSH_TEST").orEmpty(); val keys = System.getenv("POCKETIDE_SSH_KEYS").orEmpty()
        assumeTrue("set POCKETIDE_SSH_TEST and POCKETIDE_SSH_KEYS", addr.isNotEmpty() && keys.isNotEmpty())
        val home = Files.createTempDirectory("phone").toFile()
        val remoteDir = "/tmp/pocketide-remote-test-" + System.nanoTime()
        try {
            val client = RemoteClient(home)
            File(keys).appendText(client.publicKey() + "\n")
            val c = RemoteDev.parse(addr, remoteDir, "t")!!
            val (code0, out0) = client.exec(c, "echo hello", 20)
            assertEquals(out0, 0, code0); assertEquals("hello", out0.trim())
            val files = mapOf("package.json" to "{}", "src/a.js" to "console.log(1)", "node_modules/big.js" to "x").mapValues { it.value.toByteArray() }
            val r = client.run(c, "cat src/a.js; ls node_modules 2>&1 | head -1; mkdir -p out && echo built > out/b.txt; echo more >> src/a.js; exit 3", files, null, 30)
            assertEquals(r.output, 3, r.code)
            assertTrue(r.output, r.output.contains("console.log(1)") && r.output.contains("No such file"))
            assertEquals(setOf("out/b.txt", "src/a.js"), r.changed.keys)
            assertEquals("built\n", String(r.changed.getValue("out/b.txt")))
            // the second sync sends only what changed on the phone and removes what was deleted there
            val (m2, moved) = client.syncUp(c, mapOf("package.json" to "{}".toByteArray(), "src/a.js" to (String(r.changed.getValue("src/a.js"))).toByteArray(), "out/b.txt" to "built\n".toByteArray(), "c.js" to "2".toByteArray()), r.manifest)
            assertEquals(1, moved)
            assertTrue(m2.contains(" c.js"))
            val (_, ls) = client.exec(c, "cd $remoteDir && find . -type f -not -name .pocketide-mark | sort", 20)
            assertEquals("./c.js\n./out/b.txt\n./package.json\n./src/a.js", ls.trim())
            // a second client with the same phone home trusts the same server without asking again
            assertEquals(0, RemoteClient(home).exec(c, "true", 10).first)
        } finally {
            try { RemoteClient(home).exec(RemoteDev.parse(addr, "", "t")!!, "rm -rf $remoteDir", 20) } catch (e: Exception) { }
            home.deleteRecursively()
        }
    }
}
