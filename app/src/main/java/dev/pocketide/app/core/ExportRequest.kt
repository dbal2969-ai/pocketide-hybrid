package dev.pocketide.app.core

/**
 * "Save this entire chat as a .txt file": the app already does that from the chat's Export or summarize menu, exactly and with no model request.
 * A model would only rebuild it from memory, spend tokens and leave a stray file in the project, so these requests open the menu instead.
 */
object ExportRequest {
    private val ACT = Regex("""\b(save|export|download|back\s*up|backup|summari[sz]e|summary)\b""", RegexOption.IGNORE_CASE)
    // "this chat", "our conversation", "the entire chat": but not "this chat app" or "the chat input", which are about a project.
    private val CHAT = Regex(
        """\b(this|our|entire|whole|full|current)\s+(?:(?:entire|whole|full|current)\s+)*(chat|conversation)\b(?!\s+(app|page|window|feature|ui|input|box|bubble|screen|widget|component|bot|room|server|client|view|list|code|logic|function|module|history\s+table))""",
        RegexOption.IGNORE_CASE
    )

    fun matches(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 160 || t.contains('\n')) return false
        return ACT.containsMatchIn(t) && CHAT.containsMatchIn(t)
    }

    const val REPLY = "The app saves and summarizes chats itself, so this needs no model request and keeps the exact record. Choose below."
}
