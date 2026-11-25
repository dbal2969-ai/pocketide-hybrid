package dev.pocketide.app.core

// SEARCH/REPLACE edit blocks. Kotlin port of the JavaScript code in the PocketIDE web app, which is itself
// a port of the author's MobileCoder project (editblocks.py). The block format follows the design popularised
// by aider; no aider code is used.

data class EditBlock(val path: String, val search: String, val replace: String)
data class BlockScan(val text: String, val blocks: List<EditBlock>, val names: List<String>)
data class FileEdit(val path: String, val content: String)
data class ResolveResult(val edits: List<FileEdit>, val failed: List<String>)
data class ApplyResult(val text: String? = null, val error: String? = null)

object EditBlocks {
    private val SEARCH_LINE = Regex("^<{5,9} *SEARCH *$")
    private val DIV_LINE = Regex("^={5,9}$")
    private val REPLACE_LINE = Regex("^>{5,9} *REPLACE *$")
    private val FENCE_LINE = Regex("^(?:`{3,}|~{3,})")
    private val PATH_TRIM = Regex("""^[#*`'"\s]+|[:*`'"\s]+$""")
    private val PATH_PREFIX = Regex("""^(?:file|path)\s*:\s*""", RegexOption.IGNORE_CASE)
    private val PATH_OK = Regex("""^[\w.\-/]+$""")
    private val UNFENCE_A = Regex("""(^|\n)([^\n`~]+\n)(`{3,}|~{3,})[\w+.-]*[ \t]*\n(<{5,9} *SEARCH[ \t]*\n[\s\S]*?\n>{5,9} *REPLACE[ \t]*)\n\3[ \t]*(?=\n|\z)""")
    private val UNFENCE_B = Regex("""(^|\n)(`{3,}|~{3,})[\w+.-]*[ \t]*\n((?:[^\n`~]+\n)?<{5,9} *SEARCH[ \t]*\n[\s\S]*?\n>{5,9} *REPLACE[ \t]*)\n\2[ \t]*(?=\n|\z)""")
    private val MANY_LINES = Regex("\n{3,}")
    private const val MANY = "the SEARCH text matches more than once; include more surrounding lines (with edit_file, or set all to true to replace every match, as for a rename)"

    private fun blkPath(line: String, known: Set<String>): String? {
        var c = line.trim().replace(PATH_TRIM, "").replace(PATH_PREFIX, "")
        if (c.isEmpty() || c.length > 255 || c.any { it.isWhitespace() } || c[0] == '/' || c[0] == '\\') return null
        if (c.startsWith("./")) c = c.substring(2)
        if (c.split("/").contains("..") || !PATH_OK.matches(c)) return null
        return if (c.contains('.') || c.contains('/') || known.contains(c)) c else null
    }

    private fun unfence(t: String): String =
        t.replace(UNFENCE_A, "$1$2$4").replace(UNFENCE_B, "$1$3")

    /**
     * Finds edit blocks in a reply. Returns the reply without them, the blocks, and the file names they touch.
     * A block that is still being written (no closing marker yet) is hidden but not returned.
     */
    fun scan(text: String, known: Set<String>, fallback: String?): BlockScan {
        val lines = unfence(text.replace("\r\n", "\n").replace('\r', '\n')).split("\n")
        val drop = BooleanArray(lines.size)
        val blocks = ArrayList<EditBlock>()
        val names = ArrayList<String>()
        var last: String? = null
        var i = 0
        while (i < lines.size) {
            if (!SEARCH_LINE.matches(lines[i].trim())) { i++; continue }
            val start = i
            var pathAt = -1
            var path: String? = null
            var k = i - 1
            while (k > maxOf(-1, i - 6)) {
                val s = lines[k].trim()
                if (s.isEmpty() || FENCE_LINE.containsMatchIn(s)) { k--; continue }
                path = blkPath(s, known)
                if (path != null) pathAt = k
                break
            }
            val from = if (pathAt >= 0) pathAt else start
            val use = path ?: last ?: fallback
            val sl = ArrayList<String>()
            var j = i + 1
            var rl: ArrayList<String>? = null
            while (j < lines.size && !DIV_LINE.matches(lines[j].trim())) { sl.add(lines[j]); j++ }
            if (j < lines.size) {
                rl = ArrayList()
                j++
                while (j < lines.size && !REPLACE_LINE.matches(lines[j].trim())) { rl.add(lines[j]); j++ }
                if (j >= lines.size) rl = null
            }
            if (rl == null) {
                for (q in from until lines.size) drop[q] = true
                if (use != null && !names.contains(use)) names.add(use)
                break
            }
            for (q in from..j) drop[q] = true
            if (use != null) {
                blocks.add(EditBlock(use, sl.joinToString("\n"), rl.joinToString("\n")))
                last = use
                if (!names.contains(use)) names.add(use)
            }
            i = j + 1
        }
        val kept = lines.filterIndexed { idx, _ -> !drop[idx] }.joinToString("\n")
        return BlockScan(kept.replace(MANY_LINES, "\n\n").trim(), blocks, names)
    }

    private fun countOcc(hay: String, needle: String): Int {
        var n = 0
        var from = 0
        while (true) {
            val at = hay.indexOf(needle, from)
            if (at < 0) return n
            n++
            from = at + needle.length
        }
    }

    private val LINE_NO = Regex("""^\s*\d{1,6}: ?""")

    /** Models often copy read_file output with its "12: " prefixes. When every non-blank line has one, drop them. */
    fun stripLineNumbers(t: String): String {
        val lines = t.split("\n")
        val body = lines.filter { it.isNotBlank() }
        if (body.isEmpty() || !body.all { LINE_NO.containsMatchIn(it) }) return t
        return lines.joinToString("\n") { LINE_NO.replaceFirst(it, "") }
    }

    /**
     * Applies one block: exact match, then ignoring trailing spaces, then indentation, then runs of spaces inside lines,
     * then blank lines. Line-number prefixes copied from read_file are dropped first. When nothing matches, the error
     * shows the closest text with its line numbers, so the model can copy it instead of guessing again.
     */
    /** Replaces every exact match of the search text (edit_file with all=true, a rename); one match works too. */
    fun applyAll(cur: String, b: EditBlock): ApplyResult {
        if (b.search.isEmpty() || countOcc(cur, b.search) == 0) return apply(cur, b)   // no exact match: the usual matching and its error
        return ApplyResult(text = cur.replace(b.search, b.replace))
    }

    fun apply(cur: String, b0: EditBlock): ApplyResult {
        // a file may really contain "12: " lines, so the text as sent is tried first
        val numbered = stripLineNumbers(b0.search) != b0.search && (b0.search.isEmpty() || countOcc(cur, b0.search) == 0)
        val b = if (numbered) EditBlock(b0.path, stripLineNumbers(b0.search), stripLineNumbers(b0.replace)) else b0
        val s = b.search
        val exact = if (s.isEmpty()) 0 else countOcc(cur, s)
        if (exact == 1) {
            val at = cur.indexOf(s)
            return ApplyResult(text = cur.substring(0, at) + withoutRepeats(cur.substring(at + s.length), s, b.replace) + cur.substring(at + s.length))
        }
        if (exact > 1) return ApplyResult(error = MANY)
        val cl = cur.split("\n")
        val sl = s.split("\n")
        fun find(norm: (String) -> String): List<Int> {
            val a = cl.map(norm)
            val q = sl.map(norm)
            val hits = ArrayList<Int>()
            var k = 0
            while (k + q.size <= a.size) {
                var ok = true
                for (z in q.indices) if (a[k + z] != q[z]) { ok = false; break }
                if (ok) hits.add(k)
                k++
            }
            return hits
        }
        var hits = find { it.trimEnd() }
        var reindent = false
        if (hits.isEmpty()) { hits = find { it.trim() }; reindent = true }
        if (hits.isEmpty()) hits = find { it.trim().replace(SPACES, " ") }
        if (hits.size > 1) return ApplyResult(error = MANY)
        if (hits.isEmpty()) {
            // last try: ignore blank lines on both sides
            loose(cl, sl)?.let { (from, to) ->
                return ApplyResult(text = (cl.subList(0, from) + reindented(cl, sl, from, b.replace.split("\n")) + cl.subList(to + 1, cl.size)).joinToString("\n"))
            }
            // 1.5.184: Gemini Nano searched for "document.addEventListener('keydown', function(e) { ... })"
            val dots = if (s.contains("...") || s.contains("…")) ". It contains \"...\", which stands for skipped code the file does not have: " +
                "copy whole lines exactly, or add new code with edit_lines (insert after line N: start = N+1, end = N)" else ""
            return ApplyResult(error = "the SEARCH text was not found in the current file" + dots + closest(cl, sl))
        }
        var repl = b.replace.split("\n")
        if (reindent) repl = reindented(cl, sl, hits[0], repl)
        val out = cl.subList(0, hits[0]) + repl + cl.subList(hits[0] + sl.size, cl.size)
        return ApplyResult(text = out.joinToString("\n"))
    }

    private val SPACES = Regex("""\s+""")

    private val DECL = Regex("""^\s*(export\s+)?(const|let|var|function|class|import|def)\b""")

    /**
     * 1.5.184 (Gemini Nano, the owner's snake game): asked to add code after `const canvas = …;`, Nano's replacement repeated the next
     * line of the file, `const ctx = …`, and the file declared ctx twice. When the replacement starts with the search text and then
     * repeats the declaration lines that already follow it in the file, those repeats are left out.
     */
    fun withoutRepeats(after: String, search: String, replace: String): String {
        if (!replace.startsWith(search) || !(after.isEmpty() || after.startsWith("\n"))) return replace
        val extra = replace.substring(search.length).split("\n")
        val next = after.removePrefix("\n").split("\n")
        var i = 1   // extra[0] is the rest of the search's last line ("" when it ended the line)
        var k = 0
        while (i < extra.size && k < next.size && extra[i].trim() == next[k].trim() && next[k].isNotBlank() && DECL.containsMatchIn(next[k])) { i++; k++ }
        if (k == 0) return replace
        return search + (listOf(extra[0]) + extra.drop(i)).joinToString("\n").let { if (extra.size == i) it.removeSuffix("\n") else it }
    }

    /** Moves the replacement to the file's indentation when the search text was written with a different one. */
    private fun reindented(cl: List<String>, sl: List<String>, at: Int, repl0: List<String>): List<String> {
        var repl = repl0
        fun lead(x: String) = x.takeWhile { it == ' ' || it == '\t' }
        val fi = sl.indexOfFirst { it.isNotBlank() }
        if (fi < 0 || at + fi >= cl.size) return repl
        val fileLine = (at until cl.size).firstOrNull { cl[it].isNotBlank() } ?: return repl
        val fInd = lead(cl[fileLine])
        val sInd = lead(sl[fi])
        if (fInd != sInd) {
            val fTabs = fInd.isNotEmpty() && fInd.all { it == '\t' }; val sTabs = sInd.isNotEmpty() && sInd.all { it == '\t' }
            val fSpaces = fInd.isNotEmpty() && fInd.all { it == ' ' }; val sSpaces = sInd.isNotEmpty() && sInd.all { it == ' ' }
            if (sTabs && fSpaces && fInd.length % sInd.length == 0) {
                // written with tabs, the file uses spaces: one tab becomes the file's number of spaces
                val unit = " ".repeat(fInd.length / sInd.length)
                return repl.map { l -> val n = l.takeWhile { it == '\t' }.length; unit.repeat(n) + l.substring(n) }
            }
            if (sSpaces && fTabs && sInd.length % fInd.length == 0) {
                val unit = sInd.length / fInd.length
                return repl.map { l -> val n = l.takeWhile { it == ' ' }.length / unit; "\t".repeat(n) + l.substring(n * unit) }
            }
            if (fInd.startsWith(sInd)) {
                val add = fInd.substring(sInd.length)
                repl = repl.map { if (it.isNotBlank()) add + it else it }
            } else if (sInd.startsWith(fInd)) {
                val cut = sInd.substring(fInd.length)
                repl = repl.map { if (it.startsWith(cut)) it.substring(cut.length) else it }
            }
        }
        return repl
    }

    /** Finds the search lines in the file when only blank lines differ. Returns the first and last file line, or null. */
    private fun loose(cl: List<String>, sl: List<String>): Pair<Int, Int>? {
        val q = sl.filter { it.isNotBlank() }.map { it.trim().replace(SPACES, " ") }
        if (q.size < 2) return null
        val idx = cl.indices.filter { cl[it].isNotBlank() }
        val a = idx.map { cl[it].trim().replace(SPACES, " ") }
        var found: Pair<Int, Int>? = null
        var k = 0
        while (k + q.size <= a.size) {
            var ok = true
            for (z in q.indices) if (a[k + z] != q[z]) { ok = false; break }
            if (ok) { if (found != null) return null; found = idx[k] to idx[k + q.size - 1] }
            k++
        }
        return found
    }

    /** "; the closest text is at lines 40-46: …" when at least half of the search lines appear there. */
    fun closest(cl: List<String>, sl: List<String>): String {
        val q = sl.map { it.trim().replace(SPACES, " ") }
        val want = q.count { it.isNotEmpty() }
        if (want == 0 || cl.isEmpty()) return ""
        val n = sl.size.coerceAtMost(cl.size)
        var best = -1; var bestScore = 0
        for (k in 0..(cl.size - n)) {
            var score = 0
            for (z in 0 until n) { val x = cl[k + z].trim().replace(SPACES, " "); if (x.isNotEmpty() && x == q.getOrNull(z)) score++ }
            if (score > bestScore) { bestScore = score; best = k }
        }
        if (best < 0 || bestScore * 2 < want) {
            // fall back to the single most specific line
            val anchor = q.filter { it.length >= 8 }.maxByOrNull { it.length } ?: return ""
            val at = cl.indexOfFirst { it.contains(anchor.take(40)) }
            if (at < 0) return ""
            best = (at - q.indexOf(anchor)).coerceIn(0, maxOf(0, cl.size - n))
        }
        val end = minOf(cl.size, best + n + 2).coerceAtMost(best + 30)
        val shown = (best until end).joinToString("\n") { "${it + 1}: ${cl[it]}" }
        return ". The closest text is at lines ${best + 1}-$end:\n$shown\nCopy the search text from these lines (without the numbers), or use edit_lines with these line numbers"
    }

    /**
     * Replaces lines [start]..[end] (1-based, inclusive) with [text]. end = start - 1 inserts before [start];
     * start = line count + 1 appends. Returns the new text or an error.
     */
    fun applyLines(cur: String, start: Int, end: Int, text: String): ApplyResult {
        val cl = cur.split("\n")
        if (start < 1 || start > cl.size + 1) return ApplyResult(error = "start must be between 1 and ${cl.size + 1} (the file has ${cl.size} lines)")
        if (end < start - 1 || end > cl.size) return ApplyResult(error = "end must be between ${start - 1} and ${cl.size}")
        val repl = if (text.isEmpty()) emptyList() else stripLineNumbers(text).split("\n")
        val out = cl.subList(0, start - 1) + repl + cl.subList(end, cl.size)
        return ApplyResult(text = out.joinToString("\n"))
    }

    /**
     * Applies blocks in order, so several blocks can change the same file. read(path) gives the current text or null.
     * A block naming a file that does not exist is pointed at the target file, as small models often echo a placeholder name.
     */
    fun resolve(blocks: List<EditBlock>, read: (String) -> String?, isBin: (String) -> Boolean, target: String?): ResolveResult {
        val files = LinkedHashMap<String, String>()
        val failed = ArrayList<String>()
        fun cur(p: String): String? = if (files.containsKey(p)) files[p] else read(p)
        for (b in blocks) {
            var path = b.path
            if (!isBin(path) && cur(path) == null && b.search.isNotBlank() && target != null && path != target && cur(target) != null) path = target
            if (isBin(path)) { failed.add("$path: this is a binary file and cannot be edited with text blocks"); continue }
            val text = cur(path)
            if (b.search.isBlank()) {
                if (text == null || text.isEmpty()) files[path] = b.replace
                else failed.add("$path: the SEARCH text is empty but the file already exists")
                continue
            }
            if (text == null) { failed.add("$path: that file does not exist"); continue }
            val r = apply(text, b)
            if (r.error != null) failed.add("$path: ${r.error}") else files[path] = r.text ?: text
        }
        val edits = files.map { (p, t) -> FileEdit(p, if (t.isNotEmpty() && !t.endsWith("\n")) t + "\n" else t) }
        return ResolveResult(edits, failed)
    }
}
