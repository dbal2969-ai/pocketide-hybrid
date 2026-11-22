package dev.pocketide.app.core

/**
 * PocketIDE runs on a phone with no keyboard. A page whose only controls are keyboard keys (a game steered with the arrow keys, say)
 * cannot be used there, so the page check reports it and the assistant is asked to add touch controls.
 */
object TouchCheck {
    private val KEYBOARD = Regex("""\b(keydown|keyup|keypress)\b|\bonkey(down|up|press)\s*=""", RegexOption.IGNORE_CASE)
    private val TOUCH = Regex(
        """\b(touchstart|touchmove|touchend|pointerdown|pointerup|pointermove|mousedown|mouseup|click)\b|\bonclick\s*=|\bontouch\w*\s*=|\bonpointer\w*\s*=|<button\b|<input\b|<select\b|<textarea\b|<a\s[^>]*href""",
        RegexOption.IGNORE_CASE
    )

    const val MESSAGE = "The page can only be controlled with keyboard keys, and this phone has no keyboard. " +
        "Add touch controls (on-screen buttons, taps or swipes) and keep the keys as well."

    private val WEB = setOf("html", "htm", "js", "mjs")

    /** Reads only the web files of a project: path to text. */
    fun isWebFile(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in WEB

    /** One message when the web files listen for keys and nothing in them reacts to touch, taps, buttons or links; otherwise none. */
    fun problems(files: Map<String, String>): List<String> {
        val web = files.filterKeys { isWebFile(it) }.values
        if (web.none { KEYBOARD.containsMatchIn(it) }) return emptyList()
        if (web.any { TOUCH.containsMatchIn(it) }) return emptyList()
        return listOf(MESSAGE)
    }
}
