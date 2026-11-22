package dev.pocketide.app.core

/**
 * A final reply that stops where the work should start: "The rate is in tax.js. To find it, I should look at this file." Weaker
 * models (Llama 3.3 70B in the 1.5.88 benchmark, 3 of 3 runs) end the turn there, and the person gets no answer. Asked once to
 * do what it named, the model can finish with the tools it already has.
 */
object Deferral {
    private val I = RegexOption.IGNORE_CASE
    private const val VERBS = "look at|look into|take a (?:closer |quick )?look at|have a (?:closer |quick )?look at|read|open|check|inspect|examine|view|run|search|verify|test|find out|find|locate|list (?:all )?(?:the )?(?:project )?(?:files|folders|directories)"

    // "I will use the edit_file function to fix …" (Llama 3.3 70B, hard suite, 1.5.90 candidate): the tool named on the way to the verb
    private const val USE_TOOL = """(?:use\s+(?:the\s+)?`?[\w.]+`?(?:\s+(?:function|tool|command))?\s+to\s+)?"""

    // "I/we should|need to|will|'ll|must|have to|would need to (now) (try to) <verb>", and "let me|let's <verb>". "I can run …" is an offer, so it does not count.
    private val NAMED = Regex(
        """\b(?:(?:i|we)\s*(?:should|need to|will|'ll|must|have to|would (?:first )?need to|am going to|'m going to)|let me|let's)\s+(?:now\s+|first\s+|next\s+)?(?:try to\s+)?$USE_TOOL(?:$VERBS)\b""", I)

    // "To find the exact value, we can read this file.": a step the model needs for its own answer, not an offer to the person
    private val PURPOSE = Regex("""\bto (?:find|get|see|know|learn|confirm|determine|check)\b[^.!?]{0,80}?,?\s*(?:i|we) (?:can|could)\s+(?:$VERBS)\b""", I)

    // The call written out instead of made: "The file is src/tax.js.\nread_file("src/tax.js")"
    private val CALL_AS_TEXT = Regex("""\b(?:read_file|repo_map|file_outline|list_files|run_command|find_references|search_files)\s*\(""")

    // "To fix this, I will change the line where totalPages is calculated." (Llama 3.3 70B, hard suite, 1.5.89): the change itself left for later
    private val CHANGE_NAMED = Regex(
        """\b(?:(?:i|we)\s*(?:should|need to|will|'ll|must|have to|would (?:first )?need to|am going to|'m going to)|let me|let's)\s+(?:now\s+|first\s+|next\s+)?$USE_TOOL""" +
            """(?:change|fix|edit|update|modify|correct|replace|rewrite|add|remove|implement|write|make the change)\b""", I)

    /** True when the closing lines of [reply] say the model is about to make a change. Only meaningful when nothing was changed. */
    fun stopsBeforeChange(reply: String): Boolean {
        val tail = reply.trim().takeLast(300)
        return tail.isNotEmpty() && CHANGE_NAMED.containsMatchIn(tail)
    }

    /** True when the closing lines of [reply] say the model still has to read, check or run something itself. */
    fun stopsShort(reply: String): Boolean {
        val tail = reply.trim().takeLast(300)
        return tail.isNotEmpty() && (NAMED.containsMatchIn(tail) || PURPOSE.containsMatchIn(tail) || CALL_AS_TEXT.containsMatchIn(tail))
    }

    const val CHANGE_ASK = "Your reply ends by saying you will make a change, but nothing has been changed yet. Make it now with the tools " +
        "(edit_file or write_file), check it, then reply. If you cannot, say what stops you."

    // "Here is the change:" as the last words with nothing after them (Llama 3.3 70B, hard suite, 1.5.90 candidate: the fix it had
    // just found, and "Here's the updated test file:", both ended the run)
    private val ANNOUNCED = Regex("""(?:\bhere(?:'s|’s| is| are)\b|\bbelow\b|\bas follows\b|\bthe following\b)[^.!?\n]{0,80}:[*_]*$""", I)

    /** The last line of [reply] when it announces something that does not follow ("Here is the change:"), else null. */
    fun announced(reply: String): String? =
        reply.trim().substringAfterLast('\n').trim().takeIf { ANNOUNCED.containsMatchIn(it) }

    fun announcedAsk(line: String): String = "Your reply ends with \"${line.take(120)}\" and nothing follows it. Nothing written in a " +
        "reply is applied or run: make that change now with the tools (edit_file or write_file), or run that command, check the " +
        "result, then give the complete answer. If you cannot, say what stops you."

    const val ASK = "Your reply ends by saying you still need to look at or check something. You have the tools to do that: call them now, " +
        "then give the complete answer. If you cannot, say what stops you."

    private val READ_VERB = Regex("""\b(?:look at|look into|look in|read|open|check|inspect|examine|view)\b""", I)
    private val SPLIT = Regex("""[\s,;:()'"`\[\]{}<>*]+""")

    /**
     * The project file the closing lines of [reply] say the model will read next ("The cause is in total() in src/checkout.js.
     * Let's read that."), or null. Only for a reply that [stopsShort] with a reading verb; the last file named that is in [paths]
     * and [readable] wins. A name without its folder ("paginate.test.js" for test/paginate.test.js, Llama 3.3 70B in the hard
     * suite) counts when exactly one project file ends with it.
     */
    fun fileToRead(reply: String, paths: Collection<String>, readable: (String) -> Boolean = { true }): String? {
        if (!stopsShort(reply)) return null
        val tail = reply.trim().takeLast(300)
        if (!READ_VERB.containsMatchIn(tail)) return null
        return tail.split(SPLIT).map { it.trimEnd('.', '!', '?').removePrefix("./") }
            .filter { it.contains('.') && !it.startsWith(".") && !it.endsWith("/") }
            .mapNotNull { name -> if (name in paths) name else paths.filter { it.endsWith("/$name") }.singleOrNull() }
            .lastOrNull { readable(it) }
    }

    /**
     * Sent with the file when the reply stopped at reading it a second time. Llama 3.3 70B (hard suite, 1.5.90 candidate), asked
     * once to take the step it named, answered "The cause is in total() in src/checkout.js. Let's read that." and the run ended.
     */
    fun readFor(path: String, shown: String): String =
        "You said you would look at $path, so the app read it for you:\n$shown\n\nNow finish the request with this: answer it, or make " +
            "the change it asks for with the tools and check it. Do not end at another step you only name."
}
