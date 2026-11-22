package dev.pocketide.app.core

/**
 * The patch format OpenAI's GPT and Codex models are trained to write (Codex CLI's apply_patch, "V4A"). Written for PocketIDE from
 * the published description of the format; no Codex code is used.
 *
 *   *** Begin Patch
 *   *** Add File: src/new.js
 *   +line one
 *   *** Update File: src/app.js
 *   *** Move to: src/main.js        (optional)
 *   @@ function start()             (optional: a line the change comes after)
 *    unchanged line
 *   -removed line
 *   +added line
 *   *** Delete File: old.js
 *   *** End Patch
 *
 * Old lines are found from top to bottom: exactly first, then ignoring trailing spaces, then ignoring spaces at both ends.
 */
object ApplyPatch {
    sealed class Op { abstract val path: String }
    data class Add(override val path: String, val text: String) : Op()
    data class Delete(override val path: String) : Op()
    data class Update(override val path: String, val moveTo: String?, val chunks: List<Chunk>) : Op()
    data class Chunk(val anchor: String?, val old: List<String>, val new: List<String>, val atEnd: Boolean)

    class Parsed(val ops: List<Op>, val error: String?)
    class Applied(val text: String?, val error: String?)

    private const val BEGIN = "*** Begin Patch"
    private const val END = "*** End Patch"
    private const val ADD = "*** Add File:"
    private const val DEL = "*** Delete File:"
    private const val UPD = "*** Update File:"
    private const val MOVE = "*** Move to:"
    private const val EOF = "*** End of File"

    /** Model names that write this format well. */
    /** The patch text from a call's arguments: "input", or any other argument that holds a patch ("patch", "diff"). */
    fun argument(args: Map<String, Any?>): String {
        (args["input"] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        val texts = args.values.filterIsInstance<String>()
        return texts.firstOrNull { it.contains("*** Begin Patch") } ?: (args["patch"] as? String) ?: (args["diff"] as? String) ?: texts.singleOrNull() ?: ""
    }

    fun suits(providerId: String, model: String): Boolean {
        val m = model.lowercase()
        return "codex" in m || Regex("""(^|[/:\-_ ])gpt-?[4-9o]""").containsMatchIn(m) || Regex("""(^|/)o[134](-|$)""").containsMatchIn(m) ||
            (providerId == "openai" && m.isEmpty())
    }

    fun parse(patch: String): Parsed {
        var lines = patch.replace("\r", "").lines()
        // a patch wrapped in a code fence or a heredoc (apply_patch <<'EOF') still counts
        val b = lines.indexOfFirst { it.trim() == BEGIN }
        if (b < 0) return Parsed(emptyList(), "The patch must start with \"$BEGIN\".")
        val e = lines.indexOfLast { it.trim() == END }
        if (e <= b) return Parsed(emptyList(), "The patch must end with \"$END\".")
        lines = lines.subList(b + 1, e)
        val ops = ArrayList<Op>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            when {
                l.isBlank() -> i++
                // a repeated "*** End Patch" (or Begin) inside the patch: gpt-oss-120b ends some patches with it twice
                l.trim() == "*** End Patch" || l.trim() == "*** Begin Patch" -> i++
                l.startsWith(ADD) -> {
                    val path = l.removePrefix(ADD).trim()
                    val body = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].startsWith("*** ")) {
                        val x = lines[i]
                        if (!x.startsWith("+") && x.isNotEmpty()) return Parsed(ops, "In \"$ADD $path\" every line must start with +; this one does not: ${x.take(80)}")
                        body.add(x.removePrefix("+")); i++
                    }
                    ops.add(Add(path, body.joinToString("\n") + if (body.isEmpty()) "" else "\n"))
                }
                l.startsWith(DEL) -> { ops.add(Delete(l.removePrefix(DEL).trim())); i++ }
                l.startsWith(UPD) -> {
                    val path = l.removePrefix(UPD).trim()
                    i++
                    var move: String? = null
                    if (i < lines.size && lines[i].startsWith(MOVE)) { move = lines[i].removePrefix(MOVE).trim(); i++ }
                    val chunks = ArrayList<Chunk>()
                    var anchor: String? = null; var old = ArrayList<String>(); var new = ArrayList<String>(); var any = false
                    fun flush(end: Boolean) {
                        if (any) chunks.add(Chunk(anchor, old, new, end))
                        anchor = null; old = ArrayList(); new = ArrayList(); any = false
                    }
                    while (i < lines.size && (!lines[i].startsWith("*** ") || lines[i].trim() == EOF)) {
                        val x = lines[i]
                        when {
                            x.trim() == EOF -> flush(true)
                            x.startsWith("@@") -> { flush(false); anchor = x.removePrefix("@@").trim().ifEmpty { null } }
                            x.startsWith("+") -> { new.add(x.substring(1)); any = true }
                            x.startsWith("-") -> { old.add(x.substring(1)); any = true }
                            x.startsWith(" ") -> { old.add(x.substring(1)); new.add(x.substring(1)); any = true }
                            x.isEmpty() -> { old.add(""); new.add(""); any = true }  // a blank context line whose space was lost
                            else -> return Parsed(ops, "In \"$UPD $path\" each line must start with a space, - or +; this one does not: ${x.take(80)}")
                        }
                        i++
                    }
                    flush(false)
                    if (chunks.isEmpty() && move == null) return Parsed(ops, "\"$UPD $path\" has no changes.")
                    ops.add(Update(path, move, chunks))
                }
                else -> return Parsed(ops, "Unexpected line in the patch: ${l.take(80)} (expected $ADD, $UPD or $DEL).")
            }
        }
        if (ops.isEmpty()) return Parsed(ops, "The patch changes nothing.")
        return Parsed(ops, null)
    }

    private val MATCHERS: List<(String, String) -> Boolean> = listOf(
        { a, b -> a == b }, { a, b -> a.trimEnd() == b.trimEnd() }, { a, b -> a.trim() == b.trim() })

    /** Index where [seq] starts in [lines], at or after [from]; with [atEnd], only at the very end. -1 when not found. */
    private fun find(lines: List<String>, seq: List<String>, from: Int, atEnd: Boolean): Int {
        if (seq.isEmpty()) return if (atEnd) lines.size else from
        for (eq in MATCHERS) {
            val starts = if (atEnd) listOf(lines.size - seq.size) else (from..lines.size - seq.size).toList()
            for (s in starts) if (s >= 0 && seq.indices.all { k -> eq(lines[s + k], seq[k]) }) return s
        }
        return -1
    }

    /** Applies the chunks of one Update to [text]. */
    fun apply(text: String, chunks: List<Chunk>): Applied {
        val hadNewline = text.endsWith("\n")
        val lines = ArrayList(text.removeSuffix("\n").let { if (it.isEmpty() && text.isEmpty()) emptyList() else it.split("\n") })
        var cursor = 0
        for ((n, c) in chunks.withIndex()) {
            if (c.anchor != null) {
                val a = MATCHERS.firstNotNullOfOrNull { eq -> (cursor until lines.size).firstOrNull { eq(lines[it], c.anchor) } }
                    ?: return Applied(null, "change ${n + 1}: the line after @@ was not found: ${c.anchor.take(80)}")
                cursor = a + 1
            }
            var old = c.old; var new = c.new
            var at = find(lines, old, cursor, c.atEnd)
            if (at < 0 && old.lastOrNull() == "") {  // a trailing blank context line is often extra
                old = old.dropLast(1); new = if (new.lastOrNull() == "") new.dropLast(1) else new
                at = find(lines, old, cursor, c.atEnd)
            }
            if (at < 0) return Applied(null, "change ${n + 1}: these lines were not found" + (if (c.anchor != null) " after \"${c.anchor.take(60)}\"" else "") + ":\n" + c.old.take(6).joinToString("\n") { "  $it" })
            for (k in old.indices) lines.removeAt(at)
            lines.addAll(at, new)
            cursor = at + new.size
        }
        val out = lines.joinToString("\n")
        return Applied(if (hadNewline || text.isEmpty()) out + (if (out.isEmpty()) "" else "\n") else out, null)
    }

    const val DESCRIPTION = "Change files with one patch in the apply_patch format: add, update, rename or delete several files at once. " +
        "Start with *** Begin Patch and end with *** End Patch. For each file: *** Add File: path (then every line starts with +), " +
        "*** Delete File: path, or *** Update File: path (optionally followed by *** Move to: newpath) and then changes. " +
        "In a change, lines starting with a space are unchanged context (give about 3 lines before and after), - removes a line, + adds one; " +
        "start each separate change with @@, optionally followed by a line it comes after, such as a function header. Paths are relative to the project root."
}
