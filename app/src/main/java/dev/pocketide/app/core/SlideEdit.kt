package dev.pocketide.app.core

/** Edits to PowerPoint (.pptx) files: slide text, adding, deleting, moving and duplicating slides. */
internal object SlideEdit {
    private const val PRES = "ppt/presentation.xml"
    private const val PRES_RELS = "ppt/_rels/presentation.xml.rels"
    private const val CT = "[Content_Types].xml"
    private const val REL_SLIDE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"
    private const val REL_LAYOUT = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout"

    private class Slide(val id: String, val rid: String, val path: String)

    private fun relTargets(xml: String, base: String): Map<String, Pair<String, String>> {
        val out = HashMap<String, Pair<String, String>>()
        for (m in Regex("""<Relationship\b[^>]*/>""").findAll(xml)) {
            val id = X.attr(m.value, "Id") ?: continue
            val t = X.attr(m.value, "Target") ?: continue
            val type = X.attr(m.value, "Type").orEmpty()
            out[id] = (if (t.startsWith("/")) t.removePrefix("/") else normalize("$base/$t")) to type
        }
        return out
    }

    private fun normalize(p: String): String {
        val parts = ArrayList<String>()
        for (x in p.split("/")) { if (x == "..") { if (parts.isNotEmpty()) parts.removeAt(parts.size - 1) } else if (x != "." && x.isNotEmpty()) parts.add(x) }
        return parts.joinToString("/")
    }

    private fun slides(pkg: DocPkg): List<Slide> {
        val pres = pkg.text(PRES) ?: throw EditError("This is not a PowerPoint file (no ppt/presentation.xml).")
        val rels = relTargets(pkg.text(PRES_RELS) ?: "", "ppt")
        val out = ArrayList<Slide>()
        for (m in Regex("""<p:sldId\b[^>]*/>""").findAll(pres)) {
            val id = X.attr(m.value, "id") ?: continue
            val rid = Regex("""\s\w+:id="([^"]*)"""").find(m.value)?.groupValues?.get(1) ?: continue
            val t = rels[rid]?.first ?: continue
            if (pkg.has(t)) out.add(Slide(id, rid, t))
        }
        return out
    }

    private fun slideAt(pkg: DocPkg, index: Int): Slide {
        val all = slides(pkg)
        if (index < 1 || index > all.size) throw EditError("There is no slide $index (the presentation has ${all.size}).")
        return all[index - 1]
    }

    private class Shape(val start: Int, val end: Int, val kind: String, val lines: List<String>)

    private fun shapes(xml: String): List<Shape> {
        val out = ArrayList<Shape>()
        for (m in Regex("""<p:sp>.*?</p:sp>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val sp = m.value
            if (!sp.contains("<p:txBody>")) continue
            val ph = Regex("""<p:ph\b([^>]*)/?>""").find(sp)?.groupValues?.get(1)
            val type = ph?.let { Regex("""type="(\w+)"""").find(it)?.groupValues?.get(1) }
            val kind = when {
                type == "title" || type == "ctrTitle" -> "title"
                type == "subTitle" -> "subtitle"
                ph != null && (type == null || type == "body") -> "body"
                ph != null -> type ?: "placeholder"
                else -> "text"
            }
            val lines = Regex("""<a:p>.*?</a:p>""", RegexOption.DOT_MATCHES_ALL).findAll(sp).map { p ->
                Regex("""<a:t>([^<]*)</a:t>|<a:t\s[^>]*>([^<]*)</a:t>""").findAll(p.value).joinToString("") { X.unesc(it.groupValues[1] + it.groupValues[2]) }
            }.toList()
            out.add(Shape(m.range.first, m.range.last + 1, kind, lines))
        }
        return out
    }

    fun outline(pkg: DocPkg): String {
        val all = slides(pkg)
        val sb = StringBuilder("PowerPoint presentation with ${all.size} slide${if (all.size == 1) "" else "s"}. Text boxes are numbered per slide:\n")
        for ((i, s) in all.withIndex()) {
            if (i >= 60) { sb.append("… ${all.size - 60} more slides\n"); break }
            sb.append("\nSlide ${i + 1}:\n")
            val xml = pkg.text(s.path) ?: continue
            val sh = shapes(xml)
            if (sh.isEmpty()) sb.append("  (no text boxes)\n")
            for ((j, x) in sh.withIndex()) {
                val t = x.lines.filter { it.isNotBlank() }.joinToString(" / ").take(160)
                sb.append("  [${j + 1}] ${x.kind}: ").append(if (t.isEmpty()) "(empty)" else t).append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    // ---- parts ----

    private fun addOverride(pkg: DocPkg, part: String, type: String) {
        val ct = pkg.text(CT) ?: return
        if (ct.contains("PartName=\"/$part\"")) return
        pkg.put(CT, ct.replace("</Types>", "<Override PartName=\"/$part\" ContentType=\"$type\"/></Types>"))
    }

    private fun dropPart(pkg: DocPkg, part: String) {
        pkg.remove(part)
        pkg.text(CT)?.let { pkg.put(CT, it.replace(Regex("""<Override\b[^>]*PartName="/${Regex.escape(part)}"[^>]*/>"""), "")) }
    }

    private fun relsPath(part: String) = part.substringBeforeLast('/') + "/_rels/" + part.substringAfterLast('/') + ".rels"

    private fun listXml(pres: String): X.Span? {
        val m = Regex("""<p:sldIdLst>.*?</p:sldIdLst>|<p:sldIdLst\s*/>""", RegexOption.DOT_MATCHES_ALL).find(pres) ?: return null
        return X.Span(m.range.first, m.range.last + 1, "p:sldIdLst")
    }

    private fun entries(pres: String): List<String> = Regex("""<p:sldId\b[^>]*/>""").findAll(listXml(pres)?.let { pres.substring(it.start, it.end) } ?: "").map { it.value }.toList()

    private fun writeList(pkg: DocPkg, items: List<String>) {
        val pres = pkg.text(PRES)!!
        val span = listXml(pres)
        val xml = "<p:sldIdLst>" + items.joinToString("") + "</p:sldIdLst>"
        if (span != null) pkg.put(PRES, pres.substring(0, span.start) + xml + pres.substring(span.end))
        else {
            val at = pres.indexOf("<p:sldSz").takeIf { it >= 0 } ?: throw EditError("The presentation has no slide list.")
            pkg.put(PRES, pres.substring(0, at) + xml + pres.substring(at))
        }
    }

    private fun newSlidePart(pkg: DocPkg, slideXml: String, rels: String, afterIndex: Int): Int {
        var n = 1
        while (pkg.has("ppt/slides/slide$n.xml")) n++
        val path = "ppt/slides/slide$n.xml"
        pkg.put(path, slideXml)
        pkg.put(relsPath(path), rels)
        addOverride(pkg, path, "application/vnd.openxmlformats-officedocument.presentationml.slide+xml")
        var pr = pkg.text(PRES_RELS) ?: throw EditError("The presentation has no relationships file.")
        var k = 1
        while (pr.contains("Id=\"rId$k\"")) k++
        pr = pr.replace("</Relationships>", "<Relationship Id=\"rId$k\" Type=\"$REL_SLIDE\" Target=\"slides/slide$n.xml\"/></Relationships>")
        pkg.put(PRES_RELS, pr)
        val pres = pkg.text(PRES)!!
        val rp = Regex("""xmlns:(\w+)="http://schemas.openxmlformats.org/officeDocument/2006/relationships"""").find(pres)?.groupValues?.get(1) ?: "r"
        val items = entries(pres).toMutableList()
        val maxId = items.maxOfOrNull { Regex("""\sid="(\d+)"""").find(it)?.groupValues?.get(1)?.toInt() ?: 255 } ?: 255
        val entry = "<p:sldId id=\"${maxOf(maxId, 255) + 1}\" $rp:id=\"rId$k\"/>"
        items.add(afterIndex.coerceIn(0, items.size), entry)
        writeList(pkg, items)
        return afterIndex.coerceIn(0, items.size - 1) + 1
    }

    private fun pickLayout(pkg: DocPkg, fallbackSlide: Slide?): String {
        val layouts = pkg.names().filter { Regex("""ppt/slideLayouts/slideLayout\d+\.xml""").matches(it) }.sortedBy { Regex("""\d+""").find(it.substringAfterLast('/'))!!.value.toInt() }
        for (l in layouts) if (Regex("""<p:cSld\s+name="Title and Content"""").containsMatchIn(pkg.text(l) ?: "")) return l
        for (l in layouts) { val x = pkg.text(l) ?: ""; if (x.contains("type=\"title\"") && Regex("""<p:ph\s+idx="1"""").containsMatchIn(x) && x.contains("type=\"obj\"")) return l }
        for (l in layouts) { val x = pkg.text(l) ?: ""; if (x.contains("type=\"title\"") && Regex("""<p:ph\s+idx="1"""").containsMatchIn(x)) return l }
        if (fallbackSlide != null) {
            val rels = relTargets(pkg.text(relsPath(fallbackSlide.path)) ?: "", "ppt/slides")
            rels.values.firstOrNull { it.second == REL_LAYOUT }?.let { return it.first }
        }
        return layouts.firstOrNull() ?: throw EditError("The presentation has no slide layouts to build a slide from.")
    }

    private fun paragraphs(text: String, rPr: String = ""): String =
        text.split("\n").map { it.trim().removePrefix("•").removePrefix("- ").removePrefix("* ").trim() }.filter { it.isNotEmpty() }
            .joinToString("") { "<a:p><a:r>$rPr<a:t>${X.esc(it)}</a:t></a:r></a:p>" }.ifEmpty { "<a:p><a:endParaRPr lang=\"en-US\"/></a:p>" }

    private fun slideXml(title: String, body: String, hasBody: Boolean): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cSld><p:spTree>")
        sb.append("<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>")
        sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title 1\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/>")
        sb.append(paragraphs(title.replace("\n", " "), "<a:rPr lang=\"en-US\"/>")).append("</p:txBody></p:sp>")
        if (hasBody) {
            sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Content Placeholder 2\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/>")
            sb.append(paragraphs(body, "<a:rPr lang=\"en-US\"/>")).append("</p:txBody></p:sp>")
        }
        sb.append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
        return sb.toString()
    }

    private fun position(spec: String, count: Int): Int {
        val p = spec.trim().lowercase()
        return when {
            p.isEmpty() || p in setOf("end", "bottom", "last") -> count
            p in setOf("start", "top", "first", "beginning") -> 0
            p.startsWith("after:") -> (p.substringAfter(':').trim().toIntOrNull() ?: throw EditError("Position must be start, end or after:N.")).coerceIn(0, count)
            p.startsWith("before:") -> ((p.substringAfter(':').trim().toIntOrNull() ?: throw EditError("Position must be start, end or before:N.")) - 1).coerceIn(0, count)
            p.toIntOrNull() != null -> (p.toInt() - 1).coerceIn(0, count)
            else -> throw EditError("Position must be start, end, after:N or before:N.")
        }
    }

    private val TEXT_PARTS = Regex("""ppt/(slides/slide\d+|notesSlides/notesSlide\d+)\.xml""")

    fun apply(pkg: DocPkg, action: String, a: Args): String {
        when (action) {
            "outline" -> return outline(pkg)
            "replace_text" -> {
                val find = a.req("find"); val repl = a.text("replace")
                for (ic in listOf(false, true)) {
                    var total = 0
                    val changes = LinkedHashMap<String, String>()
                    for (name in pkg.names().filter { TEXT_PARTS.matches(it) }) {
                        val xml = pkg.text(name) ?: continue
                        var n = 0
                        val out = Regex("""<a:p>.*?</a:p>""", RegexOption.DOT_MATCHES_ALL).replace(xml) { m ->
                            val (r, c) = X.replaceInRuns(m.value, "a:t", find, repl, ic); n += c; r
                        }
                        if (n > 0) { changes[name] = out; total += n }
                    }
                    if (total > 0) {
                        for ((k, v) in changes) pkg.put(k, v)
                        return "Replaced $total occurrence${if (total == 1) "" else "s"} of '${find.take(40)}' on the slides" + if (ic) " (ignoring upper/lower case)." else "."
                    }
                }
                throw EditError("The text '${find.take(60)}' was not found. Use outline to see the exact wording.")
            }
            "set_slide_text" -> {
                val i = a.reqInt("slide")
                val s = slideAt(pkg, i)
                val xml = pkg.text(s.path)!!
                val sh = shapes(xml)
                val tg = a.s("target").trim().lowercase().ifEmpty { "body" }
                val shape = tg.toIntOrNull()?.let { n -> sh.getOrNull(n - 1) ?: throw EditError("Slide $i has no text box $n (it has ${sh.size}).") }
                    ?: sh.firstOrNull { it.kind == tg } ?: (if (tg == "body") sh.firstOrNull { it.kind == "text" || it.kind == "subtitle" } else null)
                    ?: throw EditError("Slide $i has no '$tg' text box. Use outline to see the numbered text boxes.")
                val sp = xml.substring(shape.start, shape.end)
                val tx = sp.indexOf("<p:txBody>")
                val firstP = sp.indexOf("<a:p>", tx)
                val lastP = sp.lastIndexOf("</a:p>")
                if (tx < 0 || firstP < 0 || lastP < 0) throw EditError("That text box cannot be changed.")
                val rPr = Regex("""<a:rPr\b[^>]*/>""").find(sp.substring(firstP))?.value ?: "<a:rPr lang=\"en-US\"/>"
                val np = sp.substring(0, firstP) + paragraphs(a.text("text"), rPr) + sp.substring(lastP + "</a:p>".length)
                pkg.put(s.path, xml.substring(0, shape.start) + np + xml.substring(shape.end))
                return "Changed the ${shape.kind} text on slide $i."
            }
            "add_slide" -> {
                val all = slides(pkg)
                val title = a.req("title")
                val body = a.text("body")
                val layout = pickLayout(pkg, all.lastOrNull())
                val hasBody = Regex("""<p:ph\s+idx="1"""").containsMatchIn(pkg.text(layout) ?: "")
                val rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"$REL_LAYOUT\" Target=\"../slideLayouts/${layout.substringAfterLast('/')}\"/></Relationships>"
                val at = position(a.s("position"), all.size)
                val n = newSlidePart(pkg, slideXml(title, body, hasBody), rels, at)
                return "Added slide $n '${title.take(50)}'." + if (body.isNotBlank() && !hasBody) " (This layout has no text area, so only the title was used.)" else ""
            }
            "duplicate_slide" -> {
                val i = a.reqInt("slide")
                val s = slideAt(pkg, i)
                val relsXml = (pkg.text(relsPath(s.path)) ?: "").replace(Regex("""<Relationship\b[^>]*notesSlide[^>]*/>"""), "")
                val n = newSlidePart(pkg, pkg.text(s.path)!!, relsXml.ifEmpty { "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>" }, i)
                return "Duplicated slide $i as slide $n."
            }
            "delete_slide" -> {
                val i = a.reqInt("slide")
                val all = slides(pkg)
                if (all.size <= 1) throw EditError("A presentation needs at least one slide.")
                val s = slideAt(pkg, i)
                val items = entries(pkg.text(PRES)!!).filter { X.attr(it, "id") != s.id }
                writeList(pkg, items)
                pkg.text(PRES)?.let { pkg.put(PRES, it.replace(Regex("""<p14:sldId\s+id="${s.id}"\s*/>"""), "")) }
                pkg.text(PRES_RELS)?.let { pkg.put(PRES_RELS, it.replace(Regex("""<Relationship\b[^>]*Id="${Regex.escape(s.rid)}"[^>]*/>"""), "")) }
                val rel = relsPath(s.path)
                for ((_, t) in relTargets(pkg.text(rel) ?: "", "ppt/slides")) if (t.second.endsWith("/notesSlide")) { dropPart(pkg, t.first); pkg.remove(relsPath(t.first)) }
                pkg.remove(rel)
                dropPart(pkg, s.path)
                return "Deleted slide $i."
            }
            "move_slide" -> {
                val i = a.reqInt("slide"); val to = a.reqInt("to")
                val all = slides(pkg)
                if (i < 1 || i > all.size || to < 1 || to > all.size) throw EditError("Slide numbers are 1 to ${all.size}.")
                val items = entries(pkg.text(PRES)!!).toMutableList()
                val e = items.removeAt(i - 1)
                items.add(to - 1, e)
                writeList(pkg, items)
                return "Moved slide $i to position $to."
            }
            else -> throw EditError("Unknown action '$action' for a PowerPoint file.")
        }
    }

    val ACTIONS = listOf(
        DocEdit.Action("outline", "Slides and their numbered text boxes.", emptyList()),
        DocEdit.Action("replace_text", "Replace text on all slides and notes.", listOf(DocEdit.Param("find"), DocEdit.Param("replace"))),
        DocEdit.Action("set_slide_text", "Replace the text of one text box.", listOf(DocEdit.Param("slide"), DocEdit.Param("target", "title, body, or a box number from outline", false), DocEdit.Param("text", "one line per paragraph"))),
        DocEdit.Action("add_slide", "Add a slide with a title and bullet text.", listOf(DocEdit.Param("title"), DocEdit.Param("body", "one bullet per line", false), DocEdit.Param("position", "start, end, after:N", false))),
        DocEdit.Action("duplicate_slide", "Copy a slide.", listOf(DocEdit.Param("slide"))),
        DocEdit.Action("delete_slide", "Delete a slide.", listOf(DocEdit.Param("slide"))),
        DocEdit.Action("move_slide", "Move a slide to a new position.", listOf(DocEdit.Param("slide"), DocEdit.Param("to")))
    )
}
