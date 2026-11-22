package dev.pocketide.app

import dev.pocketide.app.core.DocText
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocTextTest {
    @Test fun all() { DocTextSelfTest.runAll(); check(DocTextSelfTest.failed == 0) { "${DocTextSelfTest.failed} document-reader checks failed" } }
}

object DocTextSelfTest {
    var failed = 0
    private var passed = 0
    private fun ok(c: Boolean, name: String) { if (c) passed++ else { failed++; println("FAIL  $name") } }
    private fun <T> eq(a: T, b: T, name: String) { ok(a == b, name); if (a != b) println("      got: $a\n      want: $b") }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, t) in files) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray(Charsets.UTF_8)); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun deflate(s: String): ByteArray {
        val d = Deflater()
        d.setInput(s.toByteArray(Charsets.ISO_8859_1)); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end()
        return out.toByteArray()
    }

    private fun pdfRaw(content: String): ByteArray =
        ("%PDF-1.4\n1 0 obj\n<< /Type /Page >>\nendobj\n4 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\ntrailer\n%%EOF").toByteArray(Charsets.ISO_8859_1)

    private fun pdfFlate(content: String): ByteArray {
        val data = deflate(content)
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n1 0 obj\n<< /Type /Page >>\nendobj\n2 0 obj\n<< /Type /Page >>\nendobj\n4 0 obj\n<< /Filter /FlateDecode /Length ${data.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.write("\nendstream\nendobj\n%%EOF".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    fun runAll() {
        failed = 0; passed = 0

        // kinds
        eq(DocText.kind("a/Report.DOCX"), DocText.Kind.DOCX, "docx kind, any case and folder")
        eq(DocText.kind("x.xlsm"), DocText.Kind.XLSX, "xlsm is excel")
        eq(DocText.kind("deck.pptx"), DocText.Kind.PPTX, "pptx kind")
        eq(DocText.kind("n.odt"), DocText.Kind.ODF, "odt kind")
        eq(DocText.kind("app.apk"), DocText.Kind.ZIP, "apk is a zip")
        eq(DocText.kind("a.png"), null, "png is not a document")
        ok(DocText.isOffice("a.docx") && DocText.isOffice("a.ods") && !DocText.isOffice("a.pdf") && !DocText.isOffice("a.zip"), "isOffice covers word, excel, powerpoint, odf only")

        // Word
        val body = "<?xml version=\"1.0\"?><w:document><w:body>" +
            "<w:p><w:pPr><w:tabs><w:tab w:val=\"left\" w:pos=\"720\"/></w:tabs></w:pPr><w:r><w:t>Hello</w:t></w:r><w:r><w:tab/><w:t xml:space=\"preserve\"> world &amp; co</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>Line one</w:t><w:br/><w:t>Line two</w:t></w:r></w:p>" +
            "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B1</w:t></w:r></w:p></w:tc></w:tr></w:tbl>" +
            "<w:p><w:r><w:delText>gone</w:delText></w:r><w:r><w:t>&#65;&#x42;C</w:t></w:r></w:p>" +
            "</w:body></w:document>"
        val docx = zip("[Content_Types].xml" to "<Types/>", "word/document.xml" to body, "word/header1.xml" to "<w:hdr><w:p><w:r><w:t>Company header</w:t></w:r></w:p></w:hdr>")
        val dt = DocText.extract("memo.docx", docx) ?: ""
        ok(dt.startsWith("Hello\t world & co\nLine one\nLine two"), "docx paragraphs, tab, line break, entities")
        ok(!dt.contains("gone"), "deleted text is not shown")
        ok(dt.contains("A1\tB1"), "docx table cells are tab separated")
        ok(dt.contains("ABC"), "numeric character references")
        ok(!dt.startsWith("\t") && dt.count { it == '\t' } == 2, "tab stops in paragraph properties are not tabs")
        ok(dt.contains("[Header]\nCompany header"), "docx header is included and labelled")
        eq(DocText.extract("bad.docx", "not a zip".toByteArray()), null, "damaged docx gives null")
        eq(DocText.extract("empty.docx", zip("word/document.xml" to "<w:document><w:body/></w:document>")), "(This document has no text.)", "empty docx says so")

        // Excel
        val wb = "<workbook><sheets><sheet name=\"Sales &amp; Costs\" sheetId=\"1\" r:id=\"rId2\"/><sheet name=\"Empty\" sheetId=\"2\" r:id=\"rId3\"/></sheets></workbook>"
        val rels = "<Relationships><Relationship Id=\"rId2\" Type=\"x\" Target=\"worksheets/sheet1.xml\"/><Relationship Target=\"worksheets/sheet2.xml\" Id=\"rId3\" Type=\"x\"/></Relationships>"
        val shared = "<sst><si><t>Name</t></si><si><r><t>Tot</t></r><r><t>al</t></r></si><si><t>x</t><rPh><t>PHONETIC</t></rPh></si></sst>"
        val sheet1 = "<worksheet><dimension ref=\"A1:C3\"/><sheetData>" +
            "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>" +
            "<row r=\"2\"><c r=\"A2\"><v>10</v></c><c r=\"B2\"><v>20</v></c><c r=\"C2\"><f>A2+B2</f><v>30</v></c></row>" +
            "<row r=\"3\"><c r=\"A3\" t=\"b\"><v>1</v></c><c r=\"B3\" t=\"inlineStr\"><is><t>inline</t></is></c><c r=\"C3\"/><c r=\"D3\" t=\"s\"><v>2</v></c></row>" +
            "</sheetData></worksheet>"
        val xlsx = zip("xl/workbook.xml" to wb, "xl/_rels/workbook.xml.rels" to rels, "xl/sharedStrings.xml" to shared,
            "xl/worksheets/sheet1.xml" to sheet1, "xl/worksheets/sheet2.xml" to "<worksheet><sheetData/></worksheet>")
        val xt = DocText.extract("book.xlsx", xlsx) ?: ""
        ok(xt.contains("## Sheet: Sales & Costs"), "sheet name decoded")
        ok(xt.contains("(range A1:C3)"), "sheet range shown")
        ok(xt.contains("A1: Name | B1: Total"), "shared strings, runs joined")
        ok(xt.contains("C2: 30  (=A2+B2)"), "formula and value")
        ok(xt.contains("A3: TRUE | B3: inline | D3: x"), "bool, inline string, phonetic text dropped, empty cell skipped")
        ok(xt.contains("## Sheet: Empty\n(empty)"), "empty sheet")

        // PowerPoint, slides named out of order
        fun slide(t: String) = "<p:sld><a:p><a:r><a:t>$t</a:t></a:r></a:p><a:p><a:r><a:t>second &lt;line&gt;</a:t></a:r></a:p></p:sld>"
        val pptx = zip("ppt/slides/slide10.xml" to slide("Ten"), "ppt/slides/slide2.xml" to slide("Two"), "ppt/slides/slide1.xml" to slide("One"),
            "ppt/notesSlides/notesSlide2.xml" to "<p:notes><a:p><a:r><a:t>Say hello</a:t></a:r></a:p></p:notes>")
        val pt = DocText.extract("deck.pptx", pptx) ?: ""
        ok(pt.indexOf("## Slide 1") in 0 until pt.indexOf("## Slide 2") && pt.indexOf("## Slide 2") < pt.indexOf("## Slide 10"), "slides in numeric order")
        ok(pt.contains("One\nsecond <line>"), "slide paragraphs and entities")
        ok(pt.contains("Notes: Say hello"), "speaker notes follow their slide")

        // OpenDocument
        val odt = zip("content.xml" to "<office:document-content><office:body><office:text><text:h>Title</text:h><text:p>One<text:s/>two<text:tab/>three<text:line-break/>four</text:p></office:text></office:body></office:document-content>")
        eq(DocText.extract("a.odt", odt), "Title\nOne two\tthree\nfour", "odt text")
        val ods = zip("content.xml" to "<office:document-content><table:table><table:table-row><table:table-cell><text:p>a</text:p></table:table-cell><table:table-cell><text:p>b</text:p></table:table-cell></table:table-row><table:table-row><table:table-cell><text:p>c</text:p></table:table-cell><table:table-cell/></table:table-row></table:table></office:document-content>")
        eq(DocText.extract("a.ods", ods), "a\tb\nc", "ods rows and cells")

        // EPUB
        val epub = zip("OEBPS/ch1.xhtml" to "<html><head><style>p{}</style></head><body><h1>Chapter</h1><p>Some &amp; text</p><script>var x=1;</script></body></html>")
        eq(DocText.extract("b.epub", epub), "Chapter\nSome & text", "epub strips markup, script and style")

        // zip listing
        val lz = DocText.extract("lib.jar", zip("a/B.class" to "12345", "META-INF/MANIFEST.MF" to "x")) ?: ""
        ok(lz.startsWith("Archive with 2 files") && lz.contains("a/B.class  (5 bytes)"), "zip listing names and sizes")
        eq(DocText.extract("x.zip", "nope".toByteArray()), null, "damaged zip gives null")

        // PDF
        val simple = "BT /F1 12 Tf 72 720 Td (Hello PDF world) Tj 0 -14 Td [(Second) -300 (line) 20 (s)] TJ T* (Third \\(paren\\) \\101) Tj ET"
        var pd = DocText.extract("a.pdf", pdfRaw(simple)) ?: ""
        ok(pd.startsWith("PDF, 1 page"), "pdf header counts pages")
        ok(pd.contains("Hello PDF world\nSecond lines\nThird (paren) A"), "pdf Tj, TJ with kerning gap, T*, escapes and octal")
        pd = DocText.extract("a.pdf", pdfFlate(simple)) ?: ""
        ok(pd.startsWith("PDF, 2 pages") && pd.contains("Hello PDF world"), "pdf with Flate stream, two pages")
        pd = DocText.extract("a.pdf", pdfRaw("BT /F1 12 Tf 1 0 0 1 72 700 Tm <48656C6C6F20686578> Tj 1 0 0 1 72 680 Tm (next row) Tj ET")) ?: ""
        ok(pd.contains("Hello hex\nnext row"), "pdf hex string and Tm line change")
        pd = DocText.extract("a.pdf", pdfRaw("BT <0024002500260027> Tj ET")) ?: ""
        ok(pd.startsWith("(No text could be read"), "pdf with only unreadable font codes says so")
        pd = DocText.extract("a.pdf", "%PDF-1.4\n1 0 obj\n<< /Type /Page >>\nendobj\n%%EOF".toByteArray()) ?: ""
        ok(pd.startsWith("(No text could be read") && pd.contains("1 page"), "pdf without text streams")
        eq(DocText.extract("a.pdf", "plain text".toByteArray()), null, "not a pdf gives null")

        // very long output is capped
        val bigBody = "<w:document><w:body>" + "<w:p><w:r><w:t>${"x".repeat(1000)}</w:t></w:r></w:p>".repeat(300) + "</w:body></w:document>"
        val big = DocText.extract("big.docx", zip("word/document.xml" to bigBody)) ?: ""
        ok(big.length < DocText.MAX_CHARS + 100 && big.contains("more characters not shown"), "output capped with a note")

        println("\n$passed passed, $failed failed")
    }
}
