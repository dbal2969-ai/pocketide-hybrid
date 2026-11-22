package dev.pocketide.app.core

/** Edits to zip archives and CSV/TSV tables. */
internal object ZipEdit {
    private fun clean(name: String): String {
        val n = name.trim().replace('\\', '/').removePrefix("./").trimStart('/')
        if (n.isEmpty() || n.split("/").contains("..")) throw EditError("'$name' is not a valid path inside the zip.")
        return n
    }

    fun outline(pkg: DocPkg): String {
        val sb = StringBuilder("Zip archive with ${pkg.entries.size} file${if (pkg.entries.size == 1) "" else "s"}:\n")
        for ((n, b) in pkg.entries.entries.take(300)) sb.append("- $n (${b.size} bytes)\n")
        if (pkg.entries.size > 300) sb.append("… ${pkg.entries.size - 300} more\n")
        return sb.toString().trimEnd()
    }

    fun apply(pkg: DocPkg, action: String, a: Args): String {
        when (action) {
            "outline" -> return outline(pkg)
            "add_text" -> {
                val e = clean(a.req("entry"))
                val had = pkg.has(e)
                pkg.put(e, a.text("text"))
                return (if (had) "Replaced" else "Added") + " $e in the zip."
            }
            "remove_entry" -> {
                val e = clean(a.req("entry"))
                val folder = pkg.names().filter { it == e || it.startsWith("$e/") }
                if (folder.isEmpty()) throw EditError("There is no '$e' in the zip.")
                folder.forEach { pkg.remove(it) }
                return "Removed ${folder.size} file${if (folder.size == 1) "" else "s"} from the zip."
            }
            "rename_entry" -> {
                val e = clean(a.req("entry")); val to = clean(a.req("to"))
                val bytes = pkg.entries[e] ?: throw EditError("There is no file '$e' in the zip.")
                if (pkg.has(to)) throw EditError("'$to' already exists in the zip.")
                val out = LinkedHashMap<String, ByteArray>()
                for ((k, v) in pkg.entries) out[if (k == e) to else k] = v
                pkg.entries.clear(); pkg.entries.putAll(out)
                if (bytes.isEmpty()) { /* keep empty files too */ }
                return "Renamed $e to $to."
            }
            else -> throw EditError("Unknown action '$action' for a zip file.")
        }
    }

    val ACTIONS = listOf(
        DocEdit.Action("outline", "List the files in the zip.", emptyList()),
        DocEdit.Action("add_text", "Add (or replace) a text file in the zip.", listOf(DocEdit.Param("entry", "path inside the zip"), DocEdit.Param("text"))),
        DocEdit.Action("remove_entry", "Remove a file or folder from the zip.", listOf(DocEdit.Param("entry"))),
        DocEdit.Action("rename_entry", "Rename a file in the zip.", listOf(DocEdit.Param("entry"), DocEdit.Param("to")))
    )
}

internal object CsvEdit {
    class Table(val rows: MutableList<MutableList<String>>, val delimiter: Char, val eol: String, val bom: Boolean, val endsWithEol: Boolean)

    fun parse(text0: String, delimiter: Char): Table {
        val bom = text0.startsWith("﻿")
        val text = if (bom) text0.substring(1) else text0
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val rows = ArrayList<MutableList<String>>()
        var row = ArrayList<String>()
        val f = StringBuilder()
        var q = false
        var i = 0
        var any = false
        while (i < text.length) {
            val c = text[i]
            if (q) {
                if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') { f.append('"'); i++ } else q = false } else f.append(c)
            } else when {
                c == '"' && f.isEmpty() -> { q = true; any = true }
                c == delimiter -> { row.add(f.toString()); f.setLength(0); any = true }
                c == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> { row.add(f.toString()); f.setLength(0); rows.add(row); row = ArrayList(); any = false; i++ }
                c == '\n' || c == '\r' -> { row.add(f.toString()); f.setLength(0); rows.add(row); row = ArrayList(); any = false }
                else -> { f.append(c); any = true }
            }
            i++
        }
        if (any || f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
        return Table(rows, delimiter, eol, bom, text.endsWith("\n") || text.endsWith("\r"))
    }

    fun write(t: Table): String {
        fun field(s: String) = if (s.contains(t.delimiter) || s.contains('"') || s.contains('\n') || s.contains('\r') || s != s.trim() && s.isNotEmpty()) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val body = t.rows.joinToString(t.eol) { r -> r.joinToString(t.delimiter.toString()) { field(it) } }
        return (if (t.bom) "﻿" else "") + body + if (t.endsWithEol) t.eol else ""
    }

    private fun column(t: Table, spec: String): Int {
        val s = spec.trim()
        if (s.isEmpty()) throw EditError("Missing 'column'.")
        s.toIntOrNull()?.let { if (it >= 1) return it - 1 }
        val header = t.rows.firstOrNull() ?: throw EditError("The table is empty.")
        header.indexOfFirst { it.trim().equals(s, true) }.let { if (it >= 0) return it }
        if (Regex("""^[A-Za-z]{1,2}$""").matches(s)) return SheetEdit.colIndex(s) - 1
        throw EditError("No column '$s'. Columns: ${header.joinToString(", ") { "'$it'" }}.")
    }

    fun outline(t: Table): String {
        val sb = StringBuilder("Table with ${t.rows.size} rows (row 1 is usually the header) and ${t.rows.maxOfOrNull { it.size } ?: 0} columns:\n")
        for ((i, r) in t.rows.withIndex()) {
            if (i >= 40) { sb.append("… ${t.rows.size - 40} more rows\n"); break }
            sb.append("${i + 1}: ").append(r.joinToString(" | ") { it.take(40) }).append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun splitValues(s: String, t: Table): List<String> = s.replace("\\n", "\n").split("|").map { it.trim() }

    fun apply(t: Table, action: String, a: Args): String {
        when (action) {
            "outline" -> return outline(t)
            "set_cell" -> {
                val r = a.reqInt("row")
                if (r < 1 || r > t.rows.size) throw EditError("There is no row $r (the table has ${t.rows.size}).")
                val c = column(t, a.s("column"))
                val row = t.rows[r - 1]
                while (row.size <= c) row.add("")
                row[c] = a.s("value")
                return "Set row $r, column ${c + 1} to '${a.s("value").take(40)}'."
            }
            "append_row" -> {
                val vals = splitValues(a.s("values"), t)
                if (vals.all { it.isEmpty() }) throw EditError("Missing 'values': cells separated by |.")
                t.rows.add(vals.toMutableList())
                return "Added row ${t.rows.size}."
            }
            "delete_row" -> {
                val r = a.reqInt("row")
                if (r < 1 || r > t.rows.size) throw EditError("There is no row $r (the table has ${t.rows.size}).")
                t.rows.removeAt(r - 1)
                return "Deleted row $r."
            }
            "add_column" -> {
                val name = a.req("name")
                for ((i, r) in t.rows.withIndex()) r.add(if (i == 0) name else a.s("value"))
                return "Added column '$name'."
            }
            "delete_column" -> {
                val c = column(t, a.s("column"))
                for (r in t.rows) if (c < r.size) r.removeAt(c)
                return "Deleted column ${c + 1}."
            }
            "sort" -> {
                val c = column(t, a.s("column"))
                if (t.rows.size < 3) throw EditError("Nothing to sort.")
                val body = t.rows.drop(1)
                val numeric = body.all { it.getOrElse(c) { "" }.trim().toDoubleOrNull() != null || it.getOrElse(c) { "" }.isBlank() }
                val cmp: Comparator<List<String>> = if (numeric) compareBy { it.getOrElse(c) { "" }.trim().toDoubleOrNull() ?: Double.NEGATIVE_INFINITY } else compareBy(String.CASE_INSENSITIVE_ORDER) { it.getOrElse(c) { "" } }
                val sorted = body.sortedWith(if (a.s("order").lowercase().startsWith("desc")) cmp.reversed() else cmp)
                val head = t.rows[0]
                t.rows.clear(); t.rows.add(head); t.rows.addAll(sorted.map { it.toMutableList() })
                return "Sorted ${body.size} rows by column ${c + 1}" + if (a.s("order").lowercase().startsWith("desc")) " (descending)." else "."
            }
            "replace_text" -> {
                val find = a.req("find"); val repl = a.text("replace")
                for (ic in listOf(false, true)) {
                    var total = 0
                    for (r in t.rows) for (i in r.indices) { val (n, c) = X.replaceCount(r[i], find, repl, ic); if (c > 0) { r[i] = n; total += c } }
                    if (total > 0) return "Replaced $total occurrence${if (total == 1) "" else "s"} of '${find.take(40)}'" + if (ic) " (ignoring upper/lower case)." else "."
                }
                throw EditError("The text '${find.take(60)}' was not found.")
            }
            else -> throw EditError("Unknown action '$action' for a table file.")
        }
    }

    val ACTIONS = listOf(
        DocEdit.Action("outline", "The rows of the table, numbered (row 1 = header).", emptyList()),
        DocEdit.Action("set_cell", "Set one cell.", listOf(DocEdit.Param("row"), DocEdit.Param("column", "number, letter or header name"), DocEdit.Param("value"))),
        DocEdit.Action("append_row", "Add a row at the end.", listOf(DocEdit.Param("values", "cells separated by |"))),
        DocEdit.Action("delete_row", "Delete a row.", listOf(DocEdit.Param("row"))),
        DocEdit.Action("add_column", "Add a column.", listOf(DocEdit.Param("name"), DocEdit.Param("value", "fill value for existing rows", false))),
        DocEdit.Action("delete_column", "Delete a column.", listOf(DocEdit.Param("column"))),
        DocEdit.Action("sort", "Sort the rows by a column.", listOf(DocEdit.Param("column"), DocEdit.Param("order", "asc or desc", false))),
        DocEdit.Action("replace_text", "Replace text in cells.", listOf(DocEdit.Param("find"), DocEdit.Param("replace")))
    )
}
