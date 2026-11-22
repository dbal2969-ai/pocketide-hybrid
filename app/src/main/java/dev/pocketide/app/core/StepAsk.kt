package dev.pocketide.app.core

/**
 * A step of a plan or parts job that ended by asking the person something (1.5.164). In the 2009scape field run, part 2 ended "Should
 * I run setup.sh to clone the full 2009scape source, or do you want me to run build.sh as-is on the skeleton?"; the queue went on to
 * "Fixing step 2 after its check" without showing it, and the placeholder was built. Now the queue stops and shows the question.
 */
object StepAsk {
    private val FOOTER = Regex("""\n+(Stopped before the request was finished|Checks run by the app's record|<details>)[\s\S]*$""")
    private val TO_PERSON = Regex("""\b(should I|shall I|do you want|would you like|would you prefer|want me to|which (one|option|do you)|or (do|would|should) you|can you (confirm|tell)|let me know)\b""", RegexOption.IGNORE_CASE)

    /** The question in [reply]'s closing paragraphs that is put to the person, or null when it does not end by asking them. */
    fun question(reply: String): String? {
        val body = FOOTER.replace(reply.trim(), "").trim()
        if (body.isEmpty()) return null
        val tail = body.split(Regex("""\n\s*\n""")).takeLast(2).joinToString("\n\n")
        // sentences end at . ! ? followed by a space or a line end, so file names (setup.sh, server.jar) stay inside their sentence
        val sentences = tail.split(Regex("""(?<=[.!?])\s+|\n""")).map { it.trim() }.filter { it.endsWith("?") && TO_PERSON.containsMatchIn(it) }
        return sentences.lastOrNull()?.trim('*', ' ')?.takeIf { it.length >= 12 }
    }

    fun answered(answer: String): String =
        "The person answered your question: \"" + answer.trim() + "\". Carry on with this step accordingly, then finish it."

    const val BEST_GUESS = "The person chose not to answer. Go ahead with the option that best fits the original request, say in one line " +
        "which you chose and why, then finish this step."
}
