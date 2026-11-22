package dev.pocketide.app

import dev.pocketide.app.core.RunTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 1.5.164 and Copilot's PR #27 (T1.1, N5): which folders count as downloaded code that the automatic check leaves out. */
class RunTargetsOwnFilesTest {
    @Test fun nestedClonesAreLeftOutAndTheRestKept() {
        val files = listOf("a.py", "server/x.py", "server/deep/y.py", "vendor2/z.py", ".github/w.py", "lib/server/q.py")
        val own = RunTargets.ownFiles(files) { it == "server" || it == "lib/server" }
        assertEquals(listOf("a.py", "vendor2/z.py", ".github/w.py"), own)
        // the project itself being a clone (its .git at the root) is not a nested repository: nothing is left out
        assertEquals(files, RunTargets.ownFiles(files) { false })
    }

    @Test fun aSubmoduleThePersonListsKeepsItsChecks() {
        val root = Files.createTempDirectory("own").toFile()
        try {
            File(root, "server/.git").mkdirs(); File(root, "mod/.git").also { it.parentFile.mkdirs() }.writeText("gitdir: ../.git/modules/mod\n")
            File(root, ".gitmodules").writeText("[submodule \"mod\"]\n\tpath = mod\n\turl = https://example.com/mod.git\n")
            assertTrue(RunTargets.downloadedRepo(root, "server"))
            assertFalse(RunTargets.downloadedRepo(root, "mod"))
            assertFalse(RunTargets.downloadedRepo(root, "plain"))
        } finally { root.deleteRecursively() }
    }
}
