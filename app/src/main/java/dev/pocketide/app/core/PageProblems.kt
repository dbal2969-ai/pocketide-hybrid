package dev.pocketide.app.core

/**
 * Sorts what a page check or look_at_preview found into problems that stop the page and notes that do not.
 *
 * Before 1.5.76 every missing file was a blocking error and went back to the model as a fix request, up to ten times. A game
 * that streams map squares, pictures and sounds on demand asks for files that were never exported; the browser answers 404
 * and the game carries on. One run spent about two million tokens, over two requests, trying to make such 404s go away
 * ("the page check still fails after 10 fix attempts: 404 Not found: game/data/objects/50_50.json").
 *
 * A missing script, style sheet, page or wasm module stops the page, so it blocks. A missing data or media file is a note:
 * reported once, in the summary, never a fix request, unless the person's request names that file.
 */
object PageProblems {
    const val MISSING = "404 Not found: "

    /** File types a page cannot run without. Data (.json, .txt), pictures, sounds and fonts are handled by the page's own code. */
    val CODE_EXT = setOf("js", "mjs", "cjs", "css", "html", "htm", "wasm")

    class Split(val blocking: List<String>, val notes: List<String>)

    fun isMissing(e: String): Boolean = e.startsWith(MISSING)
    fun missingPath(e: String): String = e.removePrefix(MISSING).trim().substringBefore('?')

    private fun ext(path: String): String = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** A console line the browser writes for the same 404 ("Failed to load resource: the server responded with a status of 404"). */
    private fun isLoadEcho(e: String): Boolean = e.contains("Failed to load resource", true) && e.contains("404")

    /** True when a missing file stops the page: code, or a file the request names. */
    fun blocks(e: String, request: String = ""): Boolean {
        if (!isMissing(e)) return !isLoadEcho(e)
        val p = missingPath(e)
        if (ext(p) in CODE_EXT) return true
        val name = p.substringAfterLast('/')
        return name.length >= 4 && request.contains(name, ignoreCase = true)
    }

    fun split(errors: List<String>, request: String = ""): Split {
        val b = ArrayList<String>(); val n = ArrayList<String>()
        for (e in errors.distinct()) if (blocks(e, request)) b.add(e) else if (isMissing(e)) n.add(e)
        return Split(b, n)
    }

    /** Pictures: when one is missing the page still runs, but the person sees a blank where it should be. */
    val PICTURE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp")

    /** A picture the person would see blank. Map textures for squares that were never exported (ground, scene, map tiles) stay notes. */
    fun isPicture(path: String): Boolean = ext(path) in PICTURE_EXT && !Regex("""(^|/)(ground|grounds|scene|scenes|maps?|minimap|tiles)/""", RegexOption.IGNORE_CASE).containsMatchIn(path)

    private fun list(paths: List<String>): String = paths.take(6).joinToString(", ") + (if (paths.size > 6) " and ${paths.size - 6} more" else "")

    /**
     * Lines for the summary: which optional files were asked for and do not exist. Empty when there are none.
     * 1.5.98: pictures are not "not a failure". The person sees them blank (a tinderbox with no picture in the inventory), and
     * the old wording led the model to tell the person to make them, though its own cache tool exports them.
     */
    fun noteLine(notes: List<String>): String {
        if (notes.isEmpty()) return ""
        val paths = notes.map { missingPath(it) }.distinct()
        val pics = paths.filter { isPicture(it) }
        val data = paths - pics.toSet()
        val sb = StringBuilder()
        if (pics.isNotEmpty()) {
            val ids = pics.mapNotNull { Regex("""(?:^|/)(\d+)\.\w+$""").find(it)?.groupValues?.get(1) }.distinct()
            sb.append("Missing picture${if (pics.size == 1) "" else "s"} (shown blank on the page): ").append(list(pics)).append(". ")
            sb.append("Make or export ${if (pics.size == 1) "it" else "them"} rather than leaving it to the person")
            if (ids.isNotEmpty()) sb.append("; in a game made from a cache, game_cache action=export kind=icons ids=").append(ids.take(20).joinToString(",")).append(" writes item pictures")
            sb.append(".")
        }
        if (data.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("Note: ${data.size} requested data file${if (data.size == 1) "" else "s"} do${if (data.size == 1) "es" else ""} not exist " +
                "(the page asks for ${if (data.size == 1) "it" else "them"} and carries on without; not a failure): ").append(list(data)).append(".")
        }
        return sb.toString()
    }
}
