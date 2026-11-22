package dev.pocketide.app

import dev.pocketide.app.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CollabTest {
    private fun tmp(): File = java.nio.file.Files.createTempDirectory("collab").toFile()
    private fun write(d: File, p: String, t: String) { File(d, p).also { it.parentFile.mkdirs() }.writeText(t) }

    @Test fun forkSyncAndPullRequestFromFork() {
        val up = tmp(); write(up, "a.txt", "1\n2\n3\n"); val vu = Vcs(up); vu.start()
        // fork = a copy of the folder, history included, that remembers its upstream
        val fork = tmp(); up.copyRecursively(fork, true); val vf = Vcs(fork); vf.setUpstream(up)
        assertEquals(up.absolutePath, vf.upstream()!!.absolutePath)
        // upstream moves on; the fork syncs
        write(up, "b.txt", "b\n"); vu.commit("add b", null)
        vf.fetchFrom(vf.upstream()!!, "upstream")
        assertTrue(vf.merge("refs/remotes/upstream/main").fastForward)
        assertEquals("b\n", File(fork, "b.txt").readText())
        // the fork works on a branch and asks upstream to pull it
        vf.createBranch("idea"); vf.switchTo("idea"); write(fork, "a.txt", "1\nTWO\n3\n"); vf.commit("two", null)
        val c = Collab(tmp())
        val p = c.openPull("Make two loud", "Fixes #5", fork.name, "idea", "main", false)
        assertEquals("refs/remotes/${Collab.remoteName(fork.name)}/idea", p.sourceRef())
        vu.fetchFrom(fork, Collab.remoteName(fork.name))
        assertEquals(listOf("two"), vu.ahead("main", p.sourceRef()).map { it.title })
        assertEquals(listOf("a.txt"), vu.compareBranches("main", p.sourceRef()).map { it.path })
        assertTrue(vu.clashes("main", p.sourceRef()).isEmpty())
        val r = VcsFlow.mergePull(vu, c, p, squash = false, forkRoot = fork)
        assertTrue(r.message, r.conflicts.isEmpty())
        assertEquals("1\nTWO\n3\n", File(up, "a.txt").readText())
        assertEquals(Collab.MERGED, c.pull(p.number)!!.state)
        assertEquals(listOf(5), p.closes)
    }

    @Test fun squashClashesAndBlockers() {
        val d = tmp(); write(d, "a.txt", "1\n2\n3\n"); val v = Vcs(d); v.start()
        v.createBranch("f"); v.switchTo("f")
        write(d, "a.txt", "1\nf\n3\n"); v.commit("f1", null); write(d, "c.txt", "c\n"); v.commit("f2", null)
        v.switchTo("main"); write(d, "a.txt", "1\nm\n3\n"); v.commit("m1", null)
        assertEquals(listOf("a.txt"), v.clashes("main", "f"))
        val c = Collab(tmp()); c.rules.protect.add("main"); c.rules.requireApproval = true
        val p = c.openPull("F", "", "", "f", "main", true)
        val why = c.blockers(p, null, v.clashes("main", "f"))
        assertEquals(4, why.size)   // draft, clash, no checks, no approval
        p.state = Collab.OPEN; p.reviews.add(Collab.Review("you", "approved", "", 1))
        c.setChecks("x", listOf(Collab.Check("Tests", true, "", 1)))
        assertEquals(listOf("Files changed on both sides"), c.blockers(p, c.checks["x"], listOf("a.txt")).map { it.substringBefore(":") })
        // fix on the branch, then squash
        v.switchTo("f"); write(d, "a.txt", "1\nm\n3\n"); v.commit("match main", null); v.switchTo("main")
        assertTrue(v.clashes("main", "f").isEmpty())
        assertTrue(c.blockers(p, c.checks["x"], emptyList()).isEmpty())
        val before = v.log().size
        val r = VcsFlow.mergePull(v, c, p, squash = true, forkRoot = null)
        assertTrue(r.message, r.conflicts.isEmpty())
        assertEquals(before + 1, v.log().size)   // one squashed commit, the branch's commits are not in main's history
        assertEquals(1, v.store.readCommit(v.store.headId()!!)!!.parents.size)
        assertEquals("c\n", File(d, "c.txt").readText())
        assertFalse("merged branch is deleted", "f" in v.branches())
    }

    @Test fun assistantWorksOnItsOwnBranchWhenMainIsProtected() {
        val d = tmp(); write(d, "a.txt", "1\n"); val v = Vcs(d); v.start()
        val c = Collab(tmp()); c.rules.protect.add("main")
        write(d, "a.txt", "2\n")
        val r = VcsFlow.assistantCommit(v, c, "Change a to 2", "Done.", listOf("a.txt"), 1_700_000_000_000)
        assertTrue(r.branch!!.startsWith("assistant/")); assertNotNull(r.pull); assertEquals(r.branch, v.branch())
        val p = c.pull(r.pull!!)!!
        assertEquals("main", p.into); assertEquals("assistant", p.by)
        // the next turn updates the same pull request
        write(d, "a.txt", "3\n")
        val r2 = VcsFlow.assistantCommit(v, c, "Now 3", "Done.", listOf("a.txt"))
        assertEquals(r.branch, r2.branch); assertEquals(r.pull, r2.pull); assertEquals(1, c.pulls.size)
        // main untouched until merged
        assertEquals("1\n", String(v.contentAt(v.store.resolve("main")!!, "a.txt")!!))
        // unprotected: straight onto the branch
        val c2 = Collab(tmp()); v.switchTo("main"); write(d, "b.txt", "b\n")
        assertEquals("main", VcsFlow.assistantCommit(v, c2, "b", "", listOf("b.txt")).branch)
    }

    @Test fun recordsSaveAndLoad() {
        val dir = tmp()
        val c = Collab(dir)
        val i = c.openIssue("Map is cut off", "It stops at the river", listOf("bug"))
        val p = c.openPull("Fix map", "Closes #${i.number}", "", "map", "main", false)
        p.comments.add(Collab.Comment("assistant", "Use this", 5, "a.js", 3, "let x = 1"))
        c.rules.protect.add("main"); c.setChecks("abc", listOf(Collab.Check("No secrets", true, "", 9)))
        c.releases.add(Collab.Release("v1", "First", "notes", "abc", 10, mutableListOf("source.zip"))); c.save()
        val back = Collab(dir)
        assertEquals(2, back.nextNumber() - 1)
        assertEquals(i.number, back.pull(p.number)!!.closes.single())
        assertEquals(p.number, back.issue(i.number)!!.pull)
        assertEquals("let x = 1", back.pull(p.number)!!.comments[0].suggestion)
        assertEquals(setOf("main"), back.rules.protect); assertEquals(true, back.checks["abc"]!![0].ok)
        assertEquals("In progress", back.column(back.issue(i.number)!!))
        back.merged(back.pull(p.number)!!, "def")
        assertEquals("Done", Collab(dir).column(Collab(dir).issue(i.number)!!))
        assertEquals(listOf(3, 4), Collab.refs("see #3 and #4, not &#5"))
        assertEquals(listOf(7), Collab.closesIn("This fixes #7."))
    }

    @Test fun suggestionsAndReleaseNotes() {
        val s = Suggestions.parse("CHANGES\n- a.js line 2 is wrong\nSUGGEST a.js:2-3\n```js\nconst b = 2;\n```\n")
        assertEquals(1, s.size); assertEquals("a.js", s[0].path); assertEquals(2, s[0].first); assertEquals(3, s[0].last)
        assertEquals("one\nconst b = 2;\nfour", Suggestions.apply("one\ntwo\nthree\nfour", s[0]))
        assertNull(Suggestions.apply("one", s[0]))
        val d = tmp(); write(d, "a.txt", "1\n"); val v = Vcs(d); v.start()
        write(d, "a.txt", "2\n"); val c2 = v.commit("second", null)!!
        val c = Collab(tmp())
        c.releases.add(Collab.Release("v1", "v1", "", v.log().last().id, 1))
        val notes = VcsFlow.releaseNotes(v, c, c2)
        assertTrue(notes, notes.contains("- second") && !notes.contains("Start of history"))
        val out = java.io.ByteArrayOutputStream(); VcsFlow.zipCommit(v, c2, out, "proj-v2")
        val names = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(out.toByteArray())).let { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(setOf("proj-v2/a.txt", "proj-v2/.gitignore"), names.toSet())
    }
}

class IssuesAndMarkdownTest {
    private fun tmp(): File = java.nio.file.Files.createTempDirectory("issues").toFile()

    @Test fun assignedIssueOpensAPullRequestThatClosesIt() {
        val d = tmp(); File(d, "a.txt").writeText("1\n"); val v = Vcs(d); v.start()
        val c = Collab(tmp()); c.rules.protect.add("main"); c.rules.assistantBranches = true
        val i = c.openIssue("Map is cut off!", "It stops at the river", listOf("bug"))
        val branch = c.issueBranch(i)
        assertEquals("issue-1-map-is-cut-off", branch)
        // what "Assign to the assistant" sets up
        v.createBranch(branch); v.switchTo(branch); i.assistant = true; i.branch = branch; i.base = "main"; c.save()
        assertFalse(VcsFlow.ownBranch(c, branch))     // the issue branch is the assistant's own branch already
        File(d, "a.txt").writeText("2\n")
        val r = VcsFlow.assistantCommit(v, c, "Work on issue #1", "Extended the map.", listOf("a.txt"))
        assertEquals(branch, r.branch); assertNotNull(r.pull)
        val p = c.pull(r.pull!!)!!
        assertEquals("main", p.into); assertEquals(listOf(1), p.closes); assertEquals(r.pull, c.issue(1)!!.pull)
        assertEquals("In progress", c.column(c.issue(1)!!))
        // a second turn keeps the same pull request
        File(d, "a.txt").writeText("3\n")
        assertEquals(r.pull, VcsFlow.assistantCommit(v, c, "more", "", listOf("a.txt")).pull); assertEquals(1, c.pulls.size)
        // merging it closes the issue
        val m = VcsFlow.mergePull(v, c, p, false, null)
        assertTrue(m.message, m.conflicts.isEmpty())
        assertEquals(Collab.CLOSED, c.issue(1)!!.state); assertEquals("Done", c.column(c.issue(1)!!))
        assertEquals("3\n", File(d, "a.txt").readText()); assertEquals("main", v.branch())
    }

    @Test fun taskListsAndDiagrams() {
        val md = "# Plan\n- [ ] map\n- [x] npcs\n* plain\n```mermaid\ngraph TD\nA-->B\n```\n```js\nx()\n```\n"
        val b = Markdown.blocks(md)
        val tasks = b.filter { it.kind == MdKind.TASK }
        assertEquals(listOf("map" to false, "npcs" to true), tasks.map { it.text to it.checked })
        assertEquals(listOf(1, 2), tasks.map { it.line })
        assertEquals("graph TD\nA-->B", b.single { it.kind == MdKind.DIAGRAM }.text)
        assertEquals("js", b.single { it.kind == MdKind.CODE }.lang)
        assertEquals("plain", b.single { it.kind == MdKind.BULLET }.text)
        val ticked = Markdown.toggleTask(md, 1, true)
        assertTrue(ticked.contains("- [x] map")); assertEquals(md, Markdown.toggleTask(ticked, 1, false))
        assertEquals(md, Markdown.toggleTask(md, 0, true))   // not a task line
    }
}
