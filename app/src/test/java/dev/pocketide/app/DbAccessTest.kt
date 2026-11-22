package dev.pocketide.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DbAccessTest {
    @Test fun readsTablesAndRowsAndChangesOnlyWhenAllowed() {
        val dir = Files.createTempDirectory("db").toFile()
        val f = java.io.File(dir, "app.db")
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT, price REAL, pic BLOB)")
            db.execSQL("INSERT INTO items (name, price, pic) VALUES ('Bread', 2.5, x'0102'), ('Milk', NULL, NULL)")
            db.execSQL("CREATE VIEW cheap AS SELECT * FROM items WHERE price < 3")
        }
        assertEquals(listOf("cheap", "items"), DbAccess.tables(f).getOrThrow())
        val t = DbAccess.run(f, "SELECT name, price, pic FROM items ORDER BY id").getOrThrow()
        assertEquals(listOf("name", "price", "pic"), t.columns)
        assertEquals(listOf("Bread", "2.5", "(blob, 2 bytes)"), t.rows[0]); assertEquals(listOf("Milk", null, null), t.rows[1])
        assertTrue(DbAccess.run(f, "DELETE FROM items").isFailure)
        assertEquals(2, DbAccess.run(f, "SELECT * FROM items").getOrThrow().rows.size)
        assertEquals("1", DbAccess.run(f, "UPDATE items SET price = 3 WHERE name = 'Milk'", write = true).getOrThrow().rows[0][0])
        assertTrue(DbAccess.run(f, "SELEC nonsense").exceptionOrNull()!!.message!!.isNotEmpty())
        val b = DbAccess.backup(f, java.io.File(dir, "backups"))!!
        assertEquals(f.length(), b.length())
        dir.deleteRecursively()
    }
}
