package dev.pocketide.app

import org.junit.Test

class HybridAgentTest {
    private val request = listOf(mapOf<String, Any?>("role" to "system", "content" to "big system prompt"),
        mapOf<String, Any?>("role" to "user", "content" to "Add a score counter to game.js"))

    @Test fun phonePromptUsesSmallModeAndFits() {
        val p = HybridAgent.phonePrompt(false, request, 14000)
        check(p != null) { "should fit" }
        check(p!!.contains("ONE JSON object") && p.contains("PERSON: Add a score counter")) { "small mode prompt missing parts" }
        check(!p.contains("big system prompt")) { "the agent's own system prompt is replaced" }
        check(p.length <= 14000)
        check(HybridAgent.phonePrompt(false, request, 500) == null) { "too long for a 500-character limit" }
    }

    @Test fun phonePromptKeepsRequestAndDropsOldSteps() {
        val many = request + (1..40).flatMap { i ->
            listOf(mapOf<String, Any?>("role" to "assistant", "content" to "", "tool_calls" to listOf(mapOf("id" to "c$i", "type" to "function",
                "function" to mapOf("name" to "read_file", "arguments" to "{\"path\":\"f$i.js\"}")))),
                mapOf<String, Any?>("role" to "tool", "tool_call_id" to "c$i", "content" to "x".repeat(400)))
        }
        val p = HybridAgent.phonePrompt(false, many, 8000)!!
        check(p.contains("PERSON: Add a score counter")) { "the request is always kept" }
        check(p.contains("earlier steps left out")) { "old steps are dropped" }
        check(p.contains("f40.js")) { "the newest step is kept" }
        check(p.length <= 8000) { "length ${p.length}" }
    }

    @Test fun nanoActionBecomesAToolCall() {
        val (t, why) = HybridAgent.phoneTurn("""{"reason":"read it first","tool":"read_file","tool_args":{"path":"game.js"}}""", request)
        check(t != null && t.calls.size == 1 && t.calls[0].name == "read_file") { "expected a read_file call: $why" }
        check(t!!.calls[0].str("path") == "game.js")
        check(t.stop == "tool_calls")
    }

    @Test fun nanoProseGoesToTheCloud() {
        val (t, why) = HybridAgent.phoneTurn("Sure! Here is the code:\n```js\nlet score = 0\n```", request)
        check(t == null && why.contains("JSON")) { "prose is not an action" }
    }

    @Test fun claimWithoutAWriteGoesToTheCloud() {
        val (t, why) = HybridAgent.phoneTurn("""{"reason":"done","tool":"answer","tool_args":{"text":"I have updated game.js with a score counter."}}""", request)
        check(t == null && why.contains("no file was written")) { "a claim with no write must not be accepted: $why" }
    }

    @Test fun answerAfterAWriteIsAccepted() {
        val wrote = request + mapOf<String, Any?>("role" to "assistant", "content" to "", "tool_calls" to listOf(mapOf("id" to "w1", "type" to "function",
            "function" to mapOf("name" to "write_file", "arguments" to "{\"path\":\"game.js\",\"content\":\"x\"}"))))
        val (t, _) = HybridAgent.phoneTurn("""{"reason":"done","tool":"answer","tool_args":{"text":"I updated game.js."}}""", wrote)
        check(t != null && t.calls.isEmpty() && t.text.contains("updated game.js"))
    }

    @Test fun unknownToolGoesToTheCloud() {
        val (t, why) = HybridAgent.phoneTurn("""{"reason":"x","tool":"teleport","tool_args":{}}""", request)
        check(t == null && why.contains("does not exist")) { why }
    }

    @Test fun toolListBecomesDeclarations() {
        val tools = listOf(mapOf("type" to "function", "function" to mapOf("name" to "read_file", "description" to "Read a file.",
            "parameters" to mapOf("type" to "object", "properties" to mapOf(
                "path" to mapOf("type" to "string", "description" to "The file"),
                "start" to mapOf("type" to "integer"),
                "paths" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                "mode" to mapOf("type" to "string", "enum" to listOf("a", "b"))),
                "required" to listOf("path")))))
        val d = HybridAgent.declarations(tools)
        check(d.size == 1) { "one declaration" }
    }

    @Test fun messagesBecomeContentsWithFunctionParts() {
        val msgs = request + listOf(
            mapOf<String, Any?>("role" to "assistant", "content" to "Reading.\n\n☁ Ran in the cloud (x) · 1.0 s", "tool_calls" to listOf(mapOf("id" to "c1", "type" to "function",
                "function" to mapOf("name" to "read_file", "arguments" to "{\"path\":\"game.js\"}")))),
            mapOf<String, Any?>("role" to "tool", "tool_call_id" to "c1", "content" to "1| let x = 1"))
        val c = HybridAgent.contents(msgs)
        // a call the cloud did not make (here, or Gemini Nano's on the phone) goes back as text, so no thought signature is needed
        check(c.map { it.role } == listOf("user", "model", "user")) { "roles: ${c.map { it.role }}" }
        val modelText = c[1].parts.filterIsInstance<com.google.firebase.ai.type.TextPart>().joinToString { it.text }
        check(!modelText.contains("Ran in the cloud")) { "the where-it-ran label is not sent back: $modelText" }
        check(modelText.contains("[I ran read_file with {\"path\":\"game.js\"}]")) { "the call as text: $modelText" }
        check(c.none { con -> con.parts.any { it is com.google.firebase.ai.type.FunctionCallPart } }) { "no unsigned function call parts" }
        val resultText = c[2].parts.filterIsInstance<com.google.firebase.ai.type.TextPart>().joinToString { it.text }
        check(resultText.contains("[Result of read_file]") && resultText.contains("let x = 1")) { "the result as text: $resultText" }
    }
}
