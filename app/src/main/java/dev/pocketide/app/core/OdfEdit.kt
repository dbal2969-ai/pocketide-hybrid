package dev.pocketide.app.core

/** Edits to OpenDocument files (.odt text, .ods spreadsheets, .odp presentations) and EPUB books. */
internal object OdfEdit {
    private const val CONTENT = "content.xml"
    private val TEXT_BLOCKS = setOf("text:p", "text:h", "text:list", "table:table", "text:section")

    private fun plain(xml: String): String =
        X.unesc(xml.replace(Regex("""<text:s\b[^>]*/>"""), " ").replace(Regex("""<text:tab\s*/>"""), "\t").replace(Regex("""<text:line-break\s*/>"""), " ")
            .replace(Regex("""</text:p>|</text:h>"""), "\n").replace(Regex("""<[^>]*>"""), "")).replace(Regex("""\n{2,}"""), "\n").trim()

    private fun content(pkg: DocPkg): String = pkg.text(CONTENT) ?: throw EditError("This is not an OpenDocument file (no content.xml).")

    // ---- text documents ----

    private class Doc(val xml: String, val start: Int, val end: Int, val blocks: List<X.Span>)

    private fun load(pkg: DocPkg): Doc {
        val xml = content(pkg)
        val r = X.children(xml, "office:text") ?: throw EditError("This is not an OpenDocument text file.")
        return Doc(xml, r.first, r.second, r.third.filter { it.name in TEXT_BLOCKS })
    }

    private fun outlineText(pkg: DocPkg): String {
        val d = load(pkg)
        val sb = StringBuilder("OpenDocument text with ${d.blocks.size} blocks. Use these numbers as \"index\":\n")
        for ((i, b) in d.blocks.withIndex()) {
            if (i >= 300) { sb.append("… ${d.blocks.size - 300} more blocks\n"); break }
            val x = d.xml.substring(b.start, b.end)
            when (b.name) {
                "text:h" -> sb.append("${i + 1}. [Heading ${Regex("""text:outline-level="(\d+)"""").find(x)?.groupValues?.get(1) ?: "1"}] ${plain(x).take(140)}\n")
                "table:table" -> sb.append("${i + 1}. [table, ${Regex("<table:table-row[ >]").findAll(x).count()} rows] ${plain(x).replace("\n", " | ").take(100)}\n")
                "text:list" -> sb.append("${i + 1}. [list] ${plain(x).replace("\n", "; ").take(120)}\n")
                else -> sb.append("${i + 1}. ").append(plain(x).take(140).ifEmpty { "(empty)" }).append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    private fun place(d: Doc, position: String): Int {
        val p = position.trim().lowercase().ifEmpty { "bottom" }
        return when {
            p in setOf("top", "start", "beginning", "first") -> d.blocks.firstOrNull()?.start ?: d.end
            p in setOf("bottom", "end", "last") -> d.blocks.lastOrNull()?.end ?: d.end
            p.startsWith("before:") || p.startsWith("after:") -> {
                val n = p.substringAfter(':').trim().toIntOrNull() ?: throw EditError("Position must be top, bottom, before:N or after:N.")
                if (n < 1 || n > d.blocks.size) throw EditError("There is no block $n (the document has ${d.blocks.size}).")
                if (p.startsWith("before:")) d.blocks[n - 1].start else d.blocks[n - 1].end
            }
            else -> throw EditError("Position must be top, bottom, before:N or after:N.")
        }
    }

    private fun spaced(s: String): String = X.esc(s).replace(Regex(""" {2,}""")) { m -> " <text:s text:c=\"${m.value.length - 1}\"/>" }

    private fun textApply(pkg: DocPkg, action: String, a: Args): String? {
        when (action) {
            "add_heading" -> {
                val level = (a.int("level") ?: 1).coerceIn(1, 6)
                val d = load(pkg)
                val off = place(d, a.s("position"))
                pkg.put(CONTENT, d.xml.substring(0, off) + "<text:h text:style-name=\"Heading_20_$level\" text:outline-level=\"$level\">${spaced(a.req("text"))}</text:h>" + d.xml.substring(off))
                return "Added heading '${a.req("text").take(60)}' (Heading $level)."
            }
            "add_paragraph", "add_text" -> {
                val lines = a.text("text").split("\n").filter { it.isNotEmpty() }
                if (lines.isEmpty()) throw EditError("Missing 'text'.")
                val d = load(pkg)
                val off = place(d, a.s("position"))
                pkg.put(CONTENT, d.xml.substring(0, off) + lines.joinToString("") { "<text:p>${spaced(it)}</text:p>" } + d.xml.substring(off))
                return "Added ${lines.size} paragraph${if (lines.size == 1) "" else "s"}."
            }
            "add_bullets" -> {
                val items = a.text("text").split("\n", "|").map { it.trim().removePrefix("•").removePrefix("-").removePrefix("*").trim() }.filter { it.isNotEmpty() }
                if (items.isEmpty()) throw EditError("Missing 'text' (one item per line).")
                val d = load(pkg)
                val off = place(d, a.s("position"))
                pkg.put(CONTENT, d.xml.substring(0, off) + "<text:list>" + items.joinToString("") { "<text:list-item><text:p>${spaced(it)}</text:p></text:list-item>" } + "</text:list>" + d.xml.substring(off))
                return "Added ${items.size} bullet${if (items.size == 1) "" else "s"}."
            }
            "delete_paragraph", "delete_block" -> {
                val d = load(pkg)
                val i = a.reqInt("index")
                if (i < 1 || i > d.blocks.size) throw EditError("There is no block $i (the document has ${d.blocks.size}).")
                val b = d.blocks[i - 1]
                pkg.put(CONTENT, d.xml.removeRange(b.start, b.end))
                return "Deleted block $i."
            }
        }
        return null
    }

    // ---- spreadsheets ----

    private class Tbl(val name: String, val span: X.Span)

    private fun tables(xml: String): List<Tbl> {
        val r = X.children(xml, "office:spreadsheet") ?: throw EditError("This is not an OpenDocument spreadsheet.")
        return r.third.filter { it.name == "table:table" }.map { Tbl(X.attr(xml.substring(it.start, xml.indexOf('>', it.start) + 1), "table:name") ?: "Sheet", it) }
    }

    private fun pickTable(xml: String, spec: String): Tbl {
        val all = tables(xml)
        if (all.isEmpty()) throw EditError("The spreadsheet has no sheets.")
        val s = spec.trim()
        if (s.isEmpty()) return all[0]
        all.firstOrNull { it.name.equals(s, true) }?.let { return it }
        s.toIntOrNull()?.let { if (it in 1..all.size) return all[it - 1] }
        throw EditError("No sheet named '$s'. Sheets: ${all.joinToString(", ") { "'" + it.name + "'" }}.")
    }

    private fun colName(i: Int): String = SheetEdit.colName(i)

    private fun cellValue(c: String): String {
        val t = plain(c).replace("\n", " ")
        return t.ifEmpty { Regex("""office:value="([^"]*)"""").find(c)?.groupValues?.get(1)?.let { X.unesc(it) }.orEmpty() }
    }

    private fun outlineSheet(pkg: DocPkg): String {
        val xml = content(pkg)
        val all = tables(xml)
        val sb = StringBuilder("OpenDocument spreadsheet with ${all.size} sheet${if (all.size == 1) "" else "s"}: ${all.joinToString(", ") { "'" + it.name + "'" }}.\n")
        for (t in all.take(6)) {
            sb.append("\n== Sheet '${t.name}' ==\n")
            val seg = xml.substring(t.span.start, t.span.end)
            val rows = X.children(seg, "table:table")?.third?.filter { it.name == "table:table-row" } ?: emptyList()
            var r = 0; var shown = 0
            for (row in rows) {
                val rx = seg.substring(row.start, row.end)
                val repeat = Regex("""table:number-rows-repeated="(\d+)"""").find(rx.substringBefore('>'))?.groupValues?.get(1)?.toInt() ?: 1
                val cells = X.children(rx, "table:table-row")?.third ?: emptyList()
                var col = 0
                val parts = ArrayList<String>()
                for (c in cells) {
                    if (c.name != "table:table-cell" && c.name != "table:covered-table-cell") continue
                    val cx = rx.substring(c.start, c.end)
                    val rep = Regex("""table:number-columns-repeated="(\d+)"""").find(cx.substringBefore('>'))?.groupValues?.get(1)?.toInt() ?: 1
                    val v = cellValue(cx)
                    if (v.isNotEmpty() && parts.size < 14) parts.add(colName(col + 1) + (r + 1) + "=" + v.take(60))
                    col += minOf(rep, 200)
                }
                if (parts.isNotEmpty() && shown < 40) { sb.append(parts.joinToString(" | ")).append('\n'); shown++ }
                r += repeat
            }
        }
        return sb.toString().trimEnd()
    }

    private fun sheetApply(pkg: DocPkg, action: String, a: Args): String? {
        if (action != "append_row") return null
        val xml = content(pkg)
        val t = pickTable(xml, a.s("sheet"))
        val vals = a.text("values").split("|").map { it.trim() }
        if (vals.all { it.isEmpty() }) throw EditError("Missing 'values': cells separated by |.")
        val seg = xml.substring(t.span.start, t.span.end)
        val kids = X.children(seg, "table:table")!!
        val rows = kids.third.filter { it.name == "table:table-row" }
        val last = rows.lastOrNull { seg.substring(it.start, it.end).let { r -> r.contains("<text:p") || r.contains("office:value") } }
        val row = StringBuilder("<table:table-row>")
        for (v in vals) {
            row.append(
                if (v.isEmpty()) "<table:table-cell/>"
                else if (Regex("""^-?\d+(\.\d+)?$""").matches(v)) "<table:table-cell office:value-type=\"float\" office:value=\"$v\"><text:p>$v</text:p></table:table-cell>"
                else "<table:table-cell office:value-type=\"string\"><text:p>${spaced(v)}</text:p></table:table-cell>"
            )
        }
        row.append("</table:table-row>")
        val at = last?.end ?: rows.firstOrNull()?.start ?: kids.second
        val ns = seg.substring(0, at) + row + seg.substring(at)
        pkg.put(CONTENT, xml.substring(0, t.span.start) + ns + xml.substring(t.span.end))
        return "Added a row to '${t.name}'. (Formulas are stored as text in OpenDocument spreadsheets by this tool.)"
    }

    // ---- presentations ----

    private fun outlinePresentation(pkg: DocPkg): String {
        val xml = content(pkg)
        val r = X.children(xml, "office:presentation") ?: throw EditError("This is not an OpenDocument presentation.")
        val pages = r.third.filter { it.name == "draw:page" }
        val sb = StringBuilder("OpenDocument presentation with ${pages.size} slide${if (pages.size == 1) "" else "s"}:\n")
        for ((i, p) in pages.withIndex()) {
            val x = xml.substring(p.start, p.end)
            val lines = Regex("""<text:(?:p|h)\b[^>]*>.*?</text:(?:p|h)>""", RegexOption.DOT_MATCHES_ALL).findAll(x).map { plain(it.value) }.filter { it.isNotBlank() }.toList()
            sb.append("\nSlide ${i + 1}:\n")
            for (l in lines.take(20)) sb.append("  ").append(l.replace("\n", " ").take(150)).append('\n')
        }
        return sb.toString().trimEnd()
    }

    // ---- entry points ----

    fun outline(pkg: DocPkg, kind: String): String = when (kind) { "ods" -> outlineSheet(pkg); "odp" -> outlinePresentation(pkg); else -> outlineText(pkg) }

    fun apply(pkg: DocPkg, kind: String, action: String, a: Args): String {
        if (action == "outline") return outline(pkg, kind)
        if (action == "replace_text") {
            val find = a.req("find"); val repl = a.text("replace")
            for (ic in listOf(false, true)) {
                var total = 0
                val changes = LinkedHashMap<String, String>()
                for (name in listOf("content.xml", "styles.xml")) {
                    val xml = pkg.text(name) ?: continue
                    val (r, n) = X.replaceTextNodes(xml, find, repl, ic)
                    if (n > 0) { changes[name] = r; total += n }
                }
                if (total > 0) {
                    for ((k, v) in changes) pkg.put(k, v)
                    return "Replaced $total occurrence${if (total == 1) "" else "s"} of '${find.take(40)}'" + (if (ic) " (ignoring upper/lower case)." else ".")
                }
            }
            throw EditError("The text '${find.take(60)}' was not found (text split by different formatting in the middle of the phrase is not matched). Use outline to see the wording.")
        }
        val r = when (kind) { "odt" -> textApply(pkg, action, a); "ods" -> sheetApply(pkg, action, a); else -> null }
        return r ?: throw EditError("Unknown action '$action' for this file type.")
    }

    // ---- EPUB ----

    private val XHTML = Regex(""".*\.(xhtml|html|htm)""", RegexOption.IGNORE_CASE)

    fun epubOutline(pkg: DocPkg): String {
        val files = pkg.names().filter { XHTML.matches(it) }
        val sb = StringBuilder("EPUB book with ${files.size} content file${if (files.size == 1) "" else "s"}:\n")
        for (f in files.take(80)) {
            val x = pkg.text(f) ?: continue
            val h = Regex("""<h[1-3][^>]*>(.*?)</h[1-3]>""", RegexOption.DOT_MATCHES_ALL).find(x)?.groupValues?.get(1)?.let { plain(it.replace(Regex("""<[^>]*>"""), "")) }.orEmpty()
            sb.append("- $f").append(if (h.isNotEmpty()) ": " + h.take(80) else "").append(" (${x.length} characters)\n")
        }
        return sb.toString().trimEnd()
    }

    fun epubApply(pkg: DocPkg, action: String, a: Args): String {
        if (action == "outline") return epubOutline(pkg)
        if (action != "replace_text") throw EditError("Unknown action '$action' for an EPUB file.")
        val find = a.req("find"); val repl = a.text("replace")
        for (ic in listOf(false, true)) {
            var total = 0
            val changes = LinkedHashMap<String, String>()
            for (name in pkg.names().filter { XHTML.matches(it) }) {
                val xml = pkg.text(name) ?: continue
                val (r, n) = X.replaceTextNodes(xml, find, repl, ic)
                if (n > 0) { changes[name] = r; total += n }
            }
            if (total > 0) {
                for ((k, v) in changes) pkg.put(k, v)
                return "Replaced $total occurrence${if (total == 1) "" else "s"} of '${find.take(40)}' in the book" + (if (ic) " (ignoring upper/lower case)." else ".")
            }
        }
        throw EditError("The text '${find.take(60)}' was not found (text split by formatting in the middle of the phrase is not matched).")
    }

    val TEXT_ACTIONS = listOf(
        DocEdit.Action("outline", "Numbered list of the paragraphs.", emptyList()),
        DocEdit.Action("add_heading", "Add a heading.", listOf(DocEdit.Param("text"), DocEdit.Param("level", "1-6", false), DocEdit.Param("position", "top, bottom, before:N, after:N", false))),
        DocEdit.Action("add_paragraph", "Add paragraphs.", listOf(DocEdit.Param("text", "one paragraph per line"), DocEdit.Param("position", "", false))),
        DocEdit.Action("add_bullets", "Add a bullet list.", listOf(DocEdit.Param("text", "one item per line"), DocEdit.Param("position", "", false))),
        DocEdit.Action("delete_paragraph", "Delete a block.", listOf(DocEdit.Param("index"))),
        DocEdit.Action("replace_text", "Replace text.", listOf(DocEdit.Param("find"), DocEdit.Param("replace")))
    )
    val SHEET_ACTIONS = listOf(
        DocEdit.Action("outline", "Sheets and cell values.", emptyList()),
        DocEdit.Action("append_row", "Add a row after the last row with data.", listOf(DocEdit.Param("values", "cells separated by |"), DocEdit.Param("sheet", "", false))),
        DocEdit.Action("replace_text", "Replace text in cells.", listOf(DocEdit.Param("find"), DocEdit.Param("replace")))
    )
    val SLIDE_ACTIONS = listOf(
        DocEdit.Action("outline", "Slides and their text.", emptyList()),
        DocEdit.Action("replace_text", "Replace text on the slides.", listOf(DocEdit.Param("find"), DocEdit.Param("replace")))
    )
    val EPUB_ACTIONS = listOf(
        DocEdit.Action("outline", "Content files of the book.", emptyList()),
        DocEdit.Action("replace_text", "Replace text in the book.", listOf(DocEdit.Param("find"), DocEdit.Param("replace")))
    )
}
