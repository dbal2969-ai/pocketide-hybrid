package dev.pocketide.app.core

data class ParsedReply(
    val text: String, val edits: List<FileEdit>, val cmds: List<String>, val failed: List<String>,
    /** Files read from a loose layout (see LooseFiles) because the model ignored the edit format. */
    val loose: List<String> = emptyList(),
    /** Existing files a loose reply only showed a fragment of, so they were left alone. */
    val looseSkipped: List<String> = emptyList()
)
data class PreviewParts(val text: String, val names: List<String>)

object ReplyParser {
    private val FILE_FENCE = Regex("""```file:([^\n]+)\n([\s\S]*?)\n?```""")
    private val RUN_FENCE = Regex("""```run[^\S\n]*\n([\s\S]*?)\n?```""")
    /** ```javascript:game.js  (language, then the file name): models often write this instead of ```file:game.js */
    private val LANG_PATH_FENCE = Regex("""```[A-Za-z0-9+#]*:([^\s`]+)[^\S\n]*\n([\s\S]*?)\n?```""")
    private val OPEN_FENCE = Regex("""```(file:[^\n]*|run[^\S\n]*)(?:\n[\s\S]*?(?:\n?```|\z)|\z)""")
    private val FILE_PATH_OK = Regex("""^[\w.\-/ ]+$""")

    /**
     * Splits a model reply into explanation text, file edits, shell commands and edit blocks that could not be applied.
     * files holds the current text of every text file; binary files are listed in binary.
     */
    fun parse(reply: String, files: Map<String, String>, binary: Set<String>, open: String?): ParsedReply {
        val byPath = LinkedHashMap<String, String>()
        val cmds = ArrayList<String>()
        var text = FILE_FENCE.replace(reply) { m ->
            var path = m.groupValues[1].trim()
            if (path.startsWith("./")) path = path.substring(2) else if (path.startsWith("/")) path = path.substring(1)
            if (path.isNotEmpty() && FILE_PATH_OK.matches(path) && !path.contains("..")) {
                byPath[path] = m.groupValues[2].replace("\r", "") + "\n"
            }
            ""
        }
        text = LANG_PATH_FENCE.replace(text) { m ->
            val path = LooseFiles.pathLine(m.groupValues[1].trim())   // only names with a known file extension
            if (path != null && !path.contains("..")) { byPath[path] = m.groupValues[2].replace("\r", "") + "\n"; "" } else m.value
        }
        text = RUN_FENCE.replace(text) { m ->
            m.groupValues[1].split("\n").map { it.trim() }.filter { it.isNotEmpty() && it[0] != '#' }.forEach { cmds.add(it) }
            ""
        }
        val sb = EditBlocks.scan(text, files.keys + binary, open)
        val rb = EditBlocks.resolve(
            sb.blocks,
            { p -> if (byPath.containsKey(p)) byPath[p] else files[p] },
            { p -> binary.contains(p) },
            open
        )
        rb.edits.forEach { byPath[it.path] = it.content }
        if (byPath.isEmpty() && sb.blocks.isEmpty() && rb.failed.isEmpty()) {
            // Nothing in PocketIDE's format: try the looser layouts small models tend to use.
            val lf = LooseFiles.extract(sb.text, files.filterKeys { !binary.contains(it) })
            val usable = lf.files.filterKeys { !binary.contains(it) }
            if (usable.isNotEmpty() || lf.skipped.isNotEmpty()) {
                return ParsedReply(lf.text, usable.map { FileEdit(it.key, it.value) }, cmds, emptyList(), usable.keys.toList(), lf.skipped)
            }
        }
        return ParsedReply(sb.text, byPath.map { FileEdit(it.key, it.value) }, cmds, rb.failed)
    }

    /** What to show while a reply is still streaming: the text without fences or blocks, and the names being written. */
    fun preview(acc: String, files: Set<String>, open: String?): PreviewParts {
        val names = ArrayList<String>()
        val text = OPEN_FENCE.replace(acc) { m ->
            val tag = m.groupValues[1]
            names.add(if (tag.startsWith("file:")) tag.substring(5).trim() else "commands")
            ""
        }.trim()
        if (!text.contains("SEARCH")) return PreviewParts(text, names)
        val sb = EditBlocks.scan(text, files, open)
        sb.names.forEach { if (!names.contains(it)) names.add(it) }
        return PreviewParts(sb.text, names)
    }
}
