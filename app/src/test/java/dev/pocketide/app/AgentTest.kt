package dev.pocketide.app

import dev.pocketide.app.core.ActionVerb
import dev.pocketide.app.core.AgentActions
import dev.pocketide.app.core.ChatEntry
import dev.pocketide.app.core.ChatLog
import dev.pocketide.app.core.ChatSession
import dev.pocketide.app.core.Checkpoints
import dev.pocketide.app.core.Images
import dev.pocketide.app.core.Memory
import dev.pocketide.app.core.MiniJson
import dev.pocketide.app.core.ModelException
import dev.pocketide.app.core.Plans
import dev.pocketide.app.core.Prompts
import dev.pocketide.app.core.Slash
import dev.pocketide.app.core.StepStatus
import dev.pocketide.app.core.ToolCall
import dev.pocketide.app.core.ToolStream
import dev.pocketide.app.core.Tools
import org.junit.Test
import java.io.File

// v0.4 W2: chats, memory, plans, slash commands, native tool calling, images, checkpoints.
class AgentTest {
    @Test fun all() { AgentSelfTest.runAll(); check(AgentSelfTest.failed == 0) { "${AgentSelfTest.failed} agent checks failed" } }
}

object AgentSelfTest {
    var failed = 0
    private var passed = 0
    private fun ok(c: Boolean, name: String) { if (c) passed++ else { failed++; println("FAIL  $name") } }
    private fun <T> eq(a: T, b: T, name: String) { ok(a == b, name); if (a != b) println("      got: $a\n      want: $b") }

    fun runAll() {
        failed = 0; passed = 0
        chats(); memory(); plans(); slash(); openAiStream(); anthropicStream(); messages(); images(); checkpoints()
        println("\n$passed passed, $failed failed")
    }

    private fun chats() {
        val s = ChatSession("c1", "New chat", 1000, 2000)
        s.entries.add(ChatEntry("user", "make a snake game", "", 1))
        s.entries.add(ChatEntry("assistant", "Sure ```file:a.js\nx\n```", "Edited a.js"))
        s.entries.add(ChatEntry("user", "fix request", "auto"))
        s.entries.add(ChatEntry("assistant", "fixed"))
        val back = ChatSession.fromJson(s.toJson())!!
        eq(back.id, "c1", "chat round trip: id")
        eq(back.entries, s.entries, "chat round trip: entries incl. notes and image count")
        eq(back.updated, 2000L, "chat round trip: time")
        eq(ChatSession.fromJson("{bad"), null, "bad chat JSON is ignored")
        eq(s.history().map { it.first }, listOf("user", "assistant", "user", "assistant"), "history keeps automatic messages for continuity")
        val big = ChatSession("c2", "t", 0, 0)
        repeat(30) { i -> big.entries.add(ChatEntry(if (i % 2 == 0) "user" else "assistant", "m$i")) }
        val h = big.history(maxMessages = 5)
        ok(h.size <= 5 && h.first().first == "user" && h.last().second == "m29", "history: newest kept, starts with a user message")
        val long = ChatSession("c3", "t", 0, 0)
        long.entries.add(ChatEntry("user", "x".repeat(50000)))
        ok(long.history(maxChars = 1000)[0].second.length < 600, "history: a huge message is cut")
        eq(ChatLog.title("  make a snake game\nwith levels"), "make a snake game", "title: first line")
        eq(ChatLog.title("/plan build a todo app"), "build a todo app", "title: slash command prefix dropped")
        ok(ChatLog.title("a".repeat(100)).length <= 48, "title: capped")
        eq(ChatLog.sort(listOf(ChatSession("a", "", 0, 1), ChatSession("b", "", 0, 5))).map { it.id }, listOf("b", "a"), "chats newest first")
    }

    private fun memory() {
        eq(Memory.section(null), "", "no memory file: nothing added")
        eq(Memory.section("   "), "", "empty memory file: nothing added")
        ok(Memory.section("Use tabs").contains("Use tabs") && Memory.section("Use tabs").contains("POCKETIDE.md"), "memory section names the file")
        ok(Memory.section("x".repeat(9000)).length < 4300, "memory capped near 4k")
        val m1 = Memory.remember(null, "Use Go 1.22")
        ok(m1.startsWith("# Project instructions") && m1.trimEnd().endsWith("- Use Go 1.22"), "remember creates the file from the template")
        val m2 = Memory.remember(m1, "No external packages")
        ok(m2.indexOf("- Use Go 1.22") < m2.indexOf("- No external packages"), "remember appends in order")
        val m3 = Memory.remember("# Mine\n\n## Remember\n- a\n\n## Other\n- b\n", "c")
        eq(m3, "# Mine\n\n## Remember\n- a\n- c\n\n## Other\n- b\n", "remember inserts inside its section")
        eq(Memory.remember("# Mine\n", "x\ny"), "# Mine\n\n## Remember\n- x y\n", "remember adds the heading when missing, one line")
    }

    private fun plans() {
        val reply = "PLAN: Snake game\n1. Create index.html with a canvas\n2. Add game loop in game.js\n3. **Controls**: arrow keys and swipe\n\nNotes: keep it small."
        val p = Plans.parse(reply)!!
        eq(p.title, "Snake game", "plan title")
        eq(p.steps, listOf("Create index.html with a canvas", "Add game loop in game.js", "Controls: arrow keys and swipe"), "plan steps (bold label kept as text)")
        eq(p.checklist().lines().first(), "☐ 1. Create index.html with a canvas", "checklist format")
        p.status[0] = StepStatus.DONE
        eq(p.nextTodo(), 1, "next step to do")
        ok(!p.finished, "not finished")
        eq(Plans.parse("## Plan\n1) A\n2) B")?.steps, listOf("A", "B"), "markdown heading and 1) style")
        eq(Plans.parse("Step 1: A\nStep 2: B")?.steps, listOf("A", "B"), "Step N: style")
        val bulleted = "PLAN: Define the project directory\n- Create the project directory with index.html\n- Change index.html to include a canvas\n- Add JavaScript to `src/game.js`:\n```js\nlet a = 1;\n- not a step\n1. also not a step\n2. nor this\n```\n- Test the game in a browser"
        val bp = Plans.parse(bulleted)
        eq(bp?.steps?.size, 4, "bulleted plan from a small model is accepted, code fence ignored")
        eq(bp?.title, "Define the project directory", "bulleted plan keeps its title")
        eq(Plans.parse("PLAN: x\n```\n1. a\n2. b\n```"), null, "numbered lines inside a code fence are not a plan")
        eq(Plans.parse("1. only one"), null, "one step is not a plan")
        eq(Plans.parse("Sure! Here's code"), null, "no list is not a plan")
        eq(Plans.parse("PLAN: x\n1. A\n2. B\n\nAlternatives:\n1. C\n2. D")?.steps, listOf("A", "B"), "a second numbered list is not merged")
        eq(Plans.fromEdited("t", "1. one\n\n- two\nthree")?.steps, listOf("one", "two", "three"), "edited steps, numbers optional")
        val sp = Plans.stepPrompt("make snake", p, 1)
        ok(sp.contains("step 2 only") && sp.contains("make snake") && sp.contains("✓ 1."), "step prompt has task, checklist and step")
        ok(Plans.request("x").contains("PLAN:"), "plan request asks for the format")
    }

    private fun slash() {
        eq(Slash.parse("/plan build a game"), dev.pocketide.app.core.SlashCommand("plan", "build a game"), "parse /plan with argument")
        eq(Slash.parse("/CLEAR"), dev.pocketide.app.core.SlashCommand("clear", ""), "parse is case-insensitive")
        eq(Slash.parse("/etc/hosts is weird"), null, "unknown /word is a normal message")
        eq(Slash.parse("hello /plan"), null, "slash must come first")
        eq(Slash.suggest("/").size, Slash.ALL.size, "suggest all on /")
        eq(Slash.suggest("/co").map { it.name }, listOf("commit"), "suggest by prefix")
        eq(Slash.suggest("/plan x"), emptyList<Slash.Info>(), "no suggestions after a space")
        ok(Slash.fixPrompt(listOf("app.js:3 boom"), "").contains("app.js:3 boom"), "fix prompt lists problems")
        ok(Slash.fixPrompt(emptyList(), "").contains("open file"), "fix prompt without problems")
        ok(Slash.explainPrompt("a.js", "let x", "").contains("let x"), "explain with selection")
        ok(Slash.explainPrompt("a.js", "", "").contains("a.js"), "explain the open file")
        ok(Slash.commitPrompt("M app.js").contains("M app.js"), "commit prompt includes changes")
    }

    private fun openAiStream() {
        val t = ToolStream(false)
        val lines = listOf(
            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"Let me look.\"}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"pa\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"th\\\": \\\"app.js\\\"}\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"call_b\",\"function\":{\"name\":\"search\",\"arguments\":\"{\\\"pattern\\\":\\\"todo\\\"}\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
            "data: [DONE]"
        )
        val text = StringBuilder()
        for (l in lines) text.append(t.feed(l))
        val r = t.result()
        eq(text.toString(), "Let me look.", "openai: text returned as it streams")
        eq(r.calls.map { it.id to it.name }, listOf("call_a" to "read_file", "call_b" to "search"), "openai: two calls assembled by index")
        eq(r.calls[0].str("path"), "app.js", "openai: argument pieces joined into JSON")
        eq(r.stop, "tool_calls", "openai: finish reason")
        // Gemini's OpenAI endpoint: whole calls, no index, sometimes no id
        val g = ToolStream(false)
        g.feed("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"function\":{\"name\":\"list_files\",\"arguments\":\"{}\"},\"type\":\"function\"},{\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"a\\\"}\"},\"type\":\"function\",\"id\":\"\"}]},\"finish_reason\":\"tool_calls\"}]}")
        val gr = g.result()
        eq(gr.calls.map { it.name }, listOf("list_files", "read_file"), "gemini: calls without index")
        ok(gr.calls.all { it.id.isNotEmpty() } && gr.calls[0].id != gr.calls[1].id, "gemini: ids generated and distinct")
        val reasoning = ToolStream(false)
        reasoning.feed("data: {\"choices\":[{\"delta\":{\"reasoning\":\"hmm\"}}]}")
        eq(reasoning.reasoningChars, 3, "reasoning counted")
        val err = try { ToolStream(false).feed("data: {\"error\":{\"message\":\"rate limited\"}}"); null } catch (e: ModelException) { e.message }
        eq(err, "rate limited", "error event throws")
        eq(ToolCall("x", "y", "not json").args(), emptyMap<String, Any?>(), "broken arguments give an empty map")
        ok(Tools.isRejection(400, "tools are not supported for this model") && Tools.isRejection(404, "No endpoints found that support tool use"), "tool rejection detected")
        ok(!Tools.isRejection(401, "invalid tool key") && !Tools.isRejection(400, "context too long"), "other errors are not tool rejections")
        ok(Tools.providerSupports("groq") && !Tools.providerSupports("ollama") && !Tools.providerSupports("demo"), "which providers use tools")
        eq(Tools.label(ToolCall("1", "edit_file", "{\"path\":\"style.css\"}")), "Edited style.css", "chip label")
        eq(Tools.planSteps(ToolCall("1", "update_plan", "{\"steps\":[{\"text\":\"A\",\"status\":\"done\"},{\"text\":\"B\",\"status\":\"doing\"}]}")), listOf("A" to "done", "B" to "doing"), "update_plan steps")
    }

    private fun anthropicStream() {
        val t = ToolStream(true)
        val lines = listOf(
            "event: message_start", "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m\"}}",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Reading.\"}}",
            "data: {\"type\":\"content_block_stop\",\"index\":0}",
            "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"edit_file\",\"input\":{}}}",
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"path\\\": \\\"a.js\\\", \"}}",
            "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"search\\\": \\\"x\\\", \\\"replace\\\": \\\"y\\\"}\"}}",
            "data: {\"type\":\"content_block_stop\",\"index\":1}",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}"
        )
        val text = StringBuilder()
        for (l in lines) text.append(t.feed(l))
        val r = t.result()
        eq(text.toString(), "Reading.", "anthropic: text")
        eq(r.calls.size, 1, "anthropic: one tool call")
        eq(r.calls[0].id, "toolu_1", "anthropic: id")
        eq(r.calls[0].args(), mapOf("path" to "a.js", "search" to "x", "replace" to "y"), "anthropic: input JSON assembled")
        eq(r.stop, "tool_use", "anthropic: stop reason")
    }

    private fun messages() {
        val call = ToolCall("call_a", "read_file", "{\"path\":\"a\"}")
        val oa = Tools.assistantCalls(false, "", listOf(call))
        eq(MiniJson.write(oa), "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"a\\\"}\"}}]}", "openai assistant tool_calls message")
        eq(MiniJson.write(Tools.results(false, listOf(call to "1: x"))), "[{\"role\":\"tool\",\"tool_call_id\":\"call_a\",\"content\":\"1: x\"}]", "openai tool result message")
        val an = Tools.assistantCalls(true, "ok", listOf(call))
        eq(MiniJson.write(an), "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"},{\"type\":\"tool_use\",\"id\":\"call_a\",\"name\":\"read_file\",\"input\":{\"path\":\"a\"}}]}", "anthropic tool_use message")
        eq(MiniJson.write(Tools.results(true, listOf(call to "r"))), "[{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"call_a\",\"content\":\"r\"}]}]", "anthropic tool_result message")
        Tools.webEnabled = false   // the base tool counts below are without the web tools
        val b = Tools.body(false, "m", "SYS", listOf(Tools.userText(false, "hi")), true)
        val msgs = MiniJson.list(b["messages"])!!
        eq((MiniJson.obj(msgs[0])!!["role"]), "system", "openai body: system first")
        eq(MiniJson.list(b["tools"])!!.size, 21, "openai body: the base tools plus game_assets, library_docs, http_request, sql_query, app_help and app_settings")
        val bd = Tools.body(false, "m", "SYS", listOf(Tools.userText(false, "hi")), true, true)
        val toolsD = MiniJson.list(bd["tools"])!!
        eq(toolsD.size, 23, "openai body with documents: all tools")
        val docFn = MiniJson.obj(MiniJson.obj(toolsD[toolsD.size - 8])!!["function"])!!
        eq(docFn["name"], "doc_edit", "doc_edit tool is last")
        ok((docFn["description"] as String).contains("add_heading") && (docFn["description"] as String).contains("set_cell"), "doc_edit lists its actions")
        val docProps = MiniJson.obj(MiniJson.obj(docFn["parameters"])!!["properties"])!!
        ok(docProps.containsKey("position") && docProps.containsKey("cell") && MiniJson.obj(docProps["index"])!!["type"] == "integer" && MiniJson.obj(docProps["bold"])!!["type"] == "boolean", "doc_edit parameters, with typed numbers and booleans")
        eq(MiniJson.list(Tools.anthropicTools(true))!!.size, 23, "anthropic tools with documents")
        Tools.webEnabled = true
        eq(MiniJson.list(Tools.anthropicTools(false))!!.size, 23, "web tools add two")
        ok(Tools.NAMES.contains("web_search") && Tools.NAMES.contains("web_fetch"), "web tool names")
        eq(Tools.label(ToolCall("1", "doc_edit", "{\"path\":\"a.docx\",\"action\":\"add_heading\"}")), "Add heading in a.docx", "doc_edit chip label")
        val dact = AgentActions.find("ACTION: doc report.docx add_heading text=\"Test one\" position=top\nACTION: doc a.csv outline")
        eq(dact.map { it.verb }, listOf(ActionVerb.DOC, ActionVerb.DOC), "ACTION: doc lines are found")
        eq(dact[0].arg, "report.docx add_heading text=\"Test one\" position=top", "doc line keeps its quotes")
        ok(AgentActions.DOC_PROMPT.contains("ACTION: doc") && AgentActions.DOC_PROMPT.contains("outline"), "doc prompt explains the line")
        ok(AgentActions.strip("Sure.\nACTION: doc a.docx outline\nDone").let { !it.contains("ACTION") }, "doc lines are removed from the display")
        ok(b["stream"] == true && b["model"] == "m", "openai body: stream and model")
        val ab = Tools.body(true, "claude", "SYS", listOf(Tools.userText(true, "hi")), true)
        ok(ab["system"] == "SYS" && ab["max_tokens"] == 16000 && MiniJson.list(ab["messages"])!!.size == 1, "anthropic body: system separate")
        val tool0 = MiniJson.obj(MiniJson.list(ab["tools"])!![0])!!
        ok(tool0.containsKey("input_schema") && tool0["name"] == "read_file", "anthropic tool schema")
        ok(!Tools.body(false, "m", "S", emptyList(), false).containsKey("tools"), "tools=false leaves tools out")
        // the request body must be valid JSON the parser can read back
        ok(MiniJson.parse(MiniJson.write(b)) != null, "body is valid JSON")
        ok(Prompts.toolsSystem(true).contains("Android's own shell") && !Prompts.toolsSystem(true).contains("SEARCH"), "tools prompt: environment, no edit-block guide")
        ok(Prompts.toolsSystem(false, "Alpine Linux in /work.").contains("Alpine"), "tools prompt takes the environment note")
    }

    private fun images() {
        val img = "data:image/png;base64,QUJD"
        eq(Images.content(false, "hi", emptyList()), "hi", "no images: plain text content")
        val oa = MiniJson.write(Images.content(false, "look", listOf(img)))
        eq(oa, "[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,QUJD\"}},{\"type\":\"text\",\"text\":\"look\"}]", "openai image parts")
        val an = MiniJson.write(Images.content(true, "look", listOf(img)))
        eq(an, "[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"QUJD\"}},{\"type\":\"text\",\"text\":\"look\"}]", "anthropic image block")
        ok(Images.providerSees("gemini", "") && Images.providerSees("ollama", "llava:7b") && !Images.providerSees("groq", "llama-3.3-70b-versatile"), "who can see images")
    }

    private fun checkpoints() {
        val dir = java.nio.file.Files.createTempDirectory("cp").toFile()
        try {
            val project = HashMap<String, ByteArray>()
            project["a.txt"] = "v1".toByteArray()
            val cps = Checkpoints(File(dir, "cps"), maxCount = 3)
            // turn 1 edits a.txt and creates b.txt
            val cp1 = cps.save("turn 1", mapOf("a.txt" to project["a.txt"], "b.txt" to null), now = 1000)!!
            project["a.txt"] = "v2".toByteArray(); project["b.txt"] = "new".toByteArray()
            // turn 2 edits a.txt again
            cps.save("turn 2", mapOf("a.txt" to project["a.txt"]), now = 2000)
            project["a.txt"] = "v3".toByteArray()
            eq(cps.list().map { it.label }, listOf("turn 2", "turn 1"), "checkpoints newest first")
            eq(cps.save("nothing", emptyMap()), null, "empty checkpoint not saved")
            val touched = cps.restore(cp1.id, { p, b -> project[p] = b; true }, { p -> project.remove(p) })
            eq(String(project["a.txt"]!!), "v1", "restore to before turn 1: a.txt back to v1")
            ok(!project.containsKey("b.txt"), "restore deletes a file created later")
            eq(touched.toSet(), setOf("a.txt", "b.txt"), "restore reports touched files")
            eq(cps.list().size, 0, "used checkpoints removed")
            for (i in 1..5) cps.save("t$i", mapOf("a.txt" to byteArrayOf(i.toByte())), now = 3000L + i)
            eq(cps.list().map { it.label }, listOf("t5", "t4", "t3"), "pruned to the newest maxCount")
        } finally { dir.deleteRecursively() }
    }
}
