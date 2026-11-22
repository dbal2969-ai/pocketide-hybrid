package dev.pocketide.app

import dev.pocketide.app.core.ActionVerb
import dev.pocketide.app.core.AgentAction
import dev.pocketide.app.core.AgentActions
import dev.pocketide.app.core.ChangeKind
import dev.pocketide.app.core.Changes
import dev.pocketide.app.core.ContextRanker
import dev.pocketide.app.core.FileChange
import dev.pocketide.app.core.FolderFiles
import dev.pocketide.app.core.GitCore
import dev.pocketide.app.core.GitHubException
import dev.pocketide.app.core.GitRepo
import dev.pocketide.app.core.GitSync
import dev.pocketide.app.core.SyncSnapshot
import dev.pocketide.app.core.Diff
import dev.pocketide.app.core.EditBlock
import dev.pocketide.app.core.EditBlocks
import dev.pocketide.app.core.Highlighter
import dev.pocketide.app.core.MiniJson
import dev.pocketide.app.core.ModelException
import dev.pocketide.app.core.Prompts
import dev.pocketide.app.core.ProjectFile
import dev.pocketide.app.core.Providers
import dev.pocketide.app.core.ReplyParser
import dev.pocketide.app.core.Sample
import dev.pocketide.app.core.SseDecoder
import dev.pocketide.app.core.TokenKind

/** Checks for the logic that has no Android dependency. Plain Kotlin so it also runs outside Gradle. */
object CoreSelfTest {
    private var fails = 0
    private var passes = 0

    private fun ok(c: Boolean, m: String) {
        if (c) passes++ else fails++
        println((if (c) "PASS  " else "FAIL  ") + m)
    }

    private fun eq(a: Any?, b: Any?, m: String) = ok(a == b, if (a == b) m else "$m  got <$a> expected <$b>")

    private fun blk(path: String?, s: String, r: String) =
        (if (path != null) "$path\n" else "") + "<<<<<<< SEARCH\n$s\n=======\n$r\n>>>>>>> REPLACE"

    private val known = setOf("app.js", "style.css", "index.html", "Makefile")

    fun runAll() {
        parsing(); applying(); resolving(); replies(); context(); diffs(); json(); sse(); highlight(); misc()
        git(); sync(); actions(); localProviders(); runner()
        println("\n$passes passed, $fails failed")
        check(fails == 0) { "$fails self-test checks failed" }
    }

    private fun parsing() {
        var r = EditBlocks.scan("Changing the title.\n\n" + blk("app.js", "document.title = 'Old';", "document.title = 'New';") + "\n", known, "app.js")
        eq(r.blocks, listOf(EditBlock("app.js", "document.title = 'Old';", "document.title = 'New';")), "plain block parsed")
        eq(r.text, "Changing the title.", "block removed from the shown text")
        r = EditBlocks.scan("Do it.\n\n```js\n" + blk("app.js", "a", "b") + "\n```\n", known, null)
        eq(r.blocks.size to r.text, 1 to "Do it.", "block inside a fence: fence removed too")
        r = EditBlocks.scan("Do it.\napp.js\n```js\n<<<<<<< SEARCH\na\n=======\nb\n>>>>>>> REPLACE\n```\n", known, null)
        eq(Triple(r.blocks.size, r.blocks.firstOrNull()?.path, r.text), Triple(1, "app.js", "Do it."), "path above the fence")
        r = EditBlocks.scan(blk("**style.css**", "a", "b") + "\n", known, null)
        eq(r.blocks[0].path, "style.css", "path wrapped in bold")
        r = EditBlocks.scan(blk("File: app.js", "a", "b"), known, null)
        eq(r.blocks[0].path, "app.js", "File: prefix tolerated")
        r = EditBlocks.scan(blk("app.js", "a", "b") + "\n<<<<<<< SEARCH\nc\n=======\nd\n>>>>>>> REPLACE\n", known, null)
        eq(r.blocks.map { it.path }, listOf("app.js", "app.js"), "second block with no path reuses the last file")
        r = EditBlocks.scan("Sure:\n<<<<<<< SEARCH\na\n=======\nb\n>>>>>>> REPLACE", known, "app.js")
        eq(r.blocks[0].path to r.text, "app.js" to "Sure:", "prose line is not a path; falls back to the open file")
        r = EditBlocks.scan("Sure\n<<<<<<< SEARCH\na\n=======\nb\n>>>>>>> REPLACE", known, "app.js")
        eq(r.blocks[0].path, "app.js", "a bare word with no dot or slash is not a path")
        r = EditBlocks.scan("Makefile\n<<<<<<< SEARCH\na\n=======\nb\n>>>>>>> REPLACE", known, "app.js")
        eq(r.blocks[0].path, "Makefile", "a known file without an extension is a path")
        r = EditBlocks.scan("../etc/passwd\n<<<<<<< SEARCH\na\n=======\nb\n>>>>>>> REPLACE", known, "app.js")
        eq(r.blocks[0].path, "app.js", "path with .. is refused")
        r = EditBlocks.scan("/etc/passwd\n<<<<<<< SEARCH\na\n=======\nb\n>>>>>>> REPLACE", known, "app.js")
        eq(r.blocks[0].path, "app.js", "absolute path is refused")
        r = EditBlocks.scan("Working on it.\napp.js\n<<<<<<< SEARCH\nfoo\n=====", known, null)
        eq(Triple(r.blocks.size, r.text, r.names), Triple(0, "Working on it.", listOf("app.js")), "unfinished block while streaming: hidden, not returned, name reported")
        r = EditBlocks.scan("app.js\n<<<<<<< SEARCH\nfoo\n=======\nbar\n>>>>>>> RE", known, null)
        eq(r.blocks.size to r.text, 0 to "", "cut off before the closing marker: hidden")
        r = EditBlocks.scan("No edits here, just an answer.", known, null)
        eq(r.blocks.size to r.text, 0 to "No edits here, just an answer.", "reply without blocks is untouched")
        r = EditBlocks.scan("app.js\r\n<<<<<<< SEARCH\r\na\r\n=======\r\nb\r\n>>>>>>> REPLACE\r\n", known, null)
        eq(Triple(r.blocks.size, r.blocks[0].search, r.blocks[0].replace), Triple(1, "a", "b"), "CRLF replies parse")
        r = EditBlocks.scan("<<<<<<<< SEARCH\na\n=========\nb\n>>>>>>>>> REPLACE", known, "app.js")
        eq(r.blocks.size, 1, "marker lengths 5 to 9 accepted")
        r = EditBlocks.scan(blk("app.js", "a", "b") + "\nand\n" + blk("style.css", "c", "d") + "\nBye.", known, null)
        eq(r.blocks.map { it.path } to r.text, listOf("app.js", "style.css") to "and\nBye.", "two files in one reply")
    }

    private fun applying() {
        val f = "const a = 1;\nfunction go() {\n  return a;\n}\n"
        var x = EditBlocks.apply(f, EditBlock("a", "return a;", "return a + 1;"))
        eq(x.text, "const a = 1;\nfunction go() {\n  return a + 1;\n}\n", "exact single match")
        x = EditBlocks.apply("x\nx\n", EditBlock("a", "x", "y"))
        ok(x.error?.contains("more than once") == true, "two matches: refused, asks for more context")
        x = EditBlocks.apply(f, EditBlock("a", "nope", "y"))
        ok(x.error?.contains("not found") == true, "no match: clear error")
        x = EditBlocks.apply("a  \nb\t\nc\n", EditBlock("a", "a\nb", "A\nB"))
        eq(x.text, "A\nB\nc\n", "trailing-space differences tolerated")
        x = EditBlocks.apply("function f() {\n    if (x) {\n        run();\n    }\n}\n",
            EditBlock("a", "if (x) {\n    run();\n}", "if (x) {\n    run();\n    log();\n}"))
        eq(x.text, "function f() {\n    if (x) {\n        run();\n        log();\n    }\n}\n", "wrong indentation: matched and re-indented")
        x = EditBlocks.apply("    a();\n    b();\n", EditBlock("a", "a();\nb();", "a();\nc();\nb();"))
        eq(x.text, "    a();\n    c();\n    b();\n", "re-indent adds the file indentation to every replaced line")
        x = EditBlocks.apply("x = 1\n", EditBlock("a", "x = 1", "price = \"$&\" \\1 \$1"))
        eq(x.text, "price = \"$&\" \\1 \$1\n", "dollar and backslash patterns in the replacement stay literal")
        x = EditBlocks.apply("a\nb\n", EditBlock("a", "a\nb", ""))
        eq(x.text, "\n", "empty replacement deletes the lines")
    }

    private fun resolving() {
        val f = "const a = 1;\nfunction go() {\n  return a;\n}\n"
        val store = mapOf("app.js" to f, "style.css" to "body { color: red; }\n", "empty.txt" to "")
        val read = { p: String -> store[p] }
        val noBin = { _: String -> false }
        var res = EditBlocks.resolve(listOf(
            EditBlock("app.js", "const a = 1;", "const a = 2;"),
            EditBlock("app.js", "return a;", "return a * 2;"),
            EditBlock("style.css", "red", "teal")), read, noBin, "app.js")
        eq(res.edits.map { it.path }, listOf("app.js", "style.css"), "edits grouped per file")
        ok(res.edits[0].content.contains("const a = 2;") && res.edits[0].content.contains("return a * 2;"), "two blocks on one file both land")
        eq(res.failed, emptyList<String>(), "no failures")
        res = EditBlocks.resolve(listOf(EditBlock("app.js", "zzz", "y"), EditBlock("app.js", "const a = 1;", "const a = 3;")), read, noBin, "app.js")
        ok(res.failed.size == 1 && res.edits.size == 1, "one bad block does not stop the good one")
        ok(res.failed[0].startsWith("app.js:"), "failure names the file")
        res = EditBlocks.resolve(listOf(EditBlock("main.js", "const a = 1;", "const a = 9;")), read, noBin, "app.js")
        eq(res.edits.map { it.path }, listOf("app.js"), "placeholder file name is redirected to the open file")
        res = EditBlocks.resolve(listOf(EditBlock("new.js", "", "hello")), read, noBin, "app.js")
        eq(res.edits.map { it.path to it.content }, listOf("new.js" to "hello\n"), "empty SEARCH creates a file and adds a final newline")
        res = EditBlocks.resolve(listOf(EditBlock("app.js", "", "oops")), read, noBin, "app.js")
        ok(res.failed.size == 1 && res.edits.isEmpty(), "empty SEARCH on an existing file is refused")
        res = EditBlocks.resolve(listOf(EditBlock("empty.txt", "", "filled")), read, noBin, "app.js")
        eq(res.edits.map { it.path to it.content }, listOf("empty.txt" to "filled\n"), "empty SEARCH on an empty file is allowed")
        res = EditBlocks.resolve(listOf(EditBlock("logo.png", "a", "b")), read, { it == "logo.png" }, "app.js")
        ok(res.failed.size == 1 && res.failed[0].contains("binary"), "binary files are refused")
        res = EditBlocks.resolve(listOf(EditBlock("new.js", "", "a"), EditBlock("new.js", "a", "b")), read, noBin, "app.js")
        eq(res.edits.map { it.path to it.content }, listOf("new.js" to "b\n"), "a block can edit a file created earlier in the same reply")
    }

    private fun replies() {
        val files = mapOf("app.js" to "console.log('clicked');\nlet n = 0;\n", "style.css" to "body { color: red; }\n")
        val reply = "Two changes.\n\n" + blk("app.js", "let n = 0;", "let n = 1;") + "\n" +
            "```file:notes.md\n# Notes\n```\n\n```run\nls -la\n# a comment\ncat notes.md\n```\n" + blk("style.css", "zzz", "y")
        val p = ReplyParser.parse(reply, files, emptySet(), "app.js")
        eq(p.text, "Two changes.", "reply text keeps only the explanation")
        eq(p.edits.map { it.path }.sorted(), listOf("app.js", "notes.md"), "block edit and file fence both become edits")
        eq(p.edits.first { it.path == "notes.md" }.content, "# Notes\n", "file fence content gets a final newline")
        eq(p.cmds, listOf("ls -la", "cat notes.md"), "run fence lines become commands, comments skipped")
        ok(p.failed.size == 1 && p.failed[0].startsWith("style.css:"), "the block that does not match is reported")
        val q = ReplyParser.parse("```file:app.js\nA\n```\n" + blk("app.js", "A", "B"), files, emptySet(), "app.js")
        eq(q.edits.map { it.path to it.content }, listOf("app.js" to "B\n"), "a block applies on top of a whole-file fence in the same reply")
        val bin = ReplyParser.parse(blk("logo.png", "a", "b"), files, setOf("logo.png"), "app.js")
        ok(bin.failed.size == 1, "binary targets are reported")
        val v = ReplyParser.preview("Working.\n\n```file:app.js\nlet x", files.keys, "app.js")
        eq(v.text to v.names, "Working." to listOf("app.js"), "streaming: unfinished file fence hidden, name shown")
        val w = ReplyParser.preview("Working.\n\napp.js\n<<<<<<< SEARCH\nlet n", files.keys, "app.js")
        eq(w.text to w.names, "Working." to listOf("app.js"), "streaming: unfinished edit block hidden, name shown")
        val x = ReplyParser.preview("Just words, no edits.", files.keys, "app.js")
        eq(x.text to x.names, "Just words, no edits." to emptyList<String>(), "streaming: plain text passes through")
    }

    private fun context() {
        val big = (0 until 5000).joinToString("") { "// filler line $it\n" } + "function bigThing() {}\n"
        val files = listOf(
            ProjectFile("index.html", "<button id=\"buy-now\">x</button>\n", false),
            ProjectFile("app.js", "const out = 1;\n", false),
            ProjectFile("cart.js", "function addToCart(item) {\n  return item;\n}\n", false),
            ProjectFile("misc.js", "function unrelatedThing() {}\n", false),
            ProjectFile("big.js", big, false),
            ProjectFile("logo.png", null, true)
        )
        var s = ContextRanker.build(files, "index.html", "fix addToCart so it checks the item", 0, true)
        val ia = s.indexOf("--- index.html ---")
        val ic = s.indexOf("--- cart.js ---")
        val im = s.indexOf("--- misc.js ---")
        ok(ia in 0 until ic && ic < im, "open file first, then the matching file, then the rest ($ia, $ic, $im)")
        ok(s.contains("--- app.js ---") && s.contains("--- misc.js ---"), "small files still fit whole next to the cut one")
        ok(s.contains("--- big.js (first") && s.contains("truncated; do not edit lines you cannot see"), "a file too big for the budget is cut and labelled")
        ok(s.contains("Other files and what they define") && s.contains("big.js: bigThing"), "the symbol map lists what the cut file defines")
        ok(s.contains("logo.png (binary)"), "binary files are listed by name")
        ok(s.length < 70000, "prompt stays inside the budget (${s.length} chars)")
        s = ContextRanker.build(files, "index.html", "the unrelatedThing function is wrong", 0, true)
        ok(s.indexOf("--- misc.js ---") in 1 until s.indexOf("--- cart.js ---"), "ranking follows the request")
        s = ContextRanker.build(files, "index.html", "explain this", 0, false)
        ok(s.contains("--- index.html ---") && !s.contains("--- cart.js ---") && s.contains("cart.js: addToCart"), "current-file mode: open file in full, others only as names")
        s = ContextRanker.build(files, "index.html", "change cart.js so it checks the item", 0, false)
        ok(s.contains("--- cart.js ---") && !s.contains("--- misc.js ---"), "current-file mode: a file named in the request is added")
        s = ContextRanker.build(files, "index.html", "x", 6000, true)
        ok(s.length < 6500, "small budget is respected (${s.length} chars)")
        eq(ContextRanker.symTerms("loginButton_click"), listOf("login", "button", "click"), "identifier split into words")
        eq(ContextRanker.symbols("a.js", "function boot() {}\nconst helper = () => {};\nclass Cart {}\n"), listOf("Cart", "boot", "helper"), "symbols found in JS")
        eq(ContextRanker.symbols("a.html", "<button id=\"btn-save\"></button>"), listOf("btn-save"), "ids found in HTML")
        eq(ContextRanker.symbols("a.css", ".card { } .card:hover { } nav .item, .menu { }"), listOf("card", "item", "menu"), "classes found in CSS")
        eq(ContextRanker.symbols("A.kt", "class Foo {\n  fun bar() {}\n  val baz = 1\n}\nobject Qux"), listOf("Foo", "Qux", "bar", "baz"), "symbols found in Kotlin")
        val kw = ContextRanker.keywords("Make the loginButton bigger")
        ok(kw.contains("loginbutton") && kw.contains("make") && !kw.contains("to"), "keywords are lowercase words of 3+ letters")
        eq(ContextRanker.score("a.js", "x", emptySet(), emptySet()), 0, "no keywords: score 0")
    }

    private fun diffs() {
        val ops = Diff.lines("a\nb\nc\n", "a\nB\nc\nd\n")!!
        eq(ops.map { it.tag }.joinToString(""), " -+ +", "line diff marks the change and the addition")
        eq(Diff.lines("a\n", "a\n")!!.all { it.tag == ' ' }, true, "identical text: no changes")
        eq(Diff.lines("", "x\ny\n")!!.map { it.tag }.joinToString(""), "++", "new file: all lines added")
        val rows = Diff.compact(Diff.lines((1..20).joinToString("\n") + "\n", (1..20).joinToString("\n").replace("10", "ten") + "\n")!!)
        ok(rows.any { it.tag == '~' && it.skipped == 8 } && rows.count { it.tag == '+' } == 1, "compact diff skips far-away unchanged lines: " + rows.map { "${it.tag}${it.skipped}" })
        val a = (1..700).joinToString("\n") { "l$it" }
        val b = (1..700).joinToString("\n") { "m$it" }
        ok(Diff.lines(a, b) == null, "very large change: comparison skipped")
    }

    private fun json() {
        val v = MiniJson.obj(MiniJson.parse("""{"a":[1,2.5,"x\nA\"q"],"b":{"c":true,"d":null},"e":-3e2}"""))!!
        eq(MiniJson.list(v["a"]), listOf(1.0, 2.5, "x\nA\"q"), "arrays, numbers and escapes")
        eq(MiniJson.obj(v["b"])!!["c"], true, "nested object and booleans")
        eq(v["e"], -300.0, "exponent numbers")
        eq(MiniJson.write(mapOf("k" to listOf("a\"b", 1, true, null), "n" to 2.0)), """{"k":["a\"b",1,true,null],"n":2}""", "writer escapes and formats")
        eq(MiniJson.quote("tab\there\u0001"), "\"tab\\there\\u0001\"", "control characters escaped")
        eq(MiniJson.parse(MiniJson.write(mapOf("s" to "line1\nline2 \"q\" \\ end"))).let { MiniJson.obj(it)!!["s"] }, "line1\nline2 \"q\" \\ end", "write then parse round trip")
        var failed = false
        try { MiniJson.parse("{\"a\":") } catch (e: MiniJson.JsonError) { failed = true }
        ok(failed, "bad JSON throws a JsonError")
        failed = false
        try { MiniJson.parse("[1] x") } catch (e: MiniJson.JsonError) { failed = true }
        ok(failed, "text after JSON is an error")
    }

    private fun sse() {
        val oa = SseDecoder(false)
        eq(oa.feed("""data: {"choices":[{"delta":{"content":"Hel"}}]}"""), "Hel", "OpenAI-style delta")
        eq(oa.feed("data: [DONE]"), "", "[DONE] carries no text")
        eq(oa.feed(": keep-alive"), "", "comment lines ignored")
        eq(oa.feed("data: not json"), "", "bad JSON ignored")
        eq(oa.feed("""data: {"choices":[{"delta":{}}]}"""), "", "empty delta")
        val an = SseDecoder(true)
        eq(an.feed("""data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hi"}}"""), "Hi", "Anthropic-style delta")
        eq(an.feed("""data: {"type":"message_start","message":{}}"""), "", "other Anthropic events ignored")
        var msg = ""
        try { oa.feed("""data: {"error":{"message":"Rate limit reached"}}""") } catch (e: ModelException) { msg = e.message ?: "" }
        eq(msg, "Rate limit reached", "error event becomes an exception with the provider's message")
        eq(SseDecoder.errorMessage("""{"error":{"message":"Invalid API key"}}"""), "Invalid API key", "error body message")
        eq(SseDecoder.errorMessage("""{"error":"plain"}"""), "plain", "error body as a string")
        eq(SseDecoder.errorMessage("<html>"), null, "non-JSON error body gives null")
    }

    private fun highlight() {
        val js = "// hi\nconst s = 'x'; let n = 42; /* c */"
        val sp = Highlighter.spans("a.js", js)
        fun of(k: TokenKind) = sp.filter { it.kind == k }.map { js.substring(it.start, it.end) }
        eq(of(TokenKind.COMMENT), listOf("// hi", "/* c */"), "JS comments")
        eq(of(TokenKind.STRING), listOf("'x'"), "JS strings")
        eq(of(TokenKind.KEYWORD), listOf("const", "let"), "JS keywords")
        eq(of(TokenKind.NUMBER), listOf("42"), "JS numbers")
        val html = "<p class=\"a\">hi</p><!-- x -->"
        val hs = Highlighter.spans("a.html", html)
        eq(hs.filter { it.kind == TokenKind.TAG }.map { html.substring(it.start, it.end) }, listOf("<p", ">", "</p", ">"), "HTML tags")
        eq(hs.filter { it.kind == TokenKind.COMMENT }.map { html.substring(it.start, it.end) }, listOf("<!-- x -->"), "HTML comments")
        val py = "def f():\n    \"\"\"doc\nstring\"\"\"\n    return None # end"
        val ps = Highlighter.spans("a.py", py)
        ok(ps.any { it.kind == TokenKind.STRING && py.substring(it.start, it.end).startsWith("\"\"\"") }, "Python triple-quoted strings")
        ok(ps.any { it.kind == TokenKind.COMMENT && py.substring(it.start, it.end) == "# end" }, "Python comments")
        val md = "# Title\ntext `code` more"
        val ms = Highlighter.spans("a.md", md)
        eq(ms.map { md.substring(it.start, it.end) }, listOf("# Title", "`code`"), "Markdown headings and code")
        eq(Highlighter.spans("a.json", "{\"a\": true, \"b\": 12}").map { it.kind }, listOf(TokenKind.STRING, TokenKind.KEYWORD, TokenKind.STRING, TokenKind.NUMBER), "JSON tokens")
        eq(Highlighter.spans("notes.xyz", "const a = 1").isEmpty(), true, "unknown file type: no colours")
        eq(Highlighter.spans("a.js", "x".repeat(130_000)).isEmpty(), true, "very large file: no colours")
        val css = "a { color: #fff; margin: 4px; } @media (x) {}"
        eq(Highlighter.spans("a.css", css).map { css.substring(it.start, it.end) }, listOf("#fff", "4px", "@media"), "CSS colours, lengths and at-rules")
    }

    private fun misc() {
        eq(Providers.customUrl("http://127.0.0.1:11434/v1"), "http://127.0.0.1:11434/v1/chat/completions", "custom address gets /chat/completions")
        eq(Providers.customUrl("http://x/v1/chat/completions"), "http://x/v1/chat/completions", "full address kept")
        eq(Providers.customUrl("not a url"), null, "bad address refused")
        eq(Providers.byId("nope").id, "demo", "unknown provider falls back to demo")
        ok(Providers.all.map { it.id }.toSet().size == Providers.all.size, "provider ids are unique")
        ok(Providers.all.filter { it.kind.name == "OPENAI" && !it.custom }.all { it.url.startsWith("https://") && it.model.isNotEmpty() }, "built-in providers have an address and a model")
        ok(Prompts.system(true).contains("Agent mode is on") && !Prompts.system(false).contains("Agent mode is on"), "agent note only in agent mode")
        ok(Prompts.system(false).contains("<<<<<<< SEARCH") && Prompts.system(false).contains("file:<path>"), "system prompt teaches blocks and whole-file fences")
        val fix = Prompts.fixRequest(listOf("app.js: not found"), listOf("TypeError x"))
        ok(fix.contains("could not be applied") && fix.contains("- app.js: not found") && fix.contains("produced these errors") && fix.contains("- TypeError x"), "fix request lists both kinds of problem")
        eq(Prompts.fixRequest(emptyList(), listOf("E")).contains("could not be applied"), false, "fix request omits the block part when there is none")
        eq(Sample.files.keys.toList(), listOf("index.html", "style.css", "app.js"), "sample project has three files")
        ok(Sample.demoAgentReply(false).contains("getElementById('button')") && Sample.demoAgentReply(true).contains("getElementById('btn')"), "agent demo has a typo, then the fix")
        val parsed = ReplyParser.parse(Sample.demoReply(), Sample.files, emptySet(), "app.js")
        eq(parsed.edits.map { it.path }, listOf("app.js", "style.css"), "demo reply parses into two whole-file edits")
    }

    // ---- round 2: GitHub sync, investigation loop, local servers, bounded runner ----

    private fun git() {
        eq(GitCore.blobSha(ByteArray(0)), "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", "blob sha of the empty file")
        eq(GitCore.blobSha("hello\n".toByteArray()), "ce013625030ba8dba906f756967f9e9ca394464a", "blob sha of hello")
        eq(GitRepo.parse("dbal2969-ai/Test-Coder2.2"), GitRepo("dbal2969-ai", "Test-Coder2.2"), "owner/repo parsed")
        eq(GitRepo.parse("https://github.com/dbal2969-ai/Test-Coder2.2.git"), GitRepo("dbal2969-ai", "Test-Coder2.2"), "https URL parsed")
        eq(GitRepo.parse("github.com/a/b/"), GitRepo("a", "b"), "bare github.com URL parsed")
        eq(GitRepo.parse("a/b/c"), null, "three parts refused")
        eq(GitRepo.parse("../x"), null, "dot-dot refused")
        eq(GitCore.branch(" main "), "main", "branch trimmed")
        eq(GitCore.branch("feature/x"), "feature/x", "branch with slash")
        ok(listOf("a/../c", "/x", "x/", "a//b", "x.lock", ".hidden", "a b", "").all { GitCore.branch(it) == null }, "bad branches refused")
        ok(GitCore.tokenLooksValid("github_pat_" + "A1".repeat(20)) && GitCore.tokenLooksValid("ghp_" + "x".repeat(36)), "tokens accepted")
        ok(!GitCore.tokenLooksValid("hunter2") && !GitCore.tokenLooksValid("https://github.com/" + "x".repeat(30)), "password and URL refused as tokens")
        ok(GitCore.pathOk(".github/workflows/a.yml") && !GitCore.pathOk(".git/config") && !GitCore.pathOk("../x") && !GitCore.pathOk("a//b"), "path rules")
        eq(GitCore.encodePath("my dir/a+b.txt"), "my%20dir/a%2Bb.txt", "path encoded per segment")
        val tb = MiniJson.obj(MiniJson.parse(GitCore.treeBody("T", listOf(GitCore.TreeEntry("a", "S"), GitCore.TreeEntry("b", null)))))!!
        val entries = MiniJson.list(tb["tree"])!!.map { MiniJson.obj(it)!! }
        ok(tb["base_tree"] == "T" && entries[0]["sha"] == "S" && entries[1].containsKey("sha") && entries[1]["sha"] == null, "tree body: null sha deletes")
        eq(MiniJson.obj(MiniJson.parse(GitCore.commitBody("m", "T", "P")))!!["parents"], listOf("P"), "commit body has the parent")
        eq(MiniJson.obj(MiniJson.parse(GitCore.refBody("C")))!!["force"], false, "ref update never forces")
        eq(String(java.util.Base64.getDecoder().decode(MiniJson.obj(MiniJson.parse(GitCore.blobBody("hi".toByteArray())))!!["content"] as String)), "hi", "blob body is base64")
        val tree = GitCore.parseTree("""{"truncated":false,"tree":[{"path":"a.txt","mode":"100644","type":"blob","sha":"1","size":3},{"path":"d","type":"tree","sha":"2"},{"path":"l","mode":"120000","type":"blob","sha":"3"},{"path":"sub","mode":"160000","type":"commit","sha":"4"}]}""")
        eq(tree.files.map { it.path }, listOf("a.txt"), "tree parse keeps regular files only")
        eq(GitCore.refSha("""{"object":{"sha":"abc"}}"""), "abc", "ref sha parsed")
        ok(GitCore.errorMessage(401, "", "x").contains("token"), "401 explained")
        ok(GitCore.errorMessage(422, """{"message":"Update is not a fast forward"}""", "x").contains("Pull first"), "non-fast-forward explained")
        ok(GitCore.errorMessage(403, """{"message":"API rate limit exceeded"}""", "x").contains("rate limit"), "rate limit explained")
        ok(GitCore.errorMessage(404, "", "open it").contains("open it"), "404 names the action")
        val snap = SyncSnapshot("a/b", "main", "C", mapOf("x" to "1", "y/z" to "2"))
        eq(SyncSnapshot.fromJson(snap.toJson()), snap, "snapshot round trip")
        eq(SyncSnapshot.fromJson("nope"), null, "bad snapshot ignored")
        eq(Changes.compute(mapOf("a" to "1", "b" to "2", "c" to "3"), mapOf("a" to "1", "b" to "9", "d" to "4")),
            listOf(FileChange("b", ChangeKind.MODIFIED), FileChange("c", ChangeKind.DELETED), FileChange("d", ChangeKind.ADDED)), "changes: modified, deleted, added")
        // pull planning: base -> remote changed x, deleted y, added n; local changed z (kept) and x (conflict)
        val plan = Changes.planPull(mapOf("x" to "1", "y" to "2", "z" to "3"), mapOf("x" to "1b", "z" to "3", "n" to "5"), mapOf("x" to "1L", "y" to "2", "z" to "3L"), false)
        eq(Triple(plan.write, plan.delete, plan.conflicts), Triple(listOf("n"), listOf("y"), listOf("x")), "pull plan: conflict held back, local-only edit kept")
        eq(Changes.planPull(mapOf("x" to "1"), mapOf("x" to "1b"), mapOf("x" to "1L"), true).write, listOf("x"), "overwrite takes the remote version")
        eq(Changes.planPull(mapOf("y" to "2"), emptyMap(), mapOf("y" to "2L"), false).conflicts, listOf("y"), "deleted remotely but edited here is a conflict")
    }

    private fun tempDir(name: String): java.io.File {
        val d = java.nio.file.Files.createTempDirectory("pocketide-$name").toFile()
        d.deleteOnExit()
        return d
    }

    private fun sync() {
        val gh = FakeGitHub()
        val dir = tempDir("proj")
        val state = tempDir("state")
        val folder = FolderFiles(dir)
        folder.write("README.md", "# hi\n".toByteArray())
        folder.write("a.txt", "one\n".toByteArray())
        folder.write(".github/workflows/ci.yml", "on: push\n".toByteArray())
        val s = GitSync(gh, folder, state, GitRepo("me", "repo"), "main")
        eq(s.changes(), null, "never synced: no change list")
        val first = s.push("init")
        eq(gh.files().keys.sorted(), listOf(".github/workflows/ci.yml", "README.md", "a.txt"), "first push into an empty repo uploads everything, dot-folders included")
        ok(first.commit != null && s.snapshot()?.commit == gh.head, "snapshot follows the pushed commit")
        eq(s.changes(), emptyList<FileChange>(), "no changes right after a push")

        folder.write("a.txt", "two\n".toByteArray())
        folder.write("b.txt", "new\n".toByteArray())
        folder.delete("README.md")
        eq(s.changes()!!.map { "${it.kind.letter} ${it.path}" }, listOf("D README.md", "M a.txt", "A b.txt"), "changes listed")
        eq(String(s.baseBytes("a.txt")!!), "one\n", "synced version available offline")
        ok(s.revert("README.md") && String(folder.read("README.md")!!) == "# hi\n", "revert restores a deleted file")
        ok(s.revert("b.txt") && folder.read("b.txt") == null, "revert removes an added file")
        folder.write("b.txt", "new\n".toByteArray())
        val pushed = s.push("edit")
        eq(pushed.changes.size, 2, "second push sends two changes")
        eq(String(gh.files().getValue("a.txt")), "two\n", "remote has the edit")
        eq(s.push("again").commit, null, "nothing to push")

        gh.commitDirect(mapOf("a.txt" to "three\n".toByteArray(), "c.txt" to "c\n".toByteArray(), "b.txt" to null))
        val refused = try { s.push("late"); "pushed" } catch (e: GitHubException) { e.message ?: "" }
        ok(refused.contains("newer commits"), "push refused when GitHub moved on")
        val p1 = s.pull(false, false)
        ok(p1.applied && String(folder.read("a.txt")!!) == "three\n" && folder.read("c.txt") != null && folder.read("b.txt") == null, "pull applies remote edits, additions and deletions")

        folder.write("c.txt", "mine\n".toByteArray())
        gh.commitDirect(mapOf("c.txt" to "theirs\n".toByteArray()))
        val p2 = s.pull(false, false)
        ok(!p2.applied && p2.conflicts == listOf("c.txt") && String(folder.read("c.txt")!!) == "mine\n", "conflict reported, nothing overwritten")
        val p3 = s.pull(true, false)
        ok(p3.applied && String(folder.read("c.txt")!!) == "theirs\n", "overwrite takes GitHub's version")

        val other = FolderFiles(tempDir("other"))
        other.write("stray.txt", "x".toByteArray())
        val s2 = GitSync(gh, other, tempDir("state2"), GitRepo("me", "repo"), "main")
        val refused2 = try { s2.push("x"); "pushed" } catch (e: GitHubException) { e.message ?: "" }
        ok(refused2.contains("Pull first"), "push from an unsynced copy refused")
        s2.pull(true, true)
        eq(other.list(), gh.files().keys.sorted(), "first pull with Replace makes an exact copy")
        val wrongBranch = GitSync(gh, other, tempDir("state3"), GitRepo("me", "repo"), "dev")
        val m = try { wrongBranch.pull(false, false); "" } catch (e: GitHubException) { e.message ?: "" }
        ok(m.contains("branch dev does not exist"), "missing branch explained")
    }

    private fun actions() {
        val reply = "Let me look.\nACTION: read app.js\n  ACTION: grep `getElementById`\nACTION: list\nACTION: run ls -la\nACTION: read extra.js"
        val a = AgentActions.find(reply)
        eq(a, listOf(AgentAction(ActionVerb.READ, "app.js"), AgentAction(ActionVerb.GREP, "getElementById"), AgentAction(ActionVerb.LIST, ""), AgentAction(ActionVerb.RUN, "ls -la")), "actions parsed, capped at four")
        eq(AgentActions.strip(reply), "Let me look.", "action lines stripped from the shown text")
        eq(AgentActions.find("action: READ index.html").map { it.verb }, listOf(ActionVerb.READ), "case-insensitive")
        eq(AgentActions.find("ACTION: read\nACTION: grep "), emptyList<AgentAction>(), "actions without an argument ignored")
        eq(AgentActions.find("The ACTION: read x line in prose"), emptyList<AgentAction>(), "mid-line mention is not an action")
        val files = mapOf("app.js" to "const a = 1;\nconsole.log(a);\n", "src/util.js" to "export const A = 2;\n", "logo.png" to null)
        val text = { p: String -> files[p] }
        ok(AgentActions.read(files.keys, text, "./app.js").startsWith("1: const a = 1;\n2: console.log(a);"), "read numbers lines")
        ok(AgentActions.read(files.keys, text, "util.js").contains("src/util.js"), "read suggests a near match")
        ok(AgentActions.read(files.keys, text, "logo.png").contains("binary"), "read of a binary file explained")
        val g = AgentActions.grep(files.keys, text, "CONST")
        eq(g.hits to g.text.lines().size, 2 to 2, "grep is case-insensitive with file:line hits")
        ok(g.text.contains("src/util.js:1:"), "grep shows file and line")
        eq(AgentActions.grep(files.keys, text, "zzz").hits, 0, "grep with no hits")
        eq(AgentActions.list(files.keys, { 7L }, "src").trim(), "src/util.js (7 bytes)", "list a folder")
        ok(AgentActions.list(files.keys, { 1L }, "").lines().size == 3, "list the whole project")
        val big = "x".repeat(20000)
        val f = AgentActions.followUp("add a button", listOf(AgentAction(ActionVerb.READ, "a") to big, AgentAction(ActionVerb.READ, "b") to big), 1)
        ok(f.length < 20000 && f.contains("Original request: add a button") && f.contains("5 rounds left"), "follow-up is capped and repeats the task")
        ok(AgentActions.followUp("t", emptyList(), AgentActions.MAX_ROUNDS).contains("last round"), "last round says so")
        ok(Prompts.system(false).contains("ACTION: read <path>"), "system prompt describes the actions")
        ok(AgentActions.runResult("ls", 2, "").startsWith("exit code 2\n(no output)"), "run result format")
        ok(AgentAction(ActionVerb.GREP, "foo").label() == "Searched 'foo'" && AgentAction(ActionVerb.READ, "app.js").label() == "Read app.js", "chip labels")
    }

    private fun localProviders() {
        val llama = Providers.byId("llamacpp")
        ok(llama.custom && llama.keyless && llama.modelOptional && llama.defaultBase == "http://127.0.0.1:8080/v1", "llama.cpp provider: local, keyless, model optional")
        eq(Providers.byId("ollama").defaultBase, "http://127.0.0.1:11434/v1", "Ollama preset address")
        eq(Providers.modelsUrl("http://127.0.0.1:8080/v1"), "http://127.0.0.1:8080/v1/models", "models URL from a base")
        eq(Providers.modelsUrl("https://x/v1/chat/completions/"), "https://x/v1/models", "models URL from a chat URL")
        eq(Providers.modelsUrl("nope"), null, "models URL refuses junk")
        eq(Providers.parseModels("""{"object":"list","data":[{"id":"qwen"},{"id":"llama"}]}"""), listOf("qwen", "llama"), "OpenAI-style model list")
        eq(Providers.parseModels("""{"models":[{"name":"qwen2.5-coder:1.5b","model":"qwen2.5-coder:1.5b"}],"data":[{"id":"qwen2.5-coder:1.5b"}]}"""), listOf("qwen2.5-coder:1.5b"), "Ollama-style list merged without duplicates")
    }

    private fun runner() {
        val sh = listOf("/system/bin/sh", "/bin/sh").firstOrNull { java.io.File(it).canExecute() }
        if (sh == null) { println("SKIP  bounded runner (no shell)"); return }
        val dir = tempDir("run")
        fun go(cmd: String, timeoutMs: Long = 5000, cap: Int = 10000, cancelAfter: Long = -1): RunResult {
            val q = java.util.concurrent.LinkedBlockingQueue<RunResult>()
            val h = BoundedProcessRunner.start(listOf(sh, "-c", cmd), dir, emptyMap(), timeoutMs, cap, {}, { q.put(it) })
            if (cancelAfter >= 0) { Thread.sleep(cancelAfter); h.cancel() }
            return q.poll(20, java.util.concurrent.TimeUnit.SECONDS) ?: RunResult(-99, ExitReason.FAILED_TO_START, "hung", false, 0)
        }
        val a = go("echo out; echo err 1>&2; exit 0")
        ok(a.reason == ExitReason.OK && a.output.contains("out") && a.output.contains("err"), "stdout and stderr both captured")
        val b = go("exit 3")
        eq(b.code to b.reason, 3 to ExitReason.EXIT_CODE, "exit code reported")
        val t0 = System.currentTimeMillis()
        val c = go("sleep 30", timeoutMs = 600)
        ok(c.reason == ExitReason.TIMEOUT && c.code == 124 && System.currentTimeMillis() - t0 < 8000, "timeout kills a long command")
        // long output no longer stops the command: the start and the end are kept and it runs to its end (an unzip of
        // thousands of files used to be killed halfway); an endless flood still ends at the time limit
        val d = go("yes hello", timeoutMs = 1500, cap = 3000)
        ok(d.reason == ExitReason.TIMEOUT && d.truncated && d.output.length <= 3000 + BoundedProcessRunner.TAIL_CHARS + 200, "endless output ends at the time limit, kept short")
        val d2 = go("i=0; while [ ${'$'}i -lt 20000 ]; do i=${'$'}((i+1)); echo line${'$'}i; done; echo done-all", timeoutMs = 20000, cap = 3000)
        ok(d2.reason == ExitReason.OK && d2.code == 0 && d2.truncated && d2.output.startsWith("line1\n") && d2.output.trimEnd().endsWith("done-all"), "long output: the command finishes; start and end kept")
        val e = go("sleep 30", cancelAfter = 300)
        eq(e.code to e.reason, 130 to ExitReason.CANCELLED, "cancel stops the command")
        val marker = "pocketide_child_" + System.nanoTime()
        val f = go("sh -c 'sleep 40; echo $marker' & sleep 40", timeoutMs = 700)
        Thread.sleep(300)
        val leftover = java.io.File("/proc").listFiles()!!.filter { it.name.all(Char::isDigit) }.any {
            try { java.io.File(it, "cmdline").readText().contains(marker) } catch (x: Exception) { false }
        }
        ok(f.reason == ExitReason.TIMEOUT && !leftover, "timeout also kills child processes")
        val g = BoundedProcessRunner.start(listOf("/no/such/binary"), dir, emptyMap(), 1000, 1000, {}, {})
        ok(!g.isCancelled, "missing binary does not throw")
    }
}
