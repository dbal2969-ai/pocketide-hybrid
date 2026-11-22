package dev.pocketide.app

import dev.pocketide.app.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The history store and its operations, checked against the real git program (skipped where git is not installed). */
class VcsTest {
    private fun tmp(): File = java.nio.file.Files.createTempDirectory("vcs").toFile()

    private fun hasGit(): Boolean = try { ProcessBuilder("git", "--version").start().waitFor() == 0 } catch (e: Exception) { false }

    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=T", "-c", "user.email=t@t", "-c", "init.defaultBranch=main", "-c", "core.autocrlf=false") + args)
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals("git ${args.joinToString(" ")}: $out", 0, p.waitFor())
        return out
    }

    private fun write(dir: File, path: String, text: String) { File(dir, path).also { it.parentFile.mkdirs() }.writeText(text) }

    @Test fun historyIsRealGit() {
        assumeTrue(hasGit())
        val d = tmp()
        write(d, "index.html", "<h1>Hi</h1>\n"); write(d, "game/main.js", "let a = 1;\n"); write(d, "node_modules/x/y.js", "junk")
        val v = Vcs(d)
        val first = v.start()!!
        assertTrue(File(d, ".gitignore").readText().contains("node_modules/"))
        assertEquals(first, git(d, "rev-parse", "HEAD").trim())
        git(d, "fsck", "--strict")
        assertEquals("", git(d, "status", "--porcelain").trim())          // the index matches: git sees a clean folder
        assertFalse(git(d, "ls-files").contains("node_modules"))
        write(d, "game/main.js", "let a = 2;\n"); write(d, "notes.txt", "x\n")
        assertEquals(listOf("M game/main.js", "A notes.txt"), v.status().map { it.toString() })
        val second = v.commit("Change a", listOf("game/main.js"))!!
        assertEquals(listOf("A notes.txt"), v.status().map { it.toString() })
        assertEquals("game/main.js", git(d, "show", "--name-only", "--format=", second).trim())
        assertEquals(" M game/main.js".length, 15)
        assertEquals("?? notes.txt", git(d, "status", "--porcelain").trim())
        assertNull(v.commit("nothing", listOf("missing.txt")))
        assertEquals(listOf("Change a", "Start of history"), v.log().map { it.title })
        val ch = v.changes(second)
        assertEquals(1, ch.size); assertEquals(1, ch[0].added); assertEquals(1, ch[0].removed)
    }

    @Test fun readsWhatGitWroteIncludingPacks() {
        assumeTrue(hasGit())
        val d = tmp()
        git(d, "init", "-q")
        for (i in 1..5) { write(d, "a.txt", (1..200).joinToString("\n") { "line $it" } + "\nchange $i\n"); write(d, "dir/b$i.txt", "b$i\n"); git(d, "add", "-A"); git(d, "commit", "-q", "-m", "c$i") }
        git(d, "gc", "-q", "--aggressive")
        assertTrue(File(d, ".git/objects/pack").list()!!.any { it.endsWith(".pack") })
        val v = Vcs(d)
        assertTrue(v.enabled())
        assertEquals(listOf("c5", "c4", "c3", "c2", "c1"), v.log().map { it.title })
        assertEquals(6, v.headFiles().size)
        assertTrue(String(v.contentAt(v.store.headId()!!, "a.txt")!!).endsWith("change 5\n"))
        assertEquals(git(d, "rev-parse", "HEAD~2").trim(), v.store.resolve(v.log()[2].id))
        assertTrue(v.status().isEmpty())                 // git's own index is read too
        write(d, "a.txt", "new\n")
        val id = v.commit("Mine", null)!!
        assertEquals(id, git(d, "rev-parse", "HEAD").trim())
        git(d, "fsck", "--strict")
    }

    @Test fun branchesSwitchMergeAndConflicts() {
        assumeTrue(hasGit())
        val d = tmp()
        write(d, "app.js", "one\ntwo\nthree\nfour\nfive\n")
        val v = Vcs(d); v.start()
        v.createBranch("feature")
        assertEquals("Switched to feature.", v.switchTo("feature"))
        write(d, "app.js", "one\nTWO\nthree\nfour\nfive\n"); write(d, "new.js", "n\n")
        v.commit("feature work", null)
        v.switchTo("main")
        assertFalse(File(d, "new.js").exists())
        assertEquals("one\ntwo\nthree\nfour\nfive\n", File(d, "app.js").readText())
        write(d, "app.js", "one\ntwo\nthree\nfour\nFIVE\n"); v.commit("main work", null)
        val m = v.merge("feature")
        assertTrue(m.conflicts.isEmpty()); assertNotNull(m.commit)
        assertEquals("one\nTWO\nthree\nfour\nFIVE\n", File(d, "app.js").readText())
        assertTrue(File(d, "new.js").exists())
        assertEquals(2, v.store.readCommit(m.commit!!)!!.parents.size)
        git(d, "fsck", "--strict"); assertEquals("", git(d, "status", "--porcelain").trim())
        // a conflict
        v.switchTo("feature"); write(d, "app.js", "one\nfeature\nthree\nfour\nfive\n"); v.commit("f2", null)
        v.switchTo("main"); write(d, "app.js", "one\nmain\nthree\nfour\nFIVE\n"); v.commit("m2", null)
        val c = v.merge("feature")
        assertEquals(listOf("app.js"), c.conflicts); assertTrue(v.merging())
        assertEquals(listOf("app.js"), v.unresolved())
        assertTrue(File(d, "app.js").readText().contains("<<<<<<< main\nmain\n=======\nfeature\n>>>>>>> feature"))
        write(d, "app.js", "one\nboth\nthree\nfour\nFIVE\n")
        assertTrue(v.unresolved().isEmpty())
        val done = v.commit(v.mergeMessage(), null)!!
        assertEquals(2, v.store.readCommit(done)!!.parents.size); assertFalse(v.merging())
        // fast-forward and up to date
        v.createBranch("ff"); v.switchTo("ff"); write(d, "z.txt", "z\n"); v.commit("z", null); v.switchTo("main")
        assertTrue(v.merge("ff").fastForward); assertTrue(v.merge("ff").upToDate)
        git(d, "fsck", "--strict")
        assertEquals(git(d, "rev-parse", "main").trim(), v.store.headId())
    }

    @Test fun carryStashCherryPickRevert() {
        val d = tmp()
        write(d, "a.txt", "1\n2\n3\n"); write(d, "b.txt", "b\n")
        val v = Vcs(d); v.start(); v.createBranch("other")
        write(d, "b.txt", "b changed\n")
        assertTrue(v.switchTo("other").contains("came along"))
        assertEquals("b changed\n", File(d, "b.txt").readText())
        v.switchTo("main")
        val s = v.stash("wip")!!
        assertEquals("b\n", File(d, "b.txt").readText()); assertTrue(v.status().isEmpty())
        assertEquals(1, v.stashes().size)
        write(d, "a.txt", "1\n2\n3\n4\n"); val c4 = v.commit("add 4", null)!!
        v.unstash(s.id)
        assertEquals("b changed\n", File(d, "b.txt").readText()); assertTrue(v.stashes().isEmpty())
        v.commit("b", null)
        // revert keeps later work
        write(d, "a.txt", "0\n1\n2\n3\n4\n"); v.commit("add 0", null)
        val r = v.revert(c4)
        assertTrue(r.message, r.conflicts.isEmpty())
        assertEquals("0\n1\n2\n3\n", File(d, "a.txt").readText())
        // cherry-pick from another branch
        v.switchTo("other"); write(d, "c.txt", "c\n"); val cc = v.commit("add c", null)!!
        v.switchTo("main"); val p = v.cherryPick(cc)
        assertTrue(p.conflicts.isEmpty()); assertEquals("c\n", File(d, "c.txt").readText())
        assertTrue(v.log()[0].message.contains("cherry picked from commit $cc"))
        // discard on switch
        write(d, "c.txt", "edited\n"); write(d, "tmp.txt", "t\n")
        v.switchTo("other", Vcs.Carry.DISCARD)
        assertEquals("c\n", File(d, "c.txt").readText()); assertFalse(File(d, "tmp.txt").exists())
        assertTrue(v.status().isEmpty())
    }

    @Test fun largeFilesTagsBlameRestore() {
        assumeTrue(hasGit())
        val d = tmp()
        write(d, ".gitattributes", "*.bin filter=lfs diff=lfs merge=lfs -text\n")
        File(d, "data.bin").writeBytes(ByteArray(5000) { (it % 251).toByte() })
        write(d, "a.txt", "one\ntwo\n")
        val v = Vcs(d); v.start()
        val ptr = git(d, "show", "HEAD:data.bin")
        assertTrue(ptr, ptr.startsWith("version https://git-lfs.github.com/spec/v1"))
        assertEquals(5000, v.contentAt(v.store.headId()!!, "data.bin")!!.size)
        File(d, "data.bin").delete(); v.restoreFile("data.bin", "HEAD".let { v.store.headId()!! })
        assertEquals(5000L, File(d, "data.bin").length())
        write(d, "a.txt", "one\nTWO\nthree\n"); val c2 = v.commit("second", null)!!
        val blame = v.blame("a.txt")
        assertEquals(listOf("Start of history", "second", "second"), blame.map { it.second!!.title })
        assertEquals(c2, v.fileHistory("a.txt")[0].id); assertEquals(2, v.fileHistory("a.txt").size)
        v.tag("v1"); v.tag("v1.1", message = "First release")
        assertEquals(setOf("v1", "v1.1"), git(d, "tag", "-l").lines().filter { it.isNotBlank() }.toSet())
        assertEquals("tag", git(d, "cat-file", "-t", "v1.1").trim())
        assertEquals(c2, v.store.readCommit(v.tags()["v1.1"]!!)!!.id)
        v.restoreAll(v.log().last().id)
        assertEquals("one\ntwo\n", File(d, "a.txt").readText()); assertEquals(listOf("M a.txt"), v.status().map { it.toString() })
        git(d, "fsck", "--strict")
    }

    @Test fun ignoreRules() {
        val d = tmp()
        write(d, ".gitignore", "*.log\n/secret/\nbuild-*/\n!keep.log\ndocs/**/draft.md\n")
        val ig = VcsWork.Ignore(d)
        assertTrue(ig.ignored("x.log", false)); assertTrue(ig.ignored("deep/y.log", false)); assertFalse(ig.ignored("keep.log", false))
        assertTrue(ig.ignored("secret", true)); assertFalse(ig.ignored("a/secret", true))
        assertTrue(ig.ignored("build-1", true)); assertTrue(ig.ignored("node_modules", true)); assertTrue(ig.ignored("a/node_modules", true))
        assertTrue(ig.ignored("docs/a/b/draft.md", false)); assertFalse(ig.ignored("draft.md", false))
    }

    @Test fun diffAndMerge3() {
        val r = java.util.Random(7)
        repeat(60) {
            val a = List(r.nextInt(40)) { "l" + r.nextInt(8) }; val b = List(r.nextInt(40)) { "l" + r.nextInt(8) }
            val m = VcsText.matches(a, b)
            for (k in 1 until m.size) assertTrue(m[k].first > m[k - 1].first && m[k].second > m[k - 1].second)
            for ((i, j) in m) assertEquals(a[i], b[j])
            assertEquals(lcs(a, b), m.size)
            val rebuilt = VcsText.diff(a, b).filter { it.tag != '-' }.map { it.line }
            assertEquals(b, rebuilt)
        }
        val base = "a\nb\nc\nd\ne\n"
        assertEquals("A\nb\nc\nd\nE\n", VcsText.merge3(base, "A\nb\nc\nd\ne\n", "a\nb\nc\nd\nE\n").text)
        val c = VcsText.merge3(base, "a\nX\nc\nd\ne\n", "a\nY\nc\nd\ne\n", "mine", "theirs")
        assertEquals(1, c.conflicts); assertEquals("a\n<<<<<<< mine\nX\n=======\nY\n>>>>>>> theirs\nc\nd\ne\n", c.text)
        assertEquals(0, VcsText.merge3(base, "a\nX\nc\nd\ne\n", "a\nX\nc\nd\ne\n").conflicts)
        // a long file with one change: fast
        val big = (1..20000).joinToString("\n") { "line $it" }
        val t0 = System.currentTimeMillis()
        assertEquals(19999, VcsText.matches(VcsText.lines(big), VcsText.lines(big.replace("line 777\n", "LINE 777\n"))).size)
        // a smoke check that a big diff is not quadratic, not a benchmark: 3 s failed now and then on busy machines (analysis T2)
        assertTrue(System.currentTimeMillis() - t0 < 30_000)
    }

    private fun lcs(a: List<String>, b: List<String>): Int {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        return dp[0][0]
    }
}

class VcsAssistTest {
    @Test fun assistantToolAndMessages() {
        val d = java.nio.file.Files.createTempDirectory("vcsa").toFile()
        val v = Vcs(d)
        assertTrue(VcsAssist.run(v, "status", "", "").contains("does not keep version history"))
        File(d, "a.js").writeText("one\ntwo\n"); v.start()
        File(d, "a.js").writeText("one\nTWO\n")
        assertTrue(VcsAssist.run(v, "status", "", "").contains("M a.js"))
        assertTrue(VcsAssist.run(v, "diff", "a.js", "").contains("+ TWO"))
        val id = v.commit(VcsAssist.commitMessage("Make two loud\nplease", "I changed two to TWO."), null, Vcs.ASSISTANT)!!
        assertEquals("Assistant: Make two loud", v.store.readCommit(id)!!.title)
        assertTrue(VcsAssist.run(v, "log", "", "").lines().first().contains("(assistant)"))
        assertTrue(VcsAssist.run(v, "show", "", id.take(7)).contains("- two"))
        assertTrue(VcsAssist.run(v, "blame", "a.js", "").contains("${id.take(7)} TWO"))
        assertEquals("* main", VcsAssist.run(v, "branches", "", ""))
        assertTrue(VcsAssist.commitMessage("x".repeat(100), "").length < 80)
    }

    @Test fun lineLinks() {
        assertEquals("a/b.js:3-5", LineLinks.link("a/b.js", 3, 5)); assertEquals("a.js:7", LineLinks.link("a.js", 7, 7))
        assertEquals(listOf(Triple("game/main.js", 2, 3), Triple("index.html", 4, 4)), LineLinks.find("look at game/main.js:2-3 and index.html:4, not v1.2:3x"))
        val e = LineLinks.expand("see game/main.js:2-3") { if (it == "game/main.js") "a\nb\nc\nd" else null }
        assertTrue(e, e.contains("2: b\n3: c"))
        assertTrue(LineLinks.ask("x.js", 1, 2, "code").startsWith("About x.js:1-2:"))
    }

    @Test fun resolveConflictSides() {
        val t = "a\n<<<<<<< mine\nX\n=======\nY\n>>>>>>> theirs\nb\n"
        assertEquals("a\nX\nb\n", VcsText.resolve(t, true)); assertEquals("a\nY\nb\n", VcsText.resolve(t, false))
    }
}
