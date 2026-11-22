package dev.pocketide.app.core

/**
 * Next-edit suggestions from Sweep's open next-edit model (1.5.143, Cursor parity P4; experimental). sweepai/sweep-next-edit-1.5B is
 * Apache-2.0, about 1.5 GB as a Q8 GGUF, small enough to run on a phone in Ollama or llama.cpp; the app does not ship it. When the
 * suggestion model's name contains "sweep-next-edit", the editor asks it in its own format instead of the chat prompt of [NextEdit]:
 * the recent change as original/updated blocks, then the 21-line window around the cursor (10 above, the cursor's line, 10 below)
 * as it was before the change (original/), as it is (current/), and an open updated/ block the model writes. The format is the one
 * Sweep describes (blog.sweep.dev/posts/oss-next-edit); details not stated there (blank lines between blocks) follow its layout and
 * are unverified until it runs on a phone. The rewritten window is turned into one SEARCH/REPLACE edit and goes through
 * [NextEdit.validate], so it is checked like any other suggestion before it shows.
 */
object SweepNextEdit {
    const val SEP = "<|file_sep|>"
    const val ABOVE = 10
    const val BELOW = 10
    val STOPS = listOf(SEP, "<|endoftext|>", "</s>", "<|im_end|>")

    fun isSweep(model: String): Boolean = Regex("""sweep[-_]next[-_]edit""", RegexOption.IGNORE_CASE).containsMatchIn(model)

    /** The 0-based first line and the lines of the window around [cursorLine] in [lines]. */
    fun window(lines: List<String>, cursorLine: Int): Pair<Int, List<String>> {
        val c = cursorLine.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val from = (c - ABOVE).coerceAtLeast(0)
        val to = (c + BELOW + 1).coerceAtMost(lines.size)
        return from to lines.subList(from, to)
    }

    /**
     * The prompt: [before] is the file before the person's recent change, [now] the file as it is, [recent] that change, [cursor] the
     * cursor's offset in [now]. Returns the prompt and the current window (first line, text) the answer replaces.
     */
    fun prompt(path: String, before: String, now: String, recent: NextEdit.Recent, cursor: Int): Pair<String, Pair<Int, String>> {
        val p = path.ifEmpty { "untitled" }
        val nowLines = now.split('\n')
        val cursorLine = now.substring(0, cursor.coerceIn(0, now.length)).count { it == '\n' }
        val (from, cur) = window(nowLines, cursorLine)
        // the same stretch before the change: lines after the change are shifted by the lines it added or removed
        val beforeLines = before.split('\n')
        val shift = nowLines.size - beforeLines.size
        val oFrom = (if (from > recent.firstLine) from - shift else from).coerceIn(0, (beforeLines.size - 1).coerceAtLeast(0))
        val orig = beforeLines.subList(oFrom, (oFrom + cur.size).coerceAtMost(beforeLines.size))
        val removed = recent.diff.lines().filter { it.startsWith("-") }.joinToString("\n") { it.drop(1) }
        val added = recent.diff.lines().filter { it.startsWith("+") }.joinToString("\n") { it.drop(1) }
        val text = buildString {
            append(SEP).append(p).append(".diff\n")
            append("original:\n").append(removed).append("\nupdated:\n").append(added).append('\n')
            append(SEP).append("original/").append(p).append('\n').append(orig.joinToString("\n")).append('\n')
            append(SEP).append("current/").append(p).append('\n').append(cur.joinToString("\n")).append('\n')
            append(SEP).append("updated/").append(p).append('\n')
        }
        return text to (from to cur.joinToString("\n"))
    }

    /** The model's rewritten window from a raw completion: cut at the first stop marker, one trailing newline dropped. */
    fun rewritten(completion: String): String {
        var t = completion.replace("\r", "")
        for (s in STOPS) t.indexOf(s).let { if (it >= 0) t = t.substring(0, it) }
        return t.removeSuffix("\n")
    }

    /**
     * The edit between the current [window] and the model's [rewrite] as one SEARCH/REPLACE suggestion: the lines the two share at
     * both ends are left out; a pure insertion keeps one line above it so the old text is not empty. Null when nothing changed.
     */
    fun suggestion(window: String, rewrite: String): NextEdit.Suggestion? {
        if (rewrite.isBlank() || rewrite == window) return null
        val a = window.split('\n'); val b = rewrite.split('\n')
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        var start = pre
        if (start == a.size - suf && start > 0) start--   // nothing removed: anchor on the line above
        val old = a.subList(start, a.size - suf).joinToString("\n")
        val new = b.subList(start, b.size - suf).joinToString("\n")
        return if (old.isBlank() || old == new) null else NextEdit.Suggestion(old, new)
    }

    /** The body of an OpenAI-style /completions request (raw prompt, no chat template). */
    fun requestBody(model: String, prompt: String, maxTokens: Int = 512): String =
        MiniJson.write(mapOf("model" to model, "prompt" to prompt, "max_tokens" to maxTokens, "temperature" to 0, "stop" to STOPS))

    /** The text of a /completions answer, or null. */
    @Suppress("UNCHECKED_CAST")
    fun parseCompletion(json: String): String? {
        val root = MiniJson.parse(json) as? Map<String, Any?> ?: return null
        val c = (root["choices"] as? List<Any?>)?.firstOrNull() as? Map<String, Any?> ?: return null
        return c["text"] as? String
    }

    /** The /completions address for a provider's chat address (…/chat/completions), or null. */
    fun url(chatUrl: String?): String? = chatUrl?.takeIf { Regex("/chat/completions/?$").containsMatchIn(it) && !it.contains("ACCOUNT_ID") }
        ?.replace(Regex("/chat/completions/?$"), "/completions")

    /**
     * Ollama's native address for a raw prompt (1.5.146): its OpenAI-style /v1/completions does not say whether it wraps the prompt in
     * the model's chat template, which would break Sweep's format; /api/generate with raw: true sends it untouched. Null when [url] is
     * not Ollama's (the port 11434, or a path ending /v1/completions on the provider marked ollama).
     */
    fun ollamaUrl(url: String, providerId: String): String? =
        if (providerId == "ollama" || Regex(""":11434/""").containsMatchIn(url)) url.replace(Regex("/v1/completions/?$"), "/api/generate").takeIf { it != url } else null

    fun ollamaBody(model: String, prompt: String, maxTokens: Int = 512): String =
        MiniJson.write(mapOf("model" to model, "prompt" to prompt, "raw" to true, "stream" to false,
            "options" to mapOf("temperature" to 0, "num_predict" to maxTokens, "stop" to STOPS)))

    @Suppress("UNCHECKED_CAST")
    fun parseOllama(json: String): String? = (MiniJson.parse(json) as? Map<String, Any?>)?.get("response") as? String

    /** Calls [url] with [prompt]; the completion text. Throws with a readable message. Off the main thread. */
    fun complete(url: String, key: String, model: String, prompt: String, providerId: String = ""): String {
        val native = ollamaUrl(url, providerId)
        val target = native ?: url
        val c = java.net.URI(target).toURL().openConnection() as java.net.HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 15_000; c.readTimeout = 60_000
            c.setRequestProperty("Content-Type", "application/json")
            if (key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $key")
            c.outputStream.use { it.write((if (native != null) ollamaBody(model, prompt) else requestBody(model, prompt)).toByteArray()) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: " + body.take(160))
            return (if (native != null) parseOllama(body) else parseCompletion(body)) ?: throw IllegalStateException("no completion text in the answer")
        } finally { c.disconnect() }
    }
}
