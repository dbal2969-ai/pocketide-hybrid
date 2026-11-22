package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.pocketide.app.core.Symbols

/** Every definition in the project, searchable; tap one to open it. */
object SymbolsScreen {
    fun show(ctx: Context, store: ProjectStore, open: (String, Int) -> Unit) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), 0) }
        val search = EditText(ctx).apply { hint = "Name of a function, class, CSS rule or id"; setSingleLine(true) }
        box.addView(search, Ui.lp(Ui.MATCH, Ui.WRAP))
        val status = Ui.text(ctx, "Reading the project…", 12f, Ui.MUTED, false)
        box.addView(status, Ui.lp(Ui.MATCH, Ui.WRAP))
        val results = Ui.vertical(ctx)
        val sv = ScrollView(ctx).also { it.addView(results) }
        box.addView(sv, LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(ctx, 360)))
        val dlg = AlertDialog.Builder(ctx).setTitle("Definitions in this project").setView(box).setNegativeButton("Close", null).create()
        var all: List<Symbols.Symbol> = emptyList()
        fun render() {
            val q = search.text.toString().trim().lowercase()
            val shown = (if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) || it.path.lowercase().contains(q) }).take(300)
            results.removeAllViews()
            status.text = if (all.isEmpty()) "No definitions found." else "${shown.size} of ${all.size} definitions"
            for (s in shown) {
                val t = Ui.text(ctx, "${s.name}   ${s.kind}\n${s.path}:${s.line}", 13f, Ui.TEXT, false)
                t.setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8))
                t.setOnClickListener { dlg.dismiss(); open(s.path, s.line) }
                results.addView(t)
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { render() }
            override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
        })
        dlg.show()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val found = try { Symbols.index(store.list(), { p -> store.read(p) }) } catch (e: Throwable) { emptyList() }
            main.post { all = found.sortedBy { it.name.lowercase() }; render() }
        }.start()
    }
}
