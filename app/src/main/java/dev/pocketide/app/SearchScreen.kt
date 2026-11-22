package dev.pocketide.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import dev.pocketide.app.core.ProjectSearch
import dev.pocketide.app.core.SearchOptions
import dev.pocketide.app.core.SearchResult

/** Find (and replace) across every text file in the project (v0.4 W1). */
object SearchScreen {
    private var lastQuery = ""

    /**
     * open: jump to a hit (path, 1-based line, 1-based column).
     * changed: files were rewritten by Replace all.
     */
    fun show(act: Activity, store: ProjectStore, open: (String, Int, Int) -> Unit, changed: (List<String>) -> Unit) {
        val fs = FullScreen(act, "Search in ${store.root.name}")
        val main = Handler(Looper.getMainLooper())
        val query = EditText(act)
        query.hint = "Search all files"
        query.setSingleLine()
        query.setText(lastQuery)
        query.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        query.imeOptions = EditorInfo.IME_ACTION_SEARCH
        query.setTextColor(Ui.TEXT)
        query.setHintTextColor(Ui.MUTED)
        fs.body.addView(query)
        val replace = EditText(act)
        replace.hint = "Replace with (optional)"
        replace.setSingleLine()
        replace.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        replace.setTextColor(Ui.TEXT)
        replace.setHintTextColor(Ui.MUTED)
        fs.body.addView(replace)
        val toggles = Ui.horizontal(act)
        fun check(label: String): CheckBox = CheckBox(act).also { it.text = label; it.setTextColor(Ui.TEXT); it.textSize = 13f; toggles.addView(it) }
        val case = check("Aa case")
        val word = check("Whole word")
        val regex = check(".* regex")
        fs.body.addView(toggles)
        val status = Ui.text(act, "", 12f, Ui.MUTED)
        val results = Ui.vertical(act)
        val replaceBtn = Ui.button(act, "Replace all") {}
        fs.buttonRow(fs.body, Ui.button(act, "Search", true) {}.also { it.setOnClickListener { run(act, store, query, case, word, regex, status, results, open, fs) } }, replaceBtn)
        fs.body.addView(status, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(act, 8) })
        fs.body.addView(results)

        fun options() = SearchOptions(query.text.toString(), case.isChecked, regex.isChecked, word.isChecked)
        val again = Runnable { run(act, store, query, case, word, regex, status, results, open, fs) }
        query.setOnEditorActionListener { _, _, _ -> main.removeCallbacks(again); again.run(); true }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { main.removeCallbacks(again); if ((s?.length ?: 0) >= 2) main.postDelayed(again, 400) }
        })
        for (c in listOf(case, word, regex)) c.setOnCheckedChangeListener { _, _ -> main.removeCallbacks(again); main.postDelayed(again, 100) }

        replaceBtn.setOnClickListener {
            val o = options()
            if (o.query.isEmpty()) { Toast.makeText(act, "Type what to search for first.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val (re, err) = ProjectSearch.pattern(o)
            if (re == null) { status.text = err ?: ""; return@setOnClickListener }
            status.text = "Finding matches to replace…"
            val replacement = replace.text.toString()
            // reads the project one file at a time, off the main thread, so the screen keeps working
            Thread {
                val plan = try { ProjectSearch.replaceInProject(textFiles(store).first, { store.read(it) }, o, replacement) } catch (e: OutOfMemoryError) { null }
                main.post {
                    if (plan == null) { status.text = "The project is too big to replace in all at once."; return@post }
                    val total = plan.values.sumOf { it.second }
                    if (total == 0) { status.text = ""; Toast.makeText(act, "No matches to replace.", Toast.LENGTH_SHORT).show(); return@post }
                    status.text = ""
                    AlertDialog.Builder(act).setTitle("Replace $total match${if (total == 1) "" else "es"}?")
                        .setMessage("In ${plan.size} file${if (plan.size == 1) "" else "s"}:\n" + plan.entries.take(12).joinToString("\n") { "• ${it.key} (${it.value.second})" } +
                            (if (plan.size > 12) "\n… ${plan.size - 12} more" else "") + "\n\nThis cannot be undone from here (the editor's undo only covers the open file).")
                        .setPositiveButton("Replace") { _, _ ->
                            val done = ArrayList<String>()
                            for ((p, v) in plan) if (store.write(p, v.first)) done.add(p)
                            changed(done)
                            Toast.makeText(act, "Replaced $total in ${done.size} file(s).", Toast.LENGTH_SHORT).show()
                            run(act, store, query, case, word, regex, status, results, open, fs)
                        }.setNegativeButton("Cancel", null).show()
                }
            }.start()
        }

        fs.show()
        query.requestFocus()
        if (lastQuery.isNotEmpty()) again.run()
    }

    /**
     * Text files that take part (not binary, at most 1 MB, not inside installed-package folders) and how many were skipped.
     * Only the paths: each file is read when it is searched, so a big project is never held in memory at once.
     */
    private fun textFiles(store: ProjectStore): Pair<List<String>, Int> {
        val m = ArrayList<String>()
        var skipped = 0
        for (p in store.filesUnder("")) {
            if (p.split('/').any { it.startsWith(".") || dev.pocketide.app.core.ProjectGuard.isHeavy(it) }) continue
            if (!ProjectSearch.eligible(store.size(p), store.isBinary(p))) { skipped++; continue }
            m.add(p)
        }
        return m to skipped
    }

    private fun run(
        act: Activity, store: ProjectStore, query: EditText, case: CheckBox, word: CheckBox, regex: CheckBox,
        status: android.widget.TextView, results: android.widget.LinearLayout, open: (String, Int, Int) -> Unit, fs: FullScreen
    ) {
        val o = SearchOptions(query.text.toString(), case.isChecked, regex.isChecked, word.isChecked)
        lastQuery = o.query
        results.removeAllViews()
        if (o.query.isEmpty()) { status.text = ""; return }
        status.text = "Searching…"
        val main = Handler(Looper.getMainLooper())
        Thread {
            val (files, skipped) = textFiles(store)
            val r: SearchResult = try { ProjectSearch.search(files, { store.read(it) }, o) }
                catch (e: OutOfMemoryError) { SearchResult(emptyList(), 0, false, "The project is too big to search all at once. Search inside one folder instead.") }
            main.post {
                show(act, r, status, results, open, fs)
                if (skipped > 0) status.text = "${status.text}  $skipped binary or large file${if (skipped == 1) "" else "s"} not searched."
            }
        }.start()
    }

    private fun show(act: Activity, r: SearchResult, status: android.widget.TextView, results: android.widget.LinearLayout,
                     open: (String, Int, Int) -> Unit, fs: FullScreen) {
        results.removeAllViews()
        if (r.error != null) { status.text = r.error; status.setTextColor(Ui.BAD); return }
        status.setTextColor(Ui.MUTED)
        status.text = when {
            r.hits.isEmpty() -> "No matches."
            r.truncated -> "First ${r.hits.size} matches in ${r.fileCount} files (refine the search to see more)."
            else -> "${r.hits.size} match${if (r.hits.size == 1) "" else "es"} in ${r.fileCount} file${if (r.fileCount == 1) "" else "s"}."
        }
        var lastFile = ""
        var shownInFile = 0
        for (h in r.hits) {
            if (h.path != lastFile) {
                lastFile = h.path
                shownInFile = 0
                val n = r.hits.count { it.path == h.path }
                results.addView(Ui.text(act, "${h.path}  ($n)", 13f, Ui.ACCENT).also {
                    it.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    it.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 4))
                })
            }
            shownInFile++
            if (shownInFile > 50) { if (shownInFile == 51) results.addView(Ui.text(act, "   …more in this file", 12f, Ui.MUTED)); continue }
            val line = SpannableString("${h.line}:  ${h.preview}")
            val off = "${h.line}:  ".length
            line.setSpan(ForegroundColorSpan(Ui.MUTED), 0, off, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val a = (off + h.previewStart).coerceIn(0, line.length)
            val b = (a + h.length).coerceIn(a, line.length)
            if (b > a) line.setSpan(BackgroundColorSpan(0x664C63E0), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val t = Ui.text(act, line, 13f, Ui.TEXT, true)
            t.maxLines = 2
            t.setPadding(Ui.dp(act, 8), Ui.dp(act, 8), 0, Ui.dp(act, 8))
            t.setOnClickListener { fs.dialog.dismiss(); open(h.path, h.line, h.col) }
            results.addView(t)
        }
    }
}
