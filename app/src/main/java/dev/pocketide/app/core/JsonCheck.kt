package dev.pocketide.app.core

/**
 * A quick check, run after every write, that catches a JSON file the game or page cannot load (the broken data file in a
 * project would have been caught here). Other file types are checked by the game check and the page look. Returns a note for the
 * assistant, or "".
 */
object JsonCheck {
    fun note(path: String, text: String): String {
        if (!path.endsWith(".json", ignoreCase = true)) return ""
        return try { MiniJson.parse(text); "" } catch (e: Exception) {
            " WARNING: $path is not valid JSON (${(e.message ?: "syntax error").take(120)}). Fix it before anything else: the game or page will not load while it is broken."
        }
    }
}
