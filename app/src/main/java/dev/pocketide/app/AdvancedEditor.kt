package dev.pocketide.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.HorizontalScrollView
import dev.pocketide.app.core.AdvancedEditorJs

/**
 * The advanced editor (1.5.144, Cursor parity P6): the open file full screen in CodeMirror 6 (MIT, in a WebView), with multiple
 * cursors (Next match adds the next copy of the selection), folding, bracket matching, and search and replace. It saves as you type
 * and once more when closed; the normal editor then reloads the file. Inline AI suggestions and next-edit stay in the normal editor.
 */
object AdvancedEditor {
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun show(ctx: Context, store: ProjectStore, path: String, dark: Boolean, readOnly: Boolean, onClosed: () -> Unit) {
        val start = store.read(path) ?: return
        val main = Handler(Looper.getMainLooper())
        var last = start
        fun save(text: String) { if (!readOnly && text != last && store.write(path, text)) last = text }

        val d = Dialog(ctx, android.R.style.Theme_DeviceDefault_NoActionBar)
        val root = Ui.vertical(ctx)
        root.setBackgroundColor(Ui.BG)
        val web = WebView(ctx)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.settings.blockNetworkLoads = true      // the page needs nothing from the internet
        // 1.5.146 (Copilot, ANALYSIS-1.5.145 N4): without this a crashed WebView renderer ends the whole app; the text is saved after
        // each pause in typing, so the editor closes and says so (Done would ask a dead page for the text)
        var gone = false
        web.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                gone = true
                main.post {
                    if (d.isShowing) d.dismiss()
                    android.widget.Toast.makeText(ctx, AdvancedEditorJs.CRASHED, android.widget.Toast.LENGTH_LONG).show()
                }
                return true
            }
        }
        web.addJavascriptInterface(object {
            // called on WebView's own thread: hand over to the main thread
            @JavascriptInterface fun ready() { main.post { web.evaluateJavascript(AdvancedEditorJs.open(start, path, dark, readOnly), null) } }
            @JavascriptInterface fun changed(text: String) { main.post { save(text) } }
        }, "PocketBridge")

        val bar = Ui.horizontal(ctx)
        fun close() {
            if (gone) { d.dismiss(); return }
            web.evaluateJavascript(AdvancedEditorJs.TEXT) { r -> AdvancedEditorJs.decode(r)?.let { save(it) }; d.dismiss() }
        }
        // Done first, so it is on screen without scrolling the row (1.5.145: on the emulator it was past the right edge)
        bar.addView(Ui.chip(ctx, "Done") { close() }.also { it.contentDescription = "Save and close the advanced editor" })
        val title = Ui.text(ctx, path.substringAfterLast('/') + if (readOnly) " (read only)" else "", 13f, Ui.MUTED, true)
        bar.addView(title, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.marginStart = Ui.dp(ctx, 8); it.marginEnd = Ui.dp(ctx, 8) })
        fun cmd(label: String, name: String, spoken: String) = bar.addView(Ui.chip(ctx, label) { web.evaluateJavascript(AdvancedEditorJs.run(name), null) }
            .also { it.contentDescription = spoken })
        cmd("Find", "search", "Find and replace")
        cmd("Next match", "next", "Add a cursor at the next match of the selection")
        cmd("Fold all", "foldAll", "Fold all blocks")
        cmd("Unfold", "unfoldAll", "Unfold all blocks")
        cmd("Comment", "comment", "Comment or uncomment the lines")
        cmd("Undo", "undo", "Undo")
        cmd("Redo", "redo", "Redo")
        val scroll = HorizontalScrollView(ctx).also { it.addView(bar); it.isHorizontalScrollBarEnabled = false }
        root.addView(scroll, Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(web, Ui.lp(Ui.MATCH, 0, 1f))

        d.setContentView(root, ViewGroup.LayoutParams(Ui.MATCH, Ui.MATCH))
        d.setOnKeyListener { _, code, ev -> if (code == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP) { close(); true } else false }
        d.setOnDismissListener { web.destroy(); onClosed() }
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // 1.5.145: Android 15 and later draw the dialog under the status bar, where the row's buttons could not be tapped (seen on the
        // Android 16 emulator); the bars and the keyboard are kept clear, as in the terminal
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            d.window?.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                android.view.WindowInsets.CONSUMED
            }
        }
        web.loadUrl(AdvancedEditorJs.PAGE)
        d.show()
    }
}
