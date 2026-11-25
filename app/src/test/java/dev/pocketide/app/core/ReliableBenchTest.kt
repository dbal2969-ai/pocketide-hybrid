package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** 1.5.186: the on-device reliability benchmark (ReliableBench) and its task data in the assets. */
class ReliableBenchTest {
    private val assets = File("src/main/assets/bench/reliable")
    private val node by lazy { ReliableBench.nodeTasks(File(assets, "node.json").readText()) }
    private val python by lazy {
        ReliableBench.pythonTasks(File(assets, "python.json").readText()) { id ->
            ZipFile(File(assets, "python/$id.zip")).use { z -> z.entries().toList().filter { !it.isDirectory }.associate { it.name to z.getInputStream(it).readBytes().toString(Charsets.UTF_8) } }
        }
    }

    private fun facts(t: HybridBenchTasks.Task, files: Map<String, String> = t.files, exit: Int? = 0, out: String = "ok", answer: String = "",
                      hidden: Int? = null, transcript: String? = null) =
        HybridBenchTasks.Facts(files, t.files, exit, out, transcript ?: "----- round 1 · assistant said\n$answer\n⚡ Ran on this phone (Gemini Nano) · 2.0 s\n", "done",
            hiddenExit = hidden, hiddenOut = if (hidden == 0) "ok" else "hidden failed")

    @Test fun heldOutNodeTasksAreWhole() {
        assertEquals(24, node.size)
        assertEquals(node.size, node.map { it.id }.toSet().size)
        for (t in node) {
            assertTrue(t.id, t.privateMode && t.split == "held-out" && t.track == "Node" && t.files.isNotEmpty())
            if (t.command != null && t.solution.isNotEmpty()) assertTrue("${t.id} has a hidden check", t.hiddenCommand == "node hidden.js" && t.hidden.containsKey("hidden.js"))
        }
        assertTrue(node.none { it.id.startsWith("l") })   // nothing shared with the local suite (the dev split)
    }

    @Test fun judgesFixAndAnswerTasks() {
        val fix = node.first { it.id == "r05-fix-comparison" }
        assertNull(fix.judge(facts(fix, hidden = 0)))
        assertTrue(fix.judge(facts(fix, exit = 1, out = "largest gave 5"))!!.startsWith("node test.js failed"))
        assertTrue(fix.judge(facts(fix, hidden = 1))!!.startsWith("the hidden check failed"))
        val cloud = facts(fix, hidden = 0, transcript = "☁ Ran in the cloud (gemini-3.8-flash) · 2 s\n")
        assertTrue(fix.judge(cloud)!!.contains("Private"))
        val ans = node.first { it.id == "r01-find-constant" }
        assertNull(ans.judge(facts(ans, exit = null, out = "", answer = "The starting gold is set in economy.js.")))
        assertTrue(ans.judge(facts(ans, exit = null, out = "", answer = "It is in shop.js."))!!.startsWith("the answer is not right"))
        assertTrue(ans.judge(facts(ans, files = ans.files + ("x.js" to ""), exit = null, out = "", answer = "economy.js"))!!.startsWith("files were changed"))
        val two = node.first { it.id == "r02-explain-return" }
        assertNull(two.judge(facts(two, exit = null, out = "", answer = "fee(200) returns 5, because 2% of 200 is 4.")))
        assertTrue(two.judge(facts(two, exit = null, out = "", answer = "It returns 4.")) != null)
    }

    @Test fun pythonTasksAreLocatedAndWhole() {
        assertTrue(python.size >= 10)
        for (t in python) {
            assertTrue(t.id, t.track == "Python" && t.privateMode && t.solution.size == 1)
            val fix = t.solution.keys.single()
            assertTrue("${t.id}: the file to fix is in the project", t.files.containsKey(fix) && t.files.getValue(fix) != t.solution.getValue(fix))
            assertTrue(t.prompt, t.prompt.contains(fix) && t.prompt.contains("python3 -B -m pytest"))
            assertTrue(t.files.keys.any { it.startsWith("tests/") && it.endsWith("conftest.py") })
        }
        assertTrue(python.any { it.id.startsWith("requests") } && python.any { it.id.startsWith("rich") })
        val t = python.first()
        assertNull(t.judge(facts(t, exit = 0, out = "3 passed", hidden = 0)))
        assertTrue(t.judge(facts(t, exit = 1, out = "1 failed, 2 passed"))!!.startsWith("the tests fail"))
    }

    @Test fun repeatsAndReliabilitySummary() {
        val two = node.take(2)
        val rep = ReliableBench.repeated(two, 3)
        assertEquals(listOf("r01-find-constant#1", "r02-explain-return#1", "r01-find-constant#2", "r02-explain-return#2", "r01-find-constant#3", "r02-explain-return#3"), rep.map { it.id })
        assertEquals(two, ReliableBench.repeated(two, 1))
        val c = HybridBenchTasks.Counts(1, 0, emptyList())
        val results = rep.mapIndexed { i, t -> HybridBenchTasks.Result(t, !(t.id == "r02-explain-return#2"), if (i == 3) "the answer is not right" else "", c, 10, "done") }
        val s = ReliableBench.summary(results)
        assertTrue(s, s.contains("| All | 2 | 83% (5 of 6) | 1 of 2 |"))
        assertTrue(s, s.contains("r02-explain-return: 2 of 3"))
        assertTrue(ReliableBench.devTasks().all { it.split == "dev" && it.privateMode })
        assertEquals(20, ReliableBench.devTasks().size)
    }
}
