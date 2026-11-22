package dev.pocketide.app.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Checks that run on the phone with no network: the document editors work on tiny files built in memory. */
object SelfCheck {
    class Result(val name: String, val ok: Boolean, val detail: String)

    private fun zip(vararg f: Pair<String, String>): ByteArray {
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z -> for ((n, t) in f) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray(Charsets.UTF_8)); z.closeEntry() } }
        return o.toByteArray()
    }

    private const val W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
    private const val SS = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""

    private fun docx() = zip(
        "[Content_Types].xml" to "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>",
        "word/document.xml" to "<?xml version=\"1.0\"?><w:document $W><w:body><w:p><w:r><w:t>Hello world</w:t></w:r></w:p><w:sectPr/></w:body></w:document>",
        "word/styles.xml" to "<?xml version=\"1.0\"?><w:styles $W/>")

    private fun xlsx() = zip(
        "[Content_Types].xml" to "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"x\"/></Types>",
        "xl/workbook.xml" to "<?xml version=\"1.0\"?><workbook $SS xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
        "xl/_rels/workbook.xml.rels" to "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"t\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
        "xl/worksheets/sheet1.xml" to "<?xml version=\"1.0\"?><worksheet $SS><sheetData><row r=\"1\"><c r=\"A1\"><v>1</v></c></row></sheetData></worksheet>")

    private fun check(name: String, f: () -> String?): Result =
        try { val bad = f(); Result(name, bad == null, bad ?: "ok") } catch (e: Throwable) { Result(name, false, e.javaClass.simpleName + ": " + (e.message ?: "")) }

    fun run(): List<Result> = listOf(
        check("Word: add heading") {
            val r = DocEdit.apply("t.docx", docx(), "add_heading", mapOf("text" to "Title", "position" to "top"))
            if (!r.ok) r.message else if (DocText.extract("t.docx", r.bytes!!)?.contains("Title") != true) "heading missing after edit" else null
        },
        check("Word: replace text") {
            val r = DocEdit.apply("t.docx", docx(), "replace_text", mapOf("find" to "Hello", "replace" to "Bye"))
            if (!r.ok) r.message else if (DocText.extract("t.docx", r.bytes!!)?.contains("Bye world") != true) "text not replaced" else null
        },
        check("Excel: set cell") {
            val r = DocEdit.apply("t.xlsx", xlsx(), "set_cell", mapOf("cell" to "B2", "value" to "hi"))
            if (!r.ok) r.message else if (DocText.extract("t.xlsx", r.bytes!!)?.contains("hi") != true) "cell missing after edit" else null
        },
        check("Zip: read back") {
            val z = zip("a.txt" to "x")
            if (DocEdit.outline("t.zip", z).isBlank()) "empty outline" else null
        },
        check("CSV: append row") {
            val r = DocEdit.apply("t.csv", "a,b\n1,2\n".toByteArray(), "append_row", mapOf("values" to "3|4"))
            if (!r.ok) r.message else if (!String(r.bytes!!).contains("3,4")) "row missing" else null
        }
    )

    fun format(results: List<Result>): String = results.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.name + (if (it.ok) "" else " — " + it.detail) }
}
