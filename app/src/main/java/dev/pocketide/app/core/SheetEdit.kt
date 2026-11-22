package dev.pocketide.app.core

/** Edits to Excel (.xlsx) files: cells, rows, sheets and text, keeping styles, charts and everything else in the file. */
internal object SheetEdit {
    private const val WB = "xl/workbook.xml"
    private const val WB_RELS = "xl/_rels/workbook.xml.rels"
    private const val CT = "[Content_Types].xml"

    private class Sheet(val name: String, val path: String)

    private fun sheets(pkg: DocPkg): List<Sheet> {
        val wb = pkg.text(WB) ?: throw EditError("This is not an Excel workbook (no xl/workbook.xml).")
        val rels = pkg.text(WB_RELS) ?: ""
        val target = HashMap<String, String>()
        for (m in Regex("""<Relationship\b[^>]*/>""").findAll(rels)) {
            val id = X.attr(m.value, "Id") ?: continue
            val t = X.attr(m.value, "Target") ?: continue
            target[id] = if (t.startsWith("/")) t.removePrefix("/") else normalize("xl/$t")
        }
        val out = ArrayList<Sheet>()
        for (m in Regex("""<sheet\b[^>]*/>""").findAll(wb)) {
            val name = X.attr(m.value, "name") ?: continue
            val rid = Regex("""\s\w+:id="([^"]*)"""").find(m.value)?.groupValues?.get(1) ?: continue
            val path = target[rid] ?: continue
            if (pkg.has(path)) out.add(Sheet(name, path))
        }
        if (out.isEmpty()) throw EditError("The workbook has no sheets.")
        return out
    }

    private fun normalize(p: String): String {
        val parts = ArrayList<String>()
        for (x in p.split("/")) { if (x == "..") { if (parts.isNotEmpty()) parts.removeAt(parts.size - 1) } else if (x != "." && x.isNotEmpty()) parts.add(x) }
        return parts.joinToString("/")
    }

    private fun pick(pkg: DocPkg, spec: String): Sheet {
        val all = sheets(pkg)
        val s = spec.trim()
        if (s.isEmpty()) return all[0]
        all.firstOrNull { it.name == s }?.let { return it }
        all.firstOrNull { it.name.equals(s, true) }?.let { return it }
        s.toIntOrNull()?.let { n -> if (n in 1..all.size) return all[n - 1] }
        throw EditError("No sheet named '$s'. Sheets: ${all.joinToString(", ") { "'" + it.name + "'" }}.")
    }

    // ---- cells and grid ----

    private class Cell(val col: Int, var xml: String)
    private class Row(var r: Int, var attrs: String, val cells: MutableList<Cell>)
    private class Grid(val prefix: String, val suffix: String, val rows: MutableList<Row>)

    fun colIndex(letters: String): Int { var n = 0; for (c in letters.uppercase()) n = n * 26 + (c - 'A' + 1); return n }
    fun colName(index: Int): String { var n = index; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }; return sb.toString() }

    private fun parseRef(ref: String): Pair<Int, Int> {
        val m = Regex("""^\s*\$?([A-Za-z]{1,3})\$?(\d{1,7})\s*$""").find(ref) ?: throw EditError("'$ref' is not a cell address like B7.")
        val c = colIndex(m.groupValues[1]); val r = m.groupValues[2].toInt()
        if (c < 1 || c > 16384 || r < 1 || r > 1048576) throw EditError("'$ref' is outside the sheet.")
        return c to r
    }

    private val ROW = Regex("""<row\b([^>]*?)/>|<row\b([^>]*)>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("""<c\b([^>]*?)/>|<c\b([^>]*)>(.*?)</c>""", RegexOption.DOT_MATCHES_ALL)

    private fun parseGrid(xml: String): Grid {
        val empty = Regex("""<sheetData\s*/>""").find(xml)
        val full = Regex("""<sheetData(?:\s[^>]*)?>(.*?)</sheetData>""", RegexOption.DOT_MATCHES_ALL).find(xml)
        val rows = ArrayList<Row>()
        if (full == null && empty == null) throw EditError("The sheet has no data section.")
        if (full != null) {
            for (m in ROW.findAll(full.groupValues[1])) {
                val attrs = if (m.value.endsWith("/>")) m.groupValues[1] else m.groupValues[2]
                val inner = m.groupValues[3]
                val r = Regex("""\sr="(\d+)"""").find(attrs)?.groupValues?.get(1)?.toInt() ?: (rows.lastOrNull()?.r ?: 0) + 1
                val cells = ArrayList<Cell>()
                var nextCol = 1
                for (c in CELL.findAll(inner)) {
                    val cattrs = if (c.value.endsWith("/>")) c.groupValues[1] else c.groupValues[2]
                    val ref = Regex("""\sr="([A-Z]+)\d+"""").find(cattrs)?.groupValues?.get(1)
                    val col = if (ref != null) colIndex(ref) else nextCol
                    cells.add(Cell(col, c.value)); nextCol = col + 1
                }
                rows.add(Row(r, attrs.replace(Regex("""\s(r|spans)="[^"]*""""), ""), cells))
            }
        }
        val range = (full ?: empty)!!.range
        return Grid(xml.substring(0, range.first), xml.substring(range.last + 1), rows)
    }

    private fun serialize(g: Grid): String {
        val sb = StringBuilder(g.prefix.replace(Regex("""<dimension\b[^>]*/>"""), ""))
        sb.append("<sheetData>")
        for (r in g.rows.sortedBy { it.r }) {
            sb.append("<row r=\"").append(r.r).append('"').append(r.attrs)
            if (r.cells.isEmpty()) sb.append("/>") else { sb.append('>'); for (c in r.cells.sortedBy { it.col }) sb.append(c.xml); sb.append("</row>") }
        }
        sb.append("</sheetData>").append(g.suffix.replace(Regex("""<dimension\b[^>]*/>"""), ""))
        return sb.toString()
    }

    private val NUMBER = Regex("""^-?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$""")

    private fun cellXml(ref: String, value: String, oldXml: String?): String {
        val style = oldXml?.let { Regex("""\ss="(\d+)"""").find(it.substringBefore('>'))?.groupValues?.get(1) }
        val s = if (style != null) " s=\"$style\"" else ""
        val v = value
        return when {
            v.isEmpty() -> if (style != null) "<c r=\"$ref\"$s/>" else ""
            v.startsWith("=") && v.length > 1 -> "<c r=\"$ref\"$s><f>${X.esc(v.substring(1))}</f></c>"
            NUMBER.matches(v.trim()) && !(v.trim().length > 1 && v.trim().startsWith("0") && !v.trim().startsWith("0.")) && v.trim().length < 16 -> "<c r=\"$ref\"$s><v>${v.trim()}</v></c>"
            else -> "<c r=\"$ref\"$s t=\"inlineStr\"><is><t xml:space=\"preserve\">${X.esc(v)}</t></is></c>"
        }
    }

    private fun setCell(g: Grid, ref: String, value: String) {
        val (c, r) = parseRef(ref)
        val canon = colName(c) + r
        var row = g.rows.firstOrNull { it.r == r }
        if (row == null) { row = Row(r, "", ArrayList()); g.rows.add(row) }
        val old = row.cells.firstOrNull { it.col == c }
        val xml = cellXml(canon, value, old?.xml)
        if (old != null) { if (xml.isEmpty()) row.cells.remove(old) else old.xml = xml }
        else if (xml.isNotEmpty()) row.cells.add(Cell(c, xml))
    }

    // ---- reading (for the outline) ----

    private fun sharedStrings(pkg: DocPkg): List<String> {
        val x = pkg.text("xl/sharedStrings.xml") ?: return emptyList()
        return Regex("""<si\b[^>]*>(.*?)</si>|<si\s*/>""", RegexOption.DOT_MATCHES_ALL).findAll(x).map { m ->
            Regex("""<t(?:\s[^>]*)?>([^<]*)</t>""").findAll(m.groupValues[1]).joinToString("") { X.unesc(it.groupValues[1]) }
        }.toList()
    }

    private fun cellText(xml: String, shared: List<String>): String {
        val head = xml.substringBefore('>')
        val type = Regex("""\st="(\w+)"""").find(head)?.groupValues?.get(1).orEmpty()
        val f = Regex("""<f\b[^>]*>([^<]*)</f>""").find(xml)?.groupValues?.get(1)?.let { X.unesc(it) }
        val v = Regex("""<v>([^<]*)</v>""").find(xml)?.groupValues?.get(1)?.let { X.unesc(it) }.orEmpty()
        val value = when (type) {
            "s" -> v.toIntOrNull()?.let { shared.getOrNull(it) } ?: v
            "inlineStr" -> Regex("""<t(?:\s[^>]*)?>([^<]*)</t>""").findAll(xml).joinToString("") { X.unesc(it.groupValues[1]) }
            "b" -> if (v == "1") "TRUE" else "FALSE"
            else -> v
        }
        return if (f != null) "=$f" + if (value.isNotEmpty()) " ($value)" else "" else value
    }

    fun outline(pkg: DocPkg): String {
        val shared = sharedStrings(pkg)
        val sb = StringBuilder()
        val all = sheets(pkg)
        sb.append("Excel workbook with ${all.size} sheet${if (all.size == 1) "" else "s"}: ${all.joinToString(", ") { "'" + it.name + "'" }}. Cell values (formulas shown as =formula (value)):\n")
        for (s in all.take(6)) {
            val g = try { parseGrid(pkg.text(s.path) ?: "") } catch (e: EditError) { continue }
            val rows = g.rows.filter { it.cells.isNotEmpty() }.sortedBy { it.r }
            sb.append("\n== Sheet '${s.name}': ${rows.size} rows with data ==\n")
            for (r in rows.take(40)) {
                val parts = r.cells.sortedBy { it.col }.take(14).mapNotNull { c -> cellText(c.xml, shared).let { t -> if (t.isEmpty()) null else colName(c.col) + r.r + "=" + t.take(60) } }
                if (parts.isNotEmpty()) sb.append(parts.joinToString(" | ")).append('\n')
            }
            if (rows.size > 40) sb.append("… ${rows.size - 40} more rows\n")
        }
        return sb.toString().trimEnd()
    }

    // ---- bookkeeping after a change ----

    private fun settle(pkg: DocPkg) {
        // a stale calculation chain makes Excel offer to "repair" the file; formulas are recalculated on open instead
        if (pkg.has("xl/calcChain.xml")) {
            pkg.remove("xl/calcChain.xml")
            pkg.text(CT)?.let { pkg.put(CT, it.replace(Regex("""<Override\b[^>]*calcChain[^>]*/>"""), "")) }
            pkg.text(WB_RELS)?.let { pkg.put(WB_RELS, it.replace(Regex("""<Relationship\b[^>]*calcChain[^>]*/>"""), "")) }
        }
        val wb = pkg.text(WB) ?: return
        val calc = Regex("""<calcPr\b([^>]*?)(/?)>""").find(wb)
        val nw = if (calc != null) {
            if (calc.value.contains("fullCalcOnLoad")) wb else wb.replaceRange(calc.range, "<calcPr${calc.groupValues[1]} fullCalcOnLoad=\"1\"${calc.groupValues[2]}>")
        } else {
            val spots = listOf("<oleSize", "<customWorkbookViews", "<pivotCaches", "<smartTagPr", "<smartTagTypes", "<webPublishing", "<fileRecoveryPr", "<webPublishObjects", "<extLst", "</workbook>").mapNotNull { wb.indexOf(it).takeIf { i -> i >= 0 } }
            val at = spots.minOrNull() ?: return
            wb.substring(0, at) + "<calcPr fullCalcOnLoad=\"1\"/>" + wb.substring(at)
        }
        pkg.put(WB, nw)
    }

    private val BAD_NAME = Regex("""[\[\]:*?/\\]""")

    fun apply(pkg: DocPkg, action: String, a: Args): String {
        when (action) {
            "outline" -> return outline(pkg)
            "set_cell" -> {
                val sh = pick(pkg, a.s("sheet"))
                val g = parseGrid(pkg.text(sh.path)!!)
                val ref = a.req("cell")
                setCell(g, ref, a.text("value"))
                pkg.put(sh.path, serialize(g)); settle(pkg)
                return "Set ${ref.uppercase()} on '${sh.name}' to '${a.text("value").take(40)}'."
            }
            "set_cells" -> {
                val sh = pick(pkg, a.s("sheet"))
                val g = parseGrid(pkg.text(sh.path)!!)
                val pairs = a.text("cells").split("|", "\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (pairs.isEmpty()) throw EditError("Missing 'cells': for example A1=Name|B1=Qty|A2=Apples|B2=12")
                for (p in pairs) {
                    val i = p.indexOf('=')
                    if (i <= 0) throw EditError("'$p' is not CELL=value (for example B2=12).")
                    setCell(g, p.substring(0, i), p.substring(i + 1).trim())
                }
                pkg.put(sh.path, serialize(g)); settle(pkg)
                return "Set ${pairs.size} cell${if (pairs.size == 1) "" else "s"} on '${sh.name}'."
            }
            "clear_cell" -> {
                val sh = pick(pkg, a.s("sheet"))
                val g = parseGrid(pkg.text(sh.path)!!)
                setCell(g, a.req("cell"), "")
                pkg.put(sh.path, serialize(g)); settle(pkg)
                return "Cleared ${a.req("cell").uppercase()} on '${sh.name}'."
            }
            "append_row" -> {
                val sh = pick(pkg, a.s("sheet"))
                val g = parseGrid(pkg.text(sh.path)!!)
                val vals = a.text("values").split("|").map { it.trim() }
                if (vals.all { it.isEmpty() }) throw EditError("Missing 'values': cells separated by |, for example Apples|12|=B2*2")
                val next = (g.rows.filter { it.cells.isNotEmpty() }.maxOfOrNull { it.r } ?: 0) + 1
                for ((i, v) in vals.withIndex()) if (v.isNotEmpty()) setCell(g, colName(i + 1) + next, v)
                pkg.put(sh.path, serialize(g)); settle(pkg)
                return "Added a row at row $next of '${sh.name}'."
            }
            "delete_row" -> {
                val sh = pick(pkg, a.s("sheet"))
                val g = parseGrid(pkg.text(sh.path)!!)
                val n = a.reqInt("row")
                if (g.rows.none { it.r == n }) throw EditError("Row $n has no data.")
                g.rows.removeAll { it.r == n }
                for (r in g.rows.filter { it.r > n }) {
                    r.r -= 1
                    for (c in r.cells) { val m = Regex("""(\sr="[A-Z]+)(\d+)(")""").find(c.xml); if (m != null) c.xml = c.xml.replaceRange(m.range, m.groupValues[1] + r.r + m.groupValues[3]) }
                }
                pkg.put(sh.path, serialize(g)); settle(pkg)
                return "Deleted row $n of '${sh.name}'; the rows below moved up. Formulas that point at moved cells are not adjusted."
            }
            "add_sheet" -> {
                val name = a.req("name").trim()
                if (name.length > 31 || BAD_NAME.containsMatchIn(name)) throw EditError("A sheet name is at most 31 characters and cannot contain [ ] : * ? / \\")
                if (sheets(pkg).any { it.name.equals(name, true) }) throw EditError("There is already a sheet named '$name'.")
                var n = 1
                while (pkg.has("xl/worksheets/sheet$n.xml")) n++
                val path = "xl/worksheets/sheet$n.xml"
                pkg.put(path, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData/></worksheet>")
                var rels = pkg.text(WB_RELS) ?: throw EditError("The workbook has no relationships file.")
                var k = 1
                while (rels.contains("Id=\"rId$k\"")) k++
                rels = rels.replace("</Relationships>", "<Relationship Id=\"rId$k\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$n.xml\"/></Relationships>")
                pkg.put(WB_RELS, rels)
                var wb = pkg.text(WB)!!
                val rp = Regex("""xmlns:(\w+)="http://schemas.openxmlformats.org/officeDocument/2006/relationships"""").find(wb)?.groupValues?.get(1) ?: "r"
                if (!wb.contains("xmlns:$rp=")) wb = wb.replaceFirst("<workbook ", "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" ")
                val maxId = Regex("""sheetId="(\d+)"""").findAll(wb).maxOfOrNull { it.groupValues[1].toInt() } ?: 0
                wb = wb.replace("</sheets>", "<sheet name=\"${X.esc(name)}\" sheetId=\"${maxId + 1}\" $rp:id=\"rId$k\"/></sheets>")
                pkg.put(WB, wb)
                pkg.text(CT)?.let { pkg.put(CT, it.replace("</Types>", "<Override PartName=\"/$path\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>")) }
                settle(pkg)
                return "Added an empty sheet '$name'."
            }
            "rename_sheet" -> {
                val sh = pick(pkg, a.s("sheet"))
                val name = a.req("name").trim()
                if (name.length > 31 || BAD_NAME.containsMatchIn(name)) throw EditError("A sheet name is at most 31 characters and cannot contain [ ] : * ? / \\")
                var wb = pkg.text(WB)!!
                val tag = Regex("""<sheet\b[^>]*\sname="${Regex.escape(X.esc(sh.name))}"[^>]*/>""").find(wb) ?: throw EditError("Could not find the sheet entry.")
                wb = wb.replaceRange(tag.range, tag.value.replace("name=\"${X.esc(sh.name)}\"", "name=\"${X.esc(name)}\""))
                pkg.put(WB, wb); settle(pkg)
                return "Renamed '${sh.name}' to '$name'. Formulas that mention the old name are not changed."
            }
            "replace_text" -> {
                val find = a.req("find"); val repl = a.text("replace")
                for (ic in listOf(false, true)) {
                    var total = 0
                    val changes = LinkedHashMap<String, String>()
                    val parts = listOf("xl/sharedStrings.xml") + sheets(pkg).map { it.path }
                    for (name in parts) {
                        val xml = pkg.text(name) ?: continue
                        var n = 0
                        val out = Regex("""<si\b[^>]*>.*?</si>|<is\b[^>]*>.*?</is>""", RegexOption.DOT_MATCHES_ALL).replace(xml) { m ->
                            val (r, c) = X.replaceInRuns(m.value, "t", find, repl, ic); n += c; r
                        }
                        if (n > 0) { changes[name] = out; total += n }
                    }
                    if (total > 0) {
                        for ((k, v) in changes) pkg.put(k, v)
                        settle(pkg)
                        return "Replaced $total occurrence${if (total == 1) "" else "s"} of '${find.take(40)}' in cell text" + if (ic) " (ignoring upper/lower case)." else "."
                    }
                }
                throw EditError("The text '${find.take(60)}' was not found in any cell. Formulas are not searched; use outline to see the values.")
            }
            else -> throw EditError("Unknown action '$action' for an Excel file.")
        }
    }

    val ACTIONS = listOf(
        DocEdit.Action("outline", "Sheets and their cell values.", emptyList()),
        DocEdit.Action("set_cell", "Set one cell (text, number, or a formula starting with =).", listOf(DocEdit.Param("cell", "address such as B7"), DocEdit.Param("value"), DocEdit.Param("sheet", "name or number, default first", false))),
        DocEdit.Action("set_cells", "Set several cells.", listOf(DocEdit.Param("cells", "A1=Name|B1=Qty|A2=Apples|B2=12"), DocEdit.Param("sheet", "", false))),
        DocEdit.Action("append_row", "Add a row after the last row with data.", listOf(DocEdit.Param("values", "cells separated by |"), DocEdit.Param("sheet", "", false))),
        DocEdit.Action("clear_cell", "Empty a cell.", listOf(DocEdit.Param("cell"), DocEdit.Param("sheet", "", false))),
        DocEdit.Action("delete_row", "Delete a row; rows below move up.", listOf(DocEdit.Param("row", "row number"), DocEdit.Param("sheet", "", false))),
        DocEdit.Action("add_sheet", "Add an empty sheet.", listOf(DocEdit.Param("name"))),
        DocEdit.Action("rename_sheet", "Rename a sheet.", listOf(DocEdit.Param("name"), DocEdit.Param("sheet", "", false))),
        DocEdit.Action("replace_text", "Replace text in cells.", listOf(DocEdit.Param("find"), DocEdit.Param("replace")))
    )
}
