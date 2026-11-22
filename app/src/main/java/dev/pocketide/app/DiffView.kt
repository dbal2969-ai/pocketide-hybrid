package dev.pocketide.app

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import dev.pocketide.app.core.Diff

/** Coloured line diff text, shared by the Changes view. */
object DiffView {
    fun render(old: String?, new: String?, maxRows: Int = 400): CharSequence {
        val ops = Diff.lines(old ?: "", new ?: "") ?: return "Large change: ${(new ?: "").lines().size} lines in the new version."
        val rows = Diff.compact(ops)
        if (rows.none { it.tag == '+' || it.tag == '-' }) return "No line changes (only line endings or file mode may differ)."
        val sb = SpannableStringBuilder()
        var shown = 0
        for (r in rows) {
            if (shown >= maxRows) { sb.append("… more changes\n"); break }
            val start = sb.length
            when (r.tag) {
                '+' -> sb.append("+ ").append(r.text)
                '-' -> sb.append("- ").append(r.text)
                '~' -> sb.append("  … ${r.skipped} unchanged lines")
                else -> sb.append("  ").append(r.text)
            }
            sb.append('\n')
            val end = sb.length
            when (r.tag) {
                '+' -> sb.setSpan(BackgroundColorSpan(Ui.ADD_BG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                '-' -> sb.setSpan(BackgroundColorSpan(Ui.DEL_BG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                '~' -> sb.setSpan(ForegroundColorSpan(Ui.MUTED), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            shown++
        }
        return sb
    }

    /** True if the bytes look like text worth diffing. */
    fun isText(b: ByteArray?): Boolean = b == null || (b.size < 2_000_000 && b.take(8000).none { it == 0.toByte() })
}
