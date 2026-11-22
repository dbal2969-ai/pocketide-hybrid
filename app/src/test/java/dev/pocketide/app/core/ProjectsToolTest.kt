package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 1.5.168: the chat with no project works on projects by name (the field request: files from one project, a new branch on another). */
class ProjectsToolTest {
    private fun setup(): File {
        val root = Files.createTempDirectory("pt").toFile()
        File(root, ".chat").mkdirs()
        val a = File(root, "2009scape-project/server/content/quest"); a.mkdirs()
        File(a, "CooksAssistant.kt").writeText("class CooksAssistant : Quest(\"Cook's Assistant\")\n")
        File(a, "icon.png").writeBytes(byteArrayOf(0, 1, 2, 3))
        File(root, "2009scape-project/server/node_modules/x").mkdirs(); File(root, "2009scape-project/server/node_modules/x/a.js").writeText("x")
        val b = File(root, "Empty-project-10/game"); b.mkdirs()
        File(b, "main.js").writeText("console.log('game')\n")
        return root
    }

    @Test fun findsProjectsLoosely() {
        val root = setup()
        try {
            val t = ProjectsTool(root, "", { true })
            assertEquals("Empty-project-10", t.find("empty project 10"))
            assertEquals("Empty-project-10", t.find("my Empty-project-10".removePrefix("my ")))
            assertEquals("2009scape-project", t.find("2009scape"))
            assertEquals(null, t.find("this"))
            val l = t.run(mapOf("action" to "list"))
            assertTrue(l, l.contains("2009scape-project") && l.contains("Empty-project-10") && !l.contains(".chat"))
        } finally { root.deleteRecursively() }
    }

    @Test fun readsSearchesAndListsAnotherProject() {
        val root = setup()
        try {
            val t = ProjectsTool(root, "", { false })
            assertTrue(t.run(mapOf("action" to "search", "project" to "2009scape", "pattern" to "class CooksAssistant")).contains("CooksAssistant.kt:1"))
            assertTrue(t.run(mapOf("action" to "read", "project" to "2009scape-project", "path" to "server/content/quest/CooksAssistant.kt")).contains("1: class CooksAssistant"))
            val f = t.run(mapOf("action" to "files", "project" to "2009scape-project", "path" to "server"))
            assertTrue(f, f.contains("CooksAssistant.kt") && !f.contains("node_modules"))
            assertTrue(t.run(mapOf("action" to "read", "project" to "2009scape-project", "path" to "../Empty-project-10/game/main.js")).startsWith("Refused"))
        } finally { root.deleteRecursively() }
    }

    @Test fun branchThenCopyThenCommitInAnotherProject() {
        val root = setup()
        try {
            val kept = ArrayList<String>()
            val t = ProjectsTool(root, "", { true }) { p, path, _ -> kept.add("$p:$path") }
            val b = t.run(mapOf("action" to "branch", "project" to "empty project 10", "name" to "2009scape quests"))
            assertTrue(b, b.contains("Made branch 2009scape-quests in Empty-project-10"))
            val c = t.run(mapOf("action" to "copy", "from" to "2009scape-project", "to" to "Empty-project-10", "paths" to "server/content/quest", "dest" to "ported"))
            assertTrue(c, c.contains("2 new files"))
            assertTrue(File(root, "Empty-project-10/ported/quest/CooksAssistant.kt").isFile)
            assertTrue(File(root, "Empty-project-10/ported/quest/icon.png").readBytes().contentEquals(byteArrayOf(0, 1, 2, 3)))
            assertEquals(listOf("Empty-project-10:ported/quest/CooksAssistant.kt", "Empty-project-10:ported/quest/icon.png"), kept.sorted())
            val m = t.run(mapOf("action" to "commit", "project" to "Empty-project-10", "message" to "Bring over quest files"))
            assertTrue(m, m.contains("on 2009scape-quests in Empty-project-10"))
            val v = Vcs(File(root, "Empty-project-10"))
            assertEquals("2009scape-quests", v.branch())
            assertTrue(v.branches().keys.containsAll(listOf("main", "2009scape-quests")))
            assertTrue(v.headFiles().containsKey("ported/quest/CooksAssistant.kt"))
            assertTrue(!v.filesAt(v.branches()["main"]).containsKey("ported/quest/CooksAssistant.kt"))
        } finally { root.deleteRecursively() }
    }

    @Test fun writesRefusedWhenNotAllowed() {
        val root = setup()
        try {
            val t = ProjectsTool(root, "Empty-project-10", { false })
            assertTrue(t.run(mapOf("action" to "copy", "from" to "2009scape-project", "to" to "this")).startsWith("Not allowed"))
            assertTrue(t.run(mapOf("action" to "branch", "project" to "this", "name" to "x")).startsWith("Not allowed"))
            assertTrue(!File(root, "Empty-project-10/server").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun anAmbiguousNameAsksAndPathsCannotLeaveTheProject() {
        val root = setup()
        try {
            File(root, "Empty-project-11").mkdirs()
            val t = ProjectsTool(root, "", { true })
            assertEquals(null, t.find("empty project"))
            val r = t.run(mapOf("action" to "files", "project" to "empty project"))
            assertTrue(r, r.startsWith("Several projects match") && r.contains("Empty-project-10") && r.contains("Empty-project-11"))
            assertTrue(t.run(mapOf("action" to "branch", "project" to "empty", "name" to "x")).startsWith("Several projects match"))
            // .. and absolute paths stay inside the project
            assertTrue(t.run(mapOf("action" to "read", "project" to "2009scape", "path" to "../Empty-project-10/game/main.js")).startsWith("Refused"))
            assertTrue(t.run(mapOf("action" to "read", "project" to "2009scape", "path" to "/etc/passwd")).startsWith("No file"))
            assertTrue(t.run(mapOf("action" to "copy", "from" to "2009scape", "to" to "Empty-project-10", "paths" to "server", "dest" to "../escaped")).startsWith("Refused"))
            assertTrue(t.run(mapOf("action" to "copy", "from" to "2009scape", "to" to "Empty-project-10", "paths" to "../Empty-project-10")).startsWith("No file or folder"))
            assertTrue(!File(root, "escaped").exists())
            val abs = t.run(mapOf("action" to "copy", "from" to "2009scape", "to" to "Empty-project-10", "paths" to "server/content/quest/icon.png", "dest" to "/tmp/pt-escape"))
            assertTrue(abs, !File("/tmp/pt-escape/icon.png").exists() && File(root, "Empty-project-10/tmp/pt-escape/icon.png").isFile)
        } finally { root.deleteRecursively() }
    }

    @Test fun chatHomeNoteAndDetection() {
        assertTrue(ChatHome.NOTE.contains("projects tool") && ChatHome.NOTE.contains("never say it is not possible"))
        assertTrue(ChatHome.namesProject("take files from 2009scape and make a branch on my empty-project-10", listOf("Empty-project-10", "web")))
        assertTrue(!ChatHome.namesProject("explain http caching", listOf("Empty-project-10", "web")))
    }
}
