package dev.pocketide.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.pocketide.app.core.DbView
import java.io.File

/** SQLite files through Android's own SQLite (DbView, 1.5.112). Blocking: call these off the main thread. */
object DbAccess {
    class Table(val columns: List<String>, val rows: List<List<String?>>)

    private fun cell(c: Cursor, i: Int): String? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_BLOB -> "(blob, ${c.getBlob(i).size} bytes)"
        else -> c.getString(i)
    }

    private fun read(c: Cursor, max: Int): Table {
        val cols = c.columnNames.toList()
        val rows = ArrayList<List<String?>>()
        while (rows.size < max && c.moveToNext()) rows.add(cols.indices.map { cell(c, it) })
        return Table(cols, rows)
    }

    /** Runs [sql]. A statement that only reads returns its rows (at most [max]); anything else needs [write] and returns the rows changed. */
    fun run(file: File, sql: String, write: Boolean = false, max: Int = DbView.PAGE): Result<Table> = try {
        val one = DbView.single(sql) ?: throw IllegalArgumentException("Give one SQL statement at a time.")
        val reads = DbView.readsOnly(one)
        if (!reads && !write) throw IllegalArgumentException("Only statements that read (SELECT, WITH, EXPLAIN, PRAGMA) are allowed here.")
        val db = SQLiteDatabase.openDatabase(file.path, null, if (reads) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE)
        try {
            if (reads) db.rawQuery(one, null).use { Result.success(read(it, max)) }
            else {
                db.execSQL(one)
                val n = db.rawQuery("SELECT changes()", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                Result.success(Table(listOf("rows changed"), listOf(listOf(n.toString()))))
            }
        } finally { db.close() }
    } catch (e: Exception) { Result.failure(e) }

    fun tables(file: File): Result<List<String>> = run(file, DbView.TABLES, max = 1000).map { t -> t.rows.mapNotNull { it.firstOrNull() } }

    /** A copy of [file] in [dir] before the person changes it, so a wrong UPDATE can be undone by hand. */
    fun backup(file: File, dir: File): File? = try {
        dir.mkdirs()
        File(dir, file.name + "." + System.currentTimeMillis() + ".bak").also { file.copyTo(it) }.also { b ->
            dir.listFiles { f -> f.name.startsWith(file.name + ".") }?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
            if (!b.isFile) return null
        }
    } catch (e: Exception) { null }
}
