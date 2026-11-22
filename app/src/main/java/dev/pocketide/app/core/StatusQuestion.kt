package dev.pocketide.app.core

/**
 * "Are you still working?" asked while nothing is running. The assistant cannot know that, and a model call would only produce a
 * made-up "yes, still here", so the app answers it itself without sending a request.
 */
object StatusQuestion {
    private val ASK = Regex(
        """^\W*(hey|hi|hello|ok|okay)?\W*(are|is)\s+(you|it|this|that|the\s+(ai|assistant|app))\s+(still\s+)?(working|running|there|done|finished|finishing|stuck|frozen|alive|busy)\b[^.!]{0,40}$""",
        RegexOption.IGNORE_CASE
    )
    private val SHORT = Regex("""^\W*(still\s+working|any\s+progress|any\s+update|status|what'?s\s+the\s+status|are\s+you\s+done|you\s+there)\W*$""", RegexOption.IGNORE_CASE)

    fun matches(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 60 || t.contains('\n')) return false
        return ASK.matches(t.trimEnd('?', '!', ' ', '.')) || SHORT.matches(t)
    }

    const val REPLY = "Nothing is running right now. The last request has finished. " +
        "If something is missing or not working, tell me what you see and I'll change it."
}
