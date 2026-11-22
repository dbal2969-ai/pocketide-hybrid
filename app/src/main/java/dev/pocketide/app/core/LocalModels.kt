package dev.pocketide.app.core

/** Adjustments for models running on the phone (or the local network), where every token of prompt is slow. */
object LocalModels {
    /** Project context sent to local models; cloud models get 60,000 characters. */
    const val CONTEXT_CHARS = 8000
    const val CLOUD_CONTEXT_CHARS = 60000

    fun isLocal(providerId: String, base: String = ""): Boolean {
        if (providerId == "ollama" || providerId == "llamacpp" || providerId == Engine.PROVIDER) return true
        if (providerId != "custom") return false
        val b = base.lowercase()
        return b.contains("127.0.0.1") || b.contains("localhost") || b.contains("://10.") || b.contains("://192.168.")
    }

    fun contextChars(local: Boolean) = if (local) CONTEXT_CHARS else CLOUD_CONTEXT_CHARS

    /**
     * Server settings for Ollama on a phone (1.5.178). Ollama now sizes the context by memory, and below 24 GiB that is 4k tokens,
     * too short for an agent (a request and a few results); its OpenAI-compatible API cannot change it per request. The KV cache at
     * q8_0 takes about half the memory of f16 with a very small loss (Ollama FAQ); keep-alive stops a reload between steps.
     */
    val OLLAMA_SETTINGS: Map<String, String> = linkedMapOf("OLLAMA_CONTEXT_LENGTH" to "8192", "OLLAMA_KV_CACHE_TYPE" to "q8_0",
        "OLLAMA_FLASH_ATTENTION" to "1", "OLLAMA_KEEP_ALIVE" to "30m")

    /**
     * The window a local server is assumed to have when nothing says otherwise (1.5.179): the context the app tells Ollama to use.
     * Ollama's OpenAI API lists no window, so the app sized history for 30K tokens and Granite 4.2 8B on a real-project task sent
     * 11.8K tokens to an 8K server ("request (11831 tokens) exceeds the available context size (8192 tokens)") on 8 of 13 tasks.
     */
    val DEFAULT_CONTEXT: Int get() = OLLAMA_SETTINGS["OLLAMA_CONTEXT_LENGTH"]!!.toInt()

    /** The Termux command that starts Ollama with [OLLAMA_SETTINGS]. */
    val serveCommand: String get() = OLLAMA_SETTINGS.entries.joinToString(" ") { "${it.key}=${it.value}" } + " ollama serve &"

    /** Qwen3 models think out loud before answering unless told /no_think, which on a phone CPU can take minutes. */
    fun wantsNoThink(model: String): Boolean = Regex("""qwen3(?![\d.])""", RegexOption.IGNORE_CASE).containsMatchIn(model)

    fun addNoThink(user: String): String = if (user.contains("/no_think")) user else "$user\n\n/no_think"

    data class Visible(val text: String, val thinking: Boolean, val thoughtChars: Int)

    /**
     * Removes <think>…</think> sections some models put inside the answer. An unclosed <think> (still streaming)
     * hides everything after it and reports thinking = true.
     */
    fun stripThinking(acc: String): Visible {
        if (!acc.contains("<think>")) return Visible(acc, false, 0)
        val sb = StringBuilder()
        var i = 0
        var thought = 0
        while (i < acc.length) {
            val open = acc.indexOf("<think>", i)
            if (open < 0) { sb.append(acc, i, acc.length); break }
            sb.append(acc, i, open)
            val close = acc.indexOf("</think>", open + 7)
            if (close < 0) {
                thought += acc.length - open - 7
                return Visible(sb.toString().trimStart(), true, thought)
            }
            thought += close - open - 7
            i = close + 8
        }
        return Visible(sb.toString().trimStart(), false, thought)
    }

    /** The text inside <think>…</think> sections (closed ones), for the work log. */
    fun thoughtsIn(acc: String): String {
        if (!acc.contains("<think>")) return ""
        return Regex("""<think>([\s\S]*?)</think>""").findAll(acc).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /** "12s" or "1m 05s". */
    fun clock(secs: Long): String = if (secs < 60) "${secs}s" else "${secs / 60}m ${(secs % 60).toString().padStart(2, '0')}s"

    /** What to show while no answer text has arrived yet; includes the elapsed time so the app never looks frozen. */
    fun waitingStatus(secs: Long, reasoningChars: Int, local: Boolean): String = when {
        reasoningChars > 0 -> "Thinking… ($reasoningChars characters of reasoning, ${clock(secs)})"
        local && secs >= 3 -> "Waiting for the model… ${clock(secs)} (a model on the phone reads the whole prompt first; this can take a minute)"
        secs >= 1 -> "Thinking… ${clock(secs)}"
        else -> "Thinking…"
    }
}
