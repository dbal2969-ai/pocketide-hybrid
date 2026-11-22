package dev.pocketide.app

import dev.pocketide.app.core.AgentActions
import dev.pocketide.app.core.EditBlock
import dev.pocketide.app.core.EditBlocks
import dev.pocketide.app.core.MultiEdit
import dev.pocketide.app.core.NextEdit
import dev.pocketide.app.core.StubCheck
import org.junit.Test

/**
 * Release benchmark, edit layer: the shapes of model output that failed in real chats (wrong indentation, copied line numbers,
 * fenced edit blocks, bold action lines, stand-in files). Each case must come out right; any miss blocks a release.
 */
class EditBenchmarkTest {
    private val file = "function a() {\n    return 1;\n}\n\nfunction b() {\n    return 2;\n}\n"

    private fun applies(search: String, replace: String, expect: String, name: String, fails: MutableList<String>, cur: String = file) {
        val r = EditBlocks.apply(cur, EditBlock("x.js", search, replace)).text
        if (r != expect) fails.add("$name: got ${r?.take(80)}")
    }

    @Test fun benchmark() {
        val f = ArrayList<String>()
        applies("    return 1;", "    return 10;", file.replace("return 1;", "return 10;"), "exact", f)
        applies("return 1;   ", "return 10;", file.replace("return 1;", "return 10;"), "trailing spaces in search", f)
        applies("  return 1;", "  return 10;", file.replace("return 1;", "return 10;"), "wrong indentation", f)
        applies("\treturn 1;", "\treturn 10;", file.replace("return 1;", "return 10;"), "tab for spaces", f)
        applies("2:     return 1;", "    return 10;", file.replace("return 1;", "return 10;"), "copied line number", f)
        applies("function a() {\n\n    return 1;\n}", "function a() {\n    return 10;\n}", file.replace("return 1;", "return 10;"), "extra blank line", f)
        applies("return  1;", "return 10;", file.replace("return 1;", "return 10;"), "inner spacing", f)
        // a search that matches twice is refused, not guessed
        if (EditBlocks.apply("x\nx\n", EditBlock("x.js", "x", "y")).text != null) f.add("ambiguous search applied")
        // fenced SEARCH/REPLACE block with the path above it
        val reply = "Here is the change:\n```js\nx.js\n<<<<<<< SEARCH\n    return 2;\n=======\n    return 20;\n>>>>>>> REPLACE\n```\n"
        val scan = EditBlocks.scan(reply, setOf("x.js"), null)
        if (scan.blocks.size != 1 || scan.blocks[0].path != "x.js") f.add("fenced block not found")
        else applies(scan.blocks[0].search, scan.blocks[0].replace, file.replace("return 2;", "return 20;"), "fenced block applies", f)
        // action lines as models write them
        for (t in listOf("ACTION: run ls -la", "**ACTION: run ls -la**", "__ACTION: run ls -la__", "> ACTION: run ls -la", "`ACTION: run ls -la`"))
            if (AgentActions.find(t).singleOrNull()?.arg != "ls -la") f.add("action line: $t")
        // several edits in one call
        val out = MultiEdit.run(MultiEdit.parse(listOf(
            mapOf("path" to "x.js", "search" to "return 1;", "replace" to "return 3;"),
            mapOf("path" to "x.js", "search" to "return 2;", "replace" to "return 4;"))), { file }, { null })
        if (out.texts["x.js"] != file.replace("return 1;", "return 3;").replace("return 2;", "return 4;")) f.add("multi edit")
        // stand-ins
        for (t in listOf("// TODO: implement\n", "# ... rest of the code", "throw new Error('not implemented');"))
            if (StubCheck.find("m.js", t) == null) f.add("stand-in missed: $t")
        if (StubCheck.find("m.js", "const x = 1;") != null) f.add("real code flagged")
        if (f.isNotEmpty()) throw AssertionError("edit benchmark ${f.size} failed: " + f.joinToString("; "))
    }

    /**
     * Next-edit suggestions, the model case (1.5.125): recorded edit pairs (the edit made, then the edit that should follow) with
     * canned model replies, run through the same parse, check and apply steps the editor uses. This benchmarks the plumbing
     * offline; how often a live model suggests the right edit, and how fast, needs a live run (BENCHMARKS.md).
     */
    private data class NextCase(val name: String, val before: String, val after: String, val reply: String, val expect: String?)

    private val nextCases = listOf(
        NextCase("parameter added: call updated",
            "function area(w) {\n  return w * w;\n}\nconst a = area(3);\n",
            "function area(w, h) {\n  return w * h;\n}\nconst a = area(3);\n",
            "<<<<<<< SEARCH\nconst a = area(3);\n=======\nconst a = area(3, 4);\n>>>>>>> REPLACE",
            "function area(w, h) {\n  return w * h;\n}\nconst a = area(3, 4);\n"),
        NextCase("unit changed: use converted",
            "const TIMEOUT_S = 5;\nsetTimeout(done, TIMEOUT_S * 1000);\n",
            "const TIMEOUT_MS = 5000;\nsetTimeout(done, TIMEOUT_S * 1000);\n",
            "```js\n<<<<<<< SEARCH\nsetTimeout(done, TIMEOUT_S * 1000);\n=======\nsetTimeout(done, TIMEOUT_MS);\n>>>>>>> REPLACE\n```",
            "const TIMEOUT_MS = 5000;\nsetTimeout(done, TIMEOUT_MS);\n"),
        NextCase("field added: builder updated",
            "class Player {\n  constructor(name) {\n    this.name = name;\n  }\n}\nconst p = { name: 'Ann' };\n",
            "class Player {\n  constructor(name) {\n    this.name = name;\n    this.hp = 10;\n  }\n}\nconst p = { name: 'Ann' };\n",
            "<<<<<<< SEARCH\nconst p = { name: 'Ann' };\n=======\nconst p = { name: 'Ann', hp: 10 };\n>>>>>>> REPLACE",
            "class Player {\n  constructor(name) {\n    this.name = name;\n    this.hp = 10;\n  }\n}\nconst p = { name: 'Ann', hp: 10 };\n"),
        NextCase("python keyword argument",
            "def greet(name):\n    return 'hi ' + name\n\nprint(greet('Bo'))\n",
            "def greet(name, loud=False):\n    return 'hi ' + name\n\nprint(greet('Bo'))\n",
            "<think>the body should use loud</think>\n<<<<<<< SEARCH\n    return 'hi ' + name\n=======\n    s = 'hi ' + name\n    return s.upper() if loud else s\n>>>>>>> REPLACE",
            "def greet(name, loud=False):\n    s = 'hi ' + name\n    return s.upper() if loud else s\n\nprint(greet('Bo'))\n"),
        // replies that must show nothing
        NextCase("ambiguous old text refused",
            "f(1);\nf(1);\nfunction f(a) {}\n", "f(1);\nf(1);\nfunction f(a, b) {}\n",
            "<<<<<<< SEARCH\nf(1);\n=======\nf(1, 2);\n>>>>>>> REPLACE", null),
        NextCase("edit of the just-edited line refused",
            "let speed = 3;\nmove(speed);\n", "let speed = 30;\nmove(speed);\n",
            "<<<<<<< SEARCH\nlet speed = 30;\n=======\nlet speed = 3;\n>>>>>>> REPLACE", null),
        NextCase("NONE shows nothing",
            "let x = 1;\n", "let x = 2;\n", "NONE", null),
        NextCase("prose shows nothing",
            "let x = 1;\nuse(x);\n", "let y = 1;\nuse(x);\n", "You might want to rename x on line 2 as well.", null)
    )

    @Test fun nextEditPipeline() {
        val f = ArrayList<String>()
        for (c in nextCases) {
            val recent = NextEdit.pickBase(listOf(c.before), c.after)
            if (recent == null) { f.add("${c.name}: no recent edit"); continue }
            val prompt = NextEdit.prompt("x", c.after, recent, recent.start)
            if (!prompt.contains(recent.diff)) f.add("${c.name}: diff missing from prompt")
            val s = NextEdit.parse(c.reply)
            val v = s?.let { NextEdit.validate(c.after, it, recent) }
            val got = (v as? NextEdit.Verdict.Ok)?.let { NextEdit.applyTo(c.after, it) }
            if (got != c.expect) f.add("${c.name}: got ${got?.take(80)} ($v)")
        }
        if (f.isNotEmpty()) throw AssertionError("next-edit benchmark ${f.size} of ${nextCases.size} failed: " + f.joinToString("; "))
    }
}
