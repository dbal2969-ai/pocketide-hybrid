package dev.pocketide.app.core

data class SearchOptions(val query: String, val caseSensitive: Boolean = false, val regex: Boolean = false, val wholeWord: Boolean = false)

/** One match. line and col are 1-based; preview is the (possibly shortened) line with the match at previewStart. */
data class SearchHit(val path: String, val line: Int, val col: Int, val length: Int, val preview: String, val previewStart: Int)

data class SearchResult(val hits: List<SearchHit>, val fileCount: Int, val truncated: Boolean, val error: String?)

/** Find and replace across the project's text files. Pure, so it is JVM-testable. */
object ProjectSearch {
    const val MAX_HITS = 2000
    const val MAX_FILE_BYTES = 1_000_000L
    private const val PREVIEW = 120

    /** Whether a file takes part in searches: text files up to 1 MB. */
    fun eligible(size: Long, binary: Boolean): Boolean = !binary && size <= MAX_FILE_BYTES

    /** The pattern for these options, or an error message for an invalid regular expression. */
    fun pattern(o: SearchOptions): Pair<Regex?, String?> {
        if (o.query.isEmpty()) return null to null
        val core = if (o.regex) o.query else Regex.escape(o.query)
        val src = if (o.wholeWord) """(?<![\p{L}\p{N}_])(?:$core)(?![\p{L}\p{N}_])""" else core
        val opts = if (o.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return try {
            val r = Regex(src, opts)
            if (r.matches("") && o.regex) null to "That pattern matches empty text; make it more specific." else r to null
        } catch (e: Exception) {
            null to "Invalid regular expression: ${e.message?.lineSequence()?.firstOrNull() ?: "syntax error"}"
        }
    }

    fun search(files: Map<String, String>, o: SearchOptions): SearchResult = search(files.keys.toList(), { files[it] }, o)

    /**
     * Searches [paths], reading one file at a time with [read], so a big project is never held in memory at once
     * (holding every file's text ran the phone out of memory).
     */
    fun search(paths: List<String>, read: (String) -> String?, o: SearchOptions): SearchResult {
        val (re, err) = pattern(o)
        if (re == null) return SearchResult(emptyList(), 0, false, err)
        val hits = ArrayList<SearchHit>()
        var fileCount = 0
        var truncated = false
        for (path in paths.sorted()) {
            val text = read(path) ?: continue
            var found = false
            var lineNo = 1
            var lineStart = 0
            var scanned = 0
            for (m in re.findAll(text)) {
                if (m.range.isEmpty()) continue
                // advance the line counter up to the match
                while (scanned < m.range.first) {
                    if (text[scanned] == '\n') { lineNo++; lineStart = scanned + 1 }
                    scanned++
                }
                val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
                val col = m.range.first - lineStart
                val len = minOf(m.range.last + 1, lineEnd) - m.range.first
                val (preview, at) = preview(text.substring(lineStart, lineEnd), col)
                hits.add(SearchHit(path, lineNo, col + 1, maxOf(len, 1), preview, at))
                found = true
                if (hits.size >= MAX_HITS) { truncated = true; break }
            }
            if (found) fileCount++
            if (truncated) break
        }
        return SearchResult(hits, fileCount, truncated, null)
    }

    private fun preview(line: String, col: Int): Pair<String, Int> {
        val tabless = line.replace('\t', ' ')
        if (tabless.length <= PREVIEW) {
            val lead = tabless.length - tabless.trimStart().length
            return tabless.trim() to (col - lead).coerceAtLeast(0)
        }
        val from = (col - 40).coerceAtLeast(0)
        val to = (from + PREVIEW).coerceAtMost(tabless.length)
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < tabless.length) "…" else ""
        return (prefix + tabless.substring(from, to) + suffix) to (col - from + prefix.length)
    }

    /** Replaces every match in one text. In regex mode $1 etc. refer to groups; otherwise the replacement is literal. */
    fun replace(text: String, o: SearchOptions, replacement: String): Pair<String, Int> {
        val (re, _) = pattern(o)
        if (re == null) return text to 0
        var n = 0
        val out = re.replace(text) { m ->
            if (m.range.isEmpty()) m.value
            else {
                n++
                if (o.regex) expand(m, replacement) else replacement
            }
        }
        return out to n
    }

    /** $0..$9 and ${name} group references, with \$ for a literal dollar. */
    private fun expand(m: MatchResult, r: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < r.length) {
            val c = r[i]
            if (c == '\\' && i + 1 < r.length) { sb.append(r[i + 1]); i += 2; continue }
            if (c == '$' && i + 1 < r.length && r[i + 1].isDigit()) {
                val g = r[i + 1] - '0'
                sb.append(m.groupValues.getOrNull(g) ?: "")
                i += 2; continue
            }
            if (c == '$' && i + 1 < r.length && r[i + 1] == '{') {
                val end = r.indexOf('}', i + 2)
                if (end > 0) {
                    val name = r.substring(i + 2, end)
                    val v = name.toIntOrNull()?.let { m.groupValues.getOrNull(it) }
                        ?: try { m.groups[name]?.value } catch (e: Exception) { null }
                    sb.append(v ?: "")
                    i = end + 1; continue
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** New text for every file that changes, with its replacement count. */
    fun replaceInProject(files: Map<String, String>, o: SearchOptions, replacement: String): Map<String, Pair<String, Int>> =
        replaceInProject(files.keys.toList(), { files[it] }, o, replacement)

    /** Like the map version, reading one file at a time; only the files that change are kept. */
    fun replaceInProject(paths: List<String>, read: (String) -> String?, o: SearchOptions, replacement: String): Map<String, Pair<String, Int>> {
        val out = LinkedHashMap<String, Pair<String, Int>>()
        for (path in paths.sorted()) {
            val before = read(path) ?: continue
            val (t, n) = replace(before, o, replacement)
            if (n > 0 && t != before) out[path] = t to n
        }
        return out
    }
}
