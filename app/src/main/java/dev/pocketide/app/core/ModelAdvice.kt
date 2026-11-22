package dev.pocketide.app.core

/** Plain advice about a model choice, shown once when the model is likely to struggle with agent work. */
object ModelAdvice {
    private val SMALL = Regex("""(?<![\d.])([1-9]|1[0-4])b(?![a-z\d])""", RegexOption.IGNORE_CASE)
    private val LIGHT = Regex("""(nano|flash-lite|-lite\b|haiku-3|\bmini\b|tiny|1\.5-flash-8b|instant)""", RegexOption.IGNORE_CASE)
    private val NO_TOOLS = Regex("""(gemma|deepseek-r1-distill|-distill|phi-?[1-3]\b|tinyllama|qwen2\.5-coder-[0-9.]+b)""", RegexOption.IGNORE_CASE)

    /** A sentence of advice, or null when nothing is known against the model. [big] is true for a request with many parts. */
    fun note(providerId: String, model: String, big: Boolean): String? {
        val m = model.lowercase()
        if (m.isBlank() || providerId == "demo") return null
        if (NO_TOOLS.containsMatchIn(m)) return "$model often ignores tool calls or fails to edit files. If edits do not appear, switch to a larger model in Settings."
        if (big && (SMALL.containsMatchIn(m) || LIGHT.containsMatchIn(m))) return "$model is a small model and may lose track of a request this big. A larger model (or building in parts) usually does better."
        return null
    }
}
