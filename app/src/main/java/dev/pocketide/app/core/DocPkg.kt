package dev.pocketide.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What one edit action did: the new file bytes, or null with the reason it could not be done. */
class EditResult(val bytes: ByteArray?, val message: String) {
    val ok: Boolean get() = bytes != null
    companion object {
        fun fail(message: String) = EditResult(null, message)
    }
}

/** An edit that could not be done; the message goes back to the user or the AI. */
class EditError(message: String) : Exception(message)

/** The files inside a zip-based document (docx, xlsx, pptx, odf, epub, zip), in their original order. */
class DocPkg(val entries: LinkedHashMap<String, ByteArray>) {
    fun has(name: String) = entries.containsKey(name)
    fun text(name: String): String? = entries[name]?.toString(Charsets.UTF_8)
    fun put(name: String, text: String) { entries[name] = text.toByteArray(Charsets.UTF_8) }
    fun remove(name: String) { entries.remove(name) }
    fun names(): List<String> = entries.keys.toList()

    fun toBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            // "mimetype" (OpenDocument, EPUB) must come first and be stored, not compressed
            val order = entries.keys.sortedBy { if (it == "mimetype") 0 else 1 }
            for (name in order) {
                val data = entries[name] ?: continue
                val e = ZipEntry(name)
                if (name == "mimetype") {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
                    val crc = CRC32(); crc.update(data); e.crc = crc.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_ENTRY = 60L * 1024 * 1024
        private const val MAX_TOTAL = 200L * 1024 * 1024

        fun read(bytes: ByteArray): DocPkg {
            val map = LinkedHashMap<String, ByteArray>()
            var total = 0L
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (e.isDirectory) continue
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(16384)
                        var got = 0L
                        while (true) {
                            val n = z.read(buf)
                            if (n <= 0) break
                            got += n
                            if (got > MAX_ENTRY || total + got > MAX_TOTAL) throw EditError("The file is too large to edit on the phone.")
                            out.write(buf, 0, n)
                        }
                        total += got
                        map[e.name] = out.toByteArray()
                    }
                }
            } catch (e: EditError) { throw e } catch (e: Exception) { if (map.isEmpty()) throw EditError("This is not a valid zip-based file (it may be damaged or password protected).") }
            if (map.isEmpty()) throw EditError("The file has no content.")
            return DocPkg(map)
        }
    }
}

/** Small XML helpers shared by the editors. Text-based, no DOM, so they run anywhere. */
internal object X {
    val TAG = Regex("""<(/?)([A-Za-z_][\w:.\-]*)([^>]*?)(/?)>""")

    fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    fun unesc(s: String): String = DocText.unescape(s)

    /** Value of attribute name in an opening tag's text. */
    fun attr(tag: String, name: String): String? =
        Regex("""(?<![\w:.\-])${Regex.escape(name)}\s*=\s*"([^"]*)"""").find(tag)?.groupValues?.get(1)?.let { unesc(it) }

    class Span(val start: Int, val end: Int, val name: String)

    /**
     * The direct children of the first <parent> element: where its content starts and ends, and each child's span.
     * Null when the element is missing. A self-closing parent has no children (start == end).
     */
    fun children(xml: String, parent: String): Triple<Int, Int, List<Span>>? {
        val open = Regex("""<${Regex.escape(parent)}(?=[\s>/])([^>]*?)(/?)>""").find(xml) ?: return null
        if (open.groupValues[2] == "/") return Triple(open.range.last + 1, open.range.last + 1, emptyList())
        val kids = ArrayList<Span>()
        var depth = 0
        var childStart = -1
        var childName = ""
        for (m in TAG.findAll(xml, open.range.last + 1)) {
            val closing = m.groupValues[1] == "/"
            val self = m.groupValues[4] == "/"
            val name = m.groupValues[2]
            if (closing) {
                if (depth == 0) { return if (name == parent) Triple(open.range.last + 1, m.range.first, kids) else null }
                depth--
                if (depth == 0) kids.add(Span(childStart, m.range.last + 1, childName))
            } else if (self) {
                if (depth == 0) kids.add(Span(m.range.first, m.range.last + 1, name))
            } else {
                if (depth == 0) { childStart = m.range.first; childName = name }
                depth++
            }
        }
        return null
    }

    /** Inserts child markup into a container's content in schema order: before the first existing child that must come later. */
    fun insertOrdered(content: String, childName: String, childXml: String, order: List<String>): String {
        var work = content
        childrenOfContent(work).firstOrNull { localName(it.name) == childName }?.let { work = work.removeRange(it.start, it.end) }
        val idx = order.indexOf(childName)
        val later = childrenOfContent(work).firstOrNull { order.indexOf(localName(it.name)) > idx }
        return if (later != null) work.substring(0, later.start) + childXml + work.substring(later.start) else work + childXml
    }

    /** Child spans of a content string (the inside of an element). */
    fun childrenOfContent(content: String): List<Span> {
        val r = children("<w:__>$content</w:__>", "w:__") ?: return emptyList()
        return r.third.map { Span(it.start - 6, it.end - 6, it.name) }
    }

    fun localName(n: String) = n.substringAfter(':')

    /** Replaces text in the text nodes of markup (never inside tags, scripts or styles). Returns the new markup and the count. */
    fun replaceTextNodes(xml: String, find: String, repl: String, ignoreCase: Boolean): Pair<String, Int> {
        if (find.isEmpty()) return xml to 0
        val sb = StringBuilder()
        var count = 0
        var last = 0
        var skip = 0
        fun node(from: Int, to: Int) {
            if (to <= from) return
            val seg = xml.substring(from, to)
            if (skip > 0 || !seg.contains('&') && !seg.contains(find[0], ignoreCase) ) { sb.append(seg); return }
            val plain = unesc(seg)
            val (r, n) = replaceCount(plain, find, repl, ignoreCase)
            if (n == 0) sb.append(seg) else { count += n; sb.append(esc(r).replace("&quot;", "\"")) }
        }
        for (m in TAG.findAll(xml)) {
            node(last, m.range.first)
            sb.append(m.value)
            last = m.range.last + 1
            val n = m.groupValues[2].lowercase()
            if ((n == "script" || n == "style") && m.groupValues[4] != "/") skip = if (m.groupValues[1] == "/") maxOf(0, skip - 1) else skip + 1
        }
        node(last, xml.length)
        return sb.toString() to count
    }

    fun replaceCount(text: String, find: String, repl: String, ignoreCase: Boolean): Pair<String, Int> {
        if (find.isEmpty()) return text to 0
        var count = 0
        val sb = StringBuilder()
        var i = 0
        while (true) {
            val j = text.indexOf(find, i, ignoreCase)
            if (j < 0) break
            sb.append(text, i, j).append(repl)
            i = j + find.length
            count++
        }
        sb.append(text, i, text.length)
        return sb.toString() to count
    }

    /**
     * Replaces text inside one paragraph-like element whose text lives in <tName> runs (Word w:t, PowerPoint a:t, Excel t).
     * Text split across several runs is joined into the first run. Returns the new element and the replacement count.
     */
    fun replaceInRuns(el: String, tName: String, find: String, repl: String, ignoreCase: Boolean): Pair<String, Int> {
        val rx = Regex("""<${Regex.escape(tName)}((?:\s[^>]*)?)>([^<]*)</${Regex.escape(tName)}>""")
        val runs = rx.findAll(el).toList()
        if (runs.isEmpty()) return el to 0
        var total = 0
        // 1) occurrences inside a single run keep that run's formatting
        var work = el
        run {
            val sb = StringBuilder(); var last = 0
            for (m in runs) {
                sb.append(el, last, m.range.first)
                val (r, n) = replaceCount(unesc(m.groupValues[2]), find, repl, ignoreCase)
                if (n > 0) { total += n; sb.append("<$tName xml:space=\"preserve\">").append(esc(r).replace("&quot;", "\"")).append("</$tName>") } else sb.append(m.value)
                last = m.range.last + 1
            }
            sb.append(el, last, el.length)
            work = sb.toString()
        }
        // 2) what is left may span runs: join the text into the first run
        val runs2 = rx.findAll(work).toList()
        val joined = runs2.joinToString("") { unesc(it.groupValues[2]) }
        val (merged, n2) = replaceCount(joined, find, repl, ignoreCase)
        if (n2 == 0) return work to total
        total += n2
        val sb = StringBuilder(); var last = 0
        for ((i, m) in runs2.withIndex()) {
            sb.append(work, last, m.range.first)
            if (i == 0) sb.append("<$tName xml:space=\"preserve\">").append(esc(merged).replace("&quot;", "\"")).append("</$tName>")
            else sb.append("<$tName></$tName>")  // later runs lose their text but keep their formatting
            last = m.range.last + 1
        }
        sb.append(work, last, work.length)
        return sb.toString() to total
    }
}

/** The named values of one edit action (all text, as an AI or a form supplies them). */
class Args(val m: Map<String, String>) {
    fun s(k: String): String = m[k].orEmpty()
    fun has(k: String): Boolean = !m[k].isNullOrEmpty()
    fun req(k: String): String = m[k]?.takeIf { it.isNotEmpty() } ?: throw EditError("Missing '$k'.")
    fun int(k: String): Int? = m[k]?.trim()?.toDoubleOrNull()?.toInt()
    fun reqInt(k: String): Int = int(k) ?: throw EditError("'$k' must be a number.")
    fun bool(k: String): Boolean? = when (m[k]?.trim()?.lowercase()) { "true", "yes", "1", "on" -> true; "false", "no", "0", "off" -> false; else -> null }
    /** Text with the two-character sequence \n turned into a real line break (small models often send it that way). */
    fun text(k: String): String = m[k].orEmpty().replace("\\n", "\n")
}
