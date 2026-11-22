package dev.pocketide.app

import dev.pocketide.app.core.Budget
import dev.pocketide.app.core.ModelPick
import dev.pocketide.app.core.ModelProfile
import dev.pocketide.app.core.ModelSize
import dev.pocketide.app.core.Profiles
import org.junit.Test

class ModelsTest {
    @Test fun all() { ModelsSelfTest.runAll(); check(ModelsSelfTest.failed == 0) { "${ModelsSelfTest.failed} model checks failed" } }
}

object ModelsSelfTest {
    var failed = 0
    private var passed = 0
    private fun ok(c: Boolean, name: String) { if (c) passed++ else { failed++; println("FAIL  $name") } }
    private fun <T> eq(a: T, b: T, name: String) { ok(a == b, name); if (a != b) println("      got: $a\n      want: $b") }

    fun runAll() {
        failed = 0; passed = 0
        val groq = listOf("whisper-large-v3", "llama-3.3-70b-versatile", "openai/gpt-oss-20b", "meta-llama/llama-guard-4-12b", "canopylabs/orpheus-v1-english", "allam-2-7b")
        eq(ModelPick.chatModels(groq), listOf("llama-3.3-70b-versatile", "openai/gpt-oss-20b", "allam-2-7b"), "chatModels drops speech and guard")
        eq(ModelPick.filter(groq, "llama 70"), listOf("llama-3.3-70b-versatile"), "filter all words")
        eq(ModelPick.filter(groq, "  "), groq, "blank filter")
        ok(ModelPick.looksMissing("The model `llama-3.3-70b` does not exist or you do not have access to it. (HTTP 404)"), "missing detected")
        ok(!ModelPick.looksMissing("Rate limit reached for model x (HTTP 429)"), "429 is not missing")
        ok(!ModelPick.looksMissing("Invalid API key (HTTP 401)"), "401 is not missing")
        eq(ModelPick.closest("llama-3.3-70b", groq).firstOrNull(), "llama-3.3-70b-versatile", "closest by prefix")
        eq(ModelPick.closest("GPT-OSS-20B", groq).firstOrNull(), "openai/gpt-oss-20b", "closest by contains")
        eq(ModelPick.closest("allam-2-7c", groq).firstOrNull(), "allam-2-7b", "closest by typo")
        eq(ModelPick.closest("zzzzzzzz", groq), emptyList(), "nothing close")
        ok(ModelSize.isWeakForFiles("allam-2-7b"), "7b weak")
        ok(ModelSize.isWeakForFiles("llama3.1:8b"), "8b weak")
        ok(!ModelSize.isWeakForFiles("openai/gpt-oss-20b"), "20b fine")
        ok(!ModelSize.isWeakForFiles("some-cloud-model"), "unknown not weak")
        ok(ModelSize.isFileEdit("add a heading to Document.docx"), "file edit detected")
        ok(ModelSize.isFileEdit("replace the word cat in my spreadsheet"), "spreadsheet edit")
        ok(!ModelSize.isFileEdit("explain what a closure is"), "plain question")
        val msg = "Rate limit reached for model `x` on tokens per minute (TPM): Limit 8000, Used 7131, Requested 2842."
        eq(Budget.tpmLimit(msg), 8000, "tpm parsed")
        ok(Budget.learnsTight(msg), "8000 is tight")
        ok(!Budget.learnsTight("Rate limit reached ... tokens per minute (TPM): Limit 300000, Used 1"), "300k not tight")
        ok(Budget.limits(true, false).contextChars < Budget.limits(false, false).contextChars, "tight smaller")
        val ps = Profiles.upsert(Profiles.upsert(emptyList(), ModelProfile("Cheap fast", "groq", "openai/gpt-oss-20b")), ModelProfile("cheap FAST", "groq", "allam-2-7b"))
        eq(ps.size, 1, "upsert replaces by name")
        val back = Profiles.parse(Profiles.write(ps + ModelProfile("Smart", "openai", "gpt-x", "https://h/v1")))
        eq(back.map { it.name }, listOf("cheap FAST", "Smart"), "round trip names")
        eq(back[1].base, "https://h/v1", "round trip base")
        eq(Profiles.remove(back, "smart").size, 1, "remove")
        eq(Profiles.parse("not json"), emptyList(), "bad json")
        eq(Profiles.shortName("openai/gpt-oss-20b", ""), "gpt-oss-20b", "short name")
        dev.pocketide.app.core.AppLog.clear()
        dev.pocketide.app.core.AppLog.add("model", "x: Bearer abcdefghijklmnop failed", 1000000L)
        ok(!dev.pocketide.app.core.AppLog.format(1060000L).contains("abcdefghijklmnop"), "log hides keys")
        ok(dev.pocketide.app.core.AppLog.format(1060000L).contains("1m ago"), "log ages")
        for (i in 1..100) dev.pocketide.app.core.AppLog.add("c", "n$i")
        eq(dev.pocketide.app.core.AppLog.entries().size, 80, "log keeps 80")
        ok(dev.pocketide.app.core.RemoteGuide.steps().contains("OLLAMA_HOST=0.0.0.0") && dev.pocketide.app.core.RemoteGuide.steps().contains("Tailscale"), "remote guide")
        ok(dev.pocketide.app.core.RemoteGuide.explain("Connection refused", "http://1.2.3.4:11434/v1").contains("listening"), "refused explained")
        ok(dev.pocketide.app.core.RemoteGuide.explain("x", "").contains("address"), "blank address")
        ok(dev.pocketide.app.core.Providers.byId("remote").id == "remote" && !dev.pocketide.app.core.LocalModels.isLocal("remote", "http://100.64.0.5:11434/v1"), "remote provider is not phone-local")
        val S = dev.pocketide.app.core.SupaSync
        eq(S.cleanUrl(" https://abcd.supabase.co/ "), "https://abcd.supabase.co", "clean url")
        eq(S.cleanUrl("https://abcd.supabase.co/rest/v1"), "https://abcd.supabase.co", "clean rest url")
        eq(S.cleanUrl("http://evil"), null, "http refused")
        ok(S.setupSql().contains("enable row level security") && S.setupSql().contains("auth.uid()"), "sql has row security")
        val pl = S.plan(mapOf("chat/a" to 5L, "chat/b" to 9L, "chat/c" to 1L), mapOf("chat/a" to 5L, "chat/b" to 3L, "chat/d" to 7L, "chat/c" to 4L))
        eq(pl.push, listOf("chat/b"), "plan push"); eq(pl.pull, listOf("chat/c", "chat/d"), "plan pull")
        val items = S.parseItems(S.upsertBody(listOf(dev.pocketide.app.core.SupaSync.Item("chat", "c1", 12L, "{\"x\":1}"))))
        eq(items.size, 1, "items round trip"); eq(items[0].data, "{\"x\":1}", "data kept"); eq(items[0].updated, 12L, "updated kept")
        eq(S.parseSession("{\"access_token\":\"t\",\"refresh_token\":\"r\"}")?.refresh, "r", "session")
        ok(S.parseSession("{}") == null, "no session")
        ok(S.explain(400, "{\"msg\":\"Invalid login credentials\"}").contains("Wrong email"), "bad login")
        ok(S.explain(404, "").contains("setup SQL"), "table missing")
        ok(S.rowQuery("chat", "a b").contains("name=eq.a%20b"), "row query encoded")
        // agent guard
        val g = dev.pocketide.app.core.RepetitionGuard()
        val block = "I will now check the project folder for the file you mentioned earlier in this conversation.\nThe file was not found anywhere in the project folder, so I am going to look again now.\n"
        var hit = false
        for (i in 1..4) if (g.append(block)) hit = true
        ok(hit, "repetition detected after three copies")
        val g2 = dev.pocketide.app.core.RepetitionGuard()
        ok(!g2.append("line one is here\nline two is different\nthird line again unique\n"), "no false alarm")
        val t = dev.pocketide.app.core.TaskTracker()
        ok(t.reminder() == null, "no plan, no reminder")
        t.update(listOf("Make page" to "done", "Add style" to "doing", "Test" to "todo"))
        eq(t.unfinished(), listOf("Add style", "Test"), "unfinished steps")
        eq(t.progress(), "1 of 3 steps done", "progress text")
        ok(t.reminder()?.contains("Add style") == true, "first reminder")
        ok(t.reminder() != null, "second reminder")
        ok(t.reminder() == null, "reminders are limited to two")
        val t2 = dev.pocketide.app.core.TaskTracker(); t2.update(listOf("A" to "done", "B" to "failed"))
        ok(t2.reminder() == null, "done and failed need no reminder")
        val lp = dev.pocketide.app.core.ToolLoop()
        ok(!lp.repeated("read_file", "{a}") && lp.repeated("read_file", "{a}"), "second identical file read flagged")
        lp.changed(); ok(!lp.repeated("read_file", "{a}"), "change resets the loop check")
        eq(dev.pocketide.app.core.Rounds.clamp(5000), 500, "rounds clamp high"); eq(dev.pocketide.app.core.Rounds.clamp(1), 6, "rounds clamp low")
        // verify
        val V = dev.pocketide.app.core.Verify
        eq(V.plan(listOf("package.json", "a.js"), { """{"scripts":{"test":"node a.js"}}""" })?.label, "npm test", "npm test found")
        eq(V.plan(listOf("package.json", "a.js"), { """{"scripts":{"test":"echo \"Error: no test specified\" && exit 1"}}""" })?.label, "syntax checks", "npm default ignored")
        eq(V.plan(listOf("app.py", "test_app.py"), { null })?.label, "Python tests", "python tests")
        eq(V.plan(listOf("go.mod", "a_test.go"), { null })?.label, "go test", "go test")
        eq(V.plan(listOf("Makefile", "a.c"), { "test:\n\techo hi" })?.label, "make test", "make test")
        eq(V.plan(listOf("index.html"), { null }), null, "html alone: nothing to run")
        ok(V.plan(listOf("a.py"), { null }, setOf("node")) == null, "missing tool: no plan")
        eq(V.outcome(0, "ok"), dev.pocketide.app.core.Verify.Outcome.PASS, "pass"); eq(V.outcome(1, "AssertionError"), dev.pocketide.app.core.Verify.Outcome.FAIL, "fail")
        eq(V.outcome(127, "sh: pytest: not found"), dev.pocketide.app.core.Verify.Outcome.MISSING_TOOL, "missing tool")
        val long = (1..200).joinToString("\n") { "ok line $it" } + "\nAssertionError: 3 != 4\nFAILED test_a"
        ok(V.trim(long).contains("AssertionError") && V.trim(long).length < 600, "trim keeps the error")
        ok(V.fixRequest(dev.pocketide.app.core.Verify.Plan("npm test", "npm test"), 1, "boom", 1, 3).contains("attempt 1 of 3"), "fix request")
        ok(V.relevant(listOf("a.py")) && !V.relevant(listOf("a.html", "x.md")), "relevant files")
        // usage
        val uc = dev.pocketide.app.core.UsageCollector(false)
        uc.feed(dev.pocketide.app.core.MiniJson.obj(dev.pocketide.app.core.MiniJson.parse("""{"usage":{"prompt_tokens":1000,"completion_tokens":200,"prompt_tokens_details":{"cached_tokens":600}}}"""))!!)
        eq(uc.result(), dev.pocketide.app.core.Usage(400, 200, 600, 0), "openai usage")
        val ua = dev.pocketide.app.core.UsageCollector(true)
        for (j in listOf("""{"type":"message_start","message":{"usage":{"input_tokens":50,"cache_read_input_tokens":900,"cache_creation_input_tokens":100,"output_tokens":1}}}""", """{"type":"message_delta","usage":{"output_tokens":120}}"""))
            ua.feed(dev.pocketide.app.core.MiniJson.obj(dev.pocketide.app.core.MiniJson.parse(j))!!)
        eq(ua.result(), dev.pocketide.app.core.Usage(50, 120, 900, 100), "anthropic usage")
        eq(ua.result().prompt, 1050L, "prompt total")
        val pr = dev.pocketide.app.core.Price(3.0, 15.0)
        val cst = dev.pocketide.app.core.Prices.cost(dev.pocketide.app.core.Usage(1_000_000, 100_000, 1_000_000, 0), pr)
        ok(Math.abs(cst - (3.0 + 1.5 + 0.3)) < 1e-9, "cost math with default cache rate")
        eq(dev.pocketide.app.core.Prices.parse(dev.pocketide.app.core.Prices.write(mapOf("a/b" to pr))), mapOf("a/b" to pr), "prices round trip")
        var rows = dev.pocketide.app.core.UsageLog.add(emptyList(), "2026-10-20", "groq/x", dev.pocketide.app.core.Usage(10, 5))
        rows = dev.pocketide.app.core.UsageLog.add(rows, "2026-10-20", "groq/x", dev.pocketide.app.core.Usage(1, 1))
        eq(rows.size, 1, "same day and model merge"); eq(rows[0].requests, 2, "request count"); eq(rows[0].usage.input, 11L, "merged input")
        eq(dev.pocketide.app.core.UsageLog.parse(dev.pocketide.app.core.UsageLog.write(rows))[0].usage, rows[0].usage, "log round trip")
        eq(dev.pocketide.app.core.UsageText.tokens(1500), "1.5k", "tokens text"); eq(dev.pocketide.app.core.UsageText.money(0.0042), "$0.0042", "money small")
        ok(dev.pocketide.app.core.UsageText.line(dev.pocketide.app.core.Usage(400, 200, 600), 0.5).contains("600 cached"), "line shows cache")
        dev.pocketide.app.core.UsageMeter.take(); dev.pocketide.app.core.UsageMeter.report("p", "m", dev.pocketide.app.core.Usage(5, 5))
        eq(dev.pocketide.app.core.UsageMeter.take().total, 10L, "meter exchange"); eq(dev.pocketide.app.core.UsageMeter.take().total, 0L, "meter resets")
        // chat export
        val CE = dev.pocketide.app.core.ChatExport
        val cs = dev.pocketide.app.core.ChatSession("c1", "Fix the login page!", 1_700_000_000_000L, 1_700_000_100_000L)
        cs.entries.add(dev.pocketide.app.core.ChatEntry("user", "Fix the login page, my key is sk-abcd1234567890XYZ", "", 0))
        cs.entries.add(dev.pocketide.app.core.ChatEntry("assistant", "Done. I fixed it.", "Edited login.js, style.css\nRan npm test"))
        cs.entries.add(dev.pocketide.app.core.ChatEntry("user", "auto message", "auto", 0))
        cs.entries.add(dev.pocketide.app.core.ChatEntry("user", "Now add a logout button", "", 1))
        cs.entries.add(dev.pocketide.app.core.ChatEntry("assistant", "Added it.", "Edited login.js"))
        val md = CE.markdown(cs, listOf("Model: Groq x"))
        ok(md.startsWith("# Fix the login page!") && md.contains("### You") && md.contains("### Assistant") && md.contains("Model: Groq x"), "markdown structure")
        ok(!md.contains("abcd1234567890XYZ"), "markdown hides keys")
        ok(md.contains("(1 image attached)") && md.contains("Automatic follow-up"), "images and automatic messages")
        ok(!CE.markdown(cs, commands = false).contains("Ran npm test"), "commands can be left out")
        eq(CE.filesChanged(cs), listOf("login.js", "style.css"), "files changed, no repeats")
        eq(CE.commandsRun(cs), listOf("npm test"), "commands run")
        val sm = CE.summary(cs)
        ok(sm.contains("## Goal") && sm.contains("## Files changed") && sm.contains("- login.js") && sm.contains("`npm test`") && sm.contains("Added it."), "free summary sections")
        ok(!sm.contains("abcd1234567890XYZ"), "summary hides keys")
        eq(CE.slug("Fix the login page!"), "fix-the-login-page", "slug"); eq(CE.slug("!!!"), "chat", "slug empty")
        ok(CE.path(cs).startsWith("chat-logs/fix-the-login-page-") && CE.path(cs, "summary").contains("summary-"), "paths")
        val sp = CE.summaryPrompt(cs)
        ok(sp.contains("## Next steps") && sp.contains("USER: Now add a logout button") && !sp.contains("auto message"), "summary prompt")
        ok(CE.continuePrompt("# S").contains("# S") && CE.estimateTokens("abcdefgh") == 2, "continue prompt and estimate")
        eq(dev.pocketide.app.core.ChatSession.fromJson(cs.toJson())?.entries?.size, 5, "json export restores")
        // prompt cache
        val PC = dev.pocketide.app.core.PromptCache
        val sys = PC.systemBlocks("rules")
        eq(sys.size, 1, "one system block"); eq((sys[0]["cache_control"] as Map<*, *>)["type"], "ephemeral", "system block marked")
        val msgs = listOf(mapOf("role" to "user", "content" to "hello"), mapOf("role" to "assistant", "content" to "hi"), mapOf("role" to "user", "content" to "go"))
        val marked = PC.markLast(msgs)
        ok((marked.last()["content"] as List<*>).size == 1 && ((marked.last()["content"] as List<*>)[0] as Map<*, *>).containsKey("cache_control"), "last message marked")
        eq(msgs.last()["content"], "go", "input not changed"); eq(marked.first(), msgs.first(), "earlier messages untouched")
        val tr = listOf(mapOf("role" to "user", "content" to listOf(mapOf("type" to "tool_result", "tool_use_id" to "1", "content" to "a"), mapOf("type" to "tool_result", "tool_use_id" to "2", "content" to "b"))))
        val m2 = PC.markLast(tr)[0]["content"] as List<*>
        ok(!(m2[0] as Map<*, *>).containsKey("cache_control") && (m2[1] as Map<*, *>).containsKey("cache_control"), "only the last block is marked")
        val twice = PC.markLast(PC.markLast(msgs).dropLast(1) + msgs.last())
        eq(twice.count { (it["content"] as? List<*>)?.any { b -> (b as Map<*, *>).containsKey("cache_control") } == true }, 1, "never more than one message marked")
        ok(PC.withContext("files", "do x").contains("files") && PC.withContext("", "do x") == "do x", "context in the message")
        val bodyA = dev.pocketide.app.core.Tools.body(true, "m", "sys", msgs, false, cache = true)
        ok((bodyA["system"] as List<*>).isNotEmpty(), "anthropic body uses blocks when caching")
        ok(dev.pocketide.app.core.Tools.body(true, "m", "sys", msgs, false)["system"] == "sys", "anthropic body plain when off")
        ok(dev.pocketide.app.core.Tools.body(false, "m", "sys", msgs, false, cache = true)["messages"] != null, "openai body unchanged by cache")
        // router
        val R = dev.pocketide.app.core.Router
        fun strong(t: String, agent: Boolean = true) = R.route(t, false, agent).strong
        ok(!strong("What does this function do?"), "question is cheap")
        ok(!strong("explain closures in javascript"), "explain is cheap")
        ok(!strong("rename the button label to Save"), "small edit is cheap")
        ok(!strong("fix the typo in the title"), "typo is cheap")
        ok(strong("build me a todo app with login and a database"), "build is strong")
        ok(strong("refactor the project to use modules"), "refactor is strong")
        ok(strong("update a.js, b.js and c.css so the header matches"), "three files is strong")
        ok(strong("Traceback (most recent call last):\n  File app.py line 3\nValueError: bad"), "stack trace is strong")
        ok(strong("add a heading to Report.docx"), "document edit is strong")
        ok(R.route("what is in this docx?", false, true).category != dev.pocketide.app.core.Router.Category.DOCUMENT || true, "no crash on doc questions")
        eq(R.pick(R.route("what is a closure?"), "Cheap", "Smart"), "Cheap", "pick cheap"); eq(R.pick(R.route("build me a chess game app"), "Cheap", "Smart"), "Smart", "pick strong")
        eq(R.pick(R.route("what is a closure?"), "", ""), null, "no profiles: no change"); eq(R.pick(R.route("build me a chess game app"), "Cheap", ""), null, "no strong profile: no change")
        // symbols
        val SY = dev.pocketide.app.core.Symbols
        val kt = "package a\n\nclass UserStore(val n: Int) {\n    fun loadUser(id: String): String {\n        return id\n    }\n    private fun helper() {}\n}\n\nconst val MAX_USERS = 5\n"
        val js = "export function loadUser(id) { return id }\nconst cache = new Map()\nexport class Widget {}\n"
        val css = ".header { color: red }\n#main { padding: 0 }\n"
        val html = "<div id=\"root\"></div><span id=\"count\"></span>"
        val mdDoc = "# Title\n\n```\n## not a heading\n```\n\n## Real heading\n"
        val files = mapOf("a/UserStore.kt" to kt, "web/app.js" to js, "web/style.css" to css, "index.html" to html, "README.md" to mdDoc, "node_modules/x.js" to js)
        val read = { p: String -> files[p] }
        val symKt = SY.inFile("a/UserStore.kt", kt)
        ok(symKt.any { it.name == "UserStore" && it.kind == "class" }, "kotlin class")
        ok(symKt.any { it.name == "loadUser" && it.kind == "function" && it.line == 4 }, "kotlin function with line")
        ok(symKt.any { it.name == "helper" }, "private function")
        ok(SY.inFile("web/app.js", js).map { it.name }.containsAll(listOf("loadUser", "cache", "Widget")), "js symbols")
        ok(SY.inFile("web/style.css", css).map { it.name }.containsAll(listOf(".header", "#main")), "css rules")
        eq(SY.inFile("index.html", html).map { it.name }, listOf("#root", "#count"), "html ids")
        eq(SY.inFile("README.md", mdDoc).map { it.name }, listOf("Title", "Real heading"), "markdown headings skip fences")
        ok(!SY.indexable("node_modules/x.js") && !SY.indexable("a.png") && SY.indexable("a/b.kt"), "indexable")
        val all = SY.index(files.keys, read)
        ok(all.none { it.path.startsWith("node_modules/") }, "index skips ignored folders")
        val found = SY.find("loadUser", files.keys, read)
        ok(found.contains("a/UserStore.kt:4") && found.contains("web/app.js:1") && found.contains("Used"), "find shows definitions and uses")
        ok(SY.find("nothingHere", files.keys, read).startsWith("No definition"), "find reports nothing")
        ok(SY.outline("a/UserStore.kt", kt).contains("4: function loadUser"), "outline")
        ok(SY.similar("loadUse", all).contains("loadUser"), "similar names")
        ok(SY.projectMap(files.keys, read).contains("a/UserStore.kt: UserStore"), "project map")
        // trimming
        val T = dev.pocketide.app.core.Trim
        val rep = (1..6).joinToString("\n") { "npm WARN deprecated package x" } + "\nDone"
        ok(T.collapse(rep).contains("same line ×6") && T.collapse(rep).lines().size == 2, "repeated lines collapse")
        eq(T.collapse("a\nb\na"), "a\nb\na", "different lines stay")
        eq(T.collapse("\u001B[31mred\u001B[0m text"), "red text", "colour codes removed")
        val stamped = "2026-10-20 10:00:01 retrying\n2026-10-20 10:00:02 retrying\n2026-10-20 10:00:03 retrying\nok"
        ok(T.collapse(stamped).contains("×3"), "lines that differ only in time collapse")
        ok(!T.dropProgress("######## 45%\nreal line").contains("45%"), "progress bars dropped")
        ok(!T.dropProgress("[=====>    ]\n.....\nok").contains("=="), "bars and dot rows dropped")
        // what a command printed: node -e "console.log(total(...))" gave "4.5", which used to vanish (1.5.89)
        for (n in listOf("4.5", "212", "57.50", "-3.25", "1 2 3", "100"))
            eq(T.output("exit\n$n").lines().last(), n, "a printed number is kept: $n")
        val longOut = (1..200).joinToString("\n") { "line $it" } + "\nError: boom\n" + (1..50).joinToString("\n") { "tail $it" }
        val mid = T.middle(longOut, 1200)
        ok(mid.contains("line 1") && mid.contains("Error: boom") && mid.contains("tail 50") && mid.contains("lines left out") && mid.length <= 1300, "middle keeps ends and errors")
        eq(T.middle("short", 100), "short", "short text untouched")
        val para = "This is a long pasted block of text that the user sent twice by accident. ".repeat(6)
        val twicePara = para + "\n" + para
        ok(T.dedupePaste(twicePara).length < twicePara.length * 7 / 10 && T.dedupePaste(twicePara).contains("pasted twice"), "double paste collapsed")
        eq(T.dedupePaste("hello"), "hello", "short text is never changed")
        eq(T.userMessage("fix the bug"), "fix the bug", "short messages untouched")
        ok(T.saved("abcdef", "abc") == 3 && T.saved("a", "abc") == 0, "saved count")
        // linux bases (GPL)
        val DS = dev.pocketide.app.core.Distros
        eq(DS.all.size, 2, "two bases")
        ok(DS.all.all { it.url.startsWith("https://") && it.sha256.length == 64 && it.approxMb > 0 && it.maxBytes > 0 }, "each base is pinned with a checksum")
        eq(DS.byId("ubuntu").id, "ubuntu", "by id"); eq(DS.byId("nope").id, "alpine", "unknown falls back to alpine")
        ok(DS.UBUNTU.installHint.contains("apt-get") && DS.ALPINE.installHint.contains("apk"), "install hints match the base")
        ok(DS.UBUNTU.maxBytes > DS.ALPINE.maxBytes && DS.UBUNTU.approxMb > DS.ALPINE.approxMb, "ubuntu is the bigger one")
        // emulator workflow
        val AG2 = dev.pocketide.app.core.ApkGuide
        val emu = AG2.emulatorWorkflow("MyApp")
        ok(emu.startsWith("name: Test on an Android emulator"), "emulator workflow name")
        ok(emu.contains("workflow_dispatch") && !emu.contains("on:\n  push"), "emulator runs only when started")
        ok(emu.contains("android-emulator-runner") && emu.contains("connectedDebugAndroidTest"), "emulator steps")
        ok(emu.contains("MyApp-emulator-results") && emu.contains("logcat.txt") && emu.contains("screen.png"), "keeps results")
        ok(emu.contains("GITHUB_ENV") && emu.contains("github.event.inputs.api") && !emu.contains("'\u0024'"), "workflow variables are written literally")
        ok(AG2.EMULATOR_PATH.startsWith(".github/workflows/"), "emulator path")
        ok(AG2.emulatorNote().contains("Actions") && AG2.emulatorNote().contains("cannot run an Android emulator"), "emulator note is honest")
        // self-fix
        val SF = dev.pocketide.app.core.SelfFix
        val fixReport = SF.report("java.lang.NullPointerException\n at dev.pocketide.app.Foo.bar(Foo.kt:42)", "Android 15, Pixel", "0.9.2-gpl", "[2m ago] model: key sk-abcdefghijklmnop failed", "I tapped Run")
        ok(fixReport.contains("Foo.kt:42") && fixReport.contains("0.9.2-gpl") && fixReport.contains("I tapped Run"), "report has the facts")
        ok(!fixReport.contains("abcdefghijklmnop"), "report hides keys")
        ok(SF.report(null, "d", "v", "").contains("## Version and device"), "report without a crash")
        val runsJson = """{"workflow_runs":[
          {"id":5,"name":"Build Android APK","status":"in_progress","conclusion":"","head_sha":"abcdef1234","display_title":"newest"},
          {"id":4,"name":"Build Android APK","status":"completed","conclusion":"failure","head_sha":"bbbbbbbbbb","display_title":"broken"},
          {"id":3,"name":"Build Android APK","status":"completed","conclusion":"success","head_sha":"ccccccdddd","display_title":"good one","updated_at":"2026-10-20T22:00:00Z"}]}"""
        val runs = SF.parseRuns(runsJson)
        eq(runs.size, 3, "runs parsed")
        eq(SF.newestGood(runs)?.id, 3L, "newest successful run")
        eq(SF.newestGood(runs)?.head, "ccccccd", "short commit")
        ok(SF.parseRuns("not json").isEmpty(), "bad json is empty")
        val arts = SF.parseArtifacts("""{"artifacts":[{"id":1,"name":"logs","size_in_bytes":500,"expired":false},{"id":2,"name":"PocketIDE-GPL-debug-apk","size_in_bytes":100,"expired":false},{"id":3,"name":"old-apk","size_in_bytes":99999,"expired":true}]}""")
        eq(arts.size, 3, "artifacts parsed")
        eq(SF.pickApk(arts)?.id, 2L, "apk artifact chosen over logs and expired ones")
        eq(SF.pickApk(emptyList()), null, "no artifacts")
        eq(SF.apkEntry(listOf("mapping.txt", "app-release.apk", "app-debug.apk")), "app-debug.apk", "debug apk preferred")
        eq(SF.apkEntry(listOf("a.txt")), null, "no apk in the zip")
        ok(SF.runsPath("a/b").contains("/repos/a/b/actions/runs") && SF.artifactsPath("a/b", 7L).endsWith("/7/artifacts"), "paths")
        ok(SF.fileName("0.9.2-gpl", "abc1234").endsWith(".apk") && !SF.fileName("0.9/2", "x").contains("/"), "file name is safe")
        ok(SF.describe(runs[2], "0.9.1-gpl").contains("good one"), "describe")
        // sandbox
        val SB = dev.pocketide.app.core.Sandbox
        eq(SB.risk("ls -la"), dev.pocketide.app.core.Sandbox.Risk.SAFE, "ls is safe")
        eq(SB.risk("cat app.js | grep foo"), dev.pocketide.app.core.Sandbox.Risk.SAFE, "reading is safe")
        eq(SB.risk("rm -rf build"), dev.pocketide.app.core.Sandbox.Risk.DESTRUCTIVE, "rm -rf")
        eq(SB.risk("git push origin main"), dev.pocketide.app.core.Sandbox.Risk.DESTRUCTIVE, "git push")
        eq(SB.risk("sudo apk add python3"), dev.pocketide.app.core.Sandbox.Risk.OUTSIDE, "sudo")
        eq(SB.risk("cat /etc/passwd"), dev.pocketide.app.core.Sandbox.Risk.OUTSIDE, "outside the project")
        eq(SB.risk("curl https://example.com"), dev.pocketide.app.core.Sandbox.Risk.NETWORK, "curl")
        eq(SB.risk("npm install left-pad"), dev.pocketide.app.core.Sandbox.Risk.NETWORK, "npm install")
        eq(SB.risk("npm test"), dev.pocketide.app.core.Sandbox.Risk.SAFE, "npm test is not a network command")
        eq(SB.risk("mkdir src"), dev.pocketide.app.core.Sandbox.Risk.WRITES, "mkdir writes")
        eq(SB.risk("echo hi > out.txt"), dev.pocketide.app.core.Sandbox.Risk.WRITES, "redirect writes")
        eq(SB.risk("make"), dev.pocketide.app.core.Sandbox.Risk.WRITES, "make writes")
        ok(SB.needsConfirmation("rm -rf x") && SB.needsConfirmation("pip3 install requests") && !SB.needsConfirmation("python3 main.py"), "confirmation rules")
        ok(!SB.needsConfirmation("mkdir src"), "plain writes need no extra confirmation")
        ok(SB.shouldSnapshot("mkdir src") && !SB.shouldSnapshot("ls"), "snapshot rules")
        ok(SB.explain("rm -rf x").contains("delete") && SB.explain("ls").contains("reads"), "explanations")
        ok(SB.timeoutSeconds("apk add python3", true) >= 1800 && SB.timeoutSeconds("ls", true) == 600L, "timeouts")
        eq(SB.timeoutSeconds("ls", false), 120L, "shell timeout")
        // media actions
        val MO = dev.pocketide.app.core.MediaOps
        eq(MO.kindOf("a.PNG"), dev.pocketide.app.core.MediaOps.Kind.IMAGE, "png is an image")
        eq(MO.kindOf("a.pdf"), dev.pocketide.app.core.MediaOps.Kind.PDF, "pdf")
        eq(MO.kindOf("a.docx"), null, "docx is not media")
        ok(MO.actions("a.jpg").map { it.id }.containsAll(listOf("resize", "crop", "rotate", "convert", "thumbnail")), "image actions")
        ok(MO.actions("a.pdf").map { it.id }.containsAll(listOf("info", "extract_pages", "to_images")), "pdf actions")
        ok(MO.action("a.pdf", "extract_pages")!!.lossy && !MO.action("a.pdf", "to_images")!!.lossy, "lossy marked")
        eq(MO.pageList("1-3,5", 10), listOf(0, 1, 2, 4), "page list")
        eq(MO.pageList("", 3), listOf(0, 1, 2), "empty means all")
        eq(MO.pageList("2-99", 4), listOf(1, 2, 3), "range clipped to the end")
        ok(runCatching { MO.pageList("abc", 5) }.isFailure && runCatching { MO.pageList("0-2", 5) }.isFailure, "bad page specs refused")
        ok(runCatching { MO.pageList("9", 5) }.isFailure, "pages past the end refused")
        eq(MO.degrees("-90"), 270, "negative degrees wrap")
        ok(runCatching { MO.degrees("45") }.isFailure, "45 degrees refused")
        eq(MO.format("JPEG"), "jpg", "format normalised")
        ok(runCatching { MO.format("gif") }.isFailure, "gif refused")
        eq(MO.quality("", 80), 80, "default quality"); ok(runCatching { MO.quality("500") }.isFailure, "bad quality refused")
        eq(MO.scale("9", 2), 4, "scale clamped")
        eq(MO.targetSize("100", "", 400, 200), 100 to 50, "width keeps the shape")
        eq(MO.targetSize("", "50", 400, 200), 100 to 50, "height keeps the shape")
        ok(runCatching { MO.targetSize("", "", 4, 2) }.isFailure, "no size given")
        ok(runCatching { MO.targetSize("99999", "99999", 4, 2) }.isFailure, "too big refused")
        eq(MO.cropRect("0", "0", "999", "999", 100, 50).toList(), listOf(0, 0, 100, 50), "crop clipped to the picture")
        ok(runCatching { MO.cropRect("200", "0", "10", "10", 100, 50) }.isFailure, "crop outside refused")
        eq(MO.outputPath("a/photo.png", "small"), "a/photo-small.png", "output name")
        eq(MO.outputPath("a/photo.png", "small", "jpg"), "a/photo-small.jpg", "output name with new type")
        eq(MO.outputPath("a/photo.png", "small", "", { it == "a/photo-small.png" }), "a/photo-small-2.png", "output name avoids a clash")
        // rolling history
        val cs2 = dev.pocketide.app.core.ChatSession("c2", "Long chat", 1L, 2L)
        for (i in 1..12) {
            cs2.entries.add(dev.pocketide.app.core.ChatEntry("user", "please do step $i on file$i.js, and explain carefully what you changed and why it was needed", "", 0))
            cs2.entries.add(dev.pocketide.app.core.ChatEntry("assistant", "Did step $i. " + "I changed the file and checked it builds. ".repeat(4), "Edited file$i.js\nRan npm test"))
        }
        val comp = cs2.compressedHistory(4000, 8)
        ok(comp.first().first == "user" && comp.first().second.startsWith("Earlier in this chat"), "digest comes first")
        ok(comp.first().second.contains("file1.js") && comp.first().second.contains("npm test"), "digest names files and commands")
        ok(comp.size <= 8 && comp.last().second.contains("step 12"), "recent turns kept in full")
        ok(comp.sumOf { it.second.length } < cs2.entries.sumOf { it.content.length }, "shorter than the whole chat")
        val short = dev.pocketide.app.core.ChatSession("c3", "Short", 1L, 2L)
        short.entries.add(dev.pocketide.app.core.ChatEntry("user", "hi", "", 0))
        short.entries.add(dev.pocketide.app.core.ChatEntry("assistant", "hello", ""))
        eq(short.compressedHistory(4000, 8), short.history(4000, 8), "short chats are untouched")
        val tiny = dev.pocketide.app.core.ChatSession("c4", "Tiny", 1L, 2L)
        for (i in 1..12) { tiny.entries.add(dev.pocketide.app.core.ChatEntry("user", "a$i", "", 0)); tiny.entries.add(dev.pocketide.app.core.ChatEntry("assistant", "b$i", "")) }
        eq(tiny.compressedHistory(4000, 8), tiny.history(4000, 8), "no digest when it would not be shorter")
        ok(dev.pocketide.app.core.ChatExport.digest(cs2.entries).length <= 900, "digest is capped")
        // missing tools
        val MT = dev.pocketide.app.core.MissingTool
        eq(MT.missing("sh: python3: not found"), "python3", "sh not found")
        eq(MT.missing("/bin/sh: line 1: rg: command not found"), "rg", "bash style")
        eq(MT.missing("Traceback...\nModuleNotFoundError: No module named 'docx'"), "docx", "python module")
        eq(MT.missing("all good"), null, "nothing missing")
        ok(MT.isPythonModule("ModuleNotFoundError: No module named 'x'") && !MT.isPythonModule("sh: x: not found"), "module vs command")
        val ids = dev.pocketide.app.core.ToolCatalog.all.map { it.id }
        eq(MT.detect("sh: rg: not found", ids)?.second?.toolId, "ripgrep", "rg maps to ripgrep")
        eq(MT.detect("sh: gcc: not found", ids)?.second?.toolId, "cc", "gcc maps to the C tools")
        eq(MT.detect("sh: pip3: not found", ids)?.second?.toolId, "python", "pip maps to python")
        val pipFix = MT.detect("ModuleNotFoundError: No module named 'docx'", ids, listOf("python"))?.second
        ok(pipFix?.command?.contains("pip3 install") == true && pipFix.command!!.contains("python-docx"), "pip install suggested")
        eq(MT.detect("ModuleNotFoundError: No module named 'docx'", ids, emptyList())?.second?.toolId, "python", "python first when missing")
        val unknown = MT.detect("sh: frobnicate: not found", ids)?.second
        ok(unknown?.command?.contains("apk add") == true, "unknown command falls back to apk")
        eq(MT.detect("exit code 0\nall fine", ids), null, "clean output has no fix")
        // starters
        val ST = dev.pocketide.app.core.Starters
        ok(ST.all.size >= 5, "starters exist")
        ok(ST.all.all { it.files.isNotEmpty() && it.name.isNotEmpty() }, "starters have files")
        ok(ST.byId("web")!!.needs.isEmpty() && ST.byId("web")!!.files.containsKey("index.html"), "web starter needs nothing")
        ok(ST.byId("python-cli")!!.files.containsKey("test_main.py"), "python starter ships a test")
        eq(dev.pocketide.app.core.Verify.plan(ST.byId("python-cli")!!.files.keys.toList(), { null })?.label, "Python tests", "python starter is verifiable")
        eq(dev.pocketide.app.core.Verify.plan(ST.byId("node-cli")!!.files.keys.toList(), { ST.byId("node-cli")!!.files["package.json"] })?.label, "npm test", "node starter is verifiable")
        eq(ST.clashes(ST.byId("web")!!, listOf("index.html", "other.txt")), listOf("index.html"), "clashes")
        eq(ST.missingTools(ST.byId("c")!!, listOf("python")), listOf("cc"), "missing tools")
        for (r in dev.pocketide.app.core.Eval.all()) ok(r.ok, "eval " + r.name + ": " + r.detail)
        for (r in dev.pocketide.app.core.SelfCheck.run()) ok(r.ok, "selfcheck " + r.name + ": " + r.detail)
        println("$passed passed, $failed failed")
    }
}

