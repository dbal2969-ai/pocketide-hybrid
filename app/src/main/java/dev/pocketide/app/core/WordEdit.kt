package dev.pocketide.app.core

/** Edits to Word (.docx) files: text-level changes to word/document.xml that keep everything else in the file. */
internal object WordEdit {
    private val BLOCKS = setOf("w:p", "w:tbl", "w:sdt")
    private val RUN_ORDER = listOf("rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "outline", "shadow", "emboss", "imprint", "noProof", "snapToGrid", "vanish", "webHidden", "color", "spacing", "w", "kern", "position", "sz", "szCs", "highlight", "u", "effect", "bdr", "shd", "fitText", "vertAlign", "rtl", "cs", "em", "lang", "eastAsianLayout", "specVanish", "oMath")
    private val PARA_ORDER = listOf("pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr", "widowControl", "numPr", "suppressLineNumbers", "pBdr", "shd", "tabs", "suppressAutoHyphens", "kinsoku", "wordWrap", "overflowPunct", "topLinePunct", "autoSpaceDE", "autoSpaceDN", "bidi", "adjustRightInd", "snapToGrid", "spacing", "ind", "contextualSpacing", "mirrorIndents", "suppressOverlap", "jc", "textDirection", "textAlignment", "textboxTightWrap", "outlineLvl", "divId", "cnfStyle", "rPr", "sectPr", "pPrChange")
    private const val DOC = "word/document.xml"

    private class Doc(val xml: String, val bodyStart: Int, val bodyEnd: Int, val blocks: List<X.Span>)

    private fun load(pkg: DocPkg): Doc {
        val xml = pkg.text(DOC) ?: throw EditError("This is not a Word document (no word/document.xml).")
        val r = X.children(xml, "w:body") ?: throw EditError("The document has no body.")
        return Doc(xml, r.first, r.second, r.third.filter { it.name in BLOCKS })
    }

    private fun paraText(p: String): String {
        val sb = StringBuilder()
        for (m in Regex("""<w:t(?:\s[^>]*)?>([^<]*)</w:t>|<w:tab\s*/>|<w:br\s*/>""").findAll(p)) {
            if (m.value.startsWith("<w:t>") || m.value.startsWith("<w:t ")) sb.append(X.unesc(m.groupValues[1]))
            else if (m.value.startsWith("<w:tab")) sb.append('\t') else sb.append(' ')
        }
        return sb.toString()
    }

    private fun styleOf(p: String): String =
        Regex("""<w:pStyle\s+w:val="([^"]*)"""").find(p)?.groupValues?.get(1).orEmpty()

    private fun niceStyle(id: String): String = when {
        id.isEmpty() -> ""
        Regex("""(?i)^heading ?(\d)$""").matches(id) -> "Heading " + id.last()
        id.equals("Title", true) -> "Title"
        else -> id
    }

    fun outline(pkg: DocPkg): String {
        val d = load(pkg)
        val sb = StringBuilder("Word document with ${d.blocks.size} blocks (paragraphs and tables). Use these numbers as \"index\":\n")
        for ((i, b) in d.blocks.withIndex()) {
            if (i >= 300) { sb.append("… ${d.blocks.size - 300} more blocks\n"); break }
            val x = d.xml.substring(b.start, b.end)
            if (b.name == "w:tbl") {
                val rows = Regex("<w:tr[ >]").findAll(x).count()
                val cols = Regex("<w:gridCol").findAll(x).count()
                val first = Regex("""<w:tr[ >].*?</w:tr>""", RegexOption.DOT_MATCHES_ALL).find(x)?.value?.let { r ->
                    Regex("""<w:tc[ >].*?</w:tc>""", RegexOption.DOT_MATCHES_ALL).findAll(r).joinToString(" | ") { paraText(it.value).trim().take(30) }
                }.orEmpty()
                sb.append("${i + 1}. [table, $rows rows x $cols columns] $first\n")
            } else {
                val st = niceStyle(styleOf(x))
                val t = paraText(x).trim()
                sb.append("${i + 1}. ").append(if (st.isNotEmpty()) "[$st] " else "").append(if (t.isEmpty()) "(empty)" else t.take(140)).append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    // ---- positions ----

    /** Offset in the xml where new blocks go for a position such as top, bottom, before:3, after:3. */
    private fun place(d: Doc, position: String): Int {
        val p = position.trim().lowercase().ifEmpty { "bottom" }
        val sect = X.children(d.xml, "w:body")!!.third.lastOrNull { it.name == "w:sectPr" }
        return when {
            p in setOf("top", "start", "beginning", "first") -> d.blocks.firstOrNull()?.start ?: (sect?.start ?: d.bodyEnd)
            p in setOf("bottom", "end", "last") -> d.blocks.lastOrNull()?.end ?: (sect?.start ?: d.bodyEnd)
            p.startsWith("before:") || p.startsWith("after:") -> {
                val n = p.substringAfter(':').trim().toIntOrNull() ?: throw EditError("Position must be top, bottom, before:N or after:N.")
                if (n < 1 || n > d.blocks.size) throw EditError("There is no block $n (the document has ${d.blocks.size}).")
                if (p.startsWith("before:")) d.blocks[n - 1].start else d.blocks[n - 1].end
            }
            else -> throw EditError("Position must be top, bottom, before:N or after:N.")
        }
    }

    private fun nearestRunProps(d: Doc, offset: Int): String {
        val cand = d.blocks.filter { it.name == "w:p" && d.xml.substring(it.start, it.end).contains("<w:t") }
        val b = cand.minByOrNull { minOf(Math.abs(it.start - offset), Math.abs(it.end - offset)) } ?: return ""
        val x = d.xml.substring(b.start, b.end)
        val run = Regex("""<w:r(?:\s[^>]*)?>(?:(?!</w:r>).)*?<w:rPr>(.*?)</w:rPr>(?:(?!</w:r>).)*?<w:t[ >]""", RegexOption.DOT_MATCHES_ALL).find(x)?.groupValues?.get(1) ?: return ""
        val keep = listOf(Regex("""<w:rFonts[^>]*/>"""), Regex("""<w:sz [^>]*/>"""), Regex("""<w:szCs [^>]*/>""")).mapNotNull { it.find(run)?.value }
        return if (keep.isEmpty()) "" else "<w:rPr>${keep.joinToString("")}</w:rPr>"
    }

    private fun para(text: String, style: String = "", runProps: String = "", extraPPr: String = ""): String {
        val ppr = (if (style.isNotEmpty()) "<w:pStyle w:val=\"$style\"/>" else "") + extraPPr
        return "<w:p>" + (if (ppr.isNotEmpty()) "<w:pPr>$ppr</w:pPr>" else "") +
            (if (text.isEmpty()) "" else "<w:r>$runProps<w:t xml:space=\"preserve\">${X.esc(text)}</w:t></w:r>") + "</w:p>"
    }

    private fun ensureHeadingStyle(pkg: DocPkg, level: Int) {
        val id = "Heading$level"
        val st = pkg.text("word/styles.xml") ?: return
        if (st.contains("w:styleId=\"$id\"")) return
        val size = intArrayOf(0, 32, 28, 26, 24, 22, 22)[level]
        val def = "<w:style w:type=\"paragraph\" w:styleId=\"$id\"><w:name w:val=\"heading $level\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:uiPriority w:val=\"9\"/><w:qFormat/>" +
            "<w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"${level - 1}\"/></w:pPr><w:rPr><w:b/><w:bCs/><w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/></w:rPr></w:style>"
        val i = st.lastIndexOf("</w:styles>")
        if (i >= 0) pkg.put("word/styles.xml", st.substring(0, i) + def + st.substring(i))
    }

    private fun insert(pkg: DocPkg, d: Doc, offset: Int, xml: String) = pkg.put(DOC, d.xml.substring(0, offset) + xml + d.xml.substring(offset))

    private fun where(position: String) = when (val p = position.trim().lowercase().ifEmpty { "bottom" }) {
        "top", "start", "beginning", "first" -> "the top"
        "bottom", "end", "last" -> "the bottom"
        else -> p.replace(":", " block ")
    }

    // ---- paragraph editing ----

    private fun editPPr(p: String, f: (String) -> String): String {
        val open = Regex("""^<w:p(?=[\s>/])[^>]*?(/?)>""").find(p) ?: return p
        if (open.groupValues[1] == "/") return open.value.removeSuffix("/>") + "><w:pPr>" + f("") + "</w:pPr></w:p>"
        val rest = p.substring(open.range.last + 1)
        val m = Regex("""^<w:pPr>(.*?)</w:pPr>""", RegexOption.DOT_MATCHES_ALL).find(rest)
        if (m != null) return open.value + "<w:pPr>" + f(m.groupValues[1]) + "</w:pPr>" + rest.substring(m.range.last + 1)
        if (rest.startsWith("<w:pPr/>")) return open.value + "<w:pPr>" + f("") + "</w:pPr>" + rest.substring(8)
        return open.value + "<w:pPr>" + f("") + "</w:pPr>" + rest
    }

    private fun setRunFlag(p: String, name: String, tag: String, on: Boolean): String =
        Regex("""<w:r(?:\s[^>]*)?>.*?</w:r>""", RegexOption.DOT_MATCHES_ALL).replace(p) { m ->
            val run = m.value
            val rp = Regex("""<w:rPr>(.*?)</w:rPr>""", RegexOption.DOT_MATCHES_ALL).find(run)
            val open = Regex("""^<w:r(?:\s[^>]*)?>""").find(run)!!.value
            if (rp != null) {
                val c = rp.groupValues[1]
                val nc = if (on) X.insertOrdered(c, name, tag, RUN_ORDER) else X.childrenOfContent(c).firstOrNull { X.localName(it.name) == "w:$name".substringAfter(':') }?.let { c.removeRange(it.start, it.end) } ?: c
                run.substring(0, rp.range.first) + "<w:rPr>" + nc + "</w:rPr>" + run.substring(rp.range.last + 1)
            } else if (on) open + "<w:rPr>$tag</w:rPr>" + run.substring(open.length) else run
        }

    private fun block(d: Doc, index: Int): X.Span {
        if (index < 1 || index > d.blocks.size) throw EditError("There is no block $index (the document has ${d.blocks.size}). Use outline to see the numbers.")
        return d.blocks[index - 1]
    }

    private val REPLACE_PARTS = Regex("""word/(document|header\d*|footer\d*|footnotes|endnotes)\.xml""")

    fun apply(pkg: DocPkg, action: String, a: Args): String {
        when (action) {
            "outline" -> return outline(pkg)
            "add_heading" -> {
                val level = (a.int("level") ?: 1).coerceIn(1, 6)
                val text = a.req("text")
                val d = load(pkg)
                val off = place(d, a.s("position"))
                ensureHeadingStyle(pkg, level)
                insert(pkg, d, off, para(text, "Heading$level"))
                return "Added heading '${text.take(60)}' (Heading $level) at ${where(a.s("position"))}."
            }
            "add_paragraph", "add_text" -> {
                val lines = a.text("text").split("\n").filter { it.isNotEmpty() }
                if (lines.isEmpty()) throw EditError("Missing 'text'.")
                val d = load(pkg)
                val off = place(d, a.s("position"))
                val rp = nearestRunProps(d, off)
                insert(pkg, d, off, lines.joinToString("") { para(it, "", rp) })
                return "Added ${lines.size} paragraph${if (lines.size == 1) "" else "s"} at ${where(a.s("position"))}."
            }
            "add_bullets" -> {
                val items = a.text("text").split("\n", "|").map { it.trim().removePrefix("•").removePrefix("-").removePrefix("*").trim() }.filter { it.isNotEmpty() }
                if (items.isEmpty()) throw EditError("Missing 'text' (one item per line).")
                val d = load(pkg)
                val off = place(d, a.s("position"))
                val rp = nearestRunProps(d, off)
                insert(pkg, d, off, items.joinToString("") { para("•\t$it", "", rp, "<w:ind w:left=\"720\" w:hanging=\"360\"/>") })
                return "Added ${items.size} bullet${if (items.size == 1) "" else "s"} at ${where(a.s("position"))}."
            }
            "add_page_break" -> {
                val d = load(pkg)
                val off = place(d, a.s("position"))
                insert(pkg, d, off, "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                return "Added a page break at ${where(a.s("position"))}."
            }
            "add_table" -> {
                val rows = a.text("rows").split("\n").map { it.trim() }.filter { it.isNotEmpty() }.map { r -> r.split("|").map { it.trim() } }
                if (rows.isEmpty()) throw EditError("Missing 'rows': one row per line, cells separated by |.")
                val cols = rows.maxOf { it.size }
                val w = 9000 / cols
                val sb = StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>")
                for (b in listOf("top", "left", "bottom", "right", "insideH", "insideV")) sb.append("<w:$b w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>")
                sb.append("</w:tblBorders></w:tblPr><w:tblGrid>")
                repeat(cols) { sb.append("<w:gridCol w:w=\"$w\"/>") }
                sb.append("</w:tblGrid>")
                val d = load(pkg)
                val off = place(d, a.s("position"))
                val rp = nearestRunProps(d, off)
                for ((ri, r) in rows.withIndex()) {
                    sb.append("<w:tr>")
                    for (c in 0 until cols) {
                        val t = r.getOrElse(c) { "" }
                        val bold = ri == 0 && a.bool("header") != false
                        val props = if (bold) "<w:rPr><w:b/></w:rPr>" else rp
                        sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$w\" w:type=\"dxa\"/></w:tcPr>").append(para(t, "", props)).append("</w:tc>")
                    }
                    sb.append("</w:tr>")
                }
                sb.append("</w:tbl><w:p/>")
                insert(pkg, d, off, sb.toString())
                return "Added a table of ${rows.size} rows x $cols columns at ${where(a.s("position"))}."
            }
            "delete_paragraph", "delete_block" -> {
                val d = load(pkg)
                val b = block(d, a.reqInt("index"))
                val what = paraText(d.xml.substring(b.start, b.end)).trim().take(50)
                pkg.put(DOC, d.xml.removeRange(b.start, b.end))
                return "Deleted block ${a.reqInt("index")}" + if (what.isNotEmpty()) " ('$what')." else "."
            }
            "set_heading" -> {
                val level = (a.int("level") ?: 1).coerceIn(0, 6)
                val d = load(pkg)
                val i = a.reqInt("index")
                val b = block(d, i)
                if (b.name != "w:p") throw EditError("Block $i is a table, not a paragraph.")
                if (level > 0) ensureHeadingStyle(pkg, level)
                val p = d.xml.substring(b.start, b.end)
                val np = editPPr(p) { c ->
                    if (level == 0) X.childrenOfContent(c).firstOrNull { X.localName(it.name) == "pStyle" }?.let { c.removeRange(it.start, it.end) } ?: c
                    else X.insertOrdered(c, "pStyle", "<w:pStyle w:val=\"Heading$level\"/>", PARA_ORDER)
                }
                pkg.put(DOC, d.xml.substring(0, b.start) + np + d.xml.substring(b.end))
                return if (level == 0) "Block $i is now normal text." else "Block $i is now Heading $level."
            }
            "format_paragraph" -> {
                val d = load(pkg)
                val i = a.reqInt("index")
                val b = block(d, i)
                if (b.name != "w:p") throw EditError("Block $i is a table, not a paragraph.")
                var p = d.xml.substring(b.start, b.end)
                val did = ArrayList<String>()
                a.bool("bold")?.let { p = setRunFlag(p, "b", "<w:b/>", it); did.add(if (it) "bold" else "not bold") }
                a.bool("italic")?.let { p = setRunFlag(p, "i", "<w:i/>", it); did.add(if (it) "italic" else "not italic") }
                a.bool("underline")?.let { p = setRunFlag(p, "u", "<w:u w:val=\"single\"/>", it); did.add(if (it) "underlined" else "not underlined") }
                val al = a.s("align").trim().lowercase()
                if (al.isNotEmpty()) {
                    val v = when (al) { "left" -> "left"; "center", "centre" -> "center"; "right" -> "right"; "justify", "both" -> "both"; else -> throw EditError("align must be left, center, right or justify.") }
                    p = editPPr(p) { c -> X.insertOrdered(c, "jc", "<w:jc w:val=\"$v\"/>", PARA_ORDER) }
                    did.add("aligned $al")
                }
                if (did.isEmpty()) throw EditError("Nothing to change: give bold, italic, underline (true/false) or align.")
                pkg.put(DOC, d.xml.substring(0, b.start) + p + d.xml.substring(b.end))
                return "Block $i: ${did.joinToString(", ")}."
            }
            "replace_text" -> {
                val find = a.req("find")
                val repl = a.s("replace").replace("\\n", "\n")
                for (ic in listOf(false, true)) {
                    var total = 0
                    val changes = LinkedHashMap<String, String>()
                    for (name in pkg.names().filter { REPLACE_PARTS.matches(it) }) {
                        val xml = pkg.text(name) ?: continue
                        var n = 0
                        val out = Regex("""<w:p(?:\s[^>]*)?>.*?</w:p>""", RegexOption.DOT_MATCHES_ALL).replace(xml) { m ->
                            val (r, c) = X.replaceInRuns(m.value, "w:t", find, repl, ic)
                            n += c; r
                        }
                        if (n > 0) { changes[name] = out; total += n }
                    }
                    if (total > 0) {
                        for ((k, v) in changes) pkg.put(k, v)
                        return "Replaced $total occurrence${if (total == 1) "" else "s"} of '${find.take(40)}'" + if (ic) " (ignoring upper/lower case)." else "."
                    }
                }
                throw EditError("The text '${find.take(60)}' was not found. Use outline to see the exact wording.")
            }
            else -> throw EditError("Unknown action '$action' for a Word file.")
        }
    }

    val ACTIONS = listOf(
        DocEdit.Action("outline", "Numbered list of the paragraphs and tables.", emptyList()),
        DocEdit.Action("add_heading", "Add a heading.", listOf(DocEdit.Param("text"), DocEdit.Param("level", "1-6, default 1", false), DocEdit.Param("position", "top, bottom, before:N, after:N (default bottom)", false))),
        DocEdit.Action("add_paragraph", "Add one or more paragraphs.", listOf(DocEdit.Param("text", "one paragraph per line"), DocEdit.Param("position", "top, bottom, before:N, after:N", false))),
        DocEdit.Action("add_bullets", "Add a bullet list.", listOf(DocEdit.Param("text", "one item per line"), DocEdit.Param("position", "", false))),
        DocEdit.Action("add_table", "Add a table.", listOf(DocEdit.Param("rows", "one row per line, cells separated by |"), DocEdit.Param("position", "", false))),
        DocEdit.Action("add_page_break", "Add a page break.", listOf(DocEdit.Param("position", "", false))),
        DocEdit.Action("replace_text", "Replace text everywhere (body, headers, footers).", listOf(DocEdit.Param("find"), DocEdit.Param("replace"))),
        DocEdit.Action("delete_paragraph", "Delete a paragraph or table.", listOf(DocEdit.Param("index", "block number from outline"))),
        DocEdit.Action("set_heading", "Make a paragraph a heading (level 0 = normal text).", listOf(DocEdit.Param("index"), DocEdit.Param("level", "0-6"))),
        DocEdit.Action("format_paragraph", "Bold, italic, underline or align a paragraph.", listOf(DocEdit.Param("index"), DocEdit.Param("bold", "true/false", false), DocEdit.Param("italic", "true/false", false), DocEdit.Param("underline", "true/false", false), DocEdit.Param("align", "left, center, right, justify", false)))
    )
}
