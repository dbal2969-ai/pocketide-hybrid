package dev.pocketide.app.core

/** Reply length limits: how much a model may write in one reply, and what to do when a reply is cut off. */
object OutputLimit {
    /** The largest reply (in tokens) we ask an Anthropic model for. Every Claude 4 and 5 model allows at least this much; older Claude 3 models allow less. */
    fun maxTokens(model: String): Int {
        ModelCaps.maxTokens(model)?.let { return minOf(it, 16000) }
        val m = model.lowercase()
        return when {
            m.contains("claude-3-5-haiku") || m.contains("claude-3-haiku") || m.contains("claude-3-opus") -> 4096
            m.contains("claude-3") -> 8192
            else -> 16000
        }
    }

    /** Providers that stop a reply early when no limit is sent: Cloudflare Workers AI cuts gpt-oss and Llama at 256 tokens. */
    private val SMALL_DEFAULT = setOf("cloudflare")
    /**
     * The reply limit to send when the model's context window is not known yet: 0 (send none) except for providers whose own
     * default is tiny. If it does not fit a small model, the context error teaches the window and the next request uses that. Not lower: Kimi K2.6 spends 4096 tokens on reasoning alone.
     */
    fun whenUnknown(provider: String, model: String): Int = if (provider in SMALL_DEFAULT) maxTokens(model) else 0

    /** True when the provider says the reply stopped because it ran out of room. */
    fun isCut(stop: String): Boolean = stop.lowercase() in setOf("max_tokens", "length", "max_output_tokens", "model_context_window_exceeded")

    /** True when the tool call's arguments are complete JSON (a cut-off call is not). */
    fun validArgs(json: String): Boolean {
        if (json.isBlank()) return true
        return try { MiniJson.obj(MiniJson.parse(json)) != null } catch (e: MiniJson.JsonError) { false }
    }

    /** The file path named in a cut-off call's arguments, if it got that far. */
    fun pathOf(json: String): String = Regex(""""path"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1).orEmpty()

    const val FILE_LINES = 250

    /**
     * Told to the model after a cut-off reply. [what] names the calls that were lost, such as "write_file index.js". The example of
     * splitting a program fits the project: files a web page loads only when it has a page (1.5.115; a one-file Python exercise was
     * told about engine.js and ui.js).
     */
    fun cutNote(what: List<String>, paths: Collection<String> = emptyList()): String {
        val web = paths.isEmpty() || paths.any { Regex("""(?i)\.(html?|m?js|jsx|tsx?|vue|svelte)$""").containsMatchIn(it) }
        val py = paths.any { it.endsWith(".py", true) }
        val split = when {
            web -> "split a big program into several files (for example engine.js, ui.js, data.js) that the page loads"
            py -> "split a big program into modules that import each other"
            else -> "split a big program into several files"
        }
        return "Your last reply was cut off because it was too long, so " +
            (if (what.isEmpty()) "it was not finished" else "these calls were NOT run: " + what.joinToString(", ")) + ". " +
            "Do not send the same large call again. Write the work in smaller pieces: keep every file under about $FILE_LINES lines, " +
            "$split, and create a long file in steps (write the first part, then add the next part with edit_file). Continue now with the first small piece."
    }

    const val EMPTY_NOTE = "Your last reply was empty. Continue the task now: use the tools to write or change the files that are still missing."

    /** Added to the system prompt so big files are never written in one call. */
    const val SYSTEM_NOTE = " Keep each file under about $FILE_LINES lines; split bigger programs into several files that load each other, and never write one very long file in a single call."
}
