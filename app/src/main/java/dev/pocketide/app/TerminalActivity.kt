package dev.pocketide.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

/**
 * GPL route: a full-screen interactive terminal built on Termux's terminal-emulator and
 * terminal-view libraries. It opens a login shell in Alpine Linux (through PRoot) in the
 * project folder, or Android's own shell when Linux is not installed.
 */
class TerminalActivity : Activity(), TerminalSessionClient, TerminalViewClient {
    private lateinit var view: TerminalView
    private var session: TerminalSession? = null
    private var ctrl = false
    private var alt = false
    private lateinit var ctrlKey: TextView
    private lateinit var altKey: TextView
    private var fontPx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReport.install(this)
        val settings = AppSettings(this)
        val linux = LinuxRuntime(this, settings)
        val project = Projects.current(this)
        val home = File(filesDir, "home").also { it.mkdirs() }

        val root = Ui.vertical(this)
        root.setBackgroundColor(0xFF000000.toInt())

        val bar = Ui.horizontal(this)
        bar.setBackgroundColor(Ui.SURFACE)
        bar.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0)
        val title = Ui.text(this, if (linux.active) "Alpine Linux · /work" else "Android shell · project folder", 13f, Ui.MUTED)
        bar.addView(title, Ui.lp(0, Ui.WRAP, 1f))
        val close = Ui.text(this, "Close", 13f, Ui.ACCENT)
        close.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12))
        close.setOnClickListener { finish() }
        bar.addView(close)
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))

        view = TerminalView(this, null)
        view.setTerminalViewClient(this)
        // setTextSize creates the renderer, so it must come before setTypeface.
        fontPx = Ui.dp(this, 12)
        view.setTextSize(fontPx)
        view.setTypeface(Typeface.MONOSPACE)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        root.addView(view, Ui.lp(Ui.MATCH, 0, 1f))
        root.addView(extraKeys(), Ui.lp(Ui.MATCH, Ui.WRAP))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                WindowInsets.CONSUMED
            }
        }

        val base = linkedMapOf(
            "HOME" to home.absolutePath, "TMPDIR" to cacheDir.absolutePath,
            "TERM" to "xterm-256color", "LANG" to "C.UTF-8", "PATH" to "/system/bin:/system/xbin"
        )
        val s = if (linux.active) {
            val (args, env) = linux.shell(project)
            base.putAll(env)
            TerminalSession(args[0], project.absolutePath, args.toTypedArray(), envArray(base), 2000, this)
        } else {
            TerminalSession("/system/bin/sh", project.absolutePath, arrayOf("-sh"), envArray(base), 2000, this)
        }
        session = s
        view.attachSession(s)
        view.requestFocus()
    }

    private fun envArray(m: Map<String, String>) = m.map { (k, v) -> "$k=$v" }.toTypedArray()

    private fun extraKeys(): HorizontalScrollView {
        val row = Ui.horizontal(this)
        row.setBackgroundColor(Ui.SURFACE)
        fun key(label: String, action: () -> Unit): TextView {
            val t = Ui.text(this, label, 14f, Ui.TEXT, true)
            t.gravity = Gravity.CENTER
            t.minWidth = Ui.dp(this, 44)
            t.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10))
            t.setOnClickListener { action(); view.requestFocus() }
            row.addView(t)
            return t
        }
        key("Esc") { special(KeyEvent.KEYCODE_ESCAPE) }
        key("Tab") { special(KeyEvent.KEYCODE_TAB) }
        ctrlKey = key("Ctrl") { ctrl = !ctrl; paintMods() }
        altKey = key("Alt") { alt = !alt; paintMods() }
        key("←") { special(KeyEvent.KEYCODE_DPAD_LEFT) }
        key("↓") { special(KeyEvent.KEYCODE_DPAD_DOWN) }
        key("↑") { special(KeyEvent.KEYCODE_DPAD_UP) }
        key("→") { special(KeyEvent.KEYCODE_DPAD_RIGHT) }
        for (c in listOf("|", "-", "/", "~", "_", "*", "&", ">")) key(c) { char(c[0].code) }
        key("Home") { special(KeyEvent.KEYCODE_MOVE_HOME) }
        key("End") { special(KeyEvent.KEYCODE_MOVE_END) }
        key("PgUp") { special(KeyEvent.KEYCODE_PAGE_UP) }
        key("PgDn") { special(KeyEvent.KEYCODE_PAGE_DOWN) }
        key("Paste") { onPasteTextFromClipboard(session) }
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(row)
        return scroll
    }

    private fun paintMods() {
        ctrlKey.setTextColor(if (ctrl) Ui.ACCENT else Ui.TEXT)
        altKey.setTextColor(if (alt) Ui.ACCENT else Ui.TEXT)
    }

    private fun clearMods() {
        if (ctrl || alt) { ctrl = false; alt = false; paintMods() }
    }

    private fun special(keyCode: Int) {
        var mod = 0
        if (ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
        if (alt) mod = mod or KeyHandler.KEYMOD_ALT
        view.handleKeyCode(keyCode, mod)
        clearMods()
    }

    private fun char(cp: Int) {
        view.inputCodePoint(cp, ctrl, alt)
        clearMods()
    }

    private fun showKeyboard() {
        view.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, 0)
    }

    override fun onDestroy() {
        session?.finishIfRunning()
        super.onDestroy()
    }

    // ---- TerminalSessionClient ----
    override fun onTextChanged(changedSession: TerminalSession) = view.onScreenUpdated()
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) { if (!isFinishing) finish() }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        view.mEmulator?.paste(text)
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    // ---- TerminalViewClient ----
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            fontPx = (fontPx * scale).toInt().coerceIn(Ui.dp(this, 7), Ui.dp(this, 28))
            view.setTextSize(fontPx)
            return 1.0f
        }
        return scale
    }
    override fun onSingleTapUp(e: MotionEvent) = showKeyboard()
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey() = ctrl
    override fun readAltKey() = alt
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        // the soft keyboard typed a character: the Ctrl/Alt toggles apply once
        if (ctrl || alt) view.post { clearMods() }
        return false
    }
    override fun onEmulatorSet() {}
    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "terminal", e) }
}
