package dev.pocketide.app

import dev.pocketide.app.core.Lsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Talks to the real language servers (the fake one in LspTest accepted empty capabilities, so it never showed that
 * typescript-language-server sends no errors without publishDiagnostics). Runs when POCKETIDE_TSLS / POCKETIDE_PYRIGHT name
 * the server programs; skipped otherwise.
 */
class LspServerTest {
    private fun server(env: String, vararg args: String): Pair<File, Process>? {
        val bin = System.getenv(env)?.takeIf { File(it).canExecute() } ?: return null
        val dir = Files.createTempDirectory("lsp").toFile()
        return dir to ProcessBuilder(listOf(bin) + args).directory(dir).redirectError(File(dir, "err.log")).start()
    }

    private fun <T> withServer(env: String, files: Map<String, String>, vararg args: String, body: (File, Process) -> T): T? {
        val (dir, proc) = server(env, *args) ?: return null
        files.forEach { (p, t) -> File(dir, p).apply { parentFile.mkdirs() }.writeText(t) }
        try { return body(dir, proc) } finally { proc.destroy(); dir.deleteRecursively() }
    }

    @Test fun typescriptServerReportsASyntaxError() {
        assumeTrue(System.getenv("POCKETIDE_TSLS") != null)
        val text = "const a = 1;\nlet b = ;\n"
        val lines = withServer("POCKETIDE_TSLS", mapOf("game/main.js" to text), "--stdio") { dir, p ->
            Lsp.diagnose(p.inputStream, p.outputStream, dir.absolutePath, "game/main.js", text, 30_000)
        }
        assertNotNull("the server answered", lines)
        assertTrue(lines.toString(), lines!!.any { it.startsWith("line 2:") })
    }

    @Test fun typescriptServerAnswersACleanFileWithNoErrors() {
        assumeTrue(System.getenv("POCKETIDE_TSLS") != null)
        val text = "function add(a, b) { return a + b; }\nconsole.log(add(1, 2));\n"
        val lines = withServer("POCKETIDE_TSLS", mapOf("ok.js" to text), "--stdio") { dir, p ->
            Lsp.diagnose(p.inputStream, p.outputStream, dir.absolutePath, "ok.js", text, 30_000)
        }
        assertEquals(emptyList<String>(), lines)
    }

    @Test fun typescriptServerFindsADefinition() {
        assumeTrue(System.getenv("POCKETIDE_TSLS") != null)
        val main = "function drawMinimap(ctx) {\n  return ctx;\n}\ndrawMinimap(null);\n"
        val locs = withServer("POCKETIDE_TSLS", mapOf("main.js" to main), "--stdio") { dir, p ->
            // the call on line 4 (0-based 3), column 0
            Lsp.locations(p.inputStream, p.outputStream, dir.absolutePath, "main.js", main, "definition", 3, 0, 30_000)
        }
        assertNotNull(locs)
        assertTrue(locs.toString(), locs!!.contains(Lsp.Location("main.js", 1)))
    }

    @Test fun pyrightReportsAnErrorAfterAskingForItsSettings() {
        assumeTrue(System.getenv("POCKETIDE_PYRIGHT") != null)
        val text = "x: int = \"text\"\n"
        val lines = withServer("POCKETIDE_PYRIGHT", mapOf("a.py" to text), "--stdio") { dir, p ->
            Lsp.diagnose(p.inputStream, p.outputStream, dir.absolutePath, "a.py", text, 60_000)
        }
        assertNotNull("the server answered", lines)
        assertTrue(lines.toString(), lines!!.any { it.startsWith("line 1:") })
    }

    @Test fun serverRequestsGetAnAnswer() {
        val cfg = Lsp.serverReply(mapOf("id" to 7, "method" to "workspace/configuration", "params" to mapOf("items" to listOf(mapOf("section" to "python"), mapOf("section" to "python.analysis")))))
        assertEquals(7, cfg["id"])
        assertEquals(listOf(null, null), cfg["result"])
        val other = Lsp.serverReply(mapOf("id" to "x", "method" to "client/registerCapability"))
        assertTrue(other.containsKey("result"))
        assertNull(other["result"])
    }

    @Test fun capabilitiesDeclareErrorReports() {
        val td = Lsp.CLIENT_CAPABILITIES["textDocument"] as Map<*, *>
        assertTrue(td.containsKey("publishDiagnostics"))
        assertTrue(td.containsKey("definition"))
        assertTrue(td.containsKey("references"))
    }
}
