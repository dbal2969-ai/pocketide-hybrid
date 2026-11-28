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

    /**
     * 1.5.202: Gemini Nano's 24 rewrites from Python run 1126-1923 (1.5.201, 0 passed), each tried as the app now tries it: copying
     * slips put back, then all real changes, then each alone. Prints how many tasks a variant passes.
     */
    @Test fun nanosRealRewritesFromRun1923() {
        assumeTrue(python() && root.isDirectory)
        val recs = File("src/test/resources/nano/pyfix/run-1126-1923.jsonl").readLines().filter { it.isNotBlank() }.map { MiniJson.obj(MiniJson.parse(it))!! }
        val passedTasks = HashSet<String>(); var tests = 0; var raw = 0; val report = ArrayList<String>()
        for (r in recs) {
            val id = r["task"] as String
            val t = tasks.first { it["id"] == id }
            val fix = t["fix"] as String; val cmd = (t["visible"] as String).replace("python3 -m", "python3 -B -m")
            val dir = copy(id)
            try {
                val orig = File(dir, fix).readText()
                val u = PyFix.unitNamed(PyFix.units(orig), r["unit"] as String) ?: continue
                val block = PyFix.candidate(r["reply"] as String, orig, u) ?: continue
                File(dir, fix).writeText(PyFix.splice(orig, u, block)); if (run(dir, cmd).first == 0) raw++
                val vs = PyFix.variants(PyFix.replaced(orig, u, block), block, u.kind)
                val ok = vs.map { v -> tests++; File(dir, fix).writeText(PyFix.splice(orig, u, v)); run(dir, cmd).first == 0 }
                if (ok.any { it }) passedTasks.add(id)
                report.add("$id ${u.qual} T=${r["temperature"]}: ${vs.size} variants, passing ${ok.count { it }}")
            } finally { dir.deleteRecursively() }
        }
        println("run 1126-1923 rewrites: as sent ${raw} of ${recs.size} pass; with variants ($tests tests) tasks passed: $passedTasks\n" + report.joinToString("\n"))
        assertEquals("as sent, none passed on the phone either", 0, raw)
    }

    @Test fun copyingSlipsArePutBack() {
        val old = "    def f(self, obj):\n        \"\"\"The name.\"\"\"\n        x = dir(obj)  # the keys\n        return getattr(obj, \"__qualname__\", None)"
        val block = "    def f(self, obj):\n        \"\"\"the name.\"\"\"\n        x = Dir(obj)  # keys\n        return str(getattr(obj, \"__qualname__\", None))"
        val vs = PyFix.variants(old, block, "def")
        assertEquals(listOf("    def f(self, obj):\n        \"\"\"The name.\"\"\"\n        x = dir(obj)  # the keys\n        return str(getattr(obj, \"__qualname__\", None))"), vs)
        // nothing real changed: nothing to test
        assertTrue(PyFix.variants(old, old.replace("The name", "the name"), "def").isEmpty())
        // two real changes: both, then each alone
        val two = PyFix.variants("a = 1\nb = 2\nc = 3", "a = 10\nb = 2\nc = 30", "def")
        assertEquals(listOf("a = 10\nb = 2\nc = 30", "a = 10\nb = 2\nc = 3", "a = 1\nb = 2\nc = 30"), two)
        // a template string's text is code for a constant (rich_3043's HTML)
        assertEquals(1, PyFix.variants("T = \"\"\"\n<b>x</b>\n\"\"\"", "T = \"\"\"\n<i>x</i>\n\"\"\"", "constant").size)
    }

    /** 1.5.206 (run 1126-2203, rich_3518): Nano's pick of a long class becomes the member that shares most with the test. */
    @Test fun aLongClassIsNarrowedToItsMember() {
        val body = (1..70).joinToString("\n") { "    def m$it(self):\n        return $it" }
        val text = "class Table:\n    highlight = False\n\n$body\n\n    def add_row(self, row):\n        self.rows.append(row)\n"
        val us = PyFix.units(text)
        assertEquals(listOf("Table.add_row"), PyFix.narrow(text, us, listOf("Table"), "add_row should keep highlight: rows.append(row) loses it"))
        assertEquals(listOf("Table.m3"), PyFix.narrow(text, us, listOf("Table.m3"), "anything"))
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

    /** 1.5.218 (run 1127-2155): a block left early gives its unused tries to the next one, up to the first block's number. */
    @Test fun unusedTriesGoToTheNextBlock() {
        assertEquals(6, PyFix.samples(0, 6))
        assertEquals(3, PyFix.samples(1, 6))
        assertEquals(6, PyFix.samples(1, 6, spare = 4))   // rich_3518: a block left after two identical rewrites
        assertEquals(5, PyFix.samples(2, 6, spare = 2))
        assertEquals(1, PyFix.samples(1, 1))
        assertEquals(3, PyFix.samples(1, 6, spare = -2))
    }

    /** 1.5.220 (experiment): the functions the failing test runs come first. As measured on rich_3718 and rich_3006. */
    @Test fun blocksTheFailingTestNeverRunsAreTriedLast() {
        val src = "class Panel:\n" +
            "    def __init__(self):\n        self.a = 1\n" +                        // lines 2-3
            "    @classmethod\n    def fit(cls):\n        return cls()\n" +          // lines 4-6
            "    def render(self):\n        def align(t):\n            return t\n        return align(self.a)\n"   // lines 7-10
        val us = PyFix.units(src)
        // the failing test ran __init__ (as did 5 passing tests) and render with its inner align (3 passing tests); never fit
        val ran = listOf(PyFix.Ran(2, 1, 5), PyFix.Ran(7, 1, 3), PyFix.Ran(8, 1, 3))
        val order = PyFix.withCoverage(listOf("Panel.fit", "Panel.render.align"), us, ran)
        assertEquals("Panel.render.align", order.first())
        assertEquals("Panel.fit", order.last())
        assertTrue(order.toString(), order.indexOf("Panel.render") < order.indexOf("Panel.__init__"))
        // nothing known about the failing test: the order stays
        assertEquals(listOf("Panel.fit"), PyFix.withCoverage(listOf("Panel.fit"), us, emptyList()))
        assertEquals(listOf("Panel.fit"), PyFix.withCoverage(listOf("Panel.fit"), us, listOf(PyFix.Ran(2, 0, 9))))
        // a class in the list is not a function and keeps its place
        assertEquals("Panel", PyFix.withCoverage(listOf("Panel", "Panel.fit"), us, ran).first())
    }

    @Test fun theTestCommandBecomesACoverageRun() {
        val c = PyFix.covCommand("python3 -B -m pytest -q -p no:cacheprovider tests/test_panel.py", "rich/panel.py")!!
        assertTrue(c, c.startsWith("python3 -B -c 'import json, os, sys, threading\n"))
        assertTrue(c, c.endsWith("' 'rich/panel.py' -q -p no:cacheprovider tests/test_panel.py"))
        assertEquals(2, c.count { it == '\'' } - 2)   // the program itself has no single quote: only the two pairs around it and the path
        assertNull(PyFix.covCommand("node test.js", "a.py"))
        assertNull(PyFix.covCommand("python3 -m pytest tests/a.py && echo done", "a.py"))
        val out = "F.                 [100%]\n1 failed, 1 passed in 0.2s\n\n" + PyFix.COV_MARK + "[[2, 1, 5], [7, 1, 3]]" + PyFix.COV_MARK + "\n"
        assertEquals(listOf(PyFix.Ran(2, 1, 5), PyFix.Ran(7, 1, 3)), PyFix.ran(out))
        assertEquals(emptyList<PyFix.Ran>(), PyFix.ran("1 failed in 0.2s"))
        assertEquals(emptyList<PyFix.Ran>(), PyFix.ran(PyFix.COV_MARK + "[]" + PyFix.COV_MARK))
    }

    /** The program is valid Python and reports what the tests ran (python3 with pytest, when installed). */
    @Test fun theCoverageProgramRuns() {
        val ok = try { ProcessBuilder("python3", "-c", "import pytest").start().waitFor() == 0 } catch (e: Exception) { false }
        assumeTrue(ok)
        val dir = File.createTempFile("cov", ""); dir.delete(); dir.mkdirs()
        try {
            File(dir, "m.py").writeText("def good(x):\n    return x + 1\n\ndef bad(x):\n    return x - 1\n\ndef unused(x):\n    return x\n")
            File(dir, "test_m.py").writeText("from m import good, bad\n\ndef test_good():\n    assert good(1) == 2\n\ndef test_bad():\n    assert good(1) == 2 and bad(1) == 2\n")
            val cmd = PyFix.covCommand("python3 -B -m pytest -q -p no:cacheprovider test_m.py", "m.py")!!
            val p = ProcessBuilder("sh", "-c", cmd).directory(dir).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals(out, 1, p.waitFor())
            assertEquals(out, listOf(PyFix.Ran(1, 1, 1), PyFix.Ran(4, 1, 0)), PyFix.ran(out))
            val us = PyFix.units(File(dir, "m.py").readText())
            // the app's own choice that the failing test ran stays first; then the rest by coverage; the one never run last
            assertEquals(listOf("good", "bad", "unused"), PyFix.withCoverage(listOf("unused", "good"), us, PyFix.ran(out)))
        } finally { dir.deleteRecursively() }
    }
}
