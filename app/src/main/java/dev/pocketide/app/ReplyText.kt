package dev.pocketide.app

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.widget.TextView
import dev.pocketide.app.core.ChatMarkdown

/**
 * A reply bubble that shows the assistant's Markdown formatted (1.5.127; parsing in core/ChatMarkdown.kt). Every way a reply's
 * text is set, including while it streams in, goes through setText, so plain strings are formatted here and nowhere else.
 * Text that is already styled (a Spanned) is shown as given.
 */
class ReplyText(ctx: Context) : TextView(ctx) {
    override fun setText(text: CharSequence?, type: BufferType?) {
        if (text == null || text is Spanned || text.length > 60_000 || !looksMarked(text)) { super.setText(text, type); return }
        val doc = ChatMarkdown.parse(text.toString())
        val out = SpannableString(doc.text)
        val codeBg = 0x33FFFFFF
        for (sp in doc.spans) {
            if (sp.start >= sp.end || sp.end > out.length) continue
            val f = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            when (sp.kind) {
                ChatMarkdown.Kind.BOLD -> out.setSpan(StyleSpan(Typeface.BOLD), sp.start, sp.end, f)
                ChatMarkdown.Kind.ITALIC -> out.setSpan(StyleSpan(Typeface.ITALIC), sp.start, sp.end, f)
                ChatMarkdown.Kind.CODE, ChatMarkdown.Kind.CODE_BLOCK -> {
                    out.setSpan(TypefaceSpan("monospace"), sp.start, sp.end, f)
                    out.setSpan(BackgroundColorSpan(codeBg), sp.start, sp.end, f)
                }
                ChatMarkdown.Kind.HEADING -> {
                    out.setSpan(StyleSpan(Typeface.BOLD), sp.start, sp.end, f)
                    out.setSpan(RelativeSizeSpan(1.12f), sp.start, sp.end, f)
                }
                ChatMarkdown.Kind.QUOTE -> out.setSpan(ForegroundColorSpan(Ui.MUTED), sp.start, sp.end, f)
                ChatMarkdown.Kind.LINK -> out.setSpan(URLSpan(sp.url), sp.start, sp.end, f)
            }
        }
        super.setText(out, BufferType.SPANNABLE)
    }

    private fun looksMarked(t: CharSequence): Boolean {
        for (c in t) if (c == '*' || c == '_' || c == '`' || c == '#' || c == '[' || c == '>' || c == '-' || c == '~') return true
        return false
    }
}
