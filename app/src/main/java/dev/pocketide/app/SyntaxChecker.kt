package dev.pocketide.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import dev.pocketide.app.core.SyntaxCheck

/**
 * Checks files the assistant writes (see core/SyntaxCheck). JavaScript is parsed by the bundled Acorn parser
 * (assets/check/acorn.min.js, MIT) in one hidden WebView that is kept for the session. Results arrive on the main thread;
 * null means no problem found (or the file could not be checked).
 */
class SyntaxChecker(private val ctx: Context) {
    /** Runs a command in PocketIDE's Linux and gives its exit code and output (set by ChatPane); Python files need it (1.5.188). */
    var shell: ((String, (Int, String) -> Unit) -> Unit)? = null

    private var web: WebView? = null
    private var ready = false
    private val waiting = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private fun withParser(then: () -> Unit) {
        if (ready) { then(); return }
        waiting.add(then)
        if (web != null) return
        val w = WebView(ctx)
        w.settings.javaScriptEnabled = true
        w.settings.allowFileAccess = false
        web = w
        val src = listOf("check/acorn.min.js", "check/names.js").joinToString(";\n") { a ->
            try { ctx.assets.open(a).bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
        }
        var loaded = false
        w.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (loaded) return
                loaded = true
                view.evaluateJavascript(src + ";'loaded'") { _ ->
                    ready = true
                    val list = ArrayList(waiting); waiting.clear()
                    for (f in list) f()
                }
            }
        }
        w.loadDataWithBaseURL("https://check.pocketide.local/", "<!doctype html><html><body></body></html>", "text/html", "utf-8", null)
        // if the page never loads, do not hold the assistant up
        main.postDelayed({ if (!ready) { ready = true; web = null; val list = ArrayList(waiting); waiting.clear(); for (f in list) f() } }, 8000)
    }

    private fun js(code: String, module: Boolean, lineOffset: Int, done: (String?) -> Unit) {
        withParser {
            val w = web
            if (w == null) { done(null); return@withParser }
            w.evaluateJavascript(SyntaxCheck.jsCall(code, module)) { r -> done(SyntaxCheck.jsResult(r, lineOffset)) }
        }
    }

    /** Checks [text] as the file [path]; [done] gets an error description or null. */
    fun check(path: String, text: String, done: (String?) -> Unit) {
        if (text.length > SyntaxCheck.MAX_CHARS) { done(null); return }
        when (SyntaxCheck.kind(path, text)) {
            SyntaxCheck.Kind.JSON -> done(SyntaxCheck.json(text))
            SyntaxCheck.Kind.CSS -> done(SyntaxCheck.css(text))
            SyntaxCheck.Kind.SCRIPT -> js(text, false, 0, done)
            SyntaxCheck.Kind.MODULE -> js(text, true, 0, done)
            SyntaxCheck.Kind.HTML -> {
                SyntaxCheck.html(text)?.let { done(it); return }
                val scripts = SyntaxCheck.inlineScripts(text)
                fun next(i: Int) {
                    if (i >= scripts.size) { done(null); return }
                    val s = scripts[i]
                    js(s.code, s.module, s.firstLine - 1) { err -> if (err != null) done("an inline <script>, $err") else next(i + 1) }
                }
                next(0)
            }
            SyntaxCheck.Kind.PYTHON -> {
                val cmd = SyntaxCheck.pyCommand(text)
                val sh = shell
                if (cmd == null || sh == null) done(null) else sh(cmd) { code, out -> main.post { done(SyntaxCheck.pyResult(code, out)) } }
            }
            SyntaxCheck.Kind.NONE -> done(null)
        }
    }

    /** Names [text] (the script [path]) reads but never defines, as names.js reports them ("OK" or "name line" lines); null when not checked. */
    fun names(path: String, text: String, done: (String?) -> Unit) {
        if (text.length > SyntaxCheck.MAX_CHARS || !dev.pocketide.app.core.UndefinedNames.checks(path)) { done(null); return }
        val module = SyntaxCheck.kind(path, text) == SyntaxCheck.Kind.MODULE
        withParser {
            val w = web
            if (w == null) { done(null); return@withParser }
            w.evaluateJavascript("(typeof pocketideNames==='function'?pocketideNames(" + dev.pocketide.app.core.MiniJson.quote(text) + "," + module + "):'SKIP')") { r -> done(r) }
        }
    }

    fun close() { main.post { web?.destroy(); web = null; ready = false } }
}
