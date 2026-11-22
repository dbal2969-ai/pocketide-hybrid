package dev.pocketide.app

import dev.pocketide.app.core.Placeholders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.164: stand-ins instead of the real thing (the 2009scape field run). */
class PlaceholdersTest {
    private val setup = "#!/bin/sh\nif [ ! -d server/.git ]; then\n  echo \"== Downloading 2009scape\"\n" +
        "  GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 \"https://gitlab.com/2009scape/2009scape.git\" server\nfi\n"

    @Test fun aHandWrittenServerWhereTheScriptClonesIsFound() {
        val files = listOf("setup.sh", "server/Server/pom.xml", "server/Server/src/main/java/org/rs2server/Server.java", "README.md")
        val read = { p: String -> if (p == "setup.sh") setup else "public class Server {}" }
        assertEquals(listOf("server" to "setup.sh"), Placeholders.cloneTargets(files, read))
        val found = Placeholders.problems(files.drop(1), files, read) { false }
        assertTrue(found.toString(), found.single().startsWith("setup.sh downloads server/ with git clone, but server/ is not a clone: this step wrote 2 file(s)"))
        // once the clone is there, writing into it is ordinary work
        assertEquals(emptyList<String>(), Placeholders.problems(files.drop(1), files, read) { it == "server" })
    }

    @Test fun cloneWithoutAFolderNameUsesTheRepositoryName() {
        val s = "git clone https://github.com/example/tool.git\n"
        assertEquals(listOf("tool" to "get.sh"), Placeholders.cloneTargets(listOf("get.sh")) { s })
    }

    @Test fun standInMarkersAreFoundInCommentsAndThrowsOnly() {
        val code = "// Placeholder server: the real one comes later\nfunction load() {\n  throw new Error('Import is not written yet');\n}\n" +
            "<input placeholder=\"Search\">\nconst stubbornness = 3;\n"
        val m = Placeholders.markers("a.js", code)
        assertEquals(listOf("a.js:1: // Placeholder server: the real one comes later", "a.js:3: throw new Error('Import is not written yet');"), m)
    }

    // Copilot's PR #27, T1.4 (N3, N4)
    @Test fun testsMocksAndDocsMayTalkAboutStubs() {
        val stub = "// stub of the payment client for tests\nfun pay() { throw NotImplementedError() }\n"
        for (p in listOf("test/PayStub.kt", "src/__mocks__/pay.js", "tests/fake.py", "pay.spec.ts", "docs/stubs.md", "NOTES.md"))
            assertEquals(p, emptyList<String>(), Placeholders.markers(p, stub))
        assertEquals(2, Placeholders.markers("src/pay.kt", stub).size)
    }

    @Test fun clonesInScriptsFoldersAndMakefilesCount() {
        assertEquals(listOf("server" to "scripts/setup.sh"), Placeholders.cloneTargets(listOf("scripts/setup.sh")) { setup })
        assertEquals(listOf("lib" to "Makefile"), Placeholders.cloneTargets(listOf("Makefile")) { "deps:\n\tgit clone https://example.com/lib.git lib\n" })
        assertEquals(emptyList<Pair<String, String>>(), Placeholders.cloneTargets(listOf("src/deep/x.sh")) { setup })
    }
}
