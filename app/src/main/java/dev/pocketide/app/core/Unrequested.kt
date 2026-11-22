package dev.pocketide.app.core

/**
 * Stops the assistant from writing documents nobody asked for. Two kinds:
 *  - chat logs and transcripts, which the app saves itself (Export or summarize menu) without a model request;
 *  - README, changelog, notes and to-do files, which are left alone unless the request is about them.
 */
object Unrequested {
    private val CHAT_FILE = Regex("""^(chat|conversation|transcript)[\w .\-]*\.(txt|md|markdown|log)$""", RegexOption.IGNORE_CASE)
    private val DOC_FILE = Regex("""^(readme|changelog|notes?|todo|summary)[\w .\-]*\.(txt|md|markdown|rst)$""", RegexOption.IGNORE_CASE)

    const val CHAT_REFUSAL = "Not written: the app saves chats itself, with no model needed, from the chat's Export or summarize menu. " +
        "Do not write a chat log or transcript file. Tell the user to use that menu."

    /** The message to send back to the model instead of writing [path], or null when the write is fine. [request] is what the user asked for. */
    fun refusal(request: String, path: String): String? {
        val name = path.substringAfterLast('/')
        if (CHAT_FILE.matches(name)) return CHAT_REFUSAL
        val m = DOC_FILE.matchEntire(name) ?: return null
        val base = m.groupValues[1].lowercase()
        val ask = request.lowercase()
        val words = when (base) {
            "readme" -> listOf("readme", "documentation", "docs", "document")
            "changelog" -> listOf("changelog", "change log", "release notes")
            "note", "notes" -> listOf("note")
            "todo" -> listOf("todo", "to-do", "to do")
            else -> listOf("summary", "summarize", "summarise")
        }
        if (words.any { it in ask } || name.lowercase() in ask) return null
        return "Not changed: the user did not ask for changes to $name. Leave it as it is and keep to the task. " +
            "If you think it needs updating, say so in your reply and let the user decide."
    }
}
