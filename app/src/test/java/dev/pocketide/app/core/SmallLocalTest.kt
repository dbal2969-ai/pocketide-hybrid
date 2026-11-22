package dev.pocketide.app.core

import org.junit.Assert.*
import org.junit.Test

/** 1.5.177: small local model mode (one JSON action per reply for local models of 8B or fewer). */
class SmallLocalTest {
    private val types = { name: String -> if (name == "read_file") mapOf("path" to "string", "start_line" to "integer") else emptyMap() }

    @Test fun sizesComeFromTheModelName() {
        assertEquals(1.5, SmallLocal.sizeB("qwen2.5-coder:1.5b")!!, 0.0)
        assertEquals(7.0, SmallLocal.sizeB("qwen2.5-coder:7b-instruct-q3_K_S")!!, 0.0)
        assertEquals(8.0, SmallLocal.sizeB("llama3.1:8b")!!, 0.0)
        assertEquals(32.0, SmallLocal.sizeB("qwen2.5-coder:32b")!!, 0.0)
        assertNull(SmallLocal.sizeB("mistral:latest"))
        assertEquals(120.0, SmallLocal.sizeB("gpt-oss-120b")!!, 0.0)
        assertFalse(SmallLocal.applies("ollama", "", "gpt-oss:120b"))
    }

    @Test fun onlySmallModelsOnALocalServer() {
        assertTrue(SmallLocal.applies("ollama", "", "qwen2.5-coder:1.5b"))
        assertTrue(SmallLocal.applies("custom", "http://127.0.0.1:8080/v1", "qwen2.5-coder-7b"))
        assertFalse(SmallLocal.applies("ollama", "", "qwen2.5-coder:32b"))
        assertFalse(SmallLocal.applies("ollama", "", "mistral:latest"))
        assertFalse(SmallLocal.applies("openrouter", "", "qwen/qwen-2.5-coder-7b-instruct"))
        assertFalse(SmallLocal.applies("ollamacloud", "", "gemma3:4b"))
    }

    @Test fun oneActionIsReadFromTheReply() {
        val p = SmallLocal.parse("{\"thought\": \"I need the cart code.\", \"tool\": \"read_file\", \"args\": {\"path\": \"src/cart.js\", \"start_line\": \"5\"}}", types)
        assertEquals("I need the cart code.", p.text)
        assertEquals("read_file", p.calls.single().name)
        val a = MiniJson.obj(MiniJson.parse(p.calls.single().arguments))!!
        assertEquals("src/cart.js", a["path"])
        assertEquals(5, (a["start_line"] as Number).toInt())   // "5" became a number, as the tool's schema says
        // inside a fence, as some servers let through
        assertEquals("search", SmallLocal.parse("```json\n{\"thought\":\"x\",\"tool\":\"search\",\"args\":{\"query\":\"SAVE10\"}}\n```", types).calls.single().name)
    }

    @Test fun theNewKeysAreRead() {
        val p = SmallLocal.parse("{\"reason\": \"Read it.\", \"tool\": \"read_file\", \"tool_args\": {\"path\": \"a.js\"}}", types)
        assertEquals("Read it.", p.text)
        assertEquals("read_file", p.calls.single().name)
        assertEquals("Fixed.", SmallLocal.parse("{\"reason\": \"x\", \"tool\": \"answer\", \"tool_args\": {\"text\": \"Fixed.\"}}", types).text)
    }

    @Test fun anAnswerEndsTheWork() {
        val p = SmallLocal.parse("{\"thought\": \"done\", \"tool\": \"answer\", \"args\": {\"text\": \"Fixed the discount in src/cart.js; npm test passes.\"}}", types)
        assertTrue(p.calls.isEmpty())
        assertEquals("Fixed the discount in src/cart.js; npm test passes.", p.text)
        // a reply that is not JSON is plain text, with no call
        assertTrue(SmallLocal.parse("I fixed it.", types).calls.isEmpty())
    }

    @Test fun theRequestAsksForOneJsonActionWithAThoughtFirst() {
        val b = SmallLocal.body()
        val schema = (MiniJson.obj((MiniJson.obj(b["response_format"])!!)["json_schema"])!!)["schema"] as Map<*, *>
        val keys = (schema["properties"] as Map<*, *>).keys.map { it.toString() }
        assertEquals(listOf("reason", "tool", "tool_args"), keys)   // the reason before the action
        assertEquals(keys, keys.sorted())   // and still so when a server writes keys in alphabetical order (ollama#18717)
        assertEquals(keys, schema["required"])
        assertEquals(0.7, b["temperature"])
        assertTrue(SmallLocal.system("Tools:\n").contains("ONE JSON object"))
    }

    @Test fun theAppNamesTheNextStep() {
        fun n(q: Boolean, r: Boolean, e: Boolean, c: Boolean, f: Boolean) = SmallLocal.next(SmallLocal.State(q, r, e, c, f))
        assertTrue(n(false, false, false, false, false).contains("find the code"))
        assertTrue(n(false, true, false, false, false).contains("edit_file"))
        assertTrue(n(false, true, true, false, false).contains("run_command"))
        assertTrue(n(false, true, true, true, true).contains("check failed"))
        assertTrue(n(false, true, true, true, false).startsWith("Next: answer"))
        assertTrue(n(true, true, false, false, false).contains("answer from what you read"))
    }

    // the line names the action and the file, as JSON (the 1.5B searched for a name five times while the result named src/cart.js)
    @Test fun theNextStepNamesTheFile() {
        val read = SmallLocal.next(SmallLocal.State(false, false, false, false, false, target = "src/cart.js"))
        assertTrue(read, read.contains("tool \"read_file\"") && read.contains("\"path\": \"src/cart.js\""))
        // the edit format by size: a short file whole, a long one by line range (1.5.178)
        val edit = SmallLocal.next(SmallLocal.State(false, true, false, false, false, lastRead = "src/cart.js", lastReadLines = 12))
        assertTrue(edit, edit.startsWith("Next: change src/cart.js") && edit.contains("tool \"write_file\"") && edit.contains("complete new file"))
        val long = SmallLocal.next(SmallLocal.State(false, true, false, false, false, lastRead = "game/main.js", lastReadLines = 900))
        assertTrue(long, long.contains("tool \"edit_lines\"") && long.contains("\"start\""))
        // only real values: a placeholder is copied as a value by a 1.5B ("exact lines from it", "...")
        for (st in listOf(SmallLocal.State(false, true, false, false, false, lastRead = "a.js"), SmallLocal.State(false, true, true, false, false),
                SmallLocal.State(false, true, true, true, false), SmallLocal.State(true, true, false, false, false)))
            SmallLocal.next(st).let { assertFalse(it, it.contains("...") || it.contains("exact lines from it") || it.contains("\"text\": \"")) }
        assertEquals(listOf("src/cart.js", "README.md"), SmallLocal.pathsIn("- src/cart.js:1-12  (4: function total(items, code) {)\n- README.md:1-3  (2: A tiny cart.)"))
        assertEquals(listOf("test/cart.test.js"), SmallLocal.pathsIn("not ok 1 - discount (test/cart.test.js:7:3)"))
    }

    @Test fun aSmallModelMayRewriteAShortEntryFileWhole() {
        val prior = (1..40).joinToString("\n") { "line $it" }
        assertNotNull(Rewrite.refusal("main.js", prior, prior + "\nmore", "Fix the score"))   // as before
        assertNull(Rewrite.refusal("main.js", prior, prior + "\nmore", "Fix the score", small = true))
        assertNotNull(Rewrite.refusal("main.js", prior, "short", "Fix the score", small = true))   // dropping most of it is still refused
        val big = (1..400).joinToString("\n") { "line $it" }
        assertNotNull(Rewrite.refusal("game/world.js", big, big + "\nx", "Fix", small = true))   // long files are edited, not rewritten
    }

    @Test fun ollamaStartsWithAnAgentSizedContext() {
        assertEquals("8192", LocalModels.OLLAMA_SETTINGS["OLLAMA_CONTEXT_LENGTH"])
        assertTrue(LocalModels.serveCommand.endsWith("ollama serve &") && LocalModels.serveCommand.contains("OLLAMA_KV_CACHE_TYPE=q8_0"))
    }

    @Test fun qwen35NeedsANewerOllama() {
        assertTrue(ModelCatalog.ollamaAtLeast("0.17.7", "0.17.4"))
        assertTrue(ModelCatalog.ollamaAtLeast("0.40.16", "0.17.4"))
        assertFalse(ModelCatalog.ollamaAtLeast("0.12.3", "0.17.4"))
        assertTrue(ModelCatalog.ollamaAtLeast(null, "0.17.4"))
        assertEquals("0.17.4", ModelCatalog.models.first { it.name == "qwen3.5:4b" }.minOllama)
    }
}
