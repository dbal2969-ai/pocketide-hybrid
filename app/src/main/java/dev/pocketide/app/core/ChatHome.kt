package dev.pocketide.app.core

/**
 * A chat with no project (1.5.125, phase 5 of ROADMAP-HANDOFF.md): talk to the assistant the way you would in the Claude or Copilot
 * app, about anything, without opening or making a project. It is a hidden project folder (Projects.CHAT, its name starts with a dot
 * so no project list shows it), so chats, history, models, attachments and "Continue" work exactly as in a project. The assistant
 * only reads there (Ask mode, fixed); "Build this" turns the conversation into a new project.
 *
 * Also: search across the chats of every project, as the Claude app searches all conversations. Pure logic.
 */
object ChatHome {
    const val LABEL = "Chat (no project)"

    /** Added to the assistant's instructions in the Chat home. */
    const val NOTE = "\n\nThis is a general chat with no project open. Answer the person directly and fully, about anything, as a helpful " +
        "assistant would: explain, compare, write code or text in your reply, and use web search when it is available and the answer depends on " +
        "current facts. You can also work on the person's projects with the projects tool: list them, read and search any of them, copy files " +
        "and folders from one project to another, make a branch and commit in a project. When they name a project (\"my empty-project-10\"), " +
        "find it with action list and do what they asked there; never say it is not possible. For building something new from this chat, " +
        "they can tap \"Build this\" to turn the conversation into a project."

    /** True when [text] names one of [projects] (loosely: "empty project 10" names Empty-project-10), 1.5.168. */
    fun namesProject(text: String, projects: List<String>): Boolean {
        val t = text.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (projects.any { p -> val k = p.lowercase().replace(Regex("[^a-z0-9]"), ""); k.length >= 4 && t.contains(k) }) return true
        // 1.5.170: part of a name ("my scape project" for 2009scape-project): a distinctive word of the message inside a project's name
        val words = Regex("[a-z0-9]{4,}").findAll(text.lowercase()).map { it.value }.filter { it !in COMMON }.toSet()
        return projects.any { p ->
            val k = p.lowercase().replace(Regex("[^a-z0-9]"), "")
            val parts = Regex("[a-z]{4,}|[0-9]{2,}").findAll(p.lowercase()).map { it.value }.filter { it !in COMMON }.toList()
            words.any { w -> parts.any { it == w } || (w.length >= 5 && k.contains(w)) }
        }
    }

    private val COMMON = setOf("project", "projects", "file", "files", "folder", "folders", "this", "that", "these", "those", "with", "from",
        "into", "make", "made", "take", "copy", "move", "branch", "chat", "code", "game", "games", "work", "working", "please", "also", "able",
        "test", "tests", "data", "new", "empty", "main", "home", "app", "apps", "page", "site", "web", "then", "them", "they", "what", "want",
        "need", "have", "your", "mine", "just", "like", "some", "more", "over", "only", "when", "where", "which", "about", "there", "here")

    /** The empty Chat home (1.5.127): it showed the project chat's "Changes appear as cards…" and "Add a dark mode toggle". */
    const val EMPTY = "Ask anything: explain, compare, plan, or write code and text in the reply. It can also work on your projects by name: " +
        "read them, copy files between them, make a branch. To build something new, tap the mode chip and Build this as a project."
    val STARTERS = listOf("Explain how HTTP caching works", "Compare SQLite and IndexedDB for a small web app", "Write a regex that matches email addresses")

    /** The new project's name, from the chat's title. */
    fun projectName(title: String): String =
        title.replace(Regex("""[^A-Za-z0-9 _-]"""), " ").trim().replace(Regex("""\s+"""), " ").take(40).ifBlank { "New project" }

    /** Typed for the person after "Build this", in the new project's chat: the conversation above is the request. */
    const val BUILD_REQUEST = "Build what we discussed above."

    class Hit(val project: String, val chatId: String, val title: String, val snippet: String, val updated: Long)

    /**
     * The chats in [chats] (project name, chat) that contain every word of [query], titles first, then newest first, each with a
     * short piece of text around the first match. An empty query lists the newest chats of every project.
     */
    fun search(chats: List<Pair<String, ChatSession>>, query: String, max: Int = 60): List<Hit> {
        val words = query.lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }
        val hits = chats.mapNotNull { (project, c) ->
            val body = c.entries.joinToString("\n") { typed(it.content) }
            val all = (c.title + "\n" + body).lowercase()
            if (!words.all { all.contains(it) }) return@mapNotNull null
            val inTitle = words.isNotEmpty() && words.all { c.title.lowercase().contains(it) }
            Triple(Hit(project, c.id, c.title, snippet(body, words), c.updated), inTitle, c.updated)
        }
        return hits.sortedWith(compareBy<Triple<Hit, Boolean, Long>>({ !it.second }, { -it.third })).take(max).map { it.first }
    }

    private val APP_ADDED = listOf("\n\nThe person approved this list of requirements", "\n\nWork only inside the folder \"")

    /** A saved message without the text the app added after the person's own words (1.5.172: search showed "The person approved…"). */
    fun typed(content: String): String {
        val at = APP_ADDED.map { content.indexOf(it) }.filter { it > 0 }.minOrNull() ?: return content
        return content.substring(0, at)
    }

    /** About 120 characters around the first word found, on one line. */
    fun snippet(text: String, words: List<String>): String {
        val flat = text.replace(Regex("""\s+"""), " ").trim()
        val at = words.map { flat.lowercase().indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val from = (at - 40).coerceAtLeast(0)
        val to = (from + 120).coerceAtMost(flat.length)
        return (if (from > 0) "…" else "") + flat.substring(from, to) + (if (to < flat.length) "…" else "")
    }

    fun projectLabel(name: String, chatKey: String): String = if (name == chatKey) LABEL else name
}
