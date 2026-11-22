package dev.pocketide.app

import dev.pocketide.app.core.LocalModels
import dev.pocketide.app.core.LooseFiles
import dev.pocketide.app.core.SseDecoder
import dev.pocketide.app.core.ModelSize
import dev.pocketide.app.core.ReplyParser
import org.junit.Test

class LooseFilesTest {
    @Test fun all() { LooseFilesSelfTest.runAll(); check(LooseFilesSelfTest.failed == 0) { "${LooseFilesSelfTest.failed} loose-file checks failed" } }
}

object LooseFilesSelfTest {
    var failed = 0
    private var passed = 0
    private fun ok(c: Boolean, name: String) { if (c) passed++ else { failed++; println("FAIL  $name") } }
    private fun <T> eq(a: T, b: T, name: String) { ok(a == b, name); if (a != b) println("      got: $a\n      want: $b") }

    fun runAll() {
        failed = 0; passed = 0
        val none = emptyMap<String, String>()

        // the reply from a real test with a small local model
        val rs = "Yes, I can create a runescape private server engine using Go. Here's a basic outline of how I would structure the files:\n\n" +
            "runserver.go\n=======\n// Import necessary packages\nimport (\n\t\"net\"\n)\n\nfunc main() {\n}\n=======\n"
        var p = ReplyParser.parse(rs, none, emptySet(), null)
        eq(p.edits.map { it.path }, listOf("runserver.go"), "=== divider layout becomes a file")
        ok(p.edits[0].content.startsWith("// Import necessary packages\n") && p.edits[0].content.endsWith("func main() {\n}\n"), "divider body kept exactly")
        eq(p.loose, listOf("runserver.go"), "marked as loose")
        ok(p.text.startsWith("Yes, I can create") && !p.text.contains("====="), "explanation kept, file removed from text")

        // decorated names above fences
        for ((label, line) in listOf("bold" to "**app.js**", "backticks" to "`app.js`", "heading" to "### app.js", "File: prefix" to "File: app.js",
                "trailing colon" to "app.js:", "bold+backticks" to "**`app.js`**", "list item" to "1. app.js", "dot slash" to "./app.js")) {
            p = ReplyParser.parse("Here:\n$line\n```javascript\nconsole.log(1)\n```\nDone.", none, emptySet(), null)
            eq(p.edits.map { it.path }, listOf("app.js"), "name line: $label")
        }
        p = ReplyParser.parse("Here:\napp.js\n\n```js\nconsole.log(1)\n```", none, emptySet(), null)
        eq(p.edits.map { it.path }, listOf("app.js"), "one blank line between name and fence")

        // comment naming the file inside the fence
        p = ReplyParser.parse("```go\n// main.go\npackage main\n```", none, emptySet(), null)
        eq(p.edits.map { it.path to it.content }, listOf("main.go" to "package main\n"), "comment name line inside fence")
        p = ReplyParser.parse("```html\n<!-- index.html -->\n<p>hi</p>\n```", none, emptySet(), null)
        eq(p.edits.map { it.path }, listOf("index.html"), "html comment name")
        p = ReplyParser.parse("```python\n# filename: tools/run.py\nprint(1)\n```", none, emptySet(), null)
        eq(p.edits.map { it.path }, listOf("tools/run.py"), "# filename: comment with folder")

        // several files, ----- dividers, missing closing divider
        p = ReplyParser.parse("go.mod\n-----\nmodule demo\n-----\nmain.go\n-----\npackage main\n", none, emptySet(), null)
        eq(p.edits.map { it.path }, listOf("go.mod", "main.go"), "two files with ----- dividers, last one unclosed")
        p = ReplyParser.parse("Makefile\n```\nall:\n\tgo build\n```", none, emptySet(), null)
        eq(p.edits.map { it.path }, listOf("Makefile"), "bare well-known name")

        // things that must NOT become files
        p = ReplyParser.parse("Use it like this:\n```js\nconsole.log(1)\n```", none, emptySet(), null)
        ok(p.edits.isEmpty() && p.loose.isEmpty(), "plain fence without a name is left alone")
        p = ReplyParser.parse("Notes, e.g.\n```\nx\n```", none, emptySet(), null)
        ok(p.edits.isEmpty(), "prose ending in e.g. is not a file name")
        p = ReplyParser.parse("Read this sentence about app.js please\n```js\nx\n```", none, emptySet(), null)
        ok(p.edits.isEmpty(), "a sentence mentioning a file is not a name line")
        p = ReplyParser.parse("../secret.txt\n```\nx\n```", none, emptySet(), null)
        ok(p.edits.isEmpty(), "path with .. refused")
        p = ReplyParser.parse("photo.png\n```\nx\n```", none, emptySet(), null)
        ok(p.edits.isEmpty(), "unknown extension refused")

        // existing files: whole-looking replacement allowed, fragment refused
        val existing = mapOf("app.js" to "const a = 1;\nconst b = 2;\nconst c = 3;\nconsole.log(a + b + c);\n")
        p = ReplyParser.parse("app.js\n```js\nconst a = 1;\nconst b = 2;\nconst c = 30;\nconsole.log(a + b + c);\n```", existing, emptySet(), null)
        eq(p.edits.map { it.path }, listOf("app.js"), "full replacement of an existing file applied")
        p = ReplyParser.parse("app.js\n```js\nconst c = 30;\n```", existing, emptySet(), null)
        ok(p.edits.isEmpty() && p.looseSkipped == listOf("app.js"), "fragment of an existing file is not applied")
        ok(p.text.contains("const c = 30;"), "the skipped fragment stays visible in the reply")

        // the strict formats still win
        p = ReplyParser.parse("app.js\n```js\nconsole.log(1)\n```\n```file:index.html\n<p>x</p>\n```", none, emptySet(), null)
        eq(p.edits.map { it.path }, listOf("index.html"), "with a proper file: fence, loose layouts are ignored")
        ok(p.loose.isEmpty(), "and nothing is marked loose")
        p = ReplyParser.parse("x.txt\n```\nhello\n```", none, setOf("x.txt"), null)
        ok(p.edits.isEmpty(), "binary files are never written from a loose reply")

        eq(LooseFiles.pathLine("**src/main.go**:"), "src/main.go", "pathLine cleans decorations")
        eq(LooseFiles.pathLine("hello world.go"), null, "pathLine rejects spaces")
        eq(LooseFiles.pathLine("notes", setOf("notes")), "notes", "pathLine accepts a known extensionless file")

        // model size and request size
        eq(ModelSize.billions("qwen2.5-coder:1.5b"), 1.5, "size from ollama tag")
        eq(ModelSize.billions("llama3.2:3b"), 3.0, "size 3b")
        eq(ModelSize.billions("gemma3n:e2b"), 2.0, "effective size e2b")
        eq(ModelSize.billions("qwen2.5-coder-7b-instruct-q4_k_m.gguf"), 7.0, "size from a gguf file name")
        eq(ModelSize.billions("phi4-mini"), null, "no size in the name")
        ok(ModelSize.isSmall("qwen2.5-coder:3b") && !ModelSize.isSmall("qwen2.5-coder:7b") && !ModelSize.isSmall("phi4-mini"), "isSmall threshold is 7B, unknown is not small")
        ok(ModelSize.isBigRequest("Can you create a runescape private server engine to easily customize and create your own runescape worlds?"), "the RuneScape request is big")
        ok(!ModelSize.isBigRequest("rename the button to Save"), "a small edit is not big")
        ok(ModelSize.isBigRequest("make a snake game"), "make + game is big")

        // local models: thinking, budget, /no_think
        ok(LocalModels.isLocal("ollama") && LocalModels.isLocal("llamacpp") && !LocalModels.isLocal("groq"), "local providers")
        ok(LocalModels.isLocal("custom", "http://192.168.1.5:8080/v1") && !LocalModels.isLocal("custom", "https://api.example.com/v1"), "custom local vs remote")
        eq(LocalModels.contextChars(true), 8000, "local context budget")
        ok(LocalModels.wantsNoThink("qwen3:4b") && LocalModels.wantsNoThink("qwen3") && !LocalModels.wantsNoThink("qwen2.5-coder:3b"), "qwen3 gets /no_think, qwen2.5 does not")
        eq(LocalModels.addNoThink("hi"), "hi\n\n/no_think", "no_think appended")
        eq(LocalModels.addNoThink("hi /no_think"), "hi /no_think", "no_think not doubled")
        var v = LocalModels.stripThinking("<think>\nhmm let me see\n</think>\n\nHello!")
        ok(v.text == "Hello!" && !v.thinking && v.thoughtChars > 0, "closed think block removed")
        v = LocalModels.stripThinking("<think>still going")
        ok(v.text == "" && v.thinking && v.thoughtChars == 11, "open think block hides the rest while streaming")
        eq(LocalModels.stripThinking("plain answer").text, "plain answer", "no think block: unchanged")
        val d = SseDecoder(false)
        eq(d.feed("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning\":\"Okay, the user\"}}]}"), "", "reasoning delta carries no answer text")
        eq(d.reasoningChars, 14, "reasoning counted")
        eq(d.feed("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"abc\"}}]}"), "", "reasoning_content variant")
        eq(d.reasoningChars, 17, "reasoning_content counted")
        eq(d.feed("data: {\"choices\":[{\"delta\":{\"content\":\"Hi!\"}}]}"), "Hi!", "answer text still returned")
        eq(d.feed("data: {\"choices\":[{\"delta\":{\"content\":null}}]}"), "", "null content is empty, not the word null")

        // real test: prose naming the existing file, then a fence in the same language
        val mainPy = mapOf("main.py" to "def main():\n    print(\"hello\")\n\nif __name__ == \"__main__\":\n    main()\n")
        val fibReply = "To add a function, we need to modify the `main` function.\n\nHere's the updated `main.py` file:\n\n```python\ndef fib(n):\n    a, b = 0, 1\n    out = []\n    for _ in range(n):\n        out.append(a)\n        a, b = b, a + b\n    return out\n\ndef main():\n    print(fib(10))\n\nif __name__ == \"__main__\":\n    main()\n```\n\n### Explanation:\n1. fib returns a list."
        p = ReplyParser.parse(fibReply, mainPy, emptySet(), null)
        eq(p.edits.map { it.path }, listOf("main.py"), "prose naming the existing file + python fence replaces it")
        ok(p.edits.isNotEmpty() && p.edits[0].content.startsWith("def fib(n):") && p.text.contains("Explanation"), "file body applied, explanation kept")
        p = ReplyParser.parse("Run `main.py` with:\n```bash\npython main.py\n```", mainPy, emptySet(), null)
        ok(p.edits.isEmpty(), "bash fence next to a .py mention is not a file write")
        p = ReplyParser.parse("Change `main.py` like this:\n```python\nprint(2)\n```", mainPy, emptySet(), null)
        ok(p.edits.isEmpty() && p.looseSkipped == listOf("main.py"), "short snippet after a mention is not applied")
        p = ReplyParser.parse("Here is `other.py`:\n```python\nprint(1)\n```", mainPy, emptySet(), null)
        ok(p.edits.isEmpty(), "mention of a file that does not exist is ignored")

        // the waiting status always carries the elapsed time
        eq(LocalModels.clock(7), "7s", "clock seconds")
        eq(LocalModels.clock(75), "1m 15s", "clock minutes")
        eq(LocalModels.clock(120), "2m 00s", "clock pads seconds")
        eq(LocalModels.waitingStatus(0, 0, false), "Thinking…", "no time before the first second")
        eq(LocalModels.waitingStatus(5, 0, false), "Thinking… 5s", "cloud wait shows seconds")
        eq(LocalModels.waitingStatus(5, 120, false), "Thinking… (120 characters of reasoning, 5s)", "reasoning shows characters and time")
        ok(LocalModels.waitingStatus(10, 0, true).startsWith("Waiting for the model… 10s"), "local wait shows seconds")
        eq(LocalModels.waitingStatus(2, 0, true), "Thinking… 2s", "local, first seconds")

        println("\n$passed passed, $failed failed")
    }
}
