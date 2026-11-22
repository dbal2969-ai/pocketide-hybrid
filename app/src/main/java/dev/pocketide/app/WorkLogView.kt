package dev.pocketide.app

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.pocketide.app.core.WorkLog

/** How the work log (WorkLog) looks in the chat: folded lines you tap to open, as Copilot and Claude show their thinking. */
object WorkLogView {
    /** "▸ Thought for 12s" with the first line of the thought; tap to read all of it, tap again to fold. */
    fun thought(ctx: Context, item: WorkLog.Item): TextView {
        val t = Ui.text(ctx, "", 13f, Ui.MUTED)
        t.setTypeface(t.typeface, Typeface.ITALIC)
        t.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4))
        val title = WorkLog.thoughtTitle(item)
        val firstLine = item.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        fun show(open: Boolean) {
            t.text = if (open) "▾ $title\n${item.text}" else "▸ $title: $firstLine"
            t.maxLines = if (open) Int.MAX_VALUE else 1
            t.ellipsize = if (open) null else TextUtils.TruncateAt.END
            t.setTextIsSelectable(open)
            t.tag = open
        }
        show(false)
        t.setOnClickListener { show(t.tag != true) }
        return t
    }

    /** A step on one line, the full text when tapped. */
    private fun step(ctx: Context, text: String): TextView {
        val t = Ui.text(ctx, "· " + WorkLog.oneLine(text), 12f, Ui.MUTED, true)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        var open = false
        t.setOnClickListener {
            open = !open
            t.text = if (open) "· $text" else "· " + WorkLog.oneLine(text)
            t.maxLines = if (open) Int.MAX_VALUE else 1
            t.ellipsize = if (open) null else TextUtils.TruncateAt.END
        }
        return t
    }

    /**
     * The saved log under a reply: one folded line ("▸ How it worked: Thought 4 times (1m 20s) · 3 notes · 12 steps"). Opening it
     * lists the thoughts (each folded), the model's words and the steps, in the order they happened. Built on first open, so a long
     * chat loads quickly.
     */
    fun saved(ctx: Context, log: List<WorkLog.Item>, label: String = "How it worked"): View {
        val box = Ui.vertical(ctx)
        val head = Ui.text(ctx, "▸ $label: " + WorkLog.summary(log), 12f, Ui.ACCENT)
        head.setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4))
        val inner = Ui.vertical(ctx)
        inner.setPadding(Ui.dp(ctx, 8), 0, 0, Ui.dp(ctx, 4))
        inner.visibility = View.GONE
        var built = false
        head.setOnClickListener {
            if (!built) {
                built = true
                for (i in log) inner.addView(when (i.kind) {
                    WorkLog.Kind.THINK -> thought(ctx, i)
                    WorkLog.Kind.SAY -> Ui.text(ctx, i.text, 13f, Ui.TEXT).also { it.setTextIsSelectable(true); it.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4), Ui.dp(ctx, 4)) }
                    WorkLog.Kind.STEP -> step(ctx, i.text)
                }, Ui.lp(Ui.MATCH, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            val open = inner.visibility != View.VISIBLE
            inner.visibility = if (open) View.VISIBLE else View.GONE
            head.text = (if (open) "▾ " else "▸ ") + "$label: " + WorkLog.summary(log)
        }
        box.addView(head, Ui.lp(Ui.MATCH, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(inner, Ui.lp(Ui.MATCH, LinearLayout.LayoutParams.WRAP_CONTENT))
        return box
    }
}
