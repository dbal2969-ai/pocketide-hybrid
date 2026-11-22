package dev.pocketide.app.core

/**
 * Exact, repeatable edits to documents, with no AI involved: add a heading to a Word file, set an Excel cell, add a slide,
 * sort a CSV, and so on. The AI calls these as tools (so small models only have to pick an action and fill in values),
 * and the app offers the same actions in an Edit menu. Pure Kotlin on java.util.zip; no Android classes.
 */
object DocEdit {
    class Param(val name: String, val help: String = "", val required: Boolean = true)
    class Action(val name: String, val help: String, val params: List<Param>)

    enum class Type { DOCX, XLSX, PPTX, ODT, ODS, ODP, EPUB, ZIP, CSV }

    fun typeOf(path: String): Type? = when (path.substringAfterLast('.', "").lowercase()) {
        "docx", "docm", "dotx" -> Type.DOCX
        "xlsx", "xlsm", "xltx" -> Type.XLSX
        "pptx", "pptm", "ppsx" -> Type.PPTX
        "odt", "ott" -> Type.ODT
        "ods" -> Type.ODS
        "odp" -> Type.ODP
        "epub" -> Type.EPUB
        "zip" -> Type.ZIP
        "csv", "tsv" -> Type.CSV
        else -> null
    }

    fun canEdit(path: String): Boolean = typeOf(path) != null

    fun actions(path: String): List<Action> = when (typeOf(path)) {
        Type.DOCX -> WordEdit.ACTIONS
        Type.XLSX -> SheetEdit.ACTIONS
        Type.PPTX -> SlideEdit.ACTIONS
        Type.ODT -> OdfEdit.TEXT_ACTIONS
        Type.ODS -> OdfEdit.SHEET_ACTIONS
        Type.ODP -> OdfEdit.SLIDE_ACTIONS
        Type.EPUB -> OdfEdit.EPUB_ACTIONS
        Type.ZIP -> ZipEdit.ACTIONS
        Type.CSV -> CsvEdit.ACTIONS
        null -> emptyList()
    }

    /** The numbered overview an edit refers to (paragraph numbers, cell addresses, slide numbers). */
    fun outline(path: String, bytes: ByteArray): String = when (val r = apply(path, bytes, "outline", emptyMap())) {
        else -> r.message
    }

    /** One-line list of what can be done, for the AI's tool description and help text. */
    fun summary(): String =
        "Word (.docx): add_heading, add_paragraph, add_bullets, add_table, add_page_break, replace_text, delete_paragraph, set_heading, format_paragraph. " +
            "Excel (.xlsx): set_cell, set_cells, append_row, clear_cell, delete_row, add_sheet, rename_sheet, replace_text. " +
            "PowerPoint (.pptx): add_slide, set_slide_text, duplicate_slide, delete_slide, move_slide, replace_text. " +
            "OpenDocument (.odt .ods .odp) and EPUB: replace_text and a few more. Zip: add_text, remove_entry, rename_entry. CSV/TSV: set_cell, append_row, delete_row, add_column, delete_column, sort, replace_text. Every file type has outline."

    /** Runs one action on the file's bytes. Returns the new bytes (for outline, the same bytes) and a message. */
    fun apply(path: String, bytes: ByteArray, action: String, args: Map<String, String>): EditResult {
        val type = typeOf(path) ?: return EditResult.fail("Editing ${path.substringAfterLast('.', "this kind of")} files is not supported. Supported: .docx .xlsx .pptx .odt .ods .odp .epub .zip .csv .tsv.")
        val act = action.trim().lowercase().replace('-', '_').replace(' ', '_')
        val a = Args(args.mapValues { it.value })
        return try {
            when (type) {
                Type.CSV -> {
                    val tsv = path.lowercase().endsWith(".tsv")
                    val text = bytes.toString(Charsets.UTF_8)
                    val delim = if (tsv) '\t' else detectDelimiter(text)
                    val t = CsvEdit.parse(text, delim)
                    val msg = CsvEdit.apply(t, act, a)
                    if (act == "outline") EditResult(bytes, msg) else EditResult(CsvEdit.write(t).toByteArray(Charsets.UTF_8), msg)
                }
                else -> {
                    val pkg = DocPkg.read(bytes)
                    val msg = when (type) {
                        Type.DOCX -> WordEdit.apply(pkg, act, a)
                        Type.XLSX -> SheetEdit.apply(pkg, act, a)
                        Type.PPTX -> SlideEdit.apply(pkg, act, a)
                        Type.ODT -> OdfEdit.apply(pkg, "odt", act, a)
                        Type.ODS -> OdfEdit.apply(pkg, "ods", act, a)
                        Type.ODP -> OdfEdit.apply(pkg, "odp", act, a)
                        Type.EPUB -> OdfEdit.epubApply(pkg, act, a)
                        Type.ZIP -> ZipEdit.apply(pkg, act, a)
                        Type.CSV -> ""
                    }
                    if (act == "outline") EditResult(bytes, msg) else EditResult(pkg.toBytes(), msg)
                }
            }
        } catch (e: EditError) {
            EditResult.fail(e.message ?: "The edit could not be done.")
        } catch (e: Throwable) {
            EditResult.fail("The edit failed (${e.javaClass.simpleName}); the file was left unchanged.")
        }
    }

    private fun detectDelimiter(text: String): Char {
        val head = text.lineSequence().take(5).joinToString("\n")
        val counts = listOf(',', ';', '\t').associateWith { c -> head.count { it == c } }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    /** Reads "report.docx add_heading text=\"Test\" position=top" (the form small models write as an ACTION line). Null when it has no path or action. */
    fun parseLine(line: String): Triple<String, String, Map<String, String>>? {
        val tokens = ArrayList<String>()
        val sb = StringBuilder()
        var q = '\u0000'
        var started = false
        for (ch in line.trim()) {
            if (q != '\u0000') { if (ch == q) q = '\u0000' else sb.append(ch) }
            else if (ch == '"' || ch == '“') { q = if (ch == '"') '"' else '”'; started = true }
            else if (ch.isWhitespace()) { if (sb.isNotEmpty() || started) { tokens.add(sb.toString()); sb.setLength(0); started = false } }
            else { sb.append(ch); started = true }
        }
        if (sb.isNotEmpty() || started) tokens.add(sb.toString())
        if (tokens.size < 2) return null
        val args = LinkedHashMap<String, String>()
        for (t in tokens.drop(2)) {
            val i = t.indexOf('=')
            if (i > 0) args[t.substring(0, i).lowercase()] = t.substring(i + 1)
        }
        return Triple(tokens[0], tokens[1], args)
    }
}
