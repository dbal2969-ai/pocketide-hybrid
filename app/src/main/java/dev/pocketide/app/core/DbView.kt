package dev.pocketide.app.core

/**
 * The database viewer (1.5.112): browse and query the project's SQLite files and a Supabase project's tables. The person can change
 * data (a copy of the file is kept first); the assistant's sql_query tool only reads. Pure logic: the SQLite calls are in DbScreen
 * and NativeAgent, the network calls go through HttpCall.
 */
object DbView {
    const val PAGE = 100
    private const val CELL = 60

    fun isDbFile(path: String): Boolean = Regex("""(?i)\.(db|sqlite|sqlite3|db3)$""").containsMatchIn(path)

    /** The statement with comments and surrounding space removed, or null when there is more than one statement. */
    fun single(sql: String): String? {
        val noComments = sql.replace(Regex("""--[^\n]*"""), " ").replace(Regex("""/\*[\s\S]*?\*/"""), " ").trim().trimEnd(';').trim()
        if (noComments.isEmpty()) return null
        // a ; outside quotes means a second statement
        var q: Char? = null
        for (c in noComments) { if (q != null) { if (c == q) q = null } else if (c == '\'' || c == '"' || c == '`') q = c else if (c == ';') return null }
        return noComments
    }

    /** A statement that only reads: SELECT, WITH … SELECT, EXPLAIN, or a PRAGMA that reads (no "="). */
    fun readsOnly(sql: String): Boolean {
        val s = single(sql) ?: return false
        val low = s.lowercase()
        if (Regex("""^pragma\s+[\w.]+\s*(\(\s*[\w"'.]*\s*\))?$""").matches(low)) return true
        if (!Regex("""^(select|with|explain)\b""").containsMatchIn(low)) return false
        return !Regex("""\b(insert|update|delete|replace|drop|alter|create|attach|detach|vacuum|reindex)\b""").containsMatchIn(low.replace(Regex("""'(?:[^']|'')*'"""), "''"))
    }

    /** SQL that lists the tables and views, and that reads a page of one. */
    const val TABLES = "SELECT name, type FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name"
    fun pageSql(table: String, offset: Int = 0, limit: Int = PAGE): String = "SELECT * FROM " + quote(table) + " LIMIT $limit OFFSET $offset"
    fun countSql(table: String): String = "SELECT COUNT(*) FROM " + quote(table)
    fun quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    /** Rows as a text table: columns padded to the widest cell (cut at [cell] characters), NULL shown as NULL. */
    fun grid(columns: List<String>, rows: List<List<String?>>, cell: Int = CELL): String {
        if (columns.isEmpty()) return "(no columns)"
        fun show(v: String?) = (v ?: "NULL").replace('\n', ' ').let { if (it.length > cell) it.take(cell - 1) + "…" else it }
        val w = columns.indices.map { i -> maxOf(show(columns[i]).length, rows.maxOfOrNull { r -> show(r.getOrNull(i)).length } ?: 0) }
        fun line(vals: List<String?>) = vals.indices.joinToString(" | ") { i -> show(vals[i]).padEnd(w[i]) }.trimEnd()
        val sb = StringBuilder(line(columns)).append('\n').append(w.joinToString("-+-") { "-".repeat(it) }).append('\n')
        for (r in rows) sb.append(line(columns.indices.map { r.getOrNull(it) })).append('\n')
        return sb.toString().trimEnd() + (if (rows.isEmpty()) "\n(no rows)" else "")
    }

    // ---- Supabase (PostgREST) ----

    /** The project's REST address from its URL (https://xyz.supabase.co → https://xyz.supabase.co/rest/v1). */
    fun restBase(url: String): String? {
        val u = HttpCall.normalize(url) ?: return null
        if (!u.startsWith("https://") && !HttpCall.isLocal(u)) return null
        return u.trimEnd('/').removeSuffix("/rest/v1") + "/rest/v1"
    }

    fun headers(key: String): List<Pair<String, String>> = listOf("apikey" to key, "Authorization" to "Bearer $key", "Accept" to "application/json")

    /** Table names from the REST root (PostgREST's OpenAPI description). */
    fun tablesFromOpenApi(json: String): List<String> {
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: Exception) { null } ?: return emptyList()
        val defs = MiniJson.obj(o["definitions"])?.keys.orEmpty()
        val paths = MiniJson.obj(o["paths"])?.keys.orEmpty().map { it.trim('/') }.filter { it.isNotEmpty() && !it.startsWith("rpc/") }
        return (defs + paths).distinct().sorted()
    }

    fun rowsUrl(base: String, table: String, offset: Int = 0, limit: Int = PAGE): String =
        "$base/" + java.net.URLEncoder.encode(table, "UTF-8").replace("+", "%20") + "?select=*&limit=$limit&offset=$offset"

    /** A PostgREST answer (a JSON array of objects) as columns and rows; null when it is not one. */
    fun rowsFromJson(json: String): Pair<List<String>, List<List<String?>>>? {
        val list = try { MiniJson.list(MiniJson.parse(json)) } catch (e: Exception) { null } ?: return null
        val objs = list.mapNotNull { MiniJson.obj(it) }
        val cols = LinkedHashSet<String>(); for (o in objs) cols.addAll(o.keys)
        val c = cols.toList()
        return c to objs.map { o -> c.map { k -> o[k]?.let { v -> if (v is Map<*, *> || v is List<*>) MiniJson.write(v) else if (v is Double && v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString() } } }
    }
}
