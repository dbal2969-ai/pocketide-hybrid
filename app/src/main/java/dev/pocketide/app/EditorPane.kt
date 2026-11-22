package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.pocketide.app.core.Autocomplete
import dev.pocketide.app.core.DocEdit
import dev.pocketide.app.core.DocText
import dev.pocketide.app.core.ProviderKind
import dev.pocketide.app.core.Providers
import java.io.File

/**
 * The Code tab: open-file tabs, a small toolbar, the editor (or an image / binary notice), an AI suggestion
 * bar and a row of symbol keys. Tabs are remembered per project (v0.4 W1).
 */
class EditorPane(private val ctx: Context, private val store: ProjectStore, private val settings: AppSettings) : LinearLayout(ctx) {
    private val handler = Handler(Looper.getMainLooper())
    private val tabRow = Ui.horizontal(ctx)
    private val tabScroll = HorizontalScrollView(ctx)
    private val title = Ui.text(ctx, "", 12f, Ui.MUTED, true)
    private val editor = CodeEditText(ctx)

    /** v0.4: the selected text in the editor, or "" (used by the AI's /explain). */
    fun selectedText(): String {
        val a = editor.selectionStart; val b = editor.selectionEnd
        if (a < 0 || b < 0 || a == b) return ""
        return editor.text.subSequence(minOf(a, b), maxOf(a, b)).toString()
    }
    private val scroll = ScrollView(ctx)
    private val holder = FrameLayout(ctx)
    private val notice = Ui.text(ctx, "", 14f, Ui.MUTED)
    private val image = ImageView(ctx).also { it.contentDescription = "The open image" }
    // read-only viewers for documents (extracted text) and PDFs (rendered pages)
    private val docScroll = ScrollView(ctx)
    /** ✎ Edit: exact actions for the open document (shown only for Word, Excel, PowerPoint and similar files). */
    private val editBtn: TextView = smallButton("✎") {
        if (path.isNotEmpty()) {
            save()
            if (dev.pocketide.app.core.MediaOps.canEdit(path))
                MediaEditDialog.show(ctx, store, path) { written -> written.forEach { onSaved?.invoke(it) }; open(path) }
            else DocEditDialog.show(ctx, store, path) { open(path); onSaved?.invoke(path) }
        }
    }
    private val docText = Ui.text(ctx, "", 13f, Ui.TEXT, true)
    private val pdfScroll = ScrollView(ctx)
    private val pdfBox = Ui.vertical(ctx)
    private var viewStamp = 0L
    private val keys = HorizontalScrollView(ctx)
    private val renameBar = Ui.horizontal(ctx)
    private val renameLabel = Ui.text(ctx, "", 12f, Ui.TEXT)
    /** The file's text when the line being edited was entered, its line, and the text at the last pause in typing. */
    private var nextBase: String? = null
    private var nextBaseLine = -1
    private var lastIdle: String? = null
    private var pendingRename: Triple<String, String, Int>? = null
    // next-edit suggestions, the model case (core/NextEdit, 1.5.125): the text at the last few pauses (oldest first) so the
    // diff sent covers the edit just made, the text last asked about (no second request for the same text), the one request in
    // flight, and the suggestion shown in the rename bar
    private val editSnaps = ArrayDeque<String>()
    private var lastAsked: String? = null
    private var nextEditCall: ModelCall? = null
    private var pendingModel: dev.pocketide.app.core.NextEdit.Verdict.Ok? = null
    private val nextEditRun = Runnable { requestNextEdit() }
    private val goBtn: TextView = smallButton("Go") { pendingModel?.let { openAt(path, it.line + 1) } }
    private val suggestBar = Ui.horizontal(ctx)
    private val suggestLabel = Ui.text(ctx, "", 12f, Ui.MUTED)

    var path: String = ""
        private set
    private var dirty = false
    private var editable = false
    private val tabs = ArrayList<String>()

    /** Called after the editor writes a file to disk, so other screens can refresh. */
    var onSaved: ((String) -> Unit)? = null
    /** The 🔍 button: search the whole project (MainActivity opens the Search screen). */
    var onSearch: (() -> Unit)? = null
    /** Open the file tree (shown when no file is open). */
    var onShowFiles: (() -> Unit)? = null
    /** Save a copy of a project file to the phone (the host opens the system save dialog). */
    var onSaveCopy: ((String) -> Unit)? = null
    /** Line links: ask the assistant about lines, or put a link to them in the chat box. */
    var onAskLines: ((String) -> Unit)? = null
    var onLinkLines: ((String) -> Unit)? = null
    /** A file's history (false) or who changed each line (true). */
    var onHistory: ((String, Boolean) -> Unit)? = null

    /** The selected lines (or the line the cursor is on), 1-based. */
    private fun selectedLines(): IntRange {
        val t = editor.text.toString()
        val a = maxOf(0, minOf(editor.selectionStart, editor.selectionEnd)); val b = maxOf(0, maxOf(editor.selectionStart, editor.selectionEnd))
        val first = t.substring(0, minOf(a, t.length)).count { it == '\n' } + 1
        val last = t.substring(0, minOf(maxOf(a, if (b > a && b <= t.length && t[b - 1] == '\n') b - 1 else b), t.length)).count { it == '\n' } + 1
        return first..last
    }

    private val saveRun = Runnable { save() }
    private val autoRun = Runnable { requestSuggestion(auto = true) }
    private var suggestCall: ModelCall? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.BG)

        // open files
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.setBackgroundColor(Ui.SURFACE)
        tabRow.setPadding(Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4), 0)
        tabScroll.addView(tabRow)
        addView(tabScroll, Ui.lp(Ui.MATCH, Ui.WRAP))

        // toolbar: path, undo, redo, find, search project, more
        val bar = Ui.horizontal(ctx)
        bar.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 4), 0)
        bar.setBackgroundColor(Ui.SURFACE2)
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.START
        bar.addView(title, Ui.lp(0, Ui.WRAP, 1f))
        bar.addView(smallButton("↶") { if (!editor.undo()) toast("Nothing to undo") })
        bar.addView(smallButton("↷") { if (!editor.redo()) toast("Nothing to redo") })
        bar.addView(smallButton("Find") { findDialog() })
        bar.addView(smallButton("🔍") { save(); onSearch?.invoke() })
        editBtn.visibility = View.GONE
        bar.addView(editBtn)
        bar.addView(smallButton("⋯") { menu() })
        addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))

        scroll.isFillViewport = true
        scroll.addView(editor, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        holder.addView(scroll, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        notice.gravity = Gravity.CENTER
        notice.setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 24), Ui.dp(ctx, 24), Ui.dp(ctx, 24))
        notice.setOnClickListener { if (!editable && path.isEmpty()) onShowFiles?.invoke() }
        holder.addView(notice, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 12), Ui.dp(ctx, 12), Ui.dp(ctx, 12))
        holder.addView(image, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        docText.setTextIsSelectable(true)
        docText.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 24))
        docScroll.addView(docText)
        docScroll.visibility = View.GONE
        holder.addView(docScroll, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        pdfBox.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 24))
        pdfScroll.addView(pdfBox)
        pdfScroll.setBackgroundColor(0xFF2A2D35.toInt())
        pdfScroll.visibility = View.GONE
        holder.addView(pdfScroll, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        addView(holder, Ui.lp(Ui.MATCH, 0, 1f))

        // AI suggestion bar
        suggestBar.setBackgroundColor(Ui.SURFACE2)
        suggestBar.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 4), 0)
        suggestBar.addView(suggestLabel, Ui.lp(0, Ui.WRAP, 1f))
        suggestBar.addView(smallButton("Word") { if (!editor.acceptWord()) hideSuggestBar() })
        suggestBar.addView(smallButton("Accept") { if (!editor.acceptSuggestion()) hideSuggestBar() })
        suggestBar.addView(smallButton("Dismiss") { cancelSuggestion() })
        suggestBar.visibility = View.GONE
        addView(suggestBar, Ui.lp(Ui.MATCH, Ui.WRAP))

        val row = Ui.horizontal(ctx)
        row.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 4), Ui.dp(ctx, 6), Ui.dp(ctx, 4))
        row.addView(key("Tab") { if (!editor.acceptSuggestion()) editor.insert("  ") })
        row.addView(key("AI ⇥") { if (!editor.acceptSuggestion()) requestSuggestion(auto = false) })
        listOf("{", "}", "(", ")", "[", "]", ";", ":", "=", "\"", "'", "<", ">", "/", "!", "&", "|", "+", "-", "*", "_", "#").forEach { s -> row.addView(key(s) { editor.insert(s) }) }
        keys.isHorizontalScrollBarEnabled = false
        keys.setBackgroundColor(Ui.SURFACE)
        keys.addView(row)
        addView(keys, Ui.lp(Ui.MATCH, Ui.WRAP))

        editor.onUserEdit = {
            if (editable) {
                if (!dirty) { dirty = true; paintTabs() }
                handler.removeCallbacks(saveRun)
                handler.postDelayed(saveRun, 800)
                suggestCall?.cancel(); suggestCall = null
                handler.removeCallbacks(autoRun)
                if (settings.autoComplete && !editor.hasSuggestion) handler.postDelayed(autoRun, 1200)
                val line = cursorLine()
                if (nextBase == null || line != nextBaseLine) { nextBase = lastIdle; nextBaseLine = line }
                handler.removeCallbacks(renameRun)
                handler.postDelayed(renameRun, 900)
                // further typing cancels the request in flight and drops a shown suggestion: its place in the text may have moved
                nextEditCall?.cancel(); nextEditCall = null
                if (pendingModel != null) { pendingModel = null; goBtn.visibility = View.GONE; renameBar.visibility = View.GONE }
                handler.removeCallbacks(nextEditRun)
                handler.postDelayed(nextEditRun, dev.pocketide.app.core.NextEdit.DEBOUNCE_MS)
            }
        }
        // next-edit suggestion, the rename case (NextEdit, 1.5.113)
        renameBar.setBackgroundColor(Ui.SURFACE2)
        renameBar.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 4), 0)
        renameBar.addView(renameLabel, Ui.lp(0, Ui.WRAP, 1f))
        goBtn.visibility = View.GONE
        renameBar.addView(goBtn)
        renameBar.addView(smallButton("Apply") { if (pendingModel != null) applyNextEdit() else applyRename() })
        renameBar.addView(smallButton("Dismiss") { renameBar.visibility = View.GONE; pendingRename = null; nextBase = null; pendingModel = null; goBtn.visibility = View.GONE })
        renameBar.visibility = View.GONE
        addView(renameBar, indexOfChild(suggestBar), Ui.lp(Ui.MATCH, Ui.WRAP))
        editor.onSuggestionGone = { hideSuggestBar() }
        editor.onFontSizeChanged = { sp -> settings.editorFontSize = sp }
        applyEditorSettings()
        showNothing("No file open.\nTap ☰ to see the project's files.")
    }


    private fun cursorLine(): Int { val s = editor.selectionStart; val e = editor.text ?: return -1; if (s < 0) return -1; var n = 0; for (i in 0 until minOf(s, e.length)) if (e[i] == '\n') n++; return n }

    private val renameRun = Runnable {
        val now = editor.text?.toString() ?: return@Runnable
        val base = nextBase
        lastIdle = now
        val ch = if (base == null) null else dev.pocketide.app.core.NextEdit.changedLine(base, now)
        val r = ch?.let { dev.pocketide.app.core.NextEdit.renamed(it.second, it.third) }
        val others = r?.let { (old, _) -> dev.pocketide.app.core.NextEdit.places(now, old).size } ?: 0
        if (r == null || others == 0) { renameBar.visibility = View.GONE; pendingRename = null; return@Runnable }
        pendingRename = Triple(r.first, r.second, ch.first)
        pendingModel = null; goBtn.visibility = View.GONE
        renameLabel.text = "Rename ${r.first} → ${r.second} in $others more place${if (others == 1) "" else "s"} in this file?"
        renameBar.visibility = View.VISIBLE
    }

    private fun applyRename() {
        val (old, new, line) = pendingRename ?: return
        val text = editor.text?.toString() ?: return
        val start = text.split('\n').take(line).sumOf { it.length + 1 }
        val end = start + text.split('\n')[line].length
        val places = dev.pocketide.app.core.NextEdit.places(text, old)
        val out = dev.pocketide.app.core.NextEdit.apply(text, old, new, places, start..end)
        val cur = editor.selectionStart
        editor.text?.let { it.replace(0, it.length, out) }
        try { editor.setSelection(minOf(cur, out.length)) } catch (e: Exception) { }
        renameBar.visibility = View.GONE; pendingRename = null; nextBase = null; lastIdle = out
        toast("Renamed ${places.size} place${if (places.size == 1) "" else "s"}. Undo (↶) puts them back.")
    }

    /** Forgets what next-edit knew about the last file: a new file (or one changed on disk) starts with its own text as the base. */
    private fun resetNextEdit(text: String) {
        nextEditCall?.cancel(); nextEditCall = null
        handler.removeCallbacks(nextEditRun)
        editSnaps.clear(); editSnaps.addLast(text); lastAsked = text
        pendingModel = null; goBtn.visibility = View.GONE
    }

    /** Remembers the text at this pause; three are enough to see an edit made in two or three bursts. */
    private fun rememberSnap(now: String) {
        if (editSnaps.lastOrNull() == now) return
        editSnaps.addLast(now)
        while (editSnaps.size > 3) editSnaps.removeFirst()
    }

    /** After a pause in typing, when the rename case did not apply: ask the suggestion model for the next edit (1.5.125). */
    /** 1.5.129: every next-edit decision goes to the app log (⋮ → App log), so a suggestion that never shows can be explained;
     *  in the first emulator test none appeared and nothing said why. */
    private fun nextEditLog(msg: String) = dev.pocketide.app.core.AppLog.add("next-edit", "${path.substringAfterLast('/')}: $msg")

    private fun requestNextEdit() {
        if (!editable || path.isEmpty()) return
        val now = editor.content()
        val based = dev.pocketide.app.core.NextEdit.pickBaseText(editSnaps.toList(), now)
        val recent = based?.second
        rememberSnap(now)
        // the rename bar or an inline suggestion is already showing: one offer at a time
        if (pendingRename != null || editor.hasSuggestion) { nextEditLog("not asked: another suggestion is showing"); return }
        if (now == lastAsked) return
        if (recent == null) { nextEditLog("not asked: no earlier version of the file to compare yet (the next pause will have one)"); return }
        if (now.length > dev.pocketide.app.core.NextEdit.MAX_FILE) { nextEditLog("not asked: file too long (${now.length} characters)"); return }
        if (!settings.nextEditOn(dev.pocketide.app.core.NetWatch.metered(ctx))) { nextEditLog("not asked: off in Settings, or on mobile data"); return }
        // the same provider and suggestion model as inline completion; the demo provider has no model to ask
        if (Providers.byId(settings.provider).kind == ProviderKind.DEMO) { nextEditLog("not asked: the demo has no model"); return }
        lastAsked = now
        val asked = System.currentTimeMillis()
        val model = settings.completionModel(settings.provider).ifBlank { settings.model(settings.provider) }
        nextEditLog("asked ${settings.provider}/$model about lines ${recent.firstLine + 1}–${recent.firstLine + recent.lines}")
        val forPath = path
        val cursor = editor.selectionStart.coerceAtLeast(0)
        nextEditCall?.cancel()
        // 1.5.143 (experimental): Sweep's open next-edit model is asked in its own format, as a raw completion
        if (dev.pocketide.app.core.SweepNextEdit.isSweep(model) && based != null) { askSweep(model, forPath, based.first, now, recent, cursor, asked); return }
        var call: ModelCall? = null
        call = ModelClient.start(settings, { dev.pocketide.app.core.NextEdit.SYSTEM }, emptyList(), dev.pocketide.app.core.NextEdit.prompt(forPath, now, recent, cursor), object : ModelListener {
            override fun onStatus(text: String) {}
            override fun onDelta(acc: String) {}
            override fun onDone(reply: String) {
                if (nextEditCall !== call) return
                nextEditCall = null
                val ms = System.currentTimeMillis() - asked
                // a late answer for text the person has since changed is dropped: its offsets no longer hold
                if (forPath != path || editor.content() != now || pendingRename != null) { nextEditLog("answer after ${ms}ms dropped: the file changed meanwhile"); return }
                val s = dev.pocketide.app.core.NextEdit.parse(reply)
                if (s == null) { nextEditLog("answer after ${ms}ms not usable (no SEARCH/REPLACE block, or none needed): ${reply.trim().take(160)}"); return }
                val ok = when (val v = dev.pocketide.app.core.NextEdit.validate(now, s, recent)) {
                    is dev.pocketide.app.core.NextEdit.Verdict.Ok -> v
                    is dev.pocketide.app.core.NextEdit.Verdict.No -> { nextEditLog("answer after ${ms}ms rejected: ${v.why}"); return }
                }
                nextEditLog("suggestion shown after ${ms}ms (line ${ok.line + 1})")
                pendingModel = ok
                renameLabel.text = dev.pocketide.app.core.NextEdit.describe(ok)
                goBtn.visibility = View.VISIBLE
                renameBar.visibility = View.VISIBLE
            }
            // a background helper stays quiet: errors and stops show nothing
            override fun onStopped(partial: String) { if (nextEditCall === call) nextEditCall = null }
            override fun onError(message: String, needsSettings: Boolean) {
                if (nextEditCall === call) nextEditCall = null
                nextEditLog("failed after ${System.currentTimeMillis() - asked}ms: ${message.take(160)}")
            }
        }, modelOverride = settings.completionModel(settings.provider))
        nextEditCall = call
    }

    /** Answers older than the last question are dropped (Sweep's call has no ModelCall to cancel). */
    @Volatile private var sweepAsk = 0

    /** Asks Sweep's next-edit model for the rewritten window around the cursor, off the main thread, and shows it like any suggestion. */
    private fun askSweep(model: String, forPath: String, before: String, now: String, recent: dev.pocketide.app.core.NextEdit.Recent, cursor: Int, asked: Long) {
        val p = Providers.byId(settings.provider)
        val chat = if (p.custom) Providers.customUrl(settings.baseFor(p.id)) else p.url
        val url = dev.pocketide.app.core.SweepNextEdit.url(chat)
        if (url == null) { nextEditLog("not asked: ${p.name} has no completions address for Sweep's model"); return }
        val key = settings.key(p.id)
        val id = ++sweepAsk
        val (prompt, win) = dev.pocketide.app.core.SweepNextEdit.prompt(forPath, before, now, recent, cursor)
        Thread {
            val out = try { Result.success(dev.pocketide.app.core.SweepNextEdit.complete(url, key, model, prompt, p.id)) } catch (e: Exception) { Result.failure(e) }
            editor.post {
                val ms = System.currentTimeMillis() - asked
                if (id != sweepAsk) return@post
                out.exceptionOrNull()?.let { nextEditLog("Sweep failed after ${ms}ms: ${it.message?.take(160)}"); return@post }
                if (forPath != path || editor.content() != now || pendingRename != null) { nextEditLog("Sweep's answer after ${ms}ms dropped: the file changed meanwhile"); return@post }
                val s = dev.pocketide.app.core.SweepNextEdit.suggestion(win.second, dev.pocketide.app.core.SweepNextEdit.rewritten(out.getOrThrow()))
                if (s == null) { nextEditLog("Sweep after ${ms}ms: no change to suggest"); return@post }
                val ok = when (val v = dev.pocketide.app.core.NextEdit.validate(now, s, recent)) {
                    is dev.pocketide.app.core.NextEdit.Verdict.Ok -> v
                    is dev.pocketide.app.core.NextEdit.Verdict.No -> { nextEditLog("Sweep's answer after ${ms}ms rejected: ${v.why}"); return@post }
                }
                nextEditLog("Sweep's suggestion shown after ${ms}ms (line ${ok.line + 1})")
                pendingModel = ok
                renameLabel.text = dev.pocketide.app.core.NextEdit.describe(ok)
                goBtn.visibility = View.VISIBLE
                renameBar.visibility = View.VISIBLE
            }
        }.start()
    }

    private fun applyNextEdit() {
        val ok = pendingModel ?: return
        pendingModel = null; goBtn.visibility = View.GONE; renameBar.visibility = View.GONE
        editor.dismissSuggestion()
        val e = editor.text ?: return
        // checked again at the moment of applying: the text must still hold the old lines at the same place
        if (ok.at + ok.old.length > e.length || e.substring(ok.at, ok.at + ok.old.length) != ok.old) { toast("The file changed, so the suggestion was dropped."); return }
        e.replace(ok.at, ok.at + ok.old.length, ok.new)
        val out = editor.content()
        // the applied edit becomes the new base, so it is not sent back to the model as the person's own edit
        editSnaps.clear(); editSnaps.addLast(out); lastAsked = out
        nextBase = null; lastIdle = out
        openAt(path, ok.line + 1)
        toast("Applied. Undo (↶) puts it back.")
    }

    /** Applies font size, theme and word wrap from Settings. */
    fun applyEditorSettings() {
        editor.setFontSize(settings.editorFontSize)
        val t = EditorTheme.named(if (Ui.light) "light" else settings.editorTheme)
        editor.applyTheme(t)
        holder.setBackgroundColor(t.bg)
        editor.setWrap(settings.editorWrap)
    }

    private fun smallButton(label: String, onClick: () -> Unit): TextView {
        val t = Ui.text(ctx, label, 14f, Ui.ACCENT)
        t.gravity = Gravity.CENTER
        t.minWidth = Ui.dp(ctx, 48)
        t.minHeight = Ui.dp(ctx, 48)
        t.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10))
        t.isClickable = true
        t.setOnClickListener { onClick() }
        Ui.describe(t, label)
        return t
    }

    private fun key(label: String, action: () -> Unit): TextView {
        val t = Ui.text(ctx, label, 15f, if (label.startsWith("AI")) Ui.ACCENT else Ui.TEXT, true)
        t.gravity = Gravity.CENTER
        t.minWidth = Ui.dp(ctx, 38)
        t.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8))
        t.isClickable = true
        t.setOnClickListener { if (editable) action() }
        return t
    }

    private fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    // ---- tabs ----

    private fun tabsFile() = File(Projects.metaDir(ctx), "tabs.txt")

    private fun saveTabs() {
        try { tabsFile().writeText((listOf(path) + tabs).joinToString("\n")) } catch (e: Exception) { /* best effort */ }
    }

    private fun paintTabs() {
        tabRow.removeAllViews()
        for (p in tabs) {
            val on = p == path
            val cell = Ui.horizontal(ctx)
            cell.setPadding(Ui.dp(ctx, 10), 0, Ui.dp(ctx, 2), 0)
            if (on) cell.background = Ui.rounded(Ui.SURFACE2, 8, ctx)
            val name = Ui.text(ctx, (if (on && dirty) "● " else "") + p.substringAfterLast('/'), 13f, if (on) Ui.TEXT else Ui.MUTED)
            name.setPadding(0, Ui.dp(ctx, 10), Ui.dp(ctx, 4), Ui.dp(ctx, 10))
            name.maxLines = 1
            cell.addView(name)
            val x = Ui.text(ctx, "✕", 12f, Ui.MUTED)
            x.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10))
            x.setOnClickListener { closeTab(p) }
            Ui.tapTarget(x, "Close " + p.substringAfterLast('/'))
            cell.addView(x)
            cell.setOnClickListener { if (p != path) open(p) }
            tabRow.addView(cell, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 2); it.topMargin = Ui.dp(ctx, 3); it.bottomMargin = Ui.dp(ctx, 3) })
        }
        tabScroll.visibility = if (tabs.isEmpty()) View.GONE else View.VISIBLE
        val i = tabs.indexOf(path)
        if (i >= 0) tabScroll.post { tabRow.getChildAt(i)?.let { tabScroll.requestChildFocus(tabRow, it); tabScroll.smoothScrollTo(maxOf(0, it.left - Ui.dp(ctx, 24)), 0) } }
    }

    fun closeTab(p: String) {
        if (p == path) save()
        val i = tabs.indexOf(p)
        if (i < 0) return
        tabs.removeAt(i)
        if (p == path) {
            val next = tabs.getOrNull(i) ?: tabs.getOrNull(i - 1)
            if (next != null) open(next) else { showNothing("No file open.\nTap ☰ to see the project's files."); paintTabs() }
        } else paintTabs()
        saveTabs()
    }

    /** After a project switch: forget this project's editor state and load the new project's tabs. */
    fun projectChanged() {
        cancelSuggestion()
        handler.removeCallbacks(saveRun)
        tabs.clear()
        path = ""
        dirty = false
        val saved = try { tabsFile().takeIf { it.isFile }?.readLines() } catch (e: Exception) { null } ?: emptyList()
        val active = saved.firstOrNull().orEmpty()
        saved.drop(1).filter { it.isNotEmpty() && store.exists(it) && it !in tabs }.forEach { tabs.add(it) }
        val first = when {
            active.isNotEmpty() && store.exists(active) -> active
            tabs.isNotEmpty() -> tabs[0]
            store.exists("index.html") -> "index.html"
            else -> store.list().firstOrNull { !store.isBinary(it) } ?: store.list().firstOrNull() ?: ""
        }
        if (first.isEmpty()) { showNothing("This project is empty.\nTap ☰ to add a file."); paintTabs() } else open(first)
    }

    /** A file was moved or renamed (from the file tree): keep its tab. */
    fun fileMoved(from: String, to: String) {
        val prefix = from.trimEnd('/') + "/"
        for (i in tabs.indices) {
            val p = tabs[i]
            if (p == from) tabs[i] = to else if (p.startsWith(prefix)) tabs[i] = to.trimEnd('/') + "/" + p.removePrefix(prefix)
        }
        // a file moved onto one already open (1.5.117, replacing): one tab for it, and the open text is the new file's
        val once = LinkedHashSet(tabs); if (once.size != tabs.size) { tabs.clear(); tabs.addAll(once) }
        val replacedOpen = path == to
        if (path == from || path.startsWith(prefix)) {
            val newPath = if (path == from) to else to.trimEnd('/') + "/" + path.removePrefix(prefix)
            path = newPath
            settings.openFile = newPath
            title.text = newPath
        }
        paintTabs()
        saveTabs()
        if (replacedOpen) reload()
    }

    // ---- open / save ----

    private fun hideViewers() { docScroll.visibility = View.GONE; pdfScroll.visibility = View.GONE; pdfBox.removeAllViews() }

    private fun showNothing(msg: String) {
        editBtn.visibility = View.GONE
        hideViewers()
        path = ""
        editable = false
        title.text = ""
        notice.text = msg
        notice.visibility = View.VISIBLE
        scroll.visibility = View.GONE
        image.visibility = View.GONE
        keys.visibility = View.GONE
        hideSuggestBar()
        dirty = false
    }

    /** Writes pending changes to disk. */
    fun save() {
        handler.removeCallbacks(saveRun)
        if (!editable || path.isEmpty() || !dirty) return
        if (store.write(path, editor.content())) {
            dirty = false
            paintTabs()
            onSaved?.invoke(path)
        } else toast("Could not save $path")
    }

    fun open(newPath: String) {
        save()
        cancelSuggestion()
        if (newPath.isEmpty() || !store.exists(newPath)) {
            tabs.remove(newPath)
            showNothing("No file open.\nTap ☰ to see the project's files.")
            paintTabs()
            return
        }
        if (newPath !in tabs) {
            // new tabs open next to the current one
            val at = tabs.indexOf(path).let { if (it < 0) tabs.size else it + 1 }
            tabs.add(at.coerceAtMost(tabs.size), newPath)
            if (tabs.size > 12) tabs.removeAt(if (tabs[0] == newPath) 1 else 0)
        }
        path = newPath
        settings.openFile = newPath
        title.text = newPath
        editBtn.visibility = if ((DocEdit.canEdit(newPath) || dev.pocketide.app.core.MediaOps.canEdit(newPath)) && store.isBinary(newPath)) View.VISIBLE else View.GONE
        dirty = false
        paintTabs()
        saveTabs()
        if (store.isBinary(newPath)) {
            editable = false
            keys.visibility = View.GONE
            scroll.visibility = View.GONE
            hideViewers()
            viewStamp = store.stamp(newPath)
            val kind = DocText.kind(newPath)
            if (kind == DocText.Kind.PDF) { image.visibility = View.GONE; notice.visibility = View.GONE; showPdf(newPath, 0); return }
            if (kind != null) { image.visibility = View.GONE; notice.text = "Reading $newPath…"; notice.visibility = View.VISIBLE; showDocument(newPath); return }
            val bytes = store.readBytes(newPath)
            val bmp = if (bytes != null && bytes.size < 12_000_000) decodeBitmap(bytes) else null
            if (bmp != null) {
                image.setImageBitmap(bmp)
                image.visibility = View.VISIBLE
                notice.visibility = View.GONE
            } else {
                image.visibility = View.GONE
                notice.text = "$newPath is a binary file (${store.size(newPath) / 1024} KB).\nIt can be used by your pages and commands, and is included in the zip export. Use ⋯ → Save a copy to phone to download it."
                notice.visibility = View.VISIBLE
            }
            return
        }
        val text = store.read(newPath)
        if (text == null) {
            editable = false
            notice.text = "$newPath is too large to edit here."
            notice.visibility = View.VISIBLE
            hideViewers()
            scroll.visibility = View.GONE
            image.visibility = View.GONE
            keys.visibility = View.GONE
            return
        }
        editable = true
        hideViewers()
        image.visibility = View.GONE
        notice.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        keys.visibility = View.VISIBLE
        editor.load(newPath, text)
        lastIdle = text; nextBase = null; pendingRename = null; renameBar.visibility = View.GONE
        resetNextEdit(text)
        scroll.scrollTo(0, 0)
    }

    /** Shows the extracted text of a Word/Excel/PowerPoint/OpenDocument/EPUB/archive file, read-only. */
    private fun showDocument(p: String) {
        Thread {
            val text = try { store.readDoc(p) } catch (e: Throwable) { null }
            handler.post {
                if (p != path) return@post
                notice.visibility = View.GONE
                val head = when {
                    text == null -> "Could not read the text in this file. It may be damaged, password protected, or an old format (save old .doc, .xls and .ppt files as .docx, .xlsx and .pptx).\n"
                    DocText.kind(p) == DocText.Kind.ZIP -> ""
                    else -> "Read-only view of the text in this file. Tap ✎ to change it with exact actions (add a heading, replace text, set a cell…), or ask the AI. Use ⋯ → Save a copy to phone to download the file.\n\n"
                }
                docText.text = head + (text ?: "")
                docScroll.visibility = View.VISIBLE
                docScroll.scrollTo(0, 0)
            }
        }.start()
    }

    /** Draws PDF pages six at a time (a full phone-sized bitmap per page is large). */
    private fun showPdf(p: String, from: Int) {
        val f = store.resolve(p) ?: return
        if (from == 0) { pdfBox.removeAllViews(); pdfBox.addView(Ui.text(ctx, "Opening…", 13f, Ui.MUTED)) }
        pdfScroll.visibility = View.VISIBLE
        val width = minOf(resources.displayMetrics.widthPixels, 1080)
        Thread {
            val pages = ArrayList<Bitmap>()
            var total = 0
            var err: String? = null
            try {
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    val r = PdfRenderer(fd)
                    try {
                        total = r.pageCount
                        for (i in from until minOf(total, from + 6)) {
                            val page = r.openPage(i)
                            try {
                                val h = maxOf(1, (width.toFloat() * page.height / page.width).toInt())
                                val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                pages.add(bmp)
                            } finally { page.close() }
                        }
                    } finally { r.close() }
                }
            } catch (e: Throwable) { err = e.message ?: "unreadable" }
            handler.post {
                if (p != path) return@post
                if (from == 0) pdfBox.removeAllViews() else if (pdfBox.childCount > 0) pdfBox.removeViewAt(pdfBox.childCount - 1)
                if (err != null && pages.isEmpty()) {
                    pdfBox.addView(Ui.text(ctx, "Could not open this PDF ($err). It may be password protected or damaged.", 14f, Ui.MUTED))
                    return@post
                }
                for (b in pages) {
                    val iv = ImageView(ctx)
                    iv.contentDescription = "Page " + (pdfBox.childCount + 1)
                    iv.adjustViewBounds = true
                    iv.setImageBitmap(b)
                    pdfBox.addView(iv, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.bottomMargin = Ui.dp(ctx, 8) })
                }
                val next = from + 6
                if (next < total) pdfBox.addView(Ui.chip(ctx, "Show pages ${next + 1}–${minOf(total, next + 6)} of $total") { showPdf(p, next) })
            }
        }.start()
    }

    /** Opens a file with the cursor on a 1-based line (and column), scrolled into view. */
    fun openAt(path: String, line: Int, col: Int = 0) {
        if (path != this.path) open(path)
        if (!editable || this.path != path) return
        editor.post {
            val top = editor.goToLine(line.coerceAtLeast(1), col.coerceAtLeast(1))
            scroll.post { scroll.smoothScrollTo(0, (top - scroll.height / 3).coerceAtLeast(0)) }
        }
    }

    private fun decodeBitmap(bytes: ByteArray): android.graphics.Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / sample > 2048 || o.outHeight / sample > 2048) sample *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = sample
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
    }

    /** Re-reads the open file from disk, for when the assistant or a command changed it. */
    fun reload() {
        tabs.removeAll { !store.exists(it) }
        if (path.isEmpty()) { paintTabs(); return }
        if (!store.exists(path)) { showNothing("That file was deleted."); paintTabs(); return }
        if (!store.isBinary(path)) {
            val text = store.read(path) ?: return
            if (text != editor.content()) {
                cancelSuggestion()
                editor.load(path, text)
                resetNextEdit(text)
                dirty = false
                paintTabs()
            }
        } else if (store.stamp(path) != viewStamp) open(path)
    }

    fun fileGone(p: String) {
        val prefix = p.trimEnd('/') + "/"
        tabs.removeAll { it == p || it.startsWith(prefix) }
        if (p == path || path.startsWith(prefix)) {
            dirty = false
            if (tabs.isNotEmpty()) open(tabs.last()) else { showNothing("That file was deleted."); paintTabs() }
        } else paintTabs()
        saveTabs()
    }

    // ---- toolbar menu ----

    private fun menu() {
        val wrapLabel = if (settings.editorWrap) "Word wrap: on" else "Word wrap: off"
        val themeLabel = if (settings.editorTheme == "light") "Editor theme: light" else "Editor theme: dark"
        val autoLabel = if (settings.autoComplete) "Automatic AI suggestions: on" else "Automatic AI suggestions: off"
        val items = listOf<Pair<String, () -> Unit>>(
            "Go to line…" to { goToLineDialog() },
            // 1.5.144 (Cursor parity P6): CodeMirror full screen for multiple cursors, folding and bracket matching
            "Advanced editor (folding, multiple cursors)" to {
                if (path.isEmpty() || store.isBinary(path)) toast("Open a text file first") else {
                    save()
                    AdvancedEditor.show(ctx, store, path, dark = !Ui.light && settings.editorTheme != "light", readOnly = !editable) { reload() }
                }
            },
            "Ask the assistant about these lines" to {
                if (path.isEmpty() || !editable) toast("Open a file first") else {
                    save(); val r = selectedLines(); val lines = editor.text.toString().split("\n")
                    val code = lines.subList(r.first - 1, minOf(r.last, lines.size)).joinToString("\n")
                    onAskLines?.invoke(dev.pocketide.app.core.LineLinks.ask(path, r.first, r.last, code))
                }
            },
            // #BuildHybridAI: quick single-turn help on the selected lines, Gemini Nano on this phone first, cloud fallback
            "Quick assist (Gemini Nano on this phone)…" to {
                val act = ctx as? android.app.Activity
                if (path.isEmpty() || act == null) toast("Open a file first") else {
                    save(); val r = selectedLines(); val lines = editor.text.toString().split("\n")
                    val code = lines.subList(r.first - 1, minOf(r.last, lines.size)).joinToString("\n")
                    val lineStart = lines.take(r.first - 1).sumOf { it.length + 1 }
                    HybridScreen.quickAssist(act, dev.pocketide.app.core.Snippets.langOf(path), code,
                        onInsert = if (editable) { doc -> editor.text.insert(minOf(lineStart, editor.text.length), doc); save() } else null,
                        onEscalate = { prompt -> onAskLines?.invoke(prompt) })
                }
            },
            "Link these lines in the chat" to { if (path.isEmpty()) toast("Open a file first") else { val r = selectedLines(); onLinkLines?.invoke(dev.pocketide.app.core.LineLinks.link(path, r.first, r.last)) } },
            "Snippets (saved code)…" to {
                ProjectUi.snippets(ctx, selectedText(), dev.pocketide.app.core.Snippets.langOf(path)) { code ->
                    if (!editable) toast("This file cannot be edited here") else { val at = maxOf(0, editor.selectionStart); editor.text.insert(at, code); save() }
                }
            },
            "History of this file" to { if (path.isEmpty()) toast("Open a file first") else { save(); onHistory?.invoke(path, false) } },
            "Who changed each line" to { if (path.isEmpty()) toast("Open a file first") else { save(); onHistory?.invoke(path, true) } },
            "Bigger text" to { settings.editorFontSize = settings.editorFontSize + 1f; applyEditorSettings() },
            "Smaller text" to { settings.editorFontSize = settings.editorFontSize - 1f; applyEditorSettings() },
            wrapLabel to { settings.editorWrap = !settings.editorWrap; applyEditorSettings() },
            themeLabel to { settings.editorTheme = if (settings.editorTheme == "light") "dark" else "light"; applyEditorSettings() },
            autoLabel to { settings.autoComplete = !settings.autoComplete; toast(if (settings.autoComplete) "AI will suggest after you pause typing" else "Automatic suggestions off. Tap AI ⇥ to ask.") },
            "Save now" to { save(); toast("Saved") },
            "Save a copy to phone…" to { if (path.isEmpty()) toast("Open a file first") else { save(); onSaveCopy?.invoke(path) } },
            "Close all tabs" to { save(); tabs.clear(); val keep = path; if (keep.isNotEmpty()) tabs.add(keep); paintTabs(); saveTabs() }
        )
        AlertDialog.Builder(ctx).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun goToLineDialog() {
        if (!editable) return
        val box = FrameLayout(ctx)
        box.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), 0)
        val et = EditText(ctx)
        et.hint = "Line (1–${editor.lineCountText()})"
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.setSingleLine()
        box.addView(et)
        val dlg = AlertDialog.Builder(ctx).setTitle("Go to line").setView(box)
            .setPositiveButton("Go") { _, _ -> et.text.toString().toIntOrNull()?.let { openAt(path, it) } }
            .setNegativeButton("Cancel", null).create()
        dlg.show()
        et.requestFocus()
    }

    // ---- AI suggestions ----

    private fun hideSuggestBar() { suggestBar.visibility = View.GONE }

    private fun cancelSuggestion() {
        suggestCall?.cancel(); suggestCall = null
        handler.removeCallbacks(autoRun)
        editor.dismissSuggestion()
        hideSuggestBar()
    }

    private fun requestSuggestion(auto: Boolean) {
        if (!editable) return
        val provider = Providers.byId(settings.provider)
        if (provider.kind == ProviderKind.DEMO) {
            if (!auto) toast("Pick an AI provider in Settings to get suggestions.")
            return
        }
        val at = editor.caret()
        if (at < 0) { if (!auto) toast("Put the cursor where the code should go."); return }
        suggestCall?.cancel()
        val text = editor.content()
        val around = Autocomplete.around(text, at)
        val forPath = path
        suggestLabel.text = "AI is thinking…"
        suggestBar.visibility = View.VISIBLE
        suggestCall = ModelClient.start(settings, { Autocomplete.SYSTEM }, emptyList(), Autocomplete.userMessage(forPath, around), object : ModelListener {
            override fun onStatus(text: String) {}
            override fun onDelta(acc: String) {}
            override fun onDone(reply: String) {
                suggestCall = null
                // ignore a late answer if the user moved on
                if (forPath != path || editor.caret() != at || editor.content() != text) { hideSuggestBar(); return }
                val s = Autocomplete.clean(reply, around)
                if (s.isEmpty()) {
                    if (auto) hideSuggestBar() else { suggestLabel.text = "No suggestion here."; handler.postDelayed({ if (!editor.hasSuggestion) hideSuggestBar() }, 1500) }
                    return
                }
                editor.showSuggestion(at, s)
                val lines = s.count { it == '\n' } + 1
                suggestLabel.text = if (lines == 1) "AI suggestion" else "AI suggestion ($lines lines)"
                suggestBar.visibility = View.VISIBLE
            }
            override fun onStopped(partial: String) { suggestCall = null }
            override fun onError(message: String, needsSettings: Boolean) {
                suggestCall = null
                if (auto) { hideSuggestBar(); return }
                suggestLabel.text = message.take(140)
                handler.postDelayed({ if (!editor.hasSuggestion) hideSuggestBar() }, 4000)
            }
        }, modelOverride = settings.completionModel(settings.provider))
    }

    // ---- find in this file ----

    private fun findDialog() {
        if (!editable) return
        val box = Ui.vertical(ctx)
        box.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), 0)
        val find = EditText(ctx)
        find.hint = "Find"
        find.inputType = InputType.TYPE_CLASS_TEXT
        find.setSingleLine()
        val rep = EditText(ctx)
        rep.hint = "Replace with"
        rep.inputType = InputType.TYPE_CLASS_TEXT
        rep.setSingleLine()
        box.addView(find)
        box.addView(rep)
        val dlg = AlertDialog.Builder(ctx)
            .setTitle("Find in this file")
            .setView(box)
            .setPositiveButton("Find next", null)
            .setNeutralButton("Replace all", null)
            .setNegativeButton("Close", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!editor.findNext(find.text.toString())) toast("No match")
                else {
                    // hide the dialog's own keyboard so the match is visible
                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(find.windowToken, 0)
                    dlg.dismiss()
                }
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val n = editor.replaceAll(find.text.toString(), rep.text.toString())
                toast(if (n == 0) "No match" else "Replaced $n")
                if (n > 0) dlg.dismiss()
            }
        }
        dlg.show()
    }
}
