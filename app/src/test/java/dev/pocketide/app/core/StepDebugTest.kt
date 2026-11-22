package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The pause-and-step debugger (1.5.125): where checks go, that the rewritten code still runs the same, and the wait itself. */
class StepDebugTest {
    private fun statements(js: String): List<Int> = StepDebug.analyze(js).values.filter { it.statement }.map { it.line }

    @Test fun parsingUsesLogPointLines() {
        val p = StepDebug.parse("game/main.js:12 score, p.x\nmain.js:30 score")
        assertNull(p.error)
        assertEquals(listOf(12, 30), p.breakpoints.map { it.line })
        assertEquals(listOf("score", "p.x"), p.watches)
        assertNotNull(StepDebug.parse("").error)
        assertNotNull(StepDebug.parse("m.js:3 await x").error)
        assertNotNull(StepDebug.parse("m.js nope").error)
    }

    @Test fun checksGoOnlyWhereAStatementStarts() {
        val js = listOf(
            "const cfg = {",            // 1 yes
            "  a: 1,",                  // 2 no: inside an object
            "  f() {",                  // 3 no
            "    return 2;",            // 4 yes: a method body is a block
            "  },",                     // 5 no
            "};",                       // 6 no
            "class A {",                // 7 yes
            "  x = 1;",                 // 8 no: class body
            "  m(a) {",                 // 9 no
            "    let y = a",            // 10 yes
            "    y++",                  // 11 yes: a name after a value starts a statement when semicolons are left out
            "  }",                      // 12 no
            "}",                        // 13 no
            "switch (cfg.a) {",         // 14 yes
            "  case 1:",                // 15 no
            "    go();",                // 16 no: right after a case label
            "    go();",                // 17 yes
            "}",                        // 18 no
            "const t = `a \${cfg.a} {",  // 19 yes
            "  b",                      // 20 no: inside a template
            "}`;",                      // 21 no
            "const re = /[{]/g;",       // 22 yes: the brace in the regex is not a block
            "if (re)",                  // 23 yes
            "  go();",                  // 24 no: never split an if from its body
            "foo(1,",                   // 25 yes
            "  2);",                    // 26 no: inside brackets
            "const v = a",              // 27 yes
            "  .b;",                    // 28 no: carries on
            "'use strict';",            // 29 no
            "go()",                     // 30 yes
        ).joinToString("\n")
        assertEquals(listOf(1, 4, 7, 10, 11, 14, 17, 19, 22, 23, 25, 27, 30), statements(js))
    }

    @Test fun visibleNamesAreTheOnesDeclaredBefore() {
        val js = listOf(
            "const top = 1;",                          // 1
            "function f(a, b = 2, ...rest) {",         // 2
            "  let x = a;",                            // 3
            "  const { p, q: r } = b;",                // 4
            "  return x;",                             // 5
            "}",                                       // 6
            "const g = (m, n) => {",                   // 7
            "  return m;",                             // 8
            "};",                                      // 9
            "items.forEach(it => {",                   // 10
            "  use(it);",                              // 11
            "});",                                     // 12
            "try { x() } catch (err) {",               // 13
            "  log(err);",                             // 14
            "}",                                       // 15
        ).joinToString("\n")
        val info = StepDebug.analyze(js)
        // the innermost function first, each in the order written
        assertEquals(listOf("a", "b", "rest", "x", "top", "f"), info[4]!!.locals)
        assertEquals(listOf("a", "b", "rest", "x", "p", "r", "top", "f"), info[5]!!.locals)
        assertEquals(listOf("m", "n", "top", "f", "g"), info[8]!!.locals)
        assertEquals("it", info[11]!!.locals.first())
        assertEquals("err", info[14]!!.locals.first())
        assertFalse(info[14]!!.locals.contains("m"))   // names of a function that has ended are gone
    }

    @Test fun lineNumbersStayTheSameAndBreakpointsMoveToAStatement() {
        val js = "let a = [\n  1,\n  2];\nlet b = a.length;\n"
        val r = StepDebug.instrument(js, "m.js", Trace.parse("m.js:2 a; m.js:40").points, listOf("a"))
        assertEquals(js.split("\n").size, r.text.split("\n").size)
        assertEquals(4, r.placed[0])         // line 2 is inside the array: the stop goes to line 4
        assertNull(r.placed[1])              // past the end
        assertTrue(r.text.split("\n")[3].startsWith("typeof __pkD==='object'&&__pkD.h(\"m.js\",4,1,"))
        assertTrue(r.text.split("\n")[0].startsWith("typeof __pkD==='object'&&__pkD.s&&__pkD.h(\"m.js\",1,0,"))
    }

    @Test fun inlineScriptsInAPageAreRewritten() {
        val html = listOf(
            "<html><head><title>t</title></head><body>",   // 1
            "<p>{ not code; }</p>",                        // 2
            "<script>",                                    // 3
            "let n = 1;",                                  // 4
            "n++;",                                        // 5
            "</script>",                                   // 6
            "<script src=\"x.js\">",                       // 7
            "</script>",                                   // 8
            "<script type=\"text/template\">",             // 9
            "let no = 1;",                                 // 10
            "</script>",                                   // 11
            "</body></html>",                              // 12
        ).joinToString("\n")
        val r = StepDebug.instrumentHtml(html, "index.html", Trace.parse("index.html:5").points, emptyList())
        val out = r.text.split("\n")
        assertEquals(5, r.placed[0])
        assertEquals(2, r.stepLines)
        assertTrue(out[3].startsWith("typeof __pkD"))
        assertEquals("<p>{ not code; }</p>", out[1])
        assertEquals("let no = 1;", out[9])
        val page = StepDebug.withRuntime(r.text)
        assertEquals(out.size, page.split("\n").size)
        assertTrue(page.indexOf("window.__pkD") < page.indexOf("let n = 1"))
    }

    @Test fun thePanelText() {
        val d = StepDebug.describe("""{"w":[3,"error: ReferenceError: q is not defined"],"l":{"i":2,"list":[1,2],"o":{"a":"[circular]"}}}""", listOf("n", "q"))
        assertEquals("Watched:\n  n = 3\n  q = error: ReferenceError: q is not defined\nNames here:\n  i = 2\n  list = [1,2]\n  o = {\"a\":\"[circular]\"}", d)
        assertEquals("(no values could be read)", StepDebug.describe("not json", emptyList()))
        val around = StepDebug.codeAround("a\nb\nc\nd", 2).lines()
        assertEquals(listOf("     1  a", "▶    2  b", "     3  c"), around)
    }

    // ---- the gate: the wait on WebView's bridge thread ----

    @Test fun theGateWaitsForTheTapAndIgnoresStaleOnes() {
        val g = StepDebug.Gate(10_000)
        val shown = CountDownLatch(1); val no = AtomicReference(0); val got = AtomicReference<String>()
        val th = Thread { got.set(g.pause { no.set(it); shown.countDown() }) }
        th.start()
        assertTrue(shown.await(5, TimeUnit.SECONDS))
        assertFalse(g.answer(no.get() + 1, StepDebug.STEP))   // a tap meant for another pause does nothing
        assertTrue(g.answer(no.get(), StepDebug.STEP))
        assertFalse(g.answer(no.get(), StepDebug.CONTINUE))   // a second tap on the same pause does nothing
        th.join(5000)
        assertEquals(StepDebug.STEP, got.get())
    }

    @Test fun theGateLetsGoWhenClosedOrAfterTheTimeout() {
        val g = StepDebug.Gate(60_000)
        val shown = CountDownLatch(1); val got = AtomicReference<String>()
        val th = Thread { got.set(g.pause { shown.countDown() }) }
        th.start()
        assertTrue(shown.await(5, TimeUnit.SECONDS))
        g.close()
        th.join(5000)
        assertEquals(StepDebug.STOP, got.get())
        assertEquals(StepDebug.STOP, g.pause { throw AssertionError("a closed gate shows nothing") })

        val quick = StepDebug.Gate(150)
        val t0 = System.currentTimeMillis()
        assertEquals(StepDebug.CONTINUE, quick.pause { })
        assertTrue(System.currentTimeMillis() - t0 >= 140)
        assertEquals(1, quick.timedOut)

        // never wait on the screen's own thread
        val ui = StepDebug.Gate(60_000) { true }
        assertEquals(StepDebug.CONTINUE, ui.pause { throw AssertionError("not shown") })
    }

    // ---- running the rewritten code under node, with a stand-in for the app ----

    private fun hasNode() = try { ProcessBuilder("node", "--version").start().waitFor() == 0 } catch (e: Exception) { false }

    /** Runs [code] as code.js; the stand-in PocketDebug.pause records each call and answers from [answers] (the last one repeats). */
    private fun run(code: String, answers: List<String>? = null): Pair<String, List<Map<String, Any?>>> {
        val dir = Files.createTempDirectory("stepdebug").toFile()
        try {
            File(dir, "code.js").writeText(code)
            File(dir, "runtime.js").writeText(StepDebug.RUNTIME.removePrefix("<script>").removeSuffix("</script>"))
            val stub = if (answers == null) "" else "var A=" + MiniJson.write(answers) + ";window.PocketDebug={pause:function(f,n,v){C.push({f:f,n:n,v:JSON.parse(v)});return A.length>1?A.shift():A[0]}};"
            File(dir, "main.js").writeText("globalThis.window=globalThis;var C=[];$stub require('./runtime.js');" +
                "process.on('exit',function(){process.stdout.write('\\n@@'+JSON.stringify(C))});require('./code.js');")
            val p = ProcessBuilder("node", "main.js").directory(dir).redirectErrorStream(true).start()
            val all = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(30, TimeUnit.SECONDS))
            val out = all.substringBeforeLast("\n@@")
            @Suppress("UNCHECKED_CAST")
            val calls = (MiniJson.list(MiniJson.parse(all.substringAfterLast("\n@@").trim())) ?: emptyList<Any?>()).map { it as Map<String, Any?> }
            return out to calls
        } finally { dir.deleteRecursively() }
    }

    private val sample = listOf(
        "'use strict';",
        "const cfg = {",
        "  name: 'demo',",
        "  size: { w: 2, h: 3 },",
        "  list: [1, 2,",
        "    3],",
        "  f() {",
        "    return this.name;",
        "  },",
        "};",
        "class Box extends Object {",
        "  static count = 0;",
        "  #hidden = 1;",
        "  constructor(v) {",
        "    super();",
        "    this.v = v + this.#hidden;",
        "    Box.count++;",
        "  }",
        "  get double() {",
        "    return this.v * 2;",
        "  }",
        "  static {",
        "    Box.ready = true;",
        "  }",
        "}",
        "function total(items, extra = 0) {",
        "  let sum = extra",
        "  for (const it of items) {",
        "    sum += it",
        "  }",
        "  return sum",
        "}",
        "const re = /[{}]+/g",
        "const tpl = `a \${cfg.size.w} {",
        "  \${total([1, 2], 3)}",
        "} b`;",
        "let out = []",
        "out.push(total(cfg.list))",
        "out.push(new Box(4).double, Box.ready, cfg.f())",
        "switch (out.length) {",
        "  case 4:",
        "    out.push('four')",
        "    break",
        "  default:",
        "    out.push('other')",
        "}",
        "const arrow = (a, b) => {",
        "  const c = a + b",
        "  return c",
        "    * 2",
        "}",
        "const concise = x =>",
        "  x + 1",
        "out.push(arrow(1, 2), concise(5))",
        "if (out.length > 3)",
        "  out.push('long')",
        "else",
        "  out.push('short')",
        "label: for (let i = 0; i < 3; i++) {",
        "  if (i === 1) continue label",
        "  out.push(i)",
        "}",
        "try {",
        "  JSON.parse('{')",
        "} catch (e) {",
        "  out.push(e.name)",
        "} finally {",
        "  out.push('fin')",
        "}",
        "let k = 0",
        "do {",
        "  k++",
        "} while (k < 3)",
        "out.push(k)",
        "async function later() {",
        "  const v = await Promise.resolve(7)",
        "  return v",
        "}",
        "function* gen() {",
        "  yield 1",
        "  yield 2",
        "}",
        "out.push([...gen()].join('+'))",
        "out.push(tpl.replace(re, '#'))",
        "const obj = { a: 1, b: { c: [1, 2] } }",
        "const { a, b: { c } } = obj",
        "out.push(a, c.length)",
        "var x = 10",
        "x",
        "++x",
        "out.push(x)",
        "const div = 10 / 2 / 1",
        "out.push(div)",
        "const loop = { self: null }; loop.self = loop",
        "later().then(v => {",
        "  out.push(v)",
        "  console.log(JSON.stringify(out))",
        "})",
    ).joinToString("\n")

    @Test fun rewrittenCodeRunsTheSame() {
        assumeTrue(hasNode())
        val (plain, _) = run(sample)
        assertTrue(plain, plain.trim().startsWith("[6,10,true,\"demo\""))
        val r = StepDebug.instrument(sample, "s.js", emptyList(), listOf("out.length", "cfg.size"))
        assertTrue(r.stepLines > 40)
        // not paused: no app object at all, then an app that is never asked (no breakpoint, no stepping)
        assertEquals(plain, run(r.text).first)
        val (quiet, none) = run(r.text, listOf(StepDebug.CONTINUE))
        assertEquals(plain, quiet); assertTrue(none.isEmpty())
        // stepping through every statement of the whole program still gives the same output
        val bp = StepDebug.instrument(sample, "s.js", Trace.parse("s.js:2").points, listOf("out.length", "loop"))
        val (stepped, calls) = run(bp.text, listOf(StepDebug.STEP))
        assertEquals(plain, stepped)
        assertTrue(calls.size > 60)
        val lines = calls.map { (it["n"] as Number).toInt() }
        assertEquals(2, lines.first())
        assertTrue(lines.containsAll(listOf(8, 16, 29, 48, 61, 72, 97)))
        // a value that loops back to itself is sent safely
        val last = calls.last { (it["n"] as Number).toInt() == 97 }
        val w = MiniJson.list(MiniJson.obj(last["v"])!!["w"])!!
        assertEquals(mapOf("self" to "[circular]"), w[1])
    }

    @Test fun stepContinueAndStopFollowTheCode() {
        assumeTrue(hasNode())
        val js = listOf(
            "function add(a, b) {",           // 1
            "  const s = a + b;",             // 2
            "  return s;",                    // 3
            "}",                              // 4
            "let t = 0;",                     // 5
            "for (let i = 0; i < 2; i++) {",  // 6
            "  t = add(t, i);",               // 7
            "}",                              // 8
            "console.log(t);",                // 9
        ).joinToString("\n")
        val r = StepDebug.instrument(js, "a.js", Trace.parse("a.js:5; a.js:3").points, listOf("t"))
        val (out1, steps) = run(r.text, listOf(StepDebug.STEP))
        assertEquals("1", out1.trim())
        assertEquals(listOf(5, 6, 7, 2, 3, 7, 2, 3, 9), steps.map { (it["n"] as Number).toInt() })
        // at line 3 the function's own names are seen: s, a, b
        val at3 = MiniJson.obj(MiniJson.obj(steps[7]["v"])!!["l"])!!
        assertEquals(1.0, at3["s"]); assertEquals(0.0, at3["a"]); assertEquals(1.0, at3["b"])
        // Continue stops only at breakpoints
        val (_, cont) = run(r.text, listOf(StepDebug.CONTINUE))
        assertEquals(listOf(5, 3, 3), cont.map { (it["n"] as Number).toInt() })
        // Stop: nothing more is asked, and the page carries on
        val (out3, stop) = run(r.text, listOf(StepDebug.STEP, StepDebug.STOP))
        assertEquals("1", out3.trim())
        assertEquals(listOf(5, 6), stop.map { (it["n"] as Number).toInt() })
    }

    @Test fun realFilesStillParseWithEveryLineChecked() {
        assumeTrue(hasNode())
        val root = listOf(File("src/main/assets"), File("app/src/main/assets")).firstOrNull { it.isDirectory } ?: return
        val files = root.walkTopDown().filter { it.isFile && it.extension == "js" }.toList()
        assertTrue(files.size > 20)
        val dir = Files.createTempDirectory("stepdebug-parse").toFile()
        try {
            var checked = 0
            val unreadable = ArrayList<String>()
            for (f in files) {
                val text = f.readText()
                fun parses(f: File) = ProcessBuilder("node", "--check", f.path).redirectErrorStream(true).start().waitFor() == 0
                // node is told whether the file is a module by its name: try both, as the original must parse one way
                val ext = listOf(".js", ".mjs").firstOrNull { parses(File(dir, "orig$checked$it").also { o -> o.writeText(text) }) }
                if (ext == null) { unreadable.add(f.name); checked++; continue }
                val r = StepDebug.instrument(text, f.name, emptyList(), listOf("this"))
                val out = File(dir, "new$checked$ext")
                out.writeText(r.text)
                val p = ProcessBuilder("node", "--check", out.path).redirectErrorStream(true).start()
                val msg = p.inputStream.bufferedReader().readText()
                assertEquals("${f.path} no longer parses after rewriting: $msg", 0, p.waitFor())
                checked++
            }
            assertEquals(files.size, checked)
            assertTrue("node could not read these even before rewriting: $unreadable", unreadable.isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
