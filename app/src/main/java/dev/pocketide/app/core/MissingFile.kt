package dev.pocketide.app.core

/**
 * The reply when the model edits a file that does not exist. Weaker models guess a name ("map.txt") and, told only to use
 * write_file, create a junk file instead of finding the real one; listing the closest existing files steers them back.
 */
object MissingFile {
    private const val SHOWN = 8

    fun note(path: String, existing: List<String>): String {
        val files = existing.filter { !it.endsWith("/") }
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        // Llama 3.3 70B guessed utils.py in a JavaScript project and, told to use write_file, created it (hard suite, 1.5.90 candidate)
        val head = "No text file named $path." +
            if (ext.isNotEmpty() && files.isNotEmpty() && files.none { it.lowercase().endsWith(".$ext") }) " The project has no .$ext files." else ""
        val close = closest(path, existing)
        if (close.isEmpty() && files.isEmpty()) return "$head Use write_file to create it, or list_files to see the project."
        if (close.isEmpty()) return "$head Find the file that holds the code first: search for a name from it, or list_files. " +
            "Use write_file only for a genuinely new file."
        return "$head Existing files that may be the one you mean: ${close.joinToString(", ")}. " +
            "Read the right one and edit it; use write_file only for a genuinely new file."
    }

    fun closest(path: String, existing: List<String>): List<String> {
        val name = path.substringAfterLast('/').lowercase()
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        val words = Regex("[a-z0-9]+").findAll(path.lowercase()).map { it.value }.filter { it.length > 2 }.toSet()
        fun score(f: String): Int {
            val l = f.lowercase(); val n = l.substringAfterLast('/')
            var s = 0
            if (n == name) s += 100
            if (stem.isNotEmpty() && n.substringBeforeLast('.') == stem) s += 50
            if (ext.isNotEmpty() && n.endsWith(".$ext")) s += 5
            s += words.count { l.contains(it) } * 10
            return s
        }
        val scored = existing.filter { !it.endsWith("/") }.map { it to score(it) }
        val hits = scored.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }).map { it.first }
        // a tiny project: just show everything, which beats a blind guess
        val pick = if (hits.isEmpty() && existing.size <= SHOWN) existing.sorted() else hits
        return pick.take(SHOWN)
    }
}
