package dev.pocketide.app.core

/**
 * A request that takes things from another project kept inside this one ("from the 2009scape-project I imported, build the quests
 * into this game"), 1.5.167. In the field run the model listed the imported folder, was told it was empty (the capped file list),
 * decided the server's source "is not readable", and later wrote a "simplified" Cook's Assistant from memory beside the real
 * CooksAssistant.kt. The person should not have to give the path: when the request names a folder in the project and asks to take,
 * merge, port or bring things from it, the model is told which folder it is, how many files it has, and to work from them.
 */
object PortSource {
    private val TAKE = Regex("""(?i)\b(from|out of|using|based on|merge|merging|port|porting|bring|import(?:ed)?|copy|take|pull|extract|convert)\b""")

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** The project folder the request takes things from, or null. [folders] are folder paths (top level and one below). */
    fun folder(request: String, folders: List<String>): String? {
        if (!TAKE.containsMatchIn(request)) return null
        val words = norm(request)
        var best: String? = null
        for (f in folders.sortedBy { it.count { c -> c == '/' } }) {
            val name = f.trimEnd('/').substringAfterLast('/')
            val keys = listOf(norm(name), norm(name.replace(Regex("(?i)[-_ ]?(project|repo|source|src|master|main)$"), ""))).filter { it.length >= 4 }.distinct()
            if (keys.any { words.contains(it) }) { if (best == null || name.length > best.substringAfterLast('/').length) best = f.trimEnd('/') }
        }
        return best
    }

    /** The note added to the request: which folder, how big, and to read its source rather than write from memory. */
    fun note(folder: String, files: Int, sourceDirs: List<String>): String =
        "\n\n(App note: the request takes things from the project folder \"$folder/\" ($files files). That folder is the source: for each thing " +
            "you bring over, find its files there (search with path \"$folder\", or list_files on a folder below it) and read them before you write, " +
            "then follow what they do; do not write a simplified version from memory, and do not decide the folder is empty because a list left it out. " +
            (if (sourceDirs.isEmpty()) "" else "Source folders in it: " + sourceDirs.take(8).joinToString(", ") + ". ") +
            "In your answer, name the source file each change came from.)"

    /** Folders under [folder] that hold most of its source code (by extension), deepest common ones first, for the note. */
    fun sourceDirs(files: List<String>, folder: String): List<String> {
        val code = setOf("kt", "kts", "java", "js", "ts", "py", "lua", "cs", "cpp", "c", "rs", "go", "rb", "php", "gd")
        val counts = HashMap<String, Int>()
        for (p in files) {
            if (p.substringAfterLast('.', "").lowercase() !in code) continue
            val parts = p.removePrefix("$folder/").split('/')
            // the folder four levels down (server/Server/src/main/content) or less: where source trees usually start
            val d = parts.dropLast(1).take(5)
            if (d.isEmpty()) continue
            val key = "$folder/" + d.joinToString("/")
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts.entries.sortedByDescending { it.value }.take(8).map { "${it.key}/ (${it.value})" }
    }
}
