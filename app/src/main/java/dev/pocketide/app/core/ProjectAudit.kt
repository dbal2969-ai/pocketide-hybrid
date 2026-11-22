package dev.pocketide.app.core

import java.security.MessageDigest

/**
 * Finds clutter in a project: identical files stored twice, folders nested in a folder of the same name, pictures, sounds and
 * scripts that no code or data file names, and nothing else. Reads only; it never changes a file.
 */
object ProjectAudit {
    private val TEXT = setOf("js", "mjs", "ts", "html", "htm", "css", "json", "md", "txt", "toml", "kt", "java", "xml", "gradle", "kts", "py", "yml", "yaml", "csv")
    private val ASSET = setOf("png", "jpg", "jpeg", "gif", "webp", "wav", "mp3", "ogg", "m4a", "svg", "ttf", "otf", "woff", "woff2")
    private val SKIP = listOf(".git/", ".idea/", "node_modules/", ".gradle/", "build/", ".pocketide/")
    private const val MAX_TEXT_BYTES = 1L shl 20
    private const val TEXT_BUDGET = 30L shl 20
    private const val MAX_FILES = 20_000

    fun run(paths: Collection<String>, size: (String) -> Long, read: (String) -> ByteArray?): String {
        val files = paths.filter { p -> SKIP.none { p.startsWith(it) || p.contains("/" + it) } }.sorted()
        if (files.isEmpty()) return "The project has no files to check."
        if (files.size > MAX_FILES) return "The project has ${files.size} files; the audit checks up to $MAX_FILES. Check one folder at a time with list_files."
        val out = StringBuilder()

        // 1. identical files
        val byHash = LinkedHashMap<String, MutableList<String>>()
        var wasted = 0L
        for (p in files) {
            val s = size(p)
            if (s <= 0L || s > 50L shl 20) continue
            val b = read(p) ?: continue
            val h = sha(b)
            byHash.getOrPut(h) { ArrayList() }.add(p)
        }
        val dupes = byHash.values.filter { it.size > 1 }
        for (g in dupes) wasted += size(g[0]) * (g.size - 1)
        out.append("Identical copies: ${dupes.size} group${plural(dupes.size)}")
        if (dupes.isNotEmpty()) out.append(", about ${mb(wasted)} that could be removed")
        out.append(".\n")
        for (g in dupes.take(25)) out.append("- " + g.joinToString(" = ") + "\n")
        if (dupes.size > 25) out.append("- ... ${dupes.size - 25} more groups\n")

        // 2. folders nested in a folder of the same name, e.g. cache/cache
        val nested = files.mapNotNull { p ->
            val parts = p.split('/')
            (0 until parts.size - 1).firstOrNull { i -> i + 1 < parts.size - 1 && parts[i] == parts[i + 1] }?.let { parts.subList(0, it + 2).joinToString("/") }
        }.toSortedSet()
        out.append("Folders repeated inside themselves: ${nested.size}")
        if (nested.isNotEmpty()) out.append(" (" + nested.take(10).joinToString(", ") + ")")
        out.append(".\n")

        // 3. assets and scripts that no text file names
        val names = HashSet<String>()
        var budget = TEXT_BUDGET
        for (p in files) {
            val ext = p.substringAfterLast('.', "").lowercase()
            if (ext !in TEXT) continue
            val s = size(p)
            if (s > MAX_TEXT_BYTES || s > budget) continue
            val text = read(p)?.toString(Charsets.UTF_8) ?: continue
            budget -= s
            for (m in TOKEN.findAll(text)) names.add(m.value.substringAfterLast('/').lowercase())
        }
        val unused = files.filter { p ->
            val ext = p.substringAfterLast('.', "").lowercase()
            val base = p.substringAfterLast('/')
            (ext in ASSET || ext == "js" || ext == "mjs") && base.lowercase() !in names && !base.startsWith("index") && base != "main.js"
        }
        out.append("Pictures, sounds and scripts that no code or data file names: ${unused.size}.")
        out.append(" A name built in code, such as \"coin_\" + id, is not seen here, so check before deleting.\n")
        for (p in unused.take(40)) out.append("- $p\n")
        if (unused.size > 40) out.append("- ... ${unused.size - 40} more\n")
        return out.toString().trimEnd()
    }

    private val TOKEN = Regex("""[A-Za-z0-9_][A-Za-z0-9_./\-]*\.[A-Za-z0-9]{1,5}""")

    private fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun plural(n: Int) = if (n == 1) "" else "s"
    private fun mb(b: Long): String = when { b < 1024L -> "$b bytes"; b < 1L shl 20 -> "${b / 1024} KB"; else -> "%.1f MB".format(b / 1048576.0) }
}
