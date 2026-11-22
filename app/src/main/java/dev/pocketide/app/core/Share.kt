package dev.pocketide.app.core

/**
 * Share a read-only snapshot of the project as a secret GitHub gist (anyone with the link can read it; nobody can change it).
 * Gists have no folders, so paths are flattened with "__". Hidden files, caches and big or binary files are left out.
 */
object Share {
    const val MAX_FILE = 200_000
    const val MAX_TOTAL = 800_000
    const val MAX_FILES = 80
    private val SKIP = setOf("node_modules", "__pycache__", "venv", "dist", "build", "target", "tmp")

    class Snapshot(val files: Map<String, String>, val skipped: Int)

    fun flatten(path: String): String = path.trim('/').replace("/", "__")

    fun skip(path: String): Boolean {
        val parts = path.trim('/').split('/')
        return parts.any { it.startsWith(".") || it.lowercase() in SKIP }
    }

    /** [read] returns a file's text, or null for a binary or unreadable file. */
    fun snapshot(paths: List<String>, read: (String) -> String?): Snapshot {
        val out = LinkedHashMap<String, String>(); var skipped = 0; var total = 0
        for (p in paths.sorted()) {
            if (skip(p)) continue
            val text = read(p)
            if (text == null || text.isBlank() || text.length > MAX_FILE || total + text.length > MAX_TOTAL || out.size >= MAX_FILES) { skipped++; continue }
            out[flatten(p)] = text; total += text.length
        }
        return Snapshot(out, skipped)
    }

    fun payload(description: String, files: Map<String, String>): String =
        MiniJson.write(linkedMapOf("description" to description, "public" to false, "files" to files.mapValues { mapOf("content" to it.value) }))

    fun parseUrl(json: String): String? =
        (try { MiniJson.obj(MiniJson.parse(json)) } catch (e: Exception) { null })?.get("html_url") as? String

    fun error(code: Int): String = when (code) {
        401 -> "GitHub did not accept the token. Make a new one in Git settings."
        403, 404 -> "The token cannot create gists. Make a token that also has the \"gist\" permission (for a fine-grained token this is not available; use a classic token with the gist scope)."
        422 -> "GitHub refused the snapshot (too large or empty)."
        else -> "GitHub said $code."
    }
}
