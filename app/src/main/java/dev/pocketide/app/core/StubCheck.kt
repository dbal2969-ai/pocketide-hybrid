package dev.pocketide.app.core

/**
 * Finds files the assistant wrote as stand-ins ("// TODO: implement", "...rest of the code", empty bodies) so they are
 * finished before the assistant says the work is done.
 */
object StubCheck {
    private val CODE = setOf("py", "js", "mjs", "ts", "tsx", "jsx", "go", "rs", "c", "cc", "cpp", "h", "java", "kt", "sh", "rb", "php", "cs", "swift", "html", "css")
    // "// implement the function according to the rules in README.md\n// for now, just return 0" (Llama 3.3 70B, hard suite) said "implemented"
    private val MARK = Regex("""(?im)(//|#|/\*|<!--)\s*(\.\.\.|todo:?\s*implement|implement\s+(me|this|later|it\b|the\s+(function|method|logic|body|rest)\b)|rest of (the )?(code|file)|existing code|your code here|placeholder|for now,?\s*(just\s+)?(return|pass|do nothing)\b|((add|put|write)\s+)?(your|the)\s+(code|logic|implementation)\s+(goes\s+)?here[.:]?[ \t]*(\*/|-->)?[ \t]*$)|not implemented|NotImplemented(Error)?\b|\bpass\s*#\s*todo""")

    /** Why [content] of [path] looks unfinished, or null. */
    fun find(path: String, content: String): String? {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext !in CODE) return null
        if (content.isBlank()) return "the file is empty"
        val m = MARK.find(content) ?: return null
        // 1.5.184: name the whole line and its number: Gemini Nano, told only "Placeholder", edited a different comment and the run ended unfinished
        val start = content.lastIndexOf('\n', m.range.first) + 1
        val end = content.indexOf('\n', m.range.first).let { if (it < 0) content.length else it }
        val line = content.substring(start, end).trim().take(120)
        val no = content.substring(0, start).count { it == '\n' } + 1
        val tail = if (line.startsWith("//") || line.startsWith("#") || line.startsWith("/*") || line.startsWith("<!--"))
            "; if the code under it already works, delete this comment line (edit_file with search set to exactly this line and replace empty)" else ""
        return "line $no is a stand-in: `$line`$tail"
    }

    fun note(path: String, why: String): String =
        "Warning: $path looks unfinished: $why. Write the real code now; do not leave stand-ins or say the work is done while they remain."

    fun request(stubs: Map<String, String>): String =
        "These files still look unfinished:\n" + stubs.entries.joinToString("\n") { "- ${it.key}: ${it.value}" } +
            "\nReplace each stand-in with working code, or remove the file if it is not needed, then give your final answer. Do not say the work is complete until this is done."
}
