package dev.pocketide.app.core

/**
 * Small local model mode (1.5.177): Ollama, llama.cpp or another local server running a model of 8B parameters or fewer.
 *
 * Measured on qwen2.5-coder 1.5B and 7B (BENCHMARKS.md, 2026-11-17): with the full prompt (~4,000 tokens, ~20 tools) they wrote
 * tool calls as text in shapes the app did not read, looped on update_plan, and ended "finished" on wrong answers. Taken from
 * MobileCoder (same author), with what the research since says:
 *  - One action per reply as one JSON object, enforced by the server's JSON-schema output (Ollama `response_format`; MobileCoder
 *    used a llama.cpp grammar). The schema puts a short "thought" before the action: forcing a bare answer costs small models
 *    accuracy (the "constraint tax", alphaxiv 2605.26128), a rationale-first schema keeps most of it.
 *  - Every action has a required argument, "answer" included (an empty-argument finish was the main source of malformed calls
 *    in "The Parser Is Part of the Agent", NeurIPS 2026).
 *  - A short prompt and six tools; the app names the next step after each result (MobileCoder's host-observed state).
 *  - Qwen2.5-Coder's sampling profile from MobileCoder (temperature 0.7, top-p 0.8).
 * Pure logic; NativeAgent and ModelClient use it.
 */
object SmallLocal {
    /** Largest model, in billions of parameters, that gets this mode. */
    const val MAX_B = 8.5

    val TOOLS = listOf("search", "list_files", "read_file", "write_file", "edit_lines", "edit_file", "run_command", "app_help", "app_settings")

    /** 1.5.185: offered only when the request is about PocketIDE itself ([forApp]). */
    val APP_TOOLS = setOf("app_help", "app_settings")

    /** Whether [request] gets the app tools: a question about the app, or a change to one of its settings. */
    fun forApp(request: String): Boolean = AppHelp.aboutApp(request) || AppHelp.settingRequest(request) != null

    /** Files up to this many lines are rewritten whole in this mode (Rewrite.BIG_LINES); longer ones are edited by line range. */
    const val WHOLE_LINES = Rewrite.BIG_LINES
    const val ANSWER = "answer"

    private val SIZE = Regex("""(?:^|[:\-_/ ])(\d+(?:\.\d+)?)b(?![a-z0-9])""")

    /** The parameter count in a model name ("qwen2.5-coder:1.5b" → 1.5, "llama3.1:8b-instruct-q4_K_M" → 8.0), or null. */
    fun sizeB(model: String): Double? {
        val name = model.lowercase().substringAfterLast('/')
        return SIZE.findAll(name).mapNotNull { it.groupValues[1].toDoubleOrNull() }.lastOrNull()
    }

    /**
     * Benchmarks only (1.5.179): this mode for a small model that is not on a local server, so a cloud-hosted copy of a phone-sized
     * model (Granite 4.2 8B, Qwen3.5 9B on OpenRouter) can be measured in minutes. Set by LiveBench from POCKETIDE_BENCH_SMALL=1.
     */
    @Volatile var forceForBench = false

    fun applies(providerId: String, base: String, model: String): Boolean {
        if (!LocalModels.isLocal(providerId, base) && !forceForBench) return false
        val b = sizeB(model) ?: return false
        return b in 0.1..MAX_B
    }

    /**
     * The action's keys, named so that alphabetical order is the intended order (1.5.178): Ollama is reported to emit schema keys
     * in alphabetical order (ollama#18717 on /v1, and native `format` on 0.35.1), which made "args" come before "thought": the
     * answer-first order that costs small models accuracy ("Structure Tax", arXiv 2610.12056). reason < tool < tool_args.
     */
    const val REASON = "reason"
    const val TOOL = "tool"
    const val ARGS = "tool_args"

    private val SCHEMA: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            REASON to mapOf("type" to "string"),
            TOOL to mapOf("type" to "string", "enum" to TOOLS + ANSWER),
            ARGS to mapOf("type" to "object"),
        ),
        "required" to listOf(REASON, TOOL, ARGS),
    )

    /** Extra request fields (OpenAI-compatible body) for a request in this mode. */
    fun body(): Map<String, Any?> = mapOf(
        "temperature" to 0.7,
        "top_p" to 0.8,
        "response_format" to mapOf("type" to "json_schema", "json_schema" to mapOf("name" to "action", "strict" to true, "schema" to SCHEMA)),
    )

    /** The system prompt in this mode; [tools] is the catalogue of [TOOLS] (Tools.smallCatalogue). */
    fun system(tools: String): String =
        "You are a coding assistant working inside the person's project. You act by tools; the app runs them and shows you the result.\n\n" +
            "Every reply is ONE JSON object with three keys, in this order: \"reason\", \"tool\", \"tool_args\".\n" +
            "- reason: one or two short sentences: what you know now and what you do next.\n" +
            "- tool: one of the tools below, or \"answer\" to reply to the person, with tool_args {\"text\": your reply}.\n" +
            "- tool_args: the tool's parameters.\n" +
            "One tool per reply. Its result comes in the next message. Never guess a result.\n\n" +
            "How to work:\n" +
            "1. Find the code: search for a word from the request, or list_files.\n" +
            "2. read_file the file before you change it.\n" +
            "3. Change it. To ADD new code (a new feature, new event handlers), use insert_lines with after = N, the line number from " +
                "read_file that the new lines go after; nothing is removed or copied. To change existing lines, edit_file with search " +
                "copied exactly from read_file's output (without the line numbers), whole lines, never \"...\". write_file for a new file; " +
                "in a Node file, end it with module.exports = { … } naming what other files use.\n" +
            "4. Check it: run_command to run the tests or the program. If a test fails and its message does not say why, read the test " +
                "file to see what it expects, then fix the code. Never change the test, and never return a test's expected value " +
                "directly (like return 5;): fix the real logic so every input works.\n" +
            "5. answer: what you changed and how you checked it. If you could not finish or check, say so.\n" +            "A request to make an app, game or tool with no language named means a web page: write_file index.html (with its script) first.\n" +
            "For a question, read what you need and answer; change nothing.\n" +
            (if (tools.contains("- app_help")) "About PocketIDE itself (settings, menus, errors): app_help first, answer from its result. Setting changes: app_settings propose.\n" else "") +
            "\n" + tools

    /** The reply was not one JSON action (a server without JSON-schema output can let this through). */
    const val ASK = "Nothing ran: your last reply was not one JSON object. Reply with exactly one object with the keys " +
        "\"reason\", \"tool\" and \"tool_args\"; to reply to the person, tool \"answer\" with tool_args {\"text\": your reply}."

    /** One action read from a reply: the thought (shown as the reply text) and the tool call, or the answer text with no call. */
    fun parse(reply: String, types: (String) -> Map<String, String>?): TextTools.Parsed {
        val o = obj(reply) ?: return TextTools.Parsed(reply.trim(), emptyList())
        // the 1.5.177 names (thought, args) are still read
        val thought = ((o[REASON] ?: o["thought"]) as? String).orEmpty().trim()
        val tool = (o[TOOL] as? String)?.trim().orEmpty()
        val args = MiniJson.obj(o[ARGS] ?: o["args"]).orEmpty()
        if (tool.isEmpty() || tool == ANSWER) {
            val text = (args["text"] as? String)?.trim().takeUnless { it.isNullOrEmpty() } ?: thought.ifEmpty { reply.trim() }
            return TextTools.Parsed(text, emptyList())
        }
        val known = types(tool).orEmpty()
        val typed = LinkedHashMap<String, Any?>()
        for ((k, v) in args) typed[k] = if (v is String) TextTools.coerce(v, known[k]) else v
        return TextTools.Parsed(thought, listOf(ToolCall("sl${System.nanoTime()}", tool, MiniJson.write(typed))))
    }

    /** True when the reply holds a JSON object this mode can read as one action (or an answer). */
    fun isAction(reply: String): Boolean = obj(reply) != null

    /** The first JSON object in a reply (a fence around it is fine), or null. */
    private fun obj(reply: String): Map<String, Any?>? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { MiniJson.obj(MiniJson.parse(reply.substring(start, end + 1))) } catch (e: Exception) { null }
    }

    /**
     * What the app knows of the work so far, for [next]. [target]: a file the results point to that has not been read yet (a search
     * hit, a listing), [lastRead]: the file read most recently. Both are paths that exist in the project.
     */
    class State(val question: Boolean, val read: Boolean, val edited: Boolean, val checked: Boolean, val checkFailed: Boolean,
                val target: String? = null, val lastRead: String? = null, val lastReadLines: Int = 0,
                /** 1.5.185: the request is about PocketIDE itself ([AppHelp.aboutApp]); [setting]: the setting it asks to change. */
                val aboutApp: Boolean = false, val looked: Boolean = false, val setting: String? = null, val proposed: Boolean = false,
                /** The Chat home: no project, nothing can be written (ChatHome.SMALL_NOTE). */
                val chatHome: Boolean = false)

    /** A tool and its real argument values; never a placeholder (the 1.5B copied "exact lines from it" and "..." as values). */
    private fun act(tool: String, path: String? = null) = "tool \"$tool\"" + (path?.let { " with tool_args {\"path\": ${MiniJson.write(it)}" } ?: "")

    /**
     * The next step, named by the app after each result (MobileCoder's host-observed state). 1.5.177: with the file in it, since the
     * 1.5B repeated one failed search five times while the result named src/cart.js and this line only said "find the code".
     */
    fun next(s: State): String = "Next: " + when {
        s.chatHome -> "answer with tool \"$ANSWER\": this chat has no project, so nothing can be written here; to build it, the person taps Build this as a project."
        s.setting != null && !s.proposed -> "propose the change: tool \"app_settings\" with tool_args {\"action\": \"propose\", \"key\": ${MiniJson.write(s.setting)}, \"value\": the new value, \"why\": one sentence}."
        s.setting != null -> "answer with tool \"$ANSWER\": say the change waits for the person to tap Apply on the card."
        s.aboutApp && s.question && !s.looked -> "this is about PocketIDE itself: tool \"app_help\" with tool_args {\"query\": a few words from the question}."
        s.aboutApp && s.question && !s.read -> "answer with tool \"$ANSWER\" from what app_help returned: quote the path and label exactly; if it found nothing, say PocketIDE does not have that."
        s.question && !s.read && s.target != null -> "read it: " + act("read_file", s.target) + "}."
        s.question && !s.read -> "find what answers the question (search for one word from it, or list_files), then read_file the file."
        s.question -> "answer from what you read: tool \"$ANSWER\" with your reply as tool_args text." + (s.target?.let { " Or read $it first if you must." } ?: "")
        !s.read && s.target != null -> "read the file: " + act("read_file", s.target) + "}."
        !s.read -> "find the code: search for one word from the request, or list_files. Then read_file the file it is in."
        // 1.5.178: the edit format by file size: a short file is written whole, a long one is edited by line range; exact
        // search/replace is where small models fail most (Aider gives weaker models the whole-file format)
        !s.edited && s.lastRead != null && s.lastReadLines in 1..WHOLE_LINES -> "change ${s.lastRead}: write it whole with " +
            act("write_file", s.lastRead) + ", plus \"content\": the complete new file, every line you keep included}." +
            (s.target?.let { " If the change belongs in another file, read_file $it." } ?: "")
        !s.edited && s.lastRead != null -> "change ${s.lastRead}: " + act("edit_lines", s.lastRead) +
            ", plus \"start\" and \"end\": the line numbers from read_file, and \"text\": the new lines}." +
            (s.target?.let { " If the change belongs in another file, read_file $it." } ?: "")
        !s.edited -> "change the file you read with edit_file."
        !s.checked -> "check your change with tool \"run_command\": the project's test (npm test, or node with the test file)."
        s.checkFailed -> "the check failed. Read the error above, fix the code with edit_file, then run the check again."
        else -> "answer with tool \"$ANSWER\": say what you changed and how you checked it."
    }

    private val PATH = Regex("""(?<![\w./-])((?:[\w.-]+/)*[\w.-]+\.[A-Za-z0-9]{1,6})(?=[:\s)(,]|$)""")

    /** File paths named in a tool result, in order ("src/cart.js:1-12" gives src/cart.js). */
    fun pathsIn(text: String): List<String> = PATH.findAll(text).map { it.groupValues[1] }.filter { !it.startsWith(".") || it.contains('/') }.distinct().toList()
}
