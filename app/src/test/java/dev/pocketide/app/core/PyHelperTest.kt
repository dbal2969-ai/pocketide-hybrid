package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 1.5.194: the Python helper for Gemini Nano, tested on the refused Python edits of reliability run 1126-0816. */
class PyHelperTest {
    private val tasks = File("../bench/hybrid/python/tasks")
    private val cases by lazy {
        File("src/test/resources/nano/py_edits/refused-1126-0816.jsonl").readLines().filter { it.isNotBlank() }.map { MiniJson.obj(MiniJson.parse(it))!! }
    }

    private fun parses(text: String): Boolean {
        val f = File.createTempFile("pyhelper", ".py"); f.writeText(text)
        return try {
            val p = ProcessBuilder("python3", "-c", "import ast,sys; ast.parse(open(sys.argv[1]).read())", f.absolutePath).redirectErrorStream(true).start()
            p.inputStream.readBytes(); p.waitFor() == 0
        } finally { f.delete() }
    }

    private fun python() = try { ProcessBuilder("python3", "--version").start().waitFor() == 0 } catch (e: Exception) { false }

    @Test fun theRunsRefusedEditsParseMoreOftenReindented() {
        assumeTrue(python() && tasks.isDirectory)
        var before = 0; var after = 0; var ind = 0; var indFixed = 0
        for (c in cases) {
            val base = File(tasks, "${c["task"]}/${c["path"]}").readText()
            val s = c["search"] as String; val r = c["replace"] as String
            val plain = EditBlocks.apply(base, EditBlock(c["path"] as String, s, r)).text
            val fixed = EditBlocks.apply(base, EditBlock(c["path"] as String, s, PyHelper.reindentEdit(base, s, r))).text
            val okBefore = plain != null && parses(plain); val okAfter = fixed != null && parses(fixed)
            if (okBefore) before++; if (okAfter) after++
            if (c["error"] == "IndentationError") { ind++; if (okAfter) indFixed++ }
            assertTrue("never worse: ${c["task"]} ${s.take(60)}", okAfter || !okBefore)
        }
        println("refused edits: ${cases.size}; parse as sent: $before; parse re-indented: $after; IndentationErrors fixed: $indFixed of $ind")
        assertTrue("re-indenting fixes some of the run's IndentationErrors ($indFixed of $ind)", indFixed * 3 >= ind)
    }

    @Test fun reindentsTheCommonMistakes() {
        val cur = "class A:\n    def isatty(self) -> bool:\n        return self.f.isatty()\n"
        // search without its spaces, replacement with them: the def would be indented twice
        val r1 = PyHelper.reindentEdit(cur, "def isatty(self) -> bool:\n        return self.f.isatty()", "    def isatty(self) -> bool:\n        return True")
        assertEquals("class A:\n    def isatty(self) -> bool:\n        return True\n", cur.replace("def isatty(self) -> bool:\n        return self.f.isatty()", r1))
        // replacement written at column 0: its body moves one level in
        val r2 = PyHelper.reindentEdit(cur, "    def isatty(self) -> bool:\n        return self.f.isatty()", "def isatty(self) -> bool:\n    return True")
        assertEquals("    def isatty(self) -> bool:\n        return True", r2)
        // already right: unchanged
        val ok = "    def isatty(self) -> bool:\n        return True"
        assertEquals(ok, PyHelper.reindentEdit(cur, "    def isatty(self) -> bool:\n        return self.f.isatty()", ok))
        // edit_lines: a body line written at column 0 goes to the line it replaces
        assertEquals("        return True", PyHelper.reindentLines(cur, 3, 3, "return True"))
        // inserting after a def line: one level deeper than the def
        assertEquals("        x = 1", PyHelper.reindentLines(cur, 3, 2, "x = 1"))
    }

    /** 1.5.195: on the 13 real Python tasks, the function picked from the request is the one the reference fix changes. */
    @Test fun theRelevantFunctionIsTheOneTheFixChanges() {
        assumeTrue(tasks.isDirectory)
        val assets = File("src/main/assets/bench/reliable")
        val py = ReliableBench.pythonTasks(File(assets, "python.json").readText()) { id -> emptyMap() }.associateBy { it.id }
        val want = MiniJson.parse(File("src/test/resources/nano/py_edits/relevant.json").readText()) as List<*>
        var hit = 0; var scored = 0; val report = ArrayList<String>()
        for (w in want) {
            val m = w as Map<*, *>
            val id = m["id"] as String; val changed = (m["changed"] as List<*>).map { it.toString() }
            if (changed.isEmpty()) continue
            val text = File(tasks, "$id/${m["fix"]}").readText()
            val at = PyHelper.relevantDef(text, py.getValue(id).prompt)
            val name = at?.let { Regex("""(?:def|class)\s+(\w+)""").find(text.split("\n")[it - 1])?.groupValues?.get(1) }
            val trail = at?.let { PyHelper.callees(text, it).map { c -> c.substringBefore(" (") } }.orEmpty()
            scored++; if (name in changed || trail.any { it in changed }) hit++
            report.add("$id: picked $name, calls $trail, fix changes $changed")
        }
        println("relevant function or its callees: $hit of $scored\n" + report.joinToString("\n"))
        // most of these requests never name the code the fix is in (the rest are found by reading); a floor, not a target
        assertTrue(report.joinToString("\n"), hit >= 4)
    }

    @Test fun outlinesAndWhereItFailed() {
        val long = (1..400).joinToString("\n") { if (it % 50 == 0) "def f$it():" else "    x = $it" }
        val o = PyHelper.outline("big.py", long)!!
        assertTrue(o, o.startsWith("big.py has 400 lines") && o.contains("50: def f50():") && o.contains("400: def f400():"))
        assertNull(PyHelper.outline("small.py", "def f():\n    pass\n"))
        val src = "def add(a, b):\n    total = a - b\n    return total\n"
        val out = "FAILED tests/test_m.py::test_add - assert -1 == 3\ntests/test_m.py:4: in test_add\n    assert add(1, 2) == 3\nm.py:3: AssertionError\n1 failed in 0.1s"
        val w = PyHelper.whereFailed(out) { if (it == "m.py") src else null }!!
        assertTrue(w, w.startsWith("Where it failed in the code: m.py line 3") && w.contains("2:     total = a - b"))
        assertNull(PyHelper.whereFailed("1 passed in 0.1s") { src })
        assertTrue(PyHelper.applies(listOf("a/b.py"), "fix it") && PyHelper.applies(emptyList(), "write a Python script") && !PyHelper.applies(listOf("a.js"), "fix it"))
    }
}
