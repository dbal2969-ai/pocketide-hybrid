package dev.pocketide.app.core

/**
 * The settings a person can change for hybrid AI (Models → Hybrid AI). The same values go to Gemini Nano on the phone
 * and to the cloud model, except [onDeviceMaxChars], which decides when a request leaves the phone.
 * Every value is clamped to a range the models accept, so a typo cannot break requests.
 */
data class HybridSettings(
    val temperature: Float = DEFAULT_TEMPERATURE,
    val topK: Int = DEFAULT_TOP_K,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT,
    val onDeviceMaxChars: Int = DEFAULT_ON_DEVICE_CHARS,
    /** The Full on-device model (Gemma 4 E4B) instead of Fast (E2B): slower, stronger, may need its own download. */
    val fullModel: Boolean = false,
) {
    companion object {
        const val DEFAULT_TEMPERATURE = 0.2f
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_MAX_OUTPUT = 1024
        /** The length the on-phone-first mode used before these settings existed. */
        const val DEFAULT_ON_DEVICE_CHARS = 14000

        const val TEMP_MIN = 0f
        const val TEMP_MAX = 2f
        const val TOP_K_MIN = 1
        const val TOP_K_MAX = 100
        const val OUT_MIN = 64
        const val OUT_MAX = 8192
        const val CHARS_MIN = 1000
        const val CHARS_MAX = 30000

        /** Above this, Gemini Nano may read only the start of a request (its documented limit is 4,000 tokens). */
        const val NANO_SAFE_CHARS = 14000

        val DEFAULT = HybridSettings()

        fun clamp(s: HybridSettings): HybridSettings = HybridSettings(
            temperature = s.temperature.coerceIn(TEMP_MIN, TEMP_MAX),
            topK = s.topK.coerceIn(TOP_K_MIN, TOP_K_MAX),
            maxOutputTokens = s.maxOutputTokens.coerceIn(OUT_MIN, OUT_MAX),
            onDeviceMaxChars = s.onDeviceMaxChars.coerceIn(CHARS_MIN, CHARS_MAX),
            fullModel = s.fullModel,
        )

        /** The values the person typed. A field that is not a number keeps its old value; the result is clamped. */
        fun parse(old: HybridSettings, temperature: String, topK: String, maxOutput: String, onDeviceChars: String): HybridSettings =
            clamp(HybridSettings(
                temperature = temperature.trim().toFloatOrNull() ?: old.temperature,
                topK = topK.trim().toIntOrNull() ?: old.topK,
                maxOutputTokens = maxOutput.trim().toIntOrNull() ?: old.maxOutputTokens,
                onDeviceMaxChars = onDeviceChars.trim().toIntOrNull() ?: old.onDeviceMaxChars,
                fullModel = old.fullModel,
            ))

        /** A note to show under the settings when a value may cause trouble, or null. */
        fun warning(s: HybridSettings): String? =
            if (s.onDeviceMaxChars > NANO_SAFE_CHARS)
                "Above $NANO_SAFE_CHARS characters Gemini Nano may read only the start of a request, or the request may fail on the phone. Gemini Nano's documented limit is 4,000 tokens."
            else null
    }
}
