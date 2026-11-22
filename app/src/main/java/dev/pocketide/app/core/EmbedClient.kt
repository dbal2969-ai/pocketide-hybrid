package dev.pocketide.app.core

/**
 * Calls an OpenAI-style /embeddings endpoint for [SemanticIndex] (1.5.141). The address comes from the provider's chat address
 * (…/chat/completions becomes …/embeddings), so the key and server the person already set for that provider are used. Anthropic
 * has no embeddings endpoint, so it is not offered. Runs off the main thread.
 */
object EmbedClient {
    /** The /embeddings address for [p] (with the server address [base] for custom providers), or null when it has none. */
    fun url(p: Provider, base: String): String? {
        if (p.kind != ProviderKind.OPENAI) return null
        val chat = (if (p.custom) Providers.customUrl(base) else p.url) ?: return null
        if (chat.contains("ACCOUNT_ID") || !Regex("/chat/completions/?$").containsMatchIn(chat)) return null
        return chat.replace(Regex("/chat/completions/?$"), "/embeddings")
    }

    /** "provider:model" as a slot, or null when blank or malformed. */
    fun slot(setting: String): Workflow.Slot? {
        val s = setting.trim()
        if (!s.contains(':')) return null
        val p = s.substringBefore(':').trim(); val m = s.substringAfter(':').trim()
        return if (p.isEmpty() || m.isEmpty()) null else Workflow.Slot(p, m)
    }

    /** One vector per text, in order. Throws with a readable message on a failed call. */
    fun embed(url: String, key: String, model: String, texts: List<String>): List<FloatArray> {
        val c = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20_000
            c.readTimeout = 90_000
            c.setRequestProperty("Content-Type", "application/json")
            if (key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $key")
            c.outputStream.use { it.write(SemanticIndex.requestBody(model, texts).toByteArray()) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("the embeddings model answered HTTP $code: " + body.take(200))
            return SemanticIndex.parseVectors(body)
        } finally { c.disconnect() }
    }
}
