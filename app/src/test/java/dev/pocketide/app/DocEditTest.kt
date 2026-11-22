package dev.pocketide.app

import dev.pocketide.app.core.DocEdit
import dev.pocketide.app.core.DocPkg
import dev.pocketide.app.core.DocText
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DocEditTest {
    @Test fun all() { DocEditSelfTest.runAll(); check(DocEditSelfTest.failed == 0) { "${DocEditSelfTest.failed} document-edit checks failed" } }
}

object DocEditSelfTest {
    var failed = 0
    private var passed = 0
    private fun ok(c: Boolean, name: String) { if (c) passed++ else { failed++; println("FAIL  $name") } }
    private fun <T> eq(a: T, b: T, name: String) { ok(a == b, name); if (a != b) println("      got: $a\n      want: $b") }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, t) in files) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray(Charsets.UTF_8)); z.closeEntry() } }
        return out.toByteArray()
    }

    private val TAG = Regex("""<(/?)([A-Za-z_][\w:.\-]*)([^>]*?)(/?)>""")
    /** True when every opening tag is closed by the same name, in order. */
    private fun balanced(xml: String): Boolean {
        val st = ArrayList<String>()
        for (m in TAG.findAll(xml)) {
            if (m.groupValues[4] == "/") continue
            if (m.groupValues[1] == "/") { if (st.isEmpty() || st.removeAt(st.size - 1) != m.groupValues[2]) return false } else st.add(m.groupValues[2])
        }
        return st.isEmpty()
    }

    private fun parts(bytes: ByteArray): Map<String, String> = DocPkg.read(bytes).entries.mapValues { it.value.toString(Charsets.UTF_8) }
    private fun allBalanced(bytes: ByteArray): Boolean = parts(bytes).filterKeys { it.endsWith(".xml") || it.endsWith(".rels") || it.endsWith(".xhtml") }.all { balanced(it.value.removePrefix("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>").substringAfter("?>", it.value)) }

    private fun run(path: String, bytes: ByteArray, action: String, vararg args: Pair<String, String>) = DocEdit.apply(path, bytes, action, mapOf(*args))
    private fun outline(path: String, bytes: ByteArray) = DocEdit.apply(path, bytes, "outline", emptyMap()).message

    // ---------- fixtures ----------

    private const val W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""

    private fun docx(): ByteArray = zip(
        "[Content_Types].xml" to "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>",
        "word/document.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document $W><w:body>" +
            "<w:p><w:pPr><w:rPr><w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\"/><w:u w:val=\"single\"/></w:rPr></w:pPr><w:r><w:rPr><w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\"/><w:sz w:val=\"24\"/><w:u w:val=\"single\"/></w:rPr><w:t>Notes for GO</w:t></w:r></w:p>" +
            "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Hel</w:t></w:r><w:r><w:t xml:space=\"preserve\">lo world &amp; more</w:t></w:r></w:p>" +
            "<w:p/>" +
            "<w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr><w:r><w:t>Last line</w:t></w:r></w:p>" +
            "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr></w:body></w:document>",
        "word/styles.xml" to "<?xml version=\"1.0\"?><w:styles $W><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style></w:styles>",
        "word/header1.xml" to "<?xml version=\"1.0\"?><w:hdr $W><w:p><w:r><w:t>Hello header</w:t></w:r></w:p></w:hdr>"
    )

    private const val SS = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""

    private fun xlsx(): ByteArray = zip(
        "[Content_Types].xml" to "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"x\"/><Override PartName=\"/xl/calcChain.xml\" ContentType=\"c\"/></Types>",
        "xl/workbook.xml" to "<?xml version=\"1.0\"?><workbook $SS xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
        "xl/_rels/workbook.xml.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"t\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"t\" Target=\"calcChain.xml\"/></Relationships>",
        "xl/worksheets/sheet1.xml" to "<?xml version=\"1.0\"?><worksheet $SS><dimension ref=\"A1:B3\"/><sheetData>" +
            "<row r=\"1\" spans=\"1:2\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>" +
            "<row r=\"2\" spans=\"1:2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\" s=\"3\"><v>5</v></c></row>" +
            "<row r=\"3\"><c r=\"A3\" t=\"s\"><v>3</v></c><c r=\"B3\"><v>7</v></c></row></sheetData></worksheet>",
        "xl/sharedStrings.xml" to "<?xml version=\"1.0\"?><sst $SS><si><t>Item</t></si><si><t>Qty</t></si><si><t>Apples</t></si><si><r><t>Pe</t></r><r><t>ars</t></r></si></sst>",
        "xl/calcChain.xml" to "<calcChain $SS/>"
    )

    private const val P = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""

    private fun slide(title: String, body: String) = "<?xml version=\"1.0\"?><p:sld $P><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>" +
        "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title 1\"/><p:cNvSpPr/><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\"/><a:t>$title</a:t></a:r></a:p></p:txBody></p:sp>" +
        "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Content 2\"/><p:cNvSpPr/><p:nvPr><p:ph idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>$body</a:t></a:r></a:p></p:txBody></p:sp>" +
        "</p:spTree></p:cSld></p:sld>"

    private fun pptx(): ByteArray = zip(
        "[Content_Types].xml" to "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/ppt/slides/slide1.xml\" ContentType=\"s\"/><Override PartName=\"/ppt/slides/slide2.xml\" ContentType=\"s\"/></Types>",
        "ppt/presentation.xml" to "<?xml version=\"1.0\"?><p:presentation $P><p:sldMasterIdLst/><p:sldIdLst><p:sldId id=\"256\" r:id=\"rId2\"/><p:sldId id=\"257\" r:id=\"rId3\"/></p:sldIdLst><p:sldSz cx=\"9144000\" cy=\"6858000\"/></p:presentation>",
        "ppt/_rels/presentation.xml.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide1.xml\"/><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide2.xml\"/></Relationships>",
        "ppt/slides/slide1.xml" to slide("Welcome", "Hello team"),
        "ppt/slides/slide2.xml" to slide("Plan", "Ship it"),
        "ppt/slides/_rels/slide1.xml.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout2.xml\"/></Relationships>",
        "ppt/slides/_rels/slide2.xml.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout2.xml\"/></Relationships>",
        "ppt/slideLayouts/slideLayout2.xml" to "<?xml version=\"1.0\"?><p:sldLayout $P type=\"obj\"><p:cSld name=\"Title and Content\"><p:spTree><p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"t\"/><p:cNvSpPr/><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr></p:sp><p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"b\"/><p:cNvSpPr/><p:nvPr><p:ph idx=\"1\"/></p:nvPr></p:nvSpPr></p:sp></p:spTree></p:cSld></p:sldLayout>"
    )

    private const val O = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\""

    private fun odt(): ByteArray = zip(
        "mimetype" to "application/vnd.oasis.opendocument.text",
        "content.xml" to "<?xml version=\"1.0\"?><office:document-content $O><office:body><office:text><text:sequence-decls/><text:h text:outline-level=\"1\">Title</text:h><text:p>Hello <text:span>big</text:span> world</text:p><text:p>Second</text:p></office:text></office:body></office:document-content>"
    )

    private fun ods(): ByteArray = zip(
        "mimetype" to "application/vnd.oasis.opendocument.spreadsheet",
        "content.xml" to "<?xml version=\"1.0\"?><office:document-content $O><office:body><office:spreadsheet><table:table table:name=\"Sheet1\"><table:table-column table:number-columns-repeated=\"2\"/>" +
            "<table:table-row><table:table-cell office:value-type=\"string\"><text:p>Item</text:p></table:table-cell><table:table-cell office:value-type=\"string\"><text:p>Qty</text:p></table:table-cell></table:table-row>" +
            "<table:table-row><table:table-cell office:value-type=\"string\"><text:p>Apples</text:p></table:table-cell><table:table-cell office:value-type=\"float\" office:value=\"4\"><text:p>4</text:p></table:table-cell></table:table-row>" +
            "<table:table-row table:number-rows-repeated=\"1048000\"><table:table-cell table:number-columns-repeated=\"2\"/></table:table-row></table:table></office:spreadsheet></office:body></office:document-content>"
    )

    fun runAll() {
        failed = 0; passed = 0

        // ---- Word ----
        val d = docx()
        eq(DocEdit.typeOf("a/B.DOCX"), DocEdit.Type.DOCX, "docx type")
        ok(outline("x.docx", d).contains("1. Notes for GO") && outline("x.docx", d).contains("2. Hello world & more") && outline("x.docx", d).contains("3. (empty)"), "word outline numbers paragraphs and joins runs")

        val h = run("x.docx", d, "add_heading", "text" to "Test", "position" to "top")
        ok(h.ok, "add_heading ok")
        ok(outline("x.docx", h.bytes!!).startsWith("Word document with 5 blocks") && outline("x.docx", h.bytes!!).contains("1. [Heading 1] Test"), "heading goes to the top")
        ok(parts(h.bytes!!)["word/styles.xml"]!!.contains("w:styleId=\"Heading1\""), "missing Heading1 style is added")
        ok(allBalanced(h.bytes!!), "word xml stays well-formed after add_heading")
        ok(DocText.extract("x.docx", h.bytes!!)!!.startsWith("Test\nNotes for GO"), "DocText reads the edited document")

        val h2 = run("x.docx", d, "add_heading", "text" to "Sub", "level" to "2", "position" to "after:2")
        ok(outline("x.docx", h2.bytes!!).contains("3. [Heading 2] Sub"), "heading after block 2, level 2")
        val h3 = run("x.docx", d, "add_heading", "text" to "End")
        ok(outline("x.docx", h3.bytes!!).lines().last().contains("[Heading 1] End"), "heading at the bottom by default")
        ok(parts(h3.bytes!!)["word/document.xml"]!!.indexOf("End") < parts(h3.bytes!!)["word/document.xml"]!!.indexOf("<w:sectPr"), "new block stays before sectPr")

        val p = run("x.docx", d, "add_paragraph", "text" to "One\\nTwo & three", "position" to "top")
        ok(p.ok && outline("x.docx", p.bytes!!).contains("1. One") && outline("x.docx", p.bytes!!).contains("2. Two & three"), "add_paragraph makes one paragraph per line and escapes")
        ok(parts(p.bytes!!)["word/document.xml"]!!.contains("<w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\"/><w:sz w:val=\"24\"/>"), "new paragraph copies the nearby font and size")
        ok(allBalanced(p.bytes!!), "word xml well-formed after add_paragraph")

        val b = run("x.docx", d, "add_bullets", "text" to "a\nb|c")
        ok(b.ok && outline("x.docx", b.bytes!!).contains("•\ta") && parts(b.bytes!!)["word/document.xml"]!!.contains("w:hanging"), "add_bullets")

        val t = run("x.docx", d, "add_table", "rows" to "Name|Qty\nApples|3", "position" to "before:2")
        ok(t.ok && outline("x.docx", t.bytes!!).contains("2. [table, 2 rows x 2 columns] Name | Qty") && allBalanced(t.bytes!!), "add_table")
        ok(run("x.docx", d, "add_page_break", "position" to "top").ok, "add_page_break")

        val r = run("x.docx", d, "replace_text", "find" to "Hello world", "replace" to "Goodbye")
        ok(r.ok && r.message.startsWith("Replaced 1 occurrence"), "replace text across two runs: ${r.message}")
        ok(outline("x.docx", r.bytes!!).contains("2. Goodbye & more"), "replacement joined the runs")
        ok(parts(r.bytes!!)["word/document.xml"]!!.contains("<w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">Goodbye &amp; more</w:t>"), "first run keeps its formatting")
        val ri = run("x.docx", d, "replace_text", "find" to "HELLO WORLD", "replace" to "Hi")
        ok(ri.ok && ri.message.contains("ignoring upper/lower case"), "case-insensitive fallback")
        val rh = run("x.docx", d, "replace_text", "find" to "Hello header", "replace" to "Top")
        ok(rh.ok && parts(rh.bytes!!)["word/header1.xml"]!!.contains(">Top<"), "headers are searched too")
        ok(!run("x.docx", d, "replace_text", "find" to "nothing here", "replace" to "x").ok, "missing text is an error")
        ok(run("x.docx", d, "replace_text", "find" to "&", "replace" to "and").bytes?.let { parts(it)["word/document.xml"]!!.contains("and more") } == true, "text with & is found through the escape")

        val del = run("x.docx", d, "delete_paragraph", "index" to "2")
        ok(del.ok && !outline("x.docx", del.bytes!!).contains("Hello") && outline("x.docx", del.bytes!!).startsWith("Word document with 3 blocks"), "delete_paragraph")
        ok(!run("x.docx", d, "delete_paragraph", "index" to "99").ok, "bad index is an error")

        val sh = run("x.docx", d, "set_heading", "index" to "2", "level" to "3")
        ok(sh.ok && outline("x.docx", sh.bytes!!).contains("2. [Heading 3] Hello world") && allBalanced(sh.bytes!!), "set_heading on a paragraph with no pPr")
        val sh1 = run("x.docx", d, "set_heading", "index" to "4", "level" to "1")
        ok(sh1.ok && parts(sh1.bytes!!)["word/document.xml"]!!.contains("<w:pPr><w:pStyle w:val=\"Heading1\"/><w:spacing w:after=\"0\"/></w:pPr>"), "pStyle goes first in an existing pPr")
        val sh0 = run("x.docx", sh.bytes!!, "set_heading", "index" to "2", "level" to "0")
        ok(sh0.ok && !outline("x.docx", sh0.bytes!!).contains("[Heading"), "set_heading level 0 clears the style")
        val shEmpty = run("x.docx", d, "set_heading", "index" to "3", "level" to "2")
        ok(shEmpty.ok && allBalanced(shEmpty.bytes!!) && outline("x.docx", shEmpty.bytes!!).contains("3. [Heading 2] (empty)"), "set_heading on an empty paragraph")

        val f = run("x.docx", d, "format_paragraph", "index" to "2", "bold" to "true", "italic" to "true", "underline" to "true", "align" to "center")
        val fx = parts(f.bytes ?: ByteArray(0))["word/document.xml"].orEmpty()
        ok(f.ok && fx.contains("<w:jc w:val=\"center\"/>") && fx.contains("<w:i/>") && fx.contains("<w:u w:val=\"single\"/>") && allBalanced(f.bytes!!), "format_paragraph bold/italic/underline/center")
        ok(fx.contains("<w:rPr><w:b/><w:i/></w:rPr>") || fx.contains("<w:b/><w:i/>"), "bold then italic keep schema order")
        ok(!run("x.docx", d, "format_paragraph", "index" to "2").ok, "format_paragraph with nothing to do is an error")
        ok(!run("x.docx", d, "format_paragraph", "index" to "2", "align" to "diagonal").ok, "bad alignment is an error")
        ok(!run("x.docx", d, "frobnicate").ok && !run("x.docx", d, "add_heading").ok, "unknown action and missing text are errors")
        ok(!DocEdit.apply("x.docx", "not a zip".toByteArray(), "outline", emptyMap()).ok, "damaged file is an error, not a crash")
        ok(!DocEdit.apply("x.pdf", d, "outline", emptyMap()).ok && !DocEdit.canEdit("x.pdf"), "unsupported type")

        // ---- Excel ----
        val x = xlsx()
        ok(outline("a.xlsx", x).contains("A1=Item | B1=Qty") && outline("a.xlsx", x).contains("A2=Apples | B2=5") && outline("a.xlsx", x).contains("A3=Pears | B3=7"), "excel outline: shared and rich-text strings")
        val s1 = run("a.xlsx", x, "set_cell", "cell" to "b2", "value" to "12")
        val s1p = parts(s1.bytes ?: ByteArray(0))
        ok(s1.ok && s1p["xl/worksheets/sheet1.xml"]!!.contains("<c r=\"B2\" s=\"3\"><v>12</v></c>"), "set_cell keeps the cell style")
        ok(!s1p.containsKey("xl/calcChain.xml") && !s1p["xl/_rels/workbook.xml.rels"]!!.contains("calcChain") && !s1p["[Content_Types].xml"]!!.contains("calcChain"), "calcChain removed with its references")
        ok(s1p["xl/workbook.xml"]!!.contains("<calcPr fullCalcOnLoad=\"1\"/>"), "recalculate on open")
        ok(!s1p["xl/worksheets/sheet1.xml"]!!.contains("<dimension") && !s1p["xl/worksheets/sheet1.xml"]!!.contains("spans="), "stale dimension and spans dropped")
        ok(allBalanced(s1.bytes!!), "excel xml well-formed")
        val s2 = run("a.xlsx", x, "set_cell", "cell" to "D5", "value" to "=SUM(B2:B3)")
        ok(s2.ok && parts(s2.bytes!!)["xl/worksheets/sheet1.xml"]!!.contains("<row r=\"5\"><c r=\"D5\"><f>SUM(B2:B3)</f></c></row>"), "set_cell formula in a new row")
        val s3 = run("a.xlsx", x, "set_cell", "cell" to "A2", "value" to "Kiwi & Co")
        ok(s3.ok && parts(s3.bytes!!)["xl/worksheets/sheet1.xml"]!!.contains("t=\"inlineStr\"><is><t xml:space=\"preserve\">Kiwi &amp; Co</t></is>") && outline("a.xlsx", s3.bytes!!).contains("A2=Kiwi & Co"), "set_cell text uses an inline string")
        ok(run("a.xlsx", x, "set_cell", "cell" to "007", "value" to "1").ok.not(), "bad address is an error")
        val s4 = run("a.xlsx", x, "set_cell", "cell" to "C1", "value" to "007")
        ok(s4.ok && parts(s4.bytes!!)["xl/worksheets/sheet1.xml"]!!.contains("inlineStr\"><is><t xml:space=\"preserve\">007<"), "leading zeros stay text")
        val ar = run("a.xlsx", x, "append_row", "values" to "Plums|9|=B4*2")
        ok(ar.ok && outline("a.xlsx", ar.bytes!!).contains("A4=Plums | B4=9 | C4==B4*2"), "append_row after the last row")
        val sc = run("a.xlsx", x, "set_cells", "cells" to "A6=Name|B6=3.5")
        ok(sc.ok && outline("a.xlsx", sc.bytes!!).contains("A6=Name | B6=3.5"), "set_cells")
        val cc = run("a.xlsx", x, "clear_cell", "cell" to "B2")
        ok(cc.ok && parts(cc.bytes!!)["xl/worksheets/sheet1.xml"]!!.contains("<c r=\"B2\" s=\"3\"/>"), "clear_cell keeps the style")
        val dr = run("a.xlsx", x, "delete_row", "row" to "1")
        ok(dr.ok && outline("a.xlsx", dr.bytes!!).contains("A1=Apples") && outline("a.xlsx", dr.bytes!!).contains("A2=Pears"), "delete_row moves rows up")
        val as1 = run("a.xlsx", x, "add_sheet", "name" to "Totals")
        val asp = parts(as1.bytes ?: ByteArray(0))
        ok(as1.ok && asp["xl/workbook.xml"]!!.contains("<sheet name=\"Totals\" sheetId=\"2\" r:id=\"rId3\"/>") && asp["xl/_rels/workbook.xml.rels"]!!.contains("worksheets/sheet2.xml") && asp["[Content_Types].xml"]!!.contains("/xl/worksheets/sheet2.xml") && asp.containsKey("xl/worksheets/sheet2.xml"), "add_sheet registers the new part")
        val st = run("a.xlsx", as1.bytes!!, "set_cell", "sheet" to "Totals", "cell" to "A1", "value" to "Sum")
        ok(st.ok && outline("a.xlsx", st.bytes!!).contains("== Sheet 'Totals'") && outline("a.xlsx", st.bytes!!).contains("A1=Sum"), "edit the new sheet by name")
        ok(!run("a.xlsx", x, "add_sheet", "name" to "sheet1").ok && !run("a.xlsx", x, "add_sheet", "name" to "a/b").ok, "duplicate or bad sheet names are errors")
        val rs = run("a.xlsx", x, "rename_sheet", "name" to "Fruit")
        ok(rs.ok && outline("a.xlsx", rs.bytes!!).contains("'Fruit'"), "rename_sheet")
        val rt = run("a.xlsx", x, "replace_text", "find" to "Pears", "replace" to "Plums")
        ok(rt.ok && outline("a.xlsx", rt.bytes!!).contains("A3=Plums"), "replace text across rich-text runs in shared strings")
        ok(!run("a.xlsx", x, "set_cell", "sheet" to "Nope", "cell" to "A1", "value" to "x").ok, "unknown sheet is an error")

        // ---- PowerPoint ----
        val pp = pptx()
        ok(outline("a.pptx", pp).contains("Slide 1:") && outline("a.pptx", pp).contains("[1] title: Welcome") && outline("a.pptx", pp).contains("[2] body: Hello team"), "pptx outline")
        val pr = run("a.pptx", pp, "replace_text", "find" to "Hello team", "replace" to "Hi all")
        ok(pr.ok && outline("a.pptx", pr.bytes!!).contains("[2] body: Hi all"), "pptx replace_text")
        val ps = run("a.pptx", pp, "set_slide_text", "slide" to "2", "target" to "title", "text" to "Roadmap")
        ok(ps.ok && outline("a.pptx", ps.bytes!!).contains("[1] title: Roadmap") && parts(ps.bytes!!)["ppt/slides/slide2.xml"]!!.contains("<a:rPr lang=\"en-US\"/><a:t>Roadmap</a:t>") && allBalanced(ps.bytes!!), "set_slide_text title")
        val pb = run("a.pptx", pp, "set_slide_text", "slide" to "1", "target" to "body", "text" to "One\nTwo")
        ok(pb.ok && outline("a.pptx", pb.bytes!!).contains("[2] body: One / Two"), "set_slide_text body with two lines")
        val pn = run("a.pptx", pp, "set_slide_text", "slide" to "1", "target" to "2", "text" to "By number")
        ok(pn.ok && outline("a.pptx", pn.bytes!!).contains("[2] body: By number"), "set_slide_text by box number")
        val pa = run("a.pptx", pp, "add_slide", "title" to "New one", "body" to "- first\n- second")
        val pap = parts(pa.bytes ?: ByteArray(0))
        ok(pa.ok && outline("a.pptx", pa.bytes!!).contains("Slide 3:") && outline("a.pptx", pa.bytes!!).contains("[1] title: New one") && outline("a.pptx", pa.bytes!!).contains("[2] body: first / second"), "add_slide")
        ok(pap["ppt/presentation.xml"]!!.contains("<p:sldId id=\"258\" r:id=\"rId1\"/>") && pap["ppt/_rels/presentation.xml.rels"]!!.contains("slides/slide3.xml") && pap["[Content_Types].xml"]!!.contains("/ppt/slides/slide3.xml") && pap["ppt/slides/_rels/slide3.xml.rels"]!!.contains("../slideLayouts/slideLayout2.xml"), "add_slide registers id, relationship, content type and layout")
        ok(allBalanced(pa.bytes!!), "pptx well-formed after add_slide")
        val pas = run("a.pptx", pp, "add_slide", "title" to "First", "position" to "start")
        ok(pas.ok && outline("a.pptx", pas.bytes!!).substringAfter("Slide 1:").startsWith("\n  [1] title: First"), "add_slide at the start")
        val pd = run("a.pptx", pp, "duplicate_slide", "slide" to "1")
        ok(pd.ok && outline("a.pptx", pd.bytes!!).contains("Slide 3:") && outline("a.pptx", pd.bytes!!).substringAfter("Slide 2:").startsWith("\n  [1] title: Welcome"), "duplicate_slide puts the copy after the original")
        val pdel = run("a.pptx", pp, "delete_slide", "slide" to "1")
        val pdp = parts(pdel.bytes ?: ByteArray(0))
        ok(pdel.ok && !pdp.containsKey("ppt/slides/slide1.xml") && !pdp["ppt/presentation.xml"]!!.contains("rId2") && !pdp["[Content_Types].xml"]!!.contains("slide1.xml") && !pdp["ppt/_rels/presentation.xml.rels"]!!.contains("slide1.xml") && outline("a.pptx", pdel.bytes!!).contains("[1] title: Plan"), "delete_slide removes the part, relationship and content type")
        ok(!run("a.pptx", pdel.bytes!!, "delete_slide", "slide" to "1").ok, "the last slide cannot be deleted")
        val pm = run("a.pptx", pp, "move_slide", "slide" to "1", "to" to "2")
        ok(pm.ok && outline("a.pptx", pm.bytes!!).substringAfter("Slide 1:").startsWith("\n  [1] title: Plan"), "move_slide")
        ok(!run("a.pptx", pp, "set_slide_text", "slide" to "9", "text" to "x").ok, "bad slide number is an error")

        // ---- OpenDocument ----
        val t1 = odt()
        ok(outline("a.odt", t1).contains("1. [Heading 1] Title") && outline("a.odt", t1).contains("2. Hello big world"), "odt outline")
        val oh = run("a.odt", t1, "add_heading", "text" to "Test", "position" to "top")
        ok(oh.ok && outline("a.odt", oh.bytes!!).contains("1. [Heading 1] Test") && parts(oh.bytes!!)["content.xml"]!!.indexOf("<text:sequence-decls/>") < parts(oh.bytes!!)["content.xml"]!!.indexOf("Test"), "odt heading goes after the declarations")
        ok(allBalanced(oh.bytes!!), "odt well-formed")
        val first = ZipInputStream(ByteArrayInputStream(oh.bytes!!)).use { z -> val e = z.nextEntry; e.name to e.method }
        eq(first, "mimetype" to ZipEntry.STORED, "mimetype stays first and stored")
        val op = run("a.odt", t1, "add_paragraph", "text" to "New  line & more")
        ok(op.ok && outline("a.odt", op.bytes!!).contains("4. New  line & more") && parts(op.bytes!!)["content.xml"]!!.contains("New <text:s text:c=\"1\"/>line &amp; more"), "odt paragraph keeps double spaces")
        ok(run("a.odt", t1, "add_bullets", "text" to "a\nb").ok && run("a.odt", t1, "delete_paragraph", "index" to "3").ok, "odt bullets and delete")
        val orp = run("a.odt", t1, "replace_text", "find" to "Second", "replace" to "2nd")
        ok(orp.ok && outline("a.odt", orp.bytes!!).contains("3. 2nd"), "odt replace_text")
        ok(!run("a.odt", t1, "replace_text", "find" to "Hello big", "replace" to "x").ok, "odt replace across formatting is reported, not faked")
        val s = ods()
        ok(outline("a.ods", s).contains("A2=Apples | B2=4"), "ods outline")
        val oa = run("a.ods", s, "append_row", "values" to "Pears|9")
        ok(oa.ok && outline("a.ods", oa.bytes!!).contains("A3=Pears | B3=9") && allBalanced(oa.bytes!!), "ods append_row goes before the empty tail")
        ok(run("a.ods", s, "replace_text", "find" to "Apples", "replace" to "Kiwis").ok, "ods replace_text")

        // ---- EPUB, zip ----
        val ep = zip("mimetype" to "application/epub+zip", "OEBPS/ch1.xhtml" to "<html><body><h1>One</h1><p>Hello <b>big</b> world, hello.</p><script>var hello=1</script></body></html>")
        ok(outline("b.epub", ep).contains("OEBPS/ch1.xhtml: One"), "epub outline")
        val er = run("b.epub", ep, "replace_text", "find" to "hello", "replace" to "Hi")
        ok(er.ok && parts(er.bytes!!)["OEBPS/ch1.xhtml"]!!.contains("<p>Hello <b>big</b> world, Hi.</p>") && parts(er.bytes!!)["OEBPS/ch1.xhtml"]!!.contains("var hello=1"), "epub replace skips scripts and tags")
        val zp = zip("a.txt" to "A", "dir/b.txt" to "B", "dir/c.txt" to "C")
        val za = run("z.zip", zp, "add_text", "entry" to "new/readme.txt", "text" to "hi")
        ok(za.ok && parts(za.bytes!!).containsKey("new/readme.txt"), "zip add_text")
        ok(!run("z.zip", zp, "add_text", "entry" to "../evil.txt", "text" to "x").ok, "zip path traversal is refused")
        ok(run("z.zip", zp, "remove_entry", "entry" to "dir").let { it.ok && parts(it.bytes!!).keys == setOf("a.txt") }, "zip remove folder")
        val zr = run("z.zip", zp, "rename_entry", "entry" to "a.txt", "to" to "z.txt")
        ok(zr.ok && parts(zr.bytes!!).keys.toList() == listOf("z.txt", "dir/b.txt", "dir/c.txt"), "zip rename keeps order")

        // ---- CSV ----
        val csv = "name,qty,note\r\nApples,3,\"red, sweet\"\r\nPears,7,\"say \"\"hi\"\"\"\r\n".toByteArray()
        ok(outline("d.csv", csv).contains("1: name | qty | note") && outline("d.csv", csv).contains("2: Apples | 3 | red, sweet"), "csv outline handles quotes")
        val c1 = run("d.csv", csv, "set_cell", "row" to "2", "column" to "note", "value" to "green, tart")
        eq(c1.bytes?.toString(Charsets.UTF_8), "name,qty,note\r\nApples,3,\"green, tart\"\r\nPears,7,\"say \"\"hi\"\"\"\r\n", "csv set_cell by header name keeps quoting and line endings")
        val c2 = run("d.csv", csv, "append_row", "values" to "Plums|9|ok")
        ok(c2.bytes?.toString(Charsets.UTF_8)?.endsWith("Plums,9,ok\r\n") == true, "csv append_row")
        val c3 = run("d.csv", csv, "sort", "column" to "qty", "order" to "desc")
        ok(c3.bytes?.toString(Charsets.UTF_8)?.startsWith("name,qty,note\r\nPears,7") == true, "csv numeric sort descending")
        val c4 = run("d.csv", csv, "add_column", "name" to "price", "value" to "0")
        ok(c4.bytes?.toString(Charsets.UTF_8)?.startsWith("name,qty,note,price\r\nApples,3,\"red, sweet\",0") == true, "csv add_column")
        val c5 = run("d.csv", csv, "delete_column", "column" to "qty")
        ok(c5.bytes?.toString(Charsets.UTF_8)?.startsWith("name,note\r\n") == true, "csv delete_column")
        val c6 = run("d.csv", csv, "delete_row", "row" to "2")
        ok(c6.bytes?.toString(Charsets.UTF_8)?.startsWith("name,qty,note\r\nPears") == true, "csv delete_row")
        ok(run("d.csv", csv, "replace_text", "find" to "pears", "replace" to "Figs").bytes?.toString(Charsets.UTF_8)?.contains("Figs,7") == true, "csv replace_text")
        ok(run("d.tsv", "a\tb\n1\t2\n".toByteArray(), "append_row", "values" to "3|4").bytes?.toString(Charsets.UTF_8) == "a\tb\n1\t2\n3\t4\n", "tsv uses tabs")
        ok(run("d.csv", "a;b\n1;2\n".toByteArray(), "append_row", "values" to "3|4").bytes?.toString(Charsets.UTF_8) == "a;b\n1;2\n3;4\n", "semicolon delimiter is detected")
        ok(!run("d.csv", csv, "set_cell", "row" to "9", "column" to "1", "value" to "x").ok && !run("d.csv", csv, "sort", "column" to "nope").ok, "csv errors")

        // ---- action lines from small models ----
        val pl = DocEdit.parseLine("report.docx add_heading text=\"Test one\" position=top level=2")
        eq(pl, Triple("report.docx", "add_heading", mapOf("text" to "Test one", "position" to "top", "level" to "2")), "parse an ACTION line with quoted values")
        eq(DocEdit.parseLine("a.csv outline"), Triple("a.csv", "outline", emptyMap()), "parse a line with no values")
        eq(DocEdit.parseLine("a.csv"), null, "a line without an action is rejected")
        eq(DocEdit.parseLine("sheet.xlsx set_cell cell=B2 value=\"=SUM(A1:A3)\"")?.third?.get("value"), "=SUM(A1:A3)", "formula values survive")
        ok(DocEdit.actions("x.docx").any { it.name == "add_heading" } && DocEdit.actions("x.xlsx").any { it.name == "set_cell" } && DocEdit.actions("x.pptx").any { it.name == "add_slide" } && DocEdit.actions("x.txt").isEmpty(), "action catalogue per type")

        println("\n$passed passed, $failed failed")
    }
}
