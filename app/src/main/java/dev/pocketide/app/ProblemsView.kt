package dev.pocketide.app

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pocketide.app.core.Problem
import dev.pocketide.app.core.Problems
import dev.pocketide.app.core.Severity

/**
 * v0.4 W3: the Problems list (bottom of the Terminal tab). Shows errors and warnings parsed from the
 * last run or check; tap one to open the file at that line, or ask the AI to fix them all.
 * Edition-neutral (no Linux classes).
 */
class ProblemsView(private val ctx: Context) : LinearLayout(ctx) {
    private val title = Ui.text(ctx, "", 13f, Ui.TEXT)
    private val toggle = Ui.text(ctx, "Hide", 13f, Ui.ACCENT)
    private val ask = Ui.text(ctx, "Ask AI to fix", 13f, Ui.ACCENT)
    private val clear = Ui.text(ctx, "Clear", 13f, Ui.ACCENT)
    private val list = Ui.vertical(ctx)
    private val scroll = ScrollView(ctx)
    private var expanded = true

    var problems: List<Problem> = emptyList()
        private set
    private var output = ""
    private var command: String? = null

    /** Opens a file at a line (and column). Wired by MainActivity. */
    var onOpenAt: ((String, Int, Int) -> Unit)? = null
    /** Sends text to the AI chat. Wired by MainActivity. */
    var onAskAi: ((String) -> Unit)? = null
    /** Called with the new count whenever the list changes (for a badge on the Terminal tab). */
    var onChanged: ((Int) -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.SURFACE)
        val head = Ui.horizontal(ctx)
        head.setPadding(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 4), 0)
        title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        head.addView(title, Ui.lp(0, Ui.WRAP, 1f))
        for (b in listOf(ask, clear, toggle)) {
            b.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 10))
            head.addView(b)
        }
        addView(head, Ui.lp(Ui.MATCH, Ui.WRAP))
        scroll.addView(list)
        addView(scroll, Ui.lp(Ui.MATCH, Ui.WRAP))
        ask.setOnClickListener { if (problems.isNotEmpty()) onAskAi?.invoke(Problems.askText(problems, output, command)) }
        clear.setOnClickListener { show(emptyList(), "", null) }
        toggle.setOnClickListener { expanded = !expanded; paint() }
        visibility = GONE
    }

    /** Replaces the list. An empty list hides the panel. */
    fun show(items: List<Problem>, fromOutput: String, fromCommand: String?) {
        problems = items
        output = fromOutput
        command = fromCommand
        if (items.isNotEmpty()) expanded = true
        paint()
        onChanged?.invoke(items.size)
    }

    /** Drops the problems that were in these files: they were rewritten, so the old messages no longer apply. */
    fun dropForFiles(paths: Collection<String>) {
        if (problems.isEmpty() || paths.isEmpty()) return
        val gone = paths.map { it.trim().removePrefix("./").removePrefix("/") }.toSet()
        val left = problems.filter { it.file.trim().removePrefix("./").removePrefix("/") !in gone }
        if (left.size != problems.size) show(left, output, command)
    }

    private fun paint() {
        visibility = if (problems.isEmpty()) GONE else VISIBLE
        val errors = problems.count { it.severity == Severity.ERROR }
        val warnings = problems.size - errors
        title.text = buildString {
            append("⚠ ")
            if (errors > 0) append("$errors error${if (errors == 1) "" else "s"}")
            if (errors > 0 && warnings > 0) append(", ")
            if (warnings > 0) append("$warnings warning${if (warnings == 1) "" else "s"}")
        }
        title.setTextColor(if (errors > 0) Ui.BAD else Ui.TEXT)
        toggle.text = if (expanded) "Hide" else "Show"
        ask.visibility = if (onAskAi != null) VISIBLE else GONE
        list.removeAllViews()
        scroll.visibility = if (expanded) VISIBLE else GONE
        if (!expanded) return
        // keep the panel from taking over the terminal: about 4 rows visible, the rest scrolls
        scroll.layoutParams = (scroll.layoutParams as LayoutParams).also { it.height = if (problems.size > 4) Ui.dp(ctx, 190) else LayoutParams.WRAP_CONTENT }
        for (p in problems) {
            val row = Ui.vertical(ctx)
            row.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 6), Ui.dp(ctx, 12), Ui.dp(ctx, 6))
            row.isClickable = true
            row.setBackgroundResource(android.R.drawable.list_selector_background)
            val where = Ui.text(ctx, (if (p.severity == Severity.ERROR) "✕ " else "△ ") + p.where(), 12f,
                if (p.severity == Severity.ERROR) Ui.BAD else Ui.MUTED, true)
            val msg = Ui.text(ctx, p.message, 13f, Ui.TEXT)
            msg.maxLines = 3
            msg.ellipsize = TextUtils.TruncateAt.END
            row.addView(where)
            row.addView(msg)
            row.setOnClickListener { onOpenAt?.invoke(p.file, p.line, p.col) }
            list.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
            val line = View(ctx)
            line.setBackgroundColor(Ui.LINE)
            list.addView(line, Ui.lp(Ui.MATCH, 1))
        }
    }
}
