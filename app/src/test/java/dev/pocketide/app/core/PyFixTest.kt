package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 1.5.201: the Python fix path's blocks, checked on the 13 real Python tasks with their reference fixes. */
class PyFixTest {
    private val root = File("../bench/hybrid/python")
    private val tasks by lazy { (MiniJson.parse(File(root, "python_tasks.json").readText()) as List<*>).map { it as Map<*, *> } }

    private fun python() = try { ProcessBuilder("python3", "-c", "import pytest").start().waitFor() == 0 } catch (e: Exception) { false }

    private fun run(dir: File, cmd: String): Pair<Int, String> {
        val p = ProcessBuilder("sh", "-c", cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        return p.waitFor() to out
    }

    private fun copy(id: String): File {
        val d = File.createTempFile("pyfix-$id", ""); d.delete()
        File(root, "tasks/$id").copyRecursively(d)
        return d
    }

    /**
     * Each reference fix, written as Nano would answer (the changed blocks, each in a ```python block), goes in through [PyFix.candidate]
     * and [PyFix.splice], and the task's visible test passes. Also prints whether the app's guesses name the right block.
     */
    @Test fun theReferenceFixesGoInBlockByBlockAndPass() {
        assumeTrue(python() && root.isDirectory)
        var passed = 0; var guessed = 0; var scored = 0; val report = ArrayList<String>()
        for (t in tasks) {
            val id = t["id"] as String; val fix = t["fix"] as String
            val cmd = (t["visible"] as String).replace("python3 -m", "python3 -B -m")
            val dir = copy(id)
            try {
                val before = File(dir, fix).readText()
                val after = File(root, "solutions/$id/$fix").readText()
                val (c0, failure) = run(dir, cmd)
                assertTrue("$id fails before the fix", c0 != 0)
                val old = PyFix.units(before); val new = PyFix.units(after)
                // paired by name and by which one of that name it is (a property's getter and setter share it)
                fun nth(us: List<PyFix.Unit>, u: PyFix.Unit) = us.filter { it.qual == u.qual && it.kind == u.kind }.indexOf(u)
                val changed = new.filter { n -> old.filter { it.qual == n.qual && it.kind == n.kind }.getOrNull(nth(new, n))?.let { o -> PyFix.code(before, o) != PyFix.code(after, n) } == true }
                // the most deeply nested changed blocks (a method, not its whole class)
                val inner = changed.filter { c -> changed.none { o -> o !== c && o.start >= c.start && o.end <= c.end } }
                var text = before
                for (n in inner) {
                    val u = PyFix.units(text).filter { it.qual == n.qual && it.kind == n.kind }.getOrNull(nth(new, n)) ?: error("$id: no ${n.qual}")
                    val block = PyFix.candidate("```python\n" + PyFix.code(after, n).trimStart('\n') + "\n```", text, u) ?: error("$id: no block for ${n.name}")
                    text = PyFix.splice(text, u, block)
                }
                File(dir, fix).writeText(text)
                val (c1, out1) = run(dir, cmd)
                if (c1 == 0) passed++
                val g = PyFix.guesses(fix, before, old, t["test_code"].toString() + "\n" + t["statement"], failure)
                if (inner.isNotEmpty()) { scored++; if (inner.any { c -> g.take(3).any { q -> q == c.qual || c.qual.startsWith("$q.") } }) guessed++ }
                report.add("$id: blocks ${inner.map { it.kind + " " + it.qual }}, test ${if (c1 == 0) "passes" else "FAILS " + out1.lines().lastOrNull { it.isNotBlank() }}; app guesses ${g.take(3)}")
            } finally { dir.deleteRecursively() }
        }
        println("reference fixes passing through PyFix: $passed of ${tasks.size}; app's guesses include the block: $guessed of $scored\n" + report.joinToString("\n"))
        assertEquals(report.joinToString("\n"), tasks.size, passed)
    }

    @Test fun blocksAreFoundWhole() {
        val text = """
            |import re
            |
            |PATTERN = (
            |    r"a"
            |    r"b"
            |)
            |
            |class Highlighter:
            |    highlights = [
            |        r"(?P<url>https?://[-0-9a-z]*)",
            |    ]
            |
            |    @property
            |    def name(
            |        self,
            |    ) -> str:
            |        x = 1
            |
            |        return "n"
            |
            |def helper():
            |    return 2
            |""".trimMargin()
        val us = PyFix.units(text)
        assertEquals(listOf("constant PATTERN", "class Highlighter", "constant highlights", "def name", "def helper"), us.map { it.kind + " " + it.name })
        val name = us.first { it.name == "name" }
        assertEquals("    @property\n    def name(\n        self,\n    ) -> str:\n        x = 1\n\n        return \"n\"", PyFix.code(text, name))
        assertEquals("    highlights = [\n        r\"(?P<url>https?://[-0-9a-z]*)\",\n    ]", PyFix.code(text, us.first { it.name == "highlights" }))
        // Nano's answer at column 0, with prose around it: put at the method's level, the decorator kept
        val reply = "Here is the fix:\n```python\ndef name(self) -> str:\n    return \"m\"\n```\nThis returns m."
        val block = PyFix.candidate(reply, text, name)!!
        assertEquals("    def name(self) -> str:\n        return \"m\"", block)
        val out = PyFix.splice(text, name, block)
        assertTrue(out, out.contains("    @property\n    def name(self) -> str:\n        return \"m\"\n\ndef helper():"))
        // the same block again, or no block for that name: nothing to try
        assertNull(PyFix.candidate("```python\ndef other():\n    pass\n```", text, name))
        assertNull(PyFix.candidate("```python\n" + PyFix.code(text, name) + "\n```", text, name))
    }

    @Test fun theRequestGivesTheFileAndTheTest() {
        val r = "Fix this issue in rich/_inspect.py. Then run `python3 -B -m pytest -q -p no:cacheprovider tests/test_inspect.py`; it must pass."
        assertEquals("rich/_inspect.py", PyFix.target(r, listOf("rich/_inspect.py", "tests/test_inspect.py")))
        assertNull(PyFix.target("fix tests/test_inspect.py", listOf("tests/test_inspect.py")))
        assertEquals("python3 -B -m pytest -q -p no:cacheprovider tests/test_inspect.py", PyFix.testCommand(r))
        assertNull(PyFix.testCommand("Fix it and run `npm test`"))
        val us = PyFix.units("def a():\n    pass\n\ndef b_c():\n    pass\n")
        assertEquals(listOf("b_c", "a"), PyFix.picked("The bug is in `b_c`, maybe a.", us))
    }
}
