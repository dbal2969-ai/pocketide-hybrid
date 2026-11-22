package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextCallsTest {
    private val tools = mapOf(
        "read_file" to linkedMapOf("path" to "string", "start" to "integer", "end" to "integer"),
        "edit_file" to linkedMapOf("path" to "string", "search" to "string", "replace" to "string"),
        "run_command" to linkedMapOf("command" to "string"),
        "search" to linkedMapOf("pattern" to "string", "path" to "string"),
        "repo_map" to linkedMapOf(),
        "multi_edit" to linkedMapOf("edits" to "array"),
    )
    private fun first(text: String) = TextCalls.first(text) { tools[it] }
    private fun args(text: String) = MiniJson.obj(MiniJson.parse(first(text)!!.call.arguments))!!

    // 1.5.176: qwen2.5-coder 1.5B (local, Ollama template) writes the tool's name under "function", in a ```json block
    @Test fun jsonWithTheNameUnderFunctionOrTool() {
        val text = "I will read the cart first.\n\n```json\n{\n  \"function\": \"read_file\",\n  \"arguments\": {\n    \"path\": \"src/cart.js\"\n  }\n}\n```"
        assertEquals("read_file", first(text)!!.call.name)
        assertEquals(mapOf("path" to "src/cart.js"), args(text))
        assertEquals(mapOf("command" to "npm test"), args("{\"tool\": \"run_command\", \"args\": {\"command\": \"npm test\"}}"))
        // a config object with a "function" key is not a call
        assertNull(first("{\"function\": \"read_file\", \"arguments\": {}, \"version\": 2}"))
        assertNull(first("{\"function\": \"not_a_tool\", \"arguments\": {}}"))
    }

    @Test fun pythonFormWithNamedArguments() {
        val text = "First, let's search.\nsearch(pattern=\"/posts/\", path=\"\") \n\nThen read it.\nread_file(path=\"url.py\")"
        val hit = first(text)!!
        assertEquals("search", hit.call.name)
        assertEquals(mapOf("pattern" to "/posts/", "path" to ""), args(text))
        assertEquals("First, let's search.", text.substring(0, hit.at).trim())
    }

    @Test fun pythonFormWithPositionalArgumentsInParameterOrder() {
        assertEquals(mapOf("path" to "src/static.js"), args("read_file(\"src/static.js\")"))
        assertEquals(mapOf("path" to "a.js", "search" to "x = 1", "replace" to "x = 2\ny = 3"), args("edit_file('a.js', \"x = 1\", \"x = 2\\ny = 3\")"))
        assertEquals(mapOf("path" to "a.js", "start" to 3.0, "end" to 9.0), args("read_file(\"a.js\", start=3, end=9)"))
        assertEquals(mapOf("command" to "npm test"), args("- `run_command(\"npm test\")`"))
        assertEquals(mapOf("command" to "node t.js"), args("[run_command(command=\"node t.js\")]"))
        assertEquals(emptyMap<String, Any?>(), args("repo_map()"))
    }

    @Test fun pythonListsDictsAndTripleQuotes() {
        val edits = args("multi_edit(edits=[{'path': 'a.js', 'search': 'it\\'s', 'replace': \"b\"}])")["edits"] as List<*>
        assertEquals(mapOf("path" to "a.js", "search" to "it's", "replace" to "b"), edits.single())
        assertEquals("line 1\nline \"2\" end", args("edit_file(path=\"a.js\", search=\"x\", replace=\"\"\"line 1\nline \"2\" end\"\"\")")["replace"])
    }

    @Test fun jsonForms() {
        assertEquals(mapOf("path" to "test/p.js"), args("{\"type\": \"function\", \"name\": \"read_file\", \"parameters\": {\"path\": \"test/p.js\"}}"))
        assertEquals(mapOf("command" to "npm test"), args("Running it:\n```json\n{\"name\": \"run_command\", \"arguments\": {\"command\": \"npm test\"}}\n```"))
        assertEquals(mapOf("command" to "ls"), args("{\"type\":\"function\",\"function\":{\"name\":\"run_command\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}}"))
        assertEquals(mapOf("path" to "a.js"), args("<function=read_file>{\"path\": \"a.js\"}</function>"))
        assertEquals(mapOf("path" to "a.js"), args("<|start|>assistant<|channel|>commentary to=functions.read_file <|constrain|>json<|message|>{\"path\":\"a.js\"}<|call|>"))
        assertEquals(mapOf("path" to "a.js"), args("<function=read_file>\n<parameter=path>\na.js\n</parameter>\n</function>"))
    }

    @Test fun theEarliestCallWins() {
        assertEquals("run_command", first("{\"name\": \"run_command\", \"parameters\": {\"command\": \"ls\"}}\nread_file(path=\"a.js\")")!!.call.name)
        assertEquals("read_file", first("read_file(path=\"a.js\")\n{\"name\": \"run_command\", \"parameters\": {\"command\": \"ls\"}}")!!.call.name)
    }

    @Test fun textThatIsNotACallIsLeftAlone() {
        assertNull(first("The fix is in src/cart.js: total() now adds the tax."))
        // unknown tools, calls inside prose or code blocks, placeholders and unfinished calls
        assertNull(first("print(\"hello\")"))
        assertNull(first("I called read_file(path=\"a.js\") earlier and it showed the bug."))
        assertNull(first("```js\nread_file(\"a.js\")\n```"))
        assertNull(first("edit_file(\"src/format.js\", ...)"))
        assertNull(first("read_file(path=\"a.js\""))
        assertNull(first("read_file(path=myPath)"))
        assertNull(first("read_file(\"a.js\", 1, 2, 3)"))
        // a JSON object with other keys is data, not a call
        assertNull(first("{\"name\": \"read_file\", \"parameters\": {\"path\": \"a.js\"}, \"version\": \"1.0\"}"))
        assertNull(first("{\"name\": \"my-app\", \"version\": \"1.0.0\"}"))
        assertNull(first("{\"name\": \"dedupe\"}"))
    }
}
