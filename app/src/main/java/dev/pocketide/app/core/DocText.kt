package dev.pocketide.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Plain text from files that are not text files, so the AI (and the Code tab) can read them:
 * Word, Excel, PowerPoint, OpenDocument, EPUB, PDF and the contents of zip-like archives.
 * Pure Kotlin on java.util.zip, no Android classes, so it is unit tested on the JVM.
 *
 * This is a read-only view. Spreadsheet cells carry their address and formula so an edit script can find them.
 * PDF text is read without OCR: scanned pages and some embedded fonts give little or nothing.
 */
object DocText {
    enum class Kind { DOCX, XLSX, PPTX, ODF, EPUB, PDF, ZIP }

    const val MAX_CHARS = 200_000
    private const val MAX_ENTRY_BYTES = 30L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 120L * 1024 * 1024
    private const val MAX_LISTED = 300

    private val ZIPS = setOf("zip", "jar", "apk", "aar", "war", "xpi", "whl", "egg")

    fun kind(path: String): Kind? = when (path.substringAfterLast('.', "").lowercase()) {
        "docx", "docm", "dotx" -> Kind.DOCX
        "xlsx", "xlsm", "xltx" -> Kind.XLSX
        "pptx", "pptm", "potx", "ppsx" -> Kind.PPTX
        "odt", "ods", "odp", "odg", "ott" -> Kind.ODF
        "epub" -> Kind.EPUB
        "pdf" -> Kind.PDF
        in ZIPS -> Kind.ZIP
        else -> null
    }

    fun canRead(path: String): Boolean = kind(path) != null

    /** Word, Excel, PowerPoint and OpenDocument files: the ones an edit script can change. */
    fun isOffice(path: String): Boolean = when (kind(path)) { Kind.DOCX, Kind.XLSX, Kind.PPTX, Kind.ODF -> true; else -> false }

    /** Text of the file, or null when the format is not supported or the file is damaged. */
    fun extract(path: String, bytes: ByteArray): String? {
        val k = kind(path) ?: return null
        val text = try {
            when (k) {
                Kind.DOCX -> docx(bytes)
                Kind.XLSX -> xlsx(bytes)
                Kind.PPTX -> pptx(bytes)
                Kind.ODF -> odf(bytes)
                Kind.EPUB -> epub(bytes)
                Kind.PDF -> pdf(bytes)
                Kind.ZIP -> listing(bytes)
            }
        } catch (e: Throwable) { null } ?: return null
        return if (text.length <= MAX_CHARS) text else text.substring(0, MAX_CHARS) + "\n… (${text.length - MAX_CHARS} more characters not shown)"
    }

    // ---- zip ----

    private class Zipped(val names: List<String>, val data: Map<String, ByteArray>)

    private fun unzip(bytes: ByteArray, want: (String) -> Boolean): Zipped? {
        val names = ArrayList<String>()
        val data = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    names.add(e.name)
                    if (!want(e.name)) continue
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var got = 0L
                    while (true) {
                        val n = z.read(buf)
                        if (n <= 0) break
                        got += n
                        if (got > MAX_ENTRY_BYTES || total + got > MAX_TOTAL_BYTES) break
                        out.write(buf, 0, n)
                    }
                    total += got
                    data[e.name] = out.toByteArray()
                }
            }
        } catch (e: Exception) { if (names.isEmpty()) return null }
        return Zipped(names, data)
    }

    private fun Zipped.text(name: String): String? = data[name]?.toString(Charsets.UTF_8)

    private fun listing(bytes: ByteArray): String? {
        val entries = ArrayList<Pair<String, Long>>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                val buf = ByteArray(8192)
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    var size = 0L
                    while (true) { val n = z.read(buf); if (n <= 0) break; size += n }
                    entries.add(e.name to size)
                }
            }
        } catch (e: Exception) { if (entries.isEmpty()) return null }
        if (entries.isEmpty()) return null
        val sb = StringBuilder("Archive with ${entries.size} file${if (entries.size == 1) "" else "s"} (names and uncompressed sizes; contents are not shown):\n")
        for ((n, s) in entries.take(MAX_LISTED)) sb.append(n).append("  (").append(s).append(" bytes)\n")
        if (entries.size > MAX_LISTED) sb.append("… and ${entries.size - MAX_LISTED} more\n")
        return sb.toString().trimEnd()
    }

    // ---- XML ----

    private class Spec(
        val text: Set<String>,
        val para: Set<String> = emptySet(),
        val cell: Set<String> = emptySet(),
        val row: Set<String> = emptySet(),
        val lineBreak: Set<String> = emptySet(),
        val tab: Set<String> = emptySet(),
        val space: Set<String> = emptySet(),
        val skip: Set<String> = emptySet()
    )

    private val TAG = Regex("""<(/?)([A-Za-z_][\w:.\-]*)([^>]*?)(/?)>""")
    private val ENTITY = Regex("""&(#x[0-9A-Fa-f]+|#[0-9]+|amp|lt|gt|quot|apos);""")

    internal fun unescape(s: String): String {
        if (!s.contains('&')) return s
        return ENTITY.replace(s) { m ->
            when (val g = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> {
                    val code = try { if (g.startsWith("#x")) g.substring(2).toInt(16) else g.substring(1).toInt() } catch (e: Exception) { -1 }
                    if (code in 1..0x10FFFF) String(Character.toChars(code)) else ""
                }
            }
        }
    }

    private fun tidy(s: CharSequence): String =
        s.toString().replace("\r", "").replace(Regex("""\n\t"""), "\t").replace(Regex("""[ \t]+\n"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").trim()

    private fun xmlText(xml: String, spec: Spec): String {
        val sb = StringBuilder()
        var depth = 0
        var skip = 0
        var last = 0
        for (m in TAG.findAll(xml)) {
            if (depth > 0 && skip == 0 && m.range.first > last) sb.append(unescape(xml.substring(last, m.range.first)))
            last = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2]
            val self = m.groupValues[4] == "/"
            if (name in spec.skip) { if (!self) skip = maxOf(0, skip + if (closing) -1 else 1); continue }
            if (skip > 0) continue
            if (name in spec.text && !self) depth = maxOf(0, depth + if (closing) -1 else 1)
            if (closing || self) {
                if (name in spec.para || name in spec.row) sb.append('\n') else if (name in spec.cell) sb.append('\t')
            }
            if (!closing) {
                if (name in spec.lineBreak) sb.append('\n')
                if (name in spec.tab) sb.append('\t')
                if (name in spec.space) sb.append(' ')
            }
        }
        return tidy(sb)
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""(?<![\w:.\-])${Regex.escape(name)}\s*=\s*"([^"]*)"""").find(tag)?.groupValues?.get(1)?.let { unescape(it) }

    // ---- Word ----

    private val DOCX = Spec(
        text = setOf("w:t"), para = setOf("w:p"), cell = setOf("w:tc"), row = setOf("w:tr"),
        lineBreak = setOf("w:br", "w:cr"), tab = setOf("w:tab"), skip = setOf("w:pPr", "w:tblPr", "w:sectPr", "w:trPr", "w:tcPr")
    )

    private fun docx(bytes: ByteArray): String? {
        val z = unzip(bytes) { it.startsWith("word/") && it.endsWith(".xml") } ?: return null
        val main = z.text("word/document.xml") ?: return null
        val sb = StringBuilder(xmlText(main, DOCX))
        for (n in z.names.sorted()) {
            val label = when {
                n.startsWith("word/header") -> "Header"
                n.startsWith("word/footer") -> "Footer"
                n == "word/footnotes.xml" -> "Footnotes"
                n == "word/endnotes.xml" -> "Endnotes"
                n == "word/comments.xml" -> "Comments"
                else -> continue
            }
            val t = z.text(n)?.let { xmlText(it, DOCX) }.orEmpty()
            if (t.isNotBlank()) sb.append("\n\n[").append(label).append("]\n").append(t)
        }
        return sb.toString().trim().ifEmpty { "(This document has no text.)" }
    }

    // ---- Excel ----

    private val SI = Regex("""<si\b[^>]*>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
    private val PHONETIC = Regex("""<rPh\b.*?</rPh>""", RegexOption.DOT_MATCHES_ALL)
    private val T_ELEM = Regex("""<t\b[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
    private val ROW = Regex("""<row\b[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
    private val V_ELEM = Regex("""<v\b[^>]*>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
    private val F_ELEM = Regex("""<f\b[^>]*>(.*?)</f>""", RegexOption.DOT_MATCHES_ALL)

    private fun joinT(xml: String): String = T_ELEM.findAll(PHONETIC.replace(xml, "")).joinToString("") { unescape(it.groupValues[1]) }

    private fun xlsx(bytes: ByteArray): String? {
        val z = unzip(bytes) { it.startsWith("xl/") && it.endsWith(".xml") || it.endsWith(".rels") } ?: return null
        val workbook = z.text("xl/workbook.xml") ?: return null
        val shared = z.text("xl/sharedStrings.xml")?.let { x -> SI.findAll(x).map { joinT(it.groupValues[1]) }.toList() } ?: emptyList()
        val rels = HashMap<String, String>()
        z.text("xl/_rels/workbook.xml.rels")?.let { r ->
            Regex("""<Relationship\b[^>]*>""").findAll(r).forEach { m ->
                val id = attr(m.value, "Id"); val target = attr(m.value, "Target")
                if (id != null && target != null) rels[id] = if (target.startsWith("/")) target.substring(1) else "xl/$target"
            }
        }
        val sb = StringBuilder()
        var index = 0
        for (m in Regex("""<sheet\b[^>]*>""").findAll(workbook)) {
            index++
            val name = attr(m.value, "name") ?: "Sheet$index"
            val rid = attr(m.value, "r:id") ?: attr(m.value, "id")
            val file = (rid?.let { rels[it] } ?: "xl/worksheets/sheet$index.xml")
            val xml = z.text(file) ?: continue
            sb.append("## Sheet: ").append(name).append('\n')
            attr(xml.substringBefore("<sheetData", ""), "ref")?.let { sb.append("(range ").append(it).append(")\n") }
            var rows = 0
            for (r in ROW.findAll(xml)) {
                val cells = ArrayList<String>()
                for (c in CELL.findAll(r.groupValues[1])) {
                    val head = c.groupValues[1]; val body = c.groupValues[2]
                    val ref = attr(head, "r") ?: continue
                    val type = attr(head, "t")
                    val v = V_ELEM.find(body)?.groupValues?.get(1)?.let { unescape(it) }
                    val f = F_ELEM.find(body)?.groupValues?.get(1)?.let { unescape(it) }
                    val value = when (type) {
                        "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                        "inlineStr" -> joinT(body)
                        "b" -> if (v == "1") "TRUE" else "FALSE"
                        else -> v ?: ""
                    }
                    if (value.isEmpty() && f.isNullOrEmpty()) continue
                    cells.add("$ref: " + value.replace("\n", "\\n") + if (!f.isNullOrEmpty()) "  (=$f)" else "")
                }
                if (cells.isNotEmpty()) { sb.append(cells.joinToString(" | ")).append('\n'); rows++ }
            }
            if (rows == 0) sb.append("(empty)\n")
            sb.append('\n')
        }
        return sb.toString().trim().ifEmpty { null }
    }

    // ---- PowerPoint ----

    private val PPTX = Spec(text = setOf("a:t"), para = setOf("a:p"), cell = setOf("a:tc"), lineBreak = setOf("a:br"))

    private fun pptx(bytes: ByteArray): String? {
        val z = unzip(bytes) { it.startsWith("ppt/slides/slide") || it.startsWith("ppt/notesSlides/") } ?: return null
        val slide = Regex("""ppt/slides/slide(\d+)\.xml""")
        val notes = Regex("""ppt/notesSlides/notesSlide(\d+)\.xml""")
        val slides = z.data.keys.mapNotNull { n -> slide.matchEntire(n)?.let { it.groupValues[1].toInt() to n } }.sortedBy { it.first }
        if (slides.isEmpty()) return null
        val noteText = z.data.keys.mapNotNull { n -> notes.matchEntire(n)?.let { it.groupValues[1].toInt() to n } }.toMap()
        val sb = StringBuilder()
        for ((num, name) in slides) {
            sb.append("## Slide ").append(num).append('\n')
            sb.append(z.text(name)?.let { xmlText(it, PPTX) }.orEmpty().ifEmpty { "(no text)" }).append('\n')
            val n = noteText[num]?.let { z.text(it) }?.let { xmlText(it, PPTX) }.orEmpty()
            if (n.isNotBlank()) sb.append("Notes: ").append(n).append('\n')
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    // ---- OpenDocument and EPUB ----

    private val ODF = Spec(
        text = setOf("text:p", "text:h"), para = setOf("text:p", "text:h"), cell = setOf("table:table-cell"), row = setOf("table:table-row"),
        lineBreak = setOf("text:line-break"), tab = setOf("text:tab"), space = setOf("text:s")
    )

    private fun odf(bytes: ByteArray): String? {
        val z = unzip(bytes) { it == "content.xml" } ?: return null
        val t = xmlText(z.text("content.xml") ?: return null, ODF)
        return t.ifEmpty { "(This document has no text.)" }
    }

    private fun epub(bytes: ByteArray): String? {
        val z = unzip(bytes) { val l = it.lowercase(); l.endsWith(".xhtml") || l.endsWith(".html") || l.endsWith(".htm") } ?: return null
        if (z.data.isEmpty()) return null
        val sb = StringBuilder()
        for (n in z.data.keys.sorted()) {
            var h = z.text(n) ?: continue
            h = h.replace(Regex("""<(script|style)\b.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            h = h.replace(Regex("""</(p|div|h[1-6]|li|tr)>|<br\s*/?>""", RegexOption.IGNORE_CASE), "\n").replace(Regex("""<[^>]+>"""), "")
            val t = tidy(unescape(h))
            if (t.isNotBlank()) sb.append(t).append("\n\n")
        }
        return sb.toString().trim().ifEmpty { null }
    }

    // ---- PDF ----

    private class Str(val s: String)

    private fun inflate(data: ByteArray): ByteArray? {
        val inf = Inflater()
        return try {
            inf.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            var guard = 0
            while (!inf.finished() && guard++ < 200_000) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > 60_000_000) break
            }
            out.toByteArray()
        } catch (e: Exception) { null } finally { inf.end() }
    }

    private fun pdf(bytes: ByteArray): String? {
        if (bytes.size < 8 || String(bytes, 0, 5, Charsets.ISO_8859_1) != "%PDF-") return null
        val s = String(bytes, Charsets.ISO_8859_1)
        val pages = Regex("""/Type\s*/Page(?![s\w])""").findAll(s).count()
        val out = StringBuilder()
        val skipped = intArrayOf(0)
        var pos = 0
        while (out.length < MAX_CHARS * 2) {
            val k = s.indexOf("stream", pos)
            if (k < 0) break
            pos = k + 6
            if (k >= 3 && s.startsWith("end", k - 3)) continue
            var a = k + 6
            if (a < s.length && s[a] == '\r') a++
            if (a < s.length && s[a] == '\n') a++
            val e = s.indexOf("endstream", a)
            if (e < 0) break
            pos = e + 9
            val objAt = s.lastIndexOf("obj", k)
            val dict = if (objAt >= 0 && k - objAt < 3000) s.substring(objAt, k) else ""
            if (dict.contains("/Image") || dict.contains("/Length1") || dict.contains("/XRef") || dict.contains("/ObjStm") || dict.contains("/Metadata")) continue
            var data = bytes.copyOfRange(a, e)
            if (dict.contains("/FlateDecode")) data = inflate(data) ?: continue
            else if (dict.contains("/Filter")) continue
            val content = String(data, Charsets.ISO_8859_1)
            if (!content.contains("BT")) continue
            val t = pdfContent(content, skipped).trim()
            if (t.isNotEmpty()) out.append(t).append("\n\n")
        }
        val text = tidy(out)
        val letters = text.count { it.isLetter() }
        if (letters < 5) {
            return "(No text could be read from this PDF ($pages page${if (pages == 1) "" else "s"}). It may be scanned images, use embedded fonts the app cannot decode, or be encrypted. " +
                "Open it in the Code tab to look at the pages.)"
        }
        val note = if (skipped[0] > 0) "\n\n(Some text uses embedded fonts and could not be read.)" else ""
        return "PDF, ${if (pages > 0) "$pages page${if (pages == 1) "" else "s"}, " else ""}text read without OCR; layout, tables and some fonts may be missing:\n\n$text$note"
    }

    private fun isDelimiter(c: Char) = c.isWhitespace() || c in "()<>[]{}/%"

    private fun pdfContent(c: String, skipped: IntArray): String {
        val sb = StringBuilder()
        val ops = ArrayList<Any>()
        var lastY: Double? = null
        var i = 0
        val n = c.length

        fun literal(): Str {
            var depth = 1
            i++
            val b = StringBuilder()
            while (i < n) {
                val ch = c[i]
                when {
                    ch == '\\' && i + 1 < n -> {
                        i++
                        when (val e = c[i]) {
                            'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t'); 'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                            in '0'..'7' -> {
                                var v = e - '0'
                                var k = 1
                                while (k < 3 && i + 1 < n && c[i + 1] in '0'..'7') { i++; v = v * 8 + (c[i] - '0'); k++ }
                                b.append(v.toChar())
                            }
                            '\r' -> { if (i + 1 < n && c[i + 1] == '\n') i++ }
                            '\n' -> {}
                            else -> b.append(e)
                        }
                    }
                    ch == '(' -> { depth++; b.append(ch) }
                    ch == ')' -> { depth--; if (depth == 0) { i++; break }; b.append(ch) }
                    else -> b.append(ch)
                }
                i++
            }
            var t = b.toString()
            if (t.length >= 2 && t[0] == 'þ' && t[1] == 'ÿ') {
                t = String(t.substring(2).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_16BE)
            } else if (t.length >= 2 && t.length % 2 == 0 && t.indices.all { it % 2 != 0 || t[it] == '\u0000' }) {
                t = t.filterIndexed { idx, _ -> idx % 2 != 0 }
            }
            return Str(t)
        }

        fun hex(): Str? {
            val e = c.indexOf('>', i)
            val body = (if (e < 0) c.substring(i + 1) else c.substring(i + 1, e)).filter { !it.isWhitespace() }
            i = if (e < 0) n else e + 1
            val h = if (body.length % 2 == 1) body + "0" else body
            val bytes = ByteArray(h.length / 2) { k -> try { h.substring(k * 2, k * 2 + 2).toInt(16).toByte() } catch (x: Exception) { 0 } }
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Str(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE))
            val printable = bytes.count { val v = it.toInt() and 0xFF; v in 32..126 }
            if (bytes.isNotEmpty() && printable * 10 >= bytes.size * 9) return Str(String(bytes, Charsets.ISO_8859_1))
            skipped[0]++
            return null
        }

        fun num(x: Any?): Double? = x as? Double

        while (i < n) {
            val ch = c[i]
            when {
                ch.isWhitespace() || ch == '\u0000' -> i++
                ch == '%' -> { while (i < n && c[i] != '\n' && c[i] != '\r') i++ }
                ch == '(' -> ops.add(literal())
                ch == '<' && i + 1 < n && c[i + 1] == '<' -> {
                    var d = 0
                    while (i < n) {
                        if (c.startsWith("<<", i)) { d++; i += 2 } else if (c.startsWith(">>", i)) { d--; i += 2; if (d == 0) break } else i++
                    }
                }
                ch == '<' -> hex()?.let { ops.add(it) }
                ch == '[' -> {
                    i++
                    val arr = ArrayList<Any>()
                    while (i < n && c[i] != ']') {
                        val a = c[i]
                        when {
                            a == '(' -> arr.add(literal())
                            a == '<' && i + 1 < n && c[i + 1] != '<' -> hex()?.let { arr.add(it) }
                            a == '-' || a == '+' || a == '.' || a.isDigit() -> {
                                val st = i
                                i++
                                while (i < n && (c[i].isDigit() || c[i] == '.')) i++
                                c.substring(st, i).toDoubleOrNull()?.let { arr.add(it) }
                            }
                            else -> i++
                        }
                    }
                    i++
                    ops.add(arr)
                }
                ch == '/' -> { i++; while (i < n && !isDelimiter(c[i])) i++; ops.add("name") }
                ch == '-' || ch == '+' || ch == '.' || ch.isDigit() -> {
                    val st = i
                    i++
                    while (i < n && (c[i].isDigit() || c[i] == '.')) i++
                    ops.add(c.substring(st, i).toDoubleOrNull() ?: 0.0)
                }
                else -> {
                    val st = i
                    i++
                    while (i < n && !isDelimiter(c[i])) i++
                    val op = c.substring(st, i)
                    when (op) {
                        "Tj" -> (ops.lastOrNull() as? Str)?.let { sb.append(it.s) }
                        "TJ" -> (ops.lastOrNull() as? List<*>)?.forEach { item ->
                            if (item is Str) sb.append(item.s) else if (item is Double && item < -250) sb.append(' ')
                        }
                        "'", "\"" -> { sb.append('\n'); (ops.lastOrNull() as? Str)?.let { sb.append(it.s) } }
                        "Td", "TD" -> {
                            val tx = num(ops.getOrNull(ops.size - 2)) ?: 0.0
                            val ty = num(ops.lastOrNull()) ?: 0.0
                            if (Math.abs(ty) > 0.01) sb.append('\n') else if (tx > 0.01) sb.append(' ')
                        }
                        "T*" -> sb.append('\n')
                        "Tm" -> {
                            val y = num(ops.lastOrNull())
                            val prev = lastY
                            if (y != null && prev != null && Math.abs(y - prev) > 1.0) sb.append('\n')
                            if (y != null) lastY = y
                        }
                        "ET" -> sb.append('\n')
                        "ID" -> {
                            val ei = c.indexOf("EI", i)
                            i = if (ei < 0) n else ei + 2
                        }
                    }
                    ops.clear()
                }
            }
        }
        return sb.toString()
    }
}
