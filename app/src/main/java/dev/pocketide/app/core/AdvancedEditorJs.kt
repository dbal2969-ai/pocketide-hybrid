package dev.pocketide.app.core

/**
 * The calls the app makes into the advanced editor's page (1.5.144, Cursor parity P6: CodeMirror 6, MIT, in a WebView;
 * assets/codemirror, built from tools/codemirror). Pure, so the JavaScript it sends is tested on the JVM.
 */
object AdvancedEditorJs {
    const val PAGE = "file:///android_asset/codemirror/page.html"

    /** The page's language key for [path] (by extension); "" gives plain text. */
    fun ext(path: String): String = path.substringAfterLast('/').substringAfterLast('.', "").lowercase().let {
        if (it in setOf("js", "mjs", "cjs", "jsx", "ts", "tsx", "html", "htm", "css", "py", "json", "md")) it else ""
    }

    /** Opens [text] for editing. Every value goes through JSON, so quotes, backslashes and line ends in the file are safe. */
    fun open(text: String, path: String, dark: Boolean, readOnly: Boolean): String =
        "window.PocketCM.open(${MiniJson.write(text)}, ${MiniJson.write(ext(path))}, $dark, $readOnly)"

    /** Runs one of the page's commands: search, next (the next match as another cursor), foldAll, unfoldAll, comment, undo, redo. */
    val COMMANDS = listOf("search", "next", "foldAll", "unfoldAll", "comment", "undo", "redo")
    fun run(name: String): String = require(name in COMMANDS) { name }.let { "window.PocketCM.run(${MiniJson.write(name)})" }

    const val TEXT = "window.PocketCM.text()"

    /** Shown when the page's renderer crashed (1.5.146): the editor closed, and what was saved. */
    const val CRASHED = "The advanced editor stopped unexpectedly. Your text up to the last pause in typing is saved; the file is open in the normal editor."

    /** The text from evaluateJavascript's result (a JSON string literal), or null when it is not one. */
    fun decode(result: String?): String? = try { MiniJson.parse(result ?: return null) as? String } catch (e: Exception) { null }
}
