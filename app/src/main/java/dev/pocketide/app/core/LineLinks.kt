package dev.pocketide.app.core

/** Links to lines of a file ("game/main.js:12-20"), as GitHub's line links, for the chat. Pure logic. */
object LineLinks {
    fun link(path: String, first: Int, last: Int): String = if (first == last) "$path:$first" else "$path:$first-$last"

    /** The request sent when the person asks about selected lines. */
    fun ask(path: String, first: Int, last: Int, code: String): String =
        "About ${link(path, first, last)}:\n```\n" + code.take(6000) + "\n```\nExplain what these lines do and anything wrong with them. Do not change files unless I ask."

    private val LINK = Regex("""(?<![\w/.-])((?:[\w.-]+/)*[\w.-]+\.[A-Za-z0-9]{1,8}):(\d{1,6})(?:-(\d{1,6}))?\b""")

    /** Line links in a message: path with its first and last line. */
    fun find(text: String): List<Triple<String, Int, Int>> = LINK.findAll(text).map { m ->
        val a = m.groupValues[2].toInt(); val b = m.groupValues[3].toIntOrNull() ?: a
        Triple(m.groupValues[1], minOf(a, b), maxOf(a, b))
    }.toList()

    /** The linked lines' text, added to a message so the model sees what the links point at. */
    fun expand(text: String, cap: Int = 6000, read: (String) -> String?): String {
        val sb = StringBuilder()
        for ((p, a, b) in find(text).distinct().take(6)) {
            if (sb.length > cap) break
            val lines = read(p)?.split("\n") ?: continue
            if (a > lines.size) continue
            sb.append("\n\n${link(p, a, b)}:\n```\n").append(lines.subList(a - 1, minOf(b, lines.size)).mapIndexed { i, l -> "${a + i}: $l" }.joinToString("\n").take(3000)).append("\n```")
        }
        return sb.toString()
    }
}
