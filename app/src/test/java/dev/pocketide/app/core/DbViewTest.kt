package dev.pocketide.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DbViewTest {
    @Test fun onlyReadingStatementsCountAsReads() {
        for (s in listOf("SELECT * FROM items", "select count(*) from t;", "WITH x AS (SELECT 1) SELECT * FROM x", "PRAGMA table_info(items)", "pragma user_version",
                "EXPLAIN QUERY PLAN SELECT 1", "SELECT 'drop table x' AS note", "-- a note\nSELECT 1")) assertTrue(s, DbView.readsOnly(s))
        for (s in listOf("DELETE FROM items", "UPDATE t SET a=1", "SELECT 1; DROP TABLE t", "PRAGMA user_version = 3", "WITH x AS (SELECT 1) DELETE FROM t",
                "INSERT INTO t VALUES (1)", "ATTACH 'x.db' AS x", "", "   ")) assertFalse(s, DbView.readsOnly(s))
        assertEquals("SELECT 'a;b'", DbView.single("SELECT 'a;b';"))
        assertNull(DbView.single("SELECT 1; SELECT 2"))
    }

    @Test fun gridAndNames() {
        assertEquals("\"we\"\"ird\"", DbView.quote("we\"ird"))
        assertEquals("SELECT * FROM \"items\" LIMIT 100 OFFSET 200", DbView.pageSql("items", 200))
        val g = DbView.grid(listOf("id", "name"), listOf(listOf("1", "Bread"), listOf("22", null)))
        assertEquals("id | name\n---+------\n1  | Bread\n22 | NULL", g)
        assertTrue(DbView.grid(listOf("a"), emptyList()).endsWith("(no rows)"))
        assertTrue(DbView.grid(listOf("a"), listOf(listOf("x".repeat(100))), 10).contains("xxxxxxxxx…"))
        assertTrue(DbView.isDbFile("data/app.db") && DbView.isDbFile("x.SQLITE3") && !DbView.isDbFile("db.js"))
    }

    @Test fun supabase() {
        assertEquals("https://abc.supabase.co/rest/v1", DbView.restBase("https://abc.supabase.co/"))
        assertEquals("https://abc.supabase.co/rest/v1", DbView.restBase("abc.supabase.co/rest/v1"))
        assertNull(DbView.restBase("http://abc.supabase.co"))
        assertEquals(listOf("profiles", "todos"), DbView.tablesFromOpenApi("""{"paths": {"/": {}, "/todos": {}, "/rpc/do_it": {}}, "definitions": {"profiles": {}, "todos": {}}}"""))
        assertEquals("https://a/rest/v1/my%20table?select=*&limit=100&offset=0", DbView.rowsUrl("https://a/rest/v1", "my table"))
        val (cols, rows) = DbView.rowsFromJson("""[{"id": 1, "title": "Milk", "tags": ["a"]}, {"id": 2, "done": true}]""")!!
        assertEquals(listOf("id", "title", "tags", "done"), cols)
        assertEquals(listOf("1", "Milk", "[\"a\"]", null), rows[0]); assertEquals(listOf("2", null, null, "true"), rows[1])
        assertNull(DbView.rowsFromJson("{\"message\": \"no\"}"))
        assertEquals("Bearer k", DbView.headers("k").toMap()["Authorization"])
    }
}
