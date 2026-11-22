package dev.pocketide.app.core

/**
 * Pictures for a model that cannot see them (1.5.129). Before, the app only warned ("may not be able to see images") and offered
 * to send the message without them. Now, when another provider the person has a key for has a model that sees images, that model
 * describes the pictures once and the description goes to the main model as text, so a text-only model (Kimi, a Groq model) can
 * still work from a screenshot. Tool calls already had the same kind of bridge (TextTools).
 */
object ImageHelper {
    /** Providers tried in this order: their usual models see images, and Gemini has a free tier. */
    val ORDER = listOf("gemini", "openrouter", "openai", "anthropic")

    /** The provider and model to describe pictures with, or null. [model] gives the model in use for a provider. */
    fun pick(current: String, canCall: (String) -> Boolean, model: (String) -> String, sees: (String, String) -> Boolean): Pair<String, String>? =
        ORDER.filter { it != current && canCall(it) }.map { it to model(it) }.firstOrNull { (p, m) -> sees(p, m) }

    fun prompt(count: Int): String =
        "Describe ${if (count == 1) "this image" else "these $count images, one by one (Image 1, Image 2, …)"} for a programmer who cannot see " +
            "${if (count == 1) "it" else "them"} and will work only from your words. Copy all visible text exactly: code, error messages, " +
            "labels, numbers, file names. Then describe the layout: what is where, sizes and alignment, colours, icons, and anything that " +
            "looks broken, cut off or overlapping. Plain text, no introduction, no advice."

    /** The person's message with the description added, for the main model. */
    fun compose(message: String, describer: String, description: String, count: Int): String =
        message.trimEnd() + "\n\n[${if (count == 1) "The attached image" else "The $count attached images"}, described by $describer " +
            "because the model you are cannot see images. Work from this description; you cannot see the picture yourself.]\n" +
            description.trim()
}
