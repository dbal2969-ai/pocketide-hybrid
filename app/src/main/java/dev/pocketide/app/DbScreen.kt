package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pocketide.app.core.DbView
import dev.pocketide.app.core.HttpCall
import dev.pocketide.app.core.Secrets
import java.io.File

/**
 * The database viewer (1.5.112, DbView): the project's SQLite files, and a Supabase project when the project's secrets have
 * SUPABASE_URL and SUPABASE_KEY (or SUPABASE_ANON_KEY). Tap a table for its rows, page through them, or run SQL. A statement that
 * changes a SQLite file asks first and keeps a copy of the file.
 */
object DbScreen {
    private const val SUPABASE = "Supabase (from the project's secrets)"

    fun show(ctx: Context, store: ProjectStore, projectKey: String) {
        val meta = Projects.metaDir(ctx, projectKey)
        val secrets = try { Secrets(File(meta, "secrets.json")).all() } catch (e: Exception) { emptyMap() }
        val supaUrl = secrets["SUPABASE_URL"]; val supaKey = secrets["SUPABASE_KEY"] ?: secrets["SUPABASE_ANON_KEY"]
        val files = store.list().filter { DbView.isDbFile(it) }.sorted()
        val sources = files + (if (supaUrl != null && supaKey != null) listOf(SUPABASE) else emptyList())
        if (sources.isEmpty()) {
            AlertDialog.Builder(ctx).setTitle("Database viewer")
                .setMessage("This project has no SQLite file (.db, .sqlite) yet.\n\nFor a Supabase project, add the secrets SUPABASE_URL and SUPABASE_KEY (Git → Secrets), then open this again.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(ctx).setTitle("Open which database?").setItems(sources.toTypedArray()) { _, i ->
            if (sources[i] == SUPABASE) open(ctx, null, supaUrl!!, supaKey!!, meta, secrets) else open(ctx, File(store.root, sources[i]), "", "", meta, secrets)
        }.show()
    }

    private fun open(ctx: Context, file: File?, supaUrl: String, supaKey: String, meta: File, secrets: Map<String, String>) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val pad = Ui.dp(ctx, 12)
        val box = Ui.vertical(ctx).also { it.setPadding(pad, Ui.dp(ctx, 4), pad, 0) }
        val status = Ui.text(ctx, "Reading…", 12f, Ui.MUTED, false)
        val tablesRow = Ui.horizontal(ctx)
        box.addView(HorizontalScrollView(ctx).also { it.addView(tablesRow) }, Ui.lp(Ui.MATCH, Ui.WRAP))
        box.addView(status, Ui.lp(Ui.MATCH, Ui.WRAP))
        val grid = TextView(ctx).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(Ui.TEXT); setTextIsSelectable(true) }
        val gridScroll = ScrollView(ctx).also { sv -> sv.addView(HorizontalScrollView(ctx).also { it.addView(grid) }) }
        box.addView(gridScroll, LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(ctx, 300)))
        val nav = Ui.horizontal(ctx)
        box.addView(nav, Ui.lp(Ui.MATCH, Ui.WRAP))
        val sql = EditText(ctx).apply { hint = if (file != null) "SQL, e.g. SELECT * FROM items WHERE price > 5" else "Supabase tables are read here; change data in your app or the Supabase dashboard"; typeface = Typeface.MONOSPACE; textSize = 13f; minLines = 1; maxLines = 4; isEnabled = file != null }
        box.addView(sql, Ui.lp(Ui.MATCH, Ui.WRAP))

        var table: String? = null
        var offset = 0
        fun render(cols: List<String>, rows: List<List<String?>>, what: String) {
            grid.text = DbView.grid(cols, rows); status.text = what; gridScroll.scrollTo(0, 0)
        }
        fun loadPage() {
            val t = table ?: return
            status.text = "Reading $t…"
            Thread {
                if (file != null) {
                    val r = DbAccess.run(file, DbView.pageSql(t, offset))
                    val total = DbAccess.run(file, DbView.countSql(t)).getOrNull()?.rows?.firstOrNull()?.firstOrNull()
                    main.post { r.fold({ render(it.columns, it.rows, "$t: rows ${offset + 1}–${offset + it.rows.size}" + (total?.let { n -> " of $n" } ?: "")) }, { status.text = "Could not read $t: ${it.message}" }) }
                } else {
                    val base = DbView.restBase(supaUrl)
                    val resp = if (base == null) null else HttpCall.send(HttpCall.Request("GET", DbView.rowsUrl(base, t, offset), DbView.headers(supaKey), ""))
                    val parsed = resp?.takeIf { it.code in 200..299 }?.let { DbView.rowsFromJson(it.body) }
                    main.post {
                        if (parsed != null) render(parsed.first, parsed.second, "$t: rows ${offset + 1}–${offset + parsed.second.size}")
                        else status.text = "Could not read $t: " + (resp?.let { Secrets.mask("HTTP ${it.code} ${it.body.take(200)}", secrets) } ?: "SUPABASE_URL is not an https address.")
                    }
                }
            }.start()
        }
        nav.addView(Ui.button(ctx, "‹ Previous", false) { if (offset > 0) { offset = maxOf(0, offset - DbView.PAGE); loadPage() } })
        nav.addView(Ui.button(ctx, "Next ›", false) { offset += DbView.PAGE; loadPage() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
        if (file != null) nav.addView(Ui.button(ctx, "Run SQL", true) {
            val text = sql.text.toString()
            val one = DbView.single(text) ?: run { status.text = "Type one SQL statement."; return@button }
            fun go(write: Boolean) {
                status.text = "Running…"
                Thread {
                    val kept = if (write) DbAccess.backup(file, File(meta, "db-backups")) else null
                    val r = DbAccess.run(file, one, write)
                    main.post { r.fold({ render(it.columns, it.rows, if (write) "Done." + (kept?.let { k -> " A copy from before is in the app's storage (${k.name})." } ?: "") else "${it.rows.size} rows" + (if (it.rows.size >= DbView.PAGE) " (first ${DbView.PAGE})" else "")) }, { status.text = "SQL error: ${it.message}" }) }
                }.start()
            }
            if (DbView.readsOnly(one)) go(false)
            else AlertDialog.Builder(ctx).setTitle("Change the database?").setMessage("This statement changes ${file.name}:\n\n${one.take(300)}\n\nA copy of the file is kept first.")
                .setPositiveButton("Run it") { _, _ -> go(true) }.setNegativeButton("Cancel", null).show()
        }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })

        Thread {
            val names: Result<List<String>> = if (file != null) DbAccess.tables(file) else {
                val base = DbView.restBase(supaUrl)
                if (base == null) Result.failure(IllegalArgumentException("SUPABASE_URL is not an https address."))
                else HttpCall.send(HttpCall.Request("GET", "$base/", DbView.headers(supaKey), "")).let { r ->
                    if (r.code in 200..299) Result.success(DbView.tablesFromOpenApi(r.body)) else Result.failure(IllegalStateException(Secrets.mask("HTTP ${r.code} ${r.message} ${r.body.take(200)}", secrets)))
                }
            }
            main.post {
                names.fold({ list ->
                    status.text = if (list.isEmpty()) "No tables yet." else "${list.size} table${if (list.size == 1) "" else "s"}: tap one."
                    for (n in list) tablesRow.addView(Ui.button(ctx, n, false) { table = n; offset = 0; loadPage() }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
                    if (list.size == 1) { table = list[0]; loadPage() }
                }, { status.text = "Could not open it: ${it.message}" })
            }
        }.start()
        AlertDialog.Builder(ctx).setTitle(file?.name ?: "Supabase").setView(ScrollView(ctx).also { it.addView(box) }).setNegativeButton("Close", null).show()
    }
}
