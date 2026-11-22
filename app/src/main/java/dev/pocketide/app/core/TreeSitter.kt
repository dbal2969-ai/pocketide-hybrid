package dev.pocketide.app.core

/**
 * Accurate definitions from tree-sitter parsers (MIT licence), used by the repo map when the native library is present. Without
 * it, [definitions] returns null and the pattern matching in [RepoMap] is used.
 */
object TreeSitter {
    /** True once the native library is loaded. */
    @Volatile var ready = false

    private val LANG = mapOf(
        "js" to "javascript", "jsx" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "ts" to "javascript", "tsx" to "javascript",
        "py" to "python", "java" to "java", "kt" to "kotlin", "kts" to "kotlin",
    )

    fun langFor(path: String): String? = LANG[path.substringAfterLast('.', "").lowercase()]

    /** Loads the bundled library. False when it is not in the app. */
    fun load(): Boolean {
        if (ready) return true
        return try { System.loadLibrary("pocketide_ts"); ready = true; true } catch (e: Throwable) { false }
    }

    /** The names a file defines, or null when no parser covers the file or the library is not loaded. */
    fun definitions(path: String, text: String): List<String>? {
        if (!ready) return null
        val lang = langFor(path) ?: return null
        return try { names(lang, text).lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct() } catch (e: Throwable) { null }
    }

    @JvmStatic external fun names(lang: String, source: String): String
}
