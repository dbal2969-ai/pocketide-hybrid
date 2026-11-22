package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class TraceTest {
    private val code = listOf(
        "function slotOf(sid) {",                       // 1
        "  const list = data.items",                    // 2
        "    .filter(x => x.ok);",                      // 3
        "  for (const x of list) {",                    // 4
        "    if (x.id === sid) return x.slot;",         // 5
        "  }",                                          // 6
        "  // nothing found",                           // 7
        "  return null;",                               // 8
        "}",                                            // 9
        "const data = { items: [{ id: 1, slot: 'Head', ok: true }, { id: 2, slot: 'Legs', ok: true }] };",  // 10
        "if (data)",                                    // 11
        "  slotOf(1);",                                 // 12
        "slotOf(2); slotOf(3);",                        // 13
    ).joinToString("\n")

    @Test fun parsing() {
        val p = Trace.parse("game/main.js:248 sid, inv.slots; ./save.js:12\nindex.html:30 f(a, b), x[0]")
        assertNull(p.error)
        assertEquals(listOf("game/main.js", "save.js", "index.html"), p.points.map { it.path })
        assertEquals(listOf(248, 12, 30), p.points.map { it.line })
        assertEquals(listOf(listOf("sid", "inv.slots"), emptyList(), listOf("f(a, b)", "x[0]")), p.points.map { it.exprs })
        assertNotNull(Trace.parse("main.js sid").error)
        assertNotNull(Trace.parse((1..13).joinToString(";") { "a.js:$it x" }).error)
        assertEquals(listOf("a", "{b: 1, c: 2}", "'x,y'"), Trace.splitExprs("a, {b: 1, c: 2}, 'x,y'"))
    }

    @Test fun pointsGoWhereAStatementStarts() {
        val pts = Trace.parse("m.js:3 list; m.js:5 x.id, sid; m.js:8 sid; m.js:12 data").points
        val (out, used) = Trace.instrument(code, pts)
        assertEquals(4, used[0])          // line 3 continues line 2, so the point moves to 4
        assertEquals(5, used[1])
        assertEquals(8, used[2])          // the comment above line 8 is skipped when looking back
        assertEquals(13, used[3])         // line 12 is the body of an if without braces: never split them
        val lines = out.split("\n")
        assertTrue(lines[4].startsWith("    typeof __pkT==='function'&&__pkT(1,[()=>{try{return (x.id)}"))
        assertTrue(lines[11] == "  slotOf(1);" && lines[10] == "if (data)")
        assertEquals(code.lines().size, lines.size)       // line numbers stay the same
    }

    @Test fun theRecorderRunsAndTheSummaryReadsIt() {
        assumeTrue(try { ProcessBuilder("node", "--version").start().waitFor() == 0 } catch (e: Exception) { false })
        val pts = Trace.parse("m.js:5 x.id, sid, missing.y; m.js:8 sid; m.js:2").points
        val (out, used) = Trace.instrument(code, pts)
        val dir = Files.createTempDirectory("trace").toFile()
        val js = java.io.File(dir, "t.js")
        val recorder = Trace.RECORDER.removePrefix("<script>").removeSuffix("</script>")
        js.writeText("var window = globalThis; window.addEventListener = function(){};\n$recorder\n$out\nconsole.log(${Trace.RESULTS});")
        val p = ProcessBuilder("node", js.path).redirectErrorStream(true).start()
        val raw = p.inputStream.bufferedReader().readText().trim().lines().last()
        p.waitFor(); dir.deleteRecursively()
        val s = Trace.summary(MiniJson.obj(MiniJson.parse(raw)), pts, used)
        assertTrue(s, s.contains("- m.js:5 (x.id, sid, missing.y): ran 5 times"))
        assertTrue(s, s.contains("1. x.id = 1; sid = 1; missing.y = \"error: ReferenceError: missing is not defined\""))
        assertTrue(s, s.contains("- m.js:8 (sid): ran 1 time\n    1. sid = 3"))
        assertTrue(s, s.contains("- m.js:2: ran 3 times\n    1. (reached)"))
        val none = Trace.summary(null, Trace.parse("other.js:1 a").points, emptyMap())
        assertTrue(none.contains("the page did not load this file"))
    }

    @Test fun theRecorderGoesFirstInThePage() {
        val page = Trace.withRecorder("<html><head><title>x</title><script src=\"main.js\"></script></head></html>")
        assertTrue(page.indexOf(Trace.RECORDER) in 0 until page.indexOf("main.js"))
        assertTrue(Trace.withRecorder("<p>x</p>").startsWith(Trace.RECORDER))
        System.getenv("TRACE_OUT")?.let { java.io.File(it, "recorder.html").writeText(Trace.RECORDER) }
    }
}
