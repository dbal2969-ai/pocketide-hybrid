package dev.pocketide.app.core

/** Where PocketIDE hands a chat to the Claude app (or claude.ai when the app is not installed). */
object ClaudeLink {
    /** The Android package of the Claude app. */
    const val PACKAGE = "com.anthropic.claude"
    const val WEB = "https://claude.ai/new"
    const val MAX_CHARS = 60_000

    /** The text shared into Claude: a line on where it came from, then the chat, cut short when it is very long. */
    fun shareBody(title: String, markdown: String, max: Int = MAX_CHARS): String {
        val body = if (markdown.length <= max) markdown else markdown.take(max) + "\n\n… (${markdown.length - max} more characters left out)"
        return "Continuing a chat from PocketIDE: ${title.ifBlank { "untitled" }}\n\n$body"
    }
}
