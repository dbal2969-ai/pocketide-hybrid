package dev.pocketide.app.core

/**
 * How hard a thinking model thinks before each step (1.5.99).
 *
 * In the game-developer logs 85 to 90 per cent of every hour was the model thinking, about half a minute before each step, also
 * before plain reads and searches. OpenAI-style providers take "reasoning_effort" (low, medium, high): GPT-OSS on every host, and,
 * per Cloudflare, its reasoning models including Kimi K2.5/K2.6. The default sends nothing, which leaves each model as it is; the
 * benchmark (POCKETIDE_BENCH_EFFORT) measures what a lower effort costs before any default changes. A provider that rejects the field
 * is remembered for the session and asked again without it, so the setting can never stop a run.
 */
object ThinkEffort {
    val LEVELS = listOf("" to "Model's own (default)", "none" to "Off: no thinking (Ollama)", "low" to "Low: fastest", "medium" to "Medium", "high" to "High: slowest")

    private val rejected = java.util.Collections.synchronizedSet(HashSet<String>())

    /** The extra request field for [level], or nothing (Anthropic has its own thinking settings; a model that refused it). */
    fun extra(level: String, anthropic: Boolean, provider: String, model: String): Map<String, Any?> {
        val l = level.trim().lowercase()
        // "none" (1.5.182): Ollama turns thinking off for models that only switch it on or off, like Qwen3.5 (docs: OpenAI
        // compatibility, reasoning_effort); thinking filled the 8K window on 2-10 runs per suite for qwen3.5 2B/4B ("out of room")
        if (l !in setOf("none", "low", "medium", "high") || anthropic || "$provider/$model" in rejected) return emptyMap()
        return mapOf("reasoning_effort" to l)
    }

    /** True when an error says the provider does not take reasoning_effort for this model. */
    fun rejects(code: Int, message: String): Boolean =
        code in setOf(400, 422) && message.contains("reasoning_effort", ignoreCase = true)

    fun reject(provider: String, model: String) { rejected.add("$provider/$model") }

    fun clearForTests() = rejected.clear()
}
