package dev.pocketide.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.EditText
import dev.pocketide.app.core.Brackets
import dev.pocketide.app.core.Highlighter
import dev.pocketide.app.core.TokenKind

/** Colours for the editor area. The rest of the app stays dark. */
data class EditorTheme(
    val bg: Int, val text: Int, val gutterBg: Int, val gutterText: Int, val line: Int, val bracket: Int, val ghost: Int,
    val comment: Int, val string: Int, val number: Int, val keyword: Int, val tag: Int
) {
    companion object {
        val DARK = EditorTheme(
            bg = Ui.BG, text = Ui.TEXT, gutterBg = Ui.BG, gutterText = Ui.MUTED, line = Ui.LINE, bracket = 0x553A4A80, ghost = 0xFF7A8294.toInt(),
            comment = Ui.SYN_COMMENT, string = Ui.SYN_STRING, number = Ui.SYN_NUMBER, keyword = Ui.SYN_KEYWORD, tag = Ui.SYN_TAG
        )
        val LIGHT = EditorTheme(
            bg = 0xFFFBFBFD.toInt(), text = 0xFF1F2328.toInt(), gutterBg = 0xFFF1F3F6.toInt(), gutterText = 0xFF8A93A0.toInt(),
            line = 0xFFDDE1E6.toInt(), bracket = 0x553D7BFF, ghost = 0xFF9AA2AE.toInt(),
            comment = 0xFF6A737D.toInt(), string = 0xFF22863A.toInt(), number = 0xFFB35900.toInt(), keyword = 0xFF8250DF.toInt(), tag = 0xFF0550AE.toInt()
        )
        fun named(name: String) = if (name == "light") LIGHT else DARK
    }
}

/** A code editor: line numbers, colours, auto-indent, undo, bracket matching and inline suggestions, on top of the plain Android text field. */
class CodeEditText(ctx: Context) : EditText(ctx) {
    private class SyntaxSpan(color: Int) : ForegroundColorSpan(color)
    private class BracketSpan(color: Int) : BackgroundColorSpan(color)
    /** Grey italic text that is shown but not part of the file until accepted. */
    private class GhostSpan(private val color: Int) : CharacterStyle() {
        override fun updateDrawState(tp: TextPaint) { tp.color = color; tp.textSkewX = -0.2f }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gutterBgPaint = Paint()
    private val linePaint = Paint()
    private val rect = Rect()
    private var gutterWidth = Ui.dp(ctx, 44)

    private var path = ""
    private var applying = false
    private val history = ArrayList<String>()
    private var index = 0
    private var trackHistory = true
    private var theme = EditorTheme.DARK
    private var ghost: GhostSpan? = null
    private var wrap = true
    /** False while the base EditText constructor runs (it calls onSelectionChanged before our fields exist). */
    private var ready = false

    /** Called when the user (not a program) changes the text. */
    var onUserEdit: (() -> Unit)? = null
    /** Called after a pinch changes the font size, with the new size in sp. */
    var onFontSizeChanged: ((Float) -> Unit)? = null
    /** Called when a shown suggestion is accepted or dismissed. */
    var onSuggestionGone: (() -> Unit)? = null

    private val highlightRun = Runnable { applyHighlight(); updateGutter() }
    private val commitRun = Runnable { commitHistory() }
    private val dismissRun = Runnable { dismissSuggestion() }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val px = textSize * d.scaleFactor
            val sp = (px / resources.displayMetrics.scaledDensity).coerceIn(8f, 28f)
            if (kotlin.math.abs(sp - currentSp()) >= 0.25f) { setFontSize(sp); onFontSizeChanged?.invoke(sp) }
            return true
        }
    })

    init {
        typeface = Typeface.MONOSPACE
        textSize = 13f
        setHintTextColor(Ui.MUTED)
        background = null
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        setHorizontallyScrolling(false)
        isVerticalScrollBarEnabled = false
        setLineSpacing(0f, 1.15f)
        gutterPaint.typeface = Typeface.MONOSPACE
        gutterPaint.textAlign = Paint.Align.RIGHT
        applyTheme(EditorTheme.DARK)
        updateGutter()
        filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
            if (!applying && end - start == 1 && source[start] == '\n') indentAfterNewline(dest, dstart, dend) else null
        })
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (applying) return
                // typing while a suggestion shows: drop the suggestion (after this edit finishes)
                if (ghost != null) { handler.removeCallbacks(dismissRun); handler.post(dismissRun) }
                handler.removeCallbacks(highlightRun)
                handler.postDelayed(highlightRun, 120)
                if (trackHistory) {
                    handler.removeCallbacks(commitRun)
                    handler.postDelayed(commitRun, 450)
                }
                onUserEdit?.invoke()
            }
        })
        ready = true
    }

    private fun currentSp(): Float = textSize / resources.displayMetrics.scaledDensity

    // ---- appearance ----

    fun applyTheme(t: EditorTheme) {
        theme = t
        setBackgroundColor(t.bg)
        setTextColor(t.text)
        gutterPaint.color = t.gutterText
        gutterBgPaint.color = t.gutterBg
        linePaint.color = t.line
        highlightColor = (t.bracket and 0x00FFFFFF) or 0x66000000
        applyHighlight()
        updateBrackets()
        invalidate()
    }

    fun setFontSize(sp: Float) {
        textSize = sp.coerceIn(8f, 28f)
        updateGutter()
    }

    fun setWrap(on: Boolean) {
        wrap = on
        setHorizontallyScrolling(!on)
        requestLayout()
        invalidate()
    }

    private fun updateGutter() {
        gutterPaint.textSize = textSize * 0.85f
        val digits = maxOf(3, (lineCountOf(text ?: "").toString().length))
        val w = maxOf(Ui.dp(context, 36), (gutterPaint.measureText("0".repeat(digits)) + Ui.dp(context, 16)).toInt())
        if (w == gutterWidth && paddingLeft == w + Ui.dp(context, 8)) return
        gutterWidth = w
        setPadding(gutterWidth + Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 120))
    }

    private fun lineCountOf(s: CharSequence): Int { var n = 1; for (c in s) if (c == '\n') n++; return n }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 2 || scaler.isInProgress) {
            scaler.onTouchEvent(event)
            return true
        }
        return super.onTouchEvent(event)
    }

    // ---- editing helpers ----

    private fun indentAfterNewline(dest: CharSequence, dstart: Int, dend: Int): CharSequence {
        var ls = dstart
        while (ls > 0 && dest[ls - 1] != '\n') ls--
        var i = ls
        while (i < dstart && (dest[i] == ' ' || dest[i] == '\t')) i++
        val indent = dest.subSequence(ls, i).toString()
        val prev = if (dstart > 0) dest[dstart - 1] else ' '
        val extra = if (prev == '{' || prev == '(' || prev == '[' || prev == ':') "  " else ""
        return "\n" + indent + extra
    }

    /** Replaces the whole text, for example when a file is opened. Not recorded as an edit. */
    fun load(newPath: String, text: String) {
        path = newPath
        ghost = null
        handler.removeCallbacks(highlightRun)
        handler.removeCallbacks(commitRun)
        applying = true
        setText(text)
        setSelection(0)
        applying = false
        trackHistory = text.length < 300_000
        history.clear()
        history.add(text)
        index = 0
        updateGutter()
        applyHighlight()
    }

    /** The file's text, without a suggestion that is only being shown. */
    fun content(): String {
        val e = text ?: return ""
        val g = ghost ?: return e.toString()
        val a = e.getSpanStart(g)
        val b = e.getSpanEnd(g)
        if (a < 0 || b < a) return e.toString()
        return e.substring(0, a) + e.substring(b, e.length)
    }

    private fun applyHighlight() {
        val e = text ?: return
        applying = true
        try {
            for (s in e.getSpans(0, e.length, SyntaxSpan::class.java)) e.removeSpan(s)
            val src = e.toString()
            val g = ghost
            val ga = if (g != null) e.getSpanStart(g) else -1
            val gb = if (g != null) e.getSpanEnd(g) else -1
            for (sp in Highlighter.spans(path, src)) {
                if (ga >= 0 && sp.start < gb && sp.end > ga) continue
                val color = when (sp.kind) {
                    TokenKind.COMMENT -> theme.comment
                    TokenKind.STRING -> theme.string
                    TokenKind.NUMBER -> theme.number
                    TokenKind.KEYWORD -> theme.keyword
                    TokenKind.TAG -> theme.tag
                }
                e.setSpan(SyntaxSpan(color), sp.start, sp.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } finally {
            applying = false
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (ready) updateBrackets()
    }

    private fun updateBrackets() {
        val e = text ?: return
        for (s in e.getSpans(0, e.length, BracketSpan::class.java)) e.removeSpan(s)
        val a = selectionStart
        if (a < 0 || a != selectionEnd || ghost != null) return
        val m = Brackets.match(e, a) ?: return
        e.setSpan(BracketSpan(theme.bracket), m.first, m.first + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        e.setSpan(BracketSpan(theme.bracket), m.second, m.second + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun commitHistory() {
        val now = content()
        if (history.isEmpty() || now == history[index]) return
        while (history.size > index + 1) history.removeAt(history.size - 1)
        history.add(now)
        index++
        if (history.size > 80) { history.removeAt(0); index-- }
    }

    private fun restore(textValue: String) {
        val sel = selectionStart.coerceAtLeast(0)
        applying = true
        setText(textValue)
        setSelection(sel.coerceAtMost(textValue.length))
        applying = false
        applyHighlight()
        onUserEdit?.invoke()
    }

    fun undo(): Boolean {
        dismissSuggestion()
        if (!trackHistory) return false
        handler.removeCallbacks(commitRun)
        commitHistory()
        if (index <= 0) return false
        index--
        restore(history[index])
        return true
    }

    fun redo(): Boolean {
        dismissSuggestion()
        if (!trackHistory) return false
        handler.removeCallbacks(commitRun)
        commitHistory()
        if (index >= history.size - 1) return false
        index++
        restore(history[index])
        return true
    }

    fun insert(s: String) {
        dismissSuggestion()
        val e = text ?: return
        val a = selectionStart.coerceAtLeast(0)
        val b = selectionEnd.coerceAtLeast(a)
        e.replace(a, b, s)
    }

    // ---- inline suggestions ----

    val hasSuggestion: Boolean get() = ghost != null

    /** Cursor position, or -1 when there is a selection. */
    fun caret(): Int = if (selectionStart >= 0 && selectionStart == selectionEnd) selectionStart else -1

    /** Shows text at the cursor as a grey suggestion. The cursor stays before it. */
    fun showSuggestion(at: Int, s: String) {
        dismissSuggestion()
        val e = text ?: return
        if (s.isEmpty() || at < 0 || at > e.length) return
        applying = true
        try {
            e.insert(at, s)
            val g = GhostSpan(theme.ghost)
            e.setSpan(g, at, at + s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ghost = g
            setSelection(at)
        } finally { applying = false }
        updateBrackets()
    }

    /** Makes the suggestion part of the file. */
    fun acceptSuggestion(): Boolean {
        val g = ghost ?: return false
        val e = text ?: return false
        val b = e.getSpanEnd(g)
        e.removeSpan(g)
        ghost = null
        if (b >= 0) setSelection(b.coerceAtMost(e.length))
        applyHighlight()
        if (trackHistory) { handler.removeCallbacks(commitRun); commitHistory() }
        updateGutter()
        onUserEdit?.invoke()
        onSuggestionGone?.invoke()
        return true
    }

    /** Takes the next word of the suggestion; the rest stays grey. Finishes the suggestion when nothing is left. */
    fun acceptWord(): Boolean {
        val g = ghost ?: return false
        val e = text ?: return false
        val a = e.getSpanStart(g); val b = e.getSpanEnd(g)
        if (a < 0 || b <= a) return acceptSuggestion()
        val n = dev.pocketide.app.core.SuggestionWords.nextLength(e.subSequence(a, b).toString())
        if (a + n >= b) return acceptSuggestion()
        e.removeSpan(g)
        e.setSpan(g, a + n, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSelection(a + n)
        applyHighlight()
        onUserEdit?.invoke()
        return true
    }

    fun dismissSuggestion() {
        val g = ghost ?: return
        val e = text ?: return
        val a = e.getSpanStart(g)
        val b = e.getSpanEnd(g)
        ghost = null
        e.removeSpan(g)
        if (a >= 0 && b > a) {
            applying = true
            try { e.delete(a, b) } finally { applying = false }
        }
        onSuggestionGone?.invoke()
    }

    // ---- navigation ----

    /** Puts the cursor at the start of a 1-based line (plus a 1-based column) and returns the line's top in pixels. */
    fun goToLine(line: Int, col: Int = 1): Int {
        dismissSuggestion()
        val e = text ?: return 0
        val start = Brackets.offsetOfLine(e, line)
        var end = e.indexOf('\n', start).let { if (it < 0) e.length else it }
        end = end.coerceAtLeast(start)
        val at = (start + (col - 1).coerceAtLeast(0)).coerceAtMost(end)
        requestFocus()
        setSelection(at)
        val l = layout ?: return 0
        return l.getLineTop(l.getLineForOffset(at)) + totalPaddingTop
    }

    fun currentLine(): Int = Brackets.lineOf(text ?: "", selectionStart.coerceAtLeast(0))

    fun lineCountText(): Int = lineCountOf(text ?: "")

    /** Selects the next match after the cursor, wrapping round. Returns false if there is none. */
    fun findNext(query: String): Boolean {
        if (query.isEmpty()) return false
        dismissSuggestion()
        val s = content()
        val from = selectionEnd.coerceAtLeast(0)
        var at = s.indexOf(query, from, ignoreCase = true)
        if (at < 0) at = s.indexOf(query, 0, ignoreCase = true)
        if (at < 0) return false
        requestFocus()
        setSelection(at, at + query.length)
        return true
    }

    fun replaceAll(query: String, with: String): Int {
        if (query.isEmpty()) return 0
        dismissSuggestion()
        val s = content()
        var n = 0
        var from = 0
        while (true) {
            val at = s.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            n++
            from = at + query.length
        }
        if (n == 0) return 0
        val out = StringBuilder()
        from = 0
        while (true) {
            val at = s.indexOf(query, from, ignoreCase = true)
            if (at < 0) { out.append(s, from, s.length); break }
            out.append(s, from, at).append(with)
            from = at + query.length
        }
        val sel = selectionStart.coerceAtLeast(0)
        setText(out.toString())
        setSelection(sel.coerceAtMost(out.length))
        return n
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The gutter is drawn on top of the text, at the left edge of what is visible, so it stays put
        // when long lines scroll sideways (word wrap off).
        val l = layout ?: return
        if (l.lineCount == 0) return
        val clip = canvas.clipBounds
        val sx = scrollX.toFloat()
        canvas.drawRect(sx, clip.top.toFloat(), sx + gutterWidth, clip.bottom.toFloat(), gutterBgPaint)
        val first = l.getLineForVertical((clip.top - totalPaddingTop).coerceAtLeast(0))
        val last = l.getLineForVertical((clip.bottom - totalPaddingTop).coerceAtLeast(0))
        val src = text
        // number is the label for the next line that begins a paragraph. If the first visible row
        // continues a wrapped paragraph, that paragraph already has its label above the screen.
        var number = 1
        val firstStart = l.getLineStart(first)
        for (k in 0 until firstStart) if (src[k] == '\n') number++
        if (first > 0 && src[firstStart - 1] != '\n') number++
        canvas.drawLine(sx + gutterWidth, clip.top.toFloat(), sx + gutterWidth, clip.bottom.toFloat(), linePaint)
        for (i in first..last) {
            val start = l.getLineStart(i)
            val startsLine = i == 0 || src[start - 1] == '\n'
            if (startsLine) {
                val baseline = getLineBounds(i, rect)
                canvas.drawText(number.toString(), sx + gutterWidth - Ui.dp(context, 8), baseline.toFloat(), gutterPaint)
                number++
            }
        }
    }
}
