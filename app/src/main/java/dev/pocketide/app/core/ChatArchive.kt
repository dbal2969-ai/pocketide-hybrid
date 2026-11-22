package dev.pocketide.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every saved chat of a project in one Markdown file, newest first, so a project's conversations can be kept or shared together. */
object ChatArchive {
    /** The most of one chat's assistant record kept in the archive (its start and end), so one long chat cannot fill the file. */
    const val RECORD_MAX = 200_000

    fun path(now: Long = System.currentTimeMillis()): String =
        "${ChatExport.DIR}/all-chats-${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(now))}.md"

    /**
     * [record] gives a chat's assistant record (every command it ran, with the output; 1.5.121), or null when it has none:
     * it goes under the chat, keys hidden, so one file holds the messages, the thinking, the steps and what the commands printed.
     */
    fun markdown(chats: List<ChatSession>, project: String, header: List<String> = emptyList(), record: (ChatSession) -> String? = { null }): String {
        val sorted = chats.sortedByDescending { it.updated }
        val sb = StringBuilder()
        sb.append("# All chats in ").append(project.ifBlank { "this project" }).append("\n\n")
        sb.append("- Chats: ").append(sorted.size).append("\n")
        for (h in header) sb.append("- ").append(h).append("\n")
        sb.append("\n")
        for (c in sorted) {
            sb.append(ChatExport.markdown(c).replaceFirst("# ", "## "))
            record(c)?.trim()?.takeIf { it.isNotEmpty() }?.let { r ->
                val text = ChatExport.redact(if (r.length <= RECORD_MAX) r else r.take(RECORD_MAX / 2) + "\n\n… (${r.length - RECORD_MAX} characters left out) …\n\n" + r.takeLast(RECORD_MAX / 2))
                val f = ChatExport.fence(text)
                sb.append("\n### Assistant record: the commands it ran, with their output\n\n<details><summary>Show</summary>\n\n")
                    .append(f).append("text\n").append(text).append("\n").append(f).append("\n\n</details>\n")
            }
            sb.append("\n\n---\n\n")
        }
        return sb.toString()
    }
}
