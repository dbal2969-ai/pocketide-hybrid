package dev.pocketide.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.pocketide.app.core.HelpAction
import dev.pocketide.app.core.HelpContent
import dev.pocketide.app.core.HelpEntry
import dev.pocketide.app.core.HelpSection

/** Help & FAQ: free key guides, local models, GitHub, agent mode, Linux and troubleshooting. */
object HelpScreen {
    /**
     * section: a section id (HelpContent.KEYS …) or an entry id (a provider id such as "groq", or "trouble-429");
     * that part is opened and scrolled to. openSettingsFor opens Settings at a provider ("Paste key in Settings").
     * openModels opens the Models screen.
     */
    fun show(
        activity: Activity,
        section: String? = null,
        openSettingsFor: ((providerId: String) -> Unit)? = null,
        openModels: (() -> Unit)? = null
    ) {
        val fs = FullScreen(activity, "Help & FAQ")
        val ctx = activity

        // search
        val search = EditText(ctx)
        search.hint = "Search help (e.g. 429, ollama, token)"
        search.setHintTextColor(Ui.MUTED)
        search.setTextColor(Ui.TEXT)
        search.textSize = 14f
        search.setSingleLine()
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        search.background = Ui.rounded(Ui.SURFACE, 10, ctx, Ui.LINE)
        search.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10))
        fs.body.addView(search, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })

        // section shortcuts
        val chipsRow = Ui.horizontal(ctx)
        val chipsScroll = HorizontalScrollView(ctx)
        chipsScroll.isHorizontalScrollBarEnabled = false
        chipsScroll.addView(chipsRow)
        fs.body.addView(chipsScroll, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 10) })

        val content = Ui.vertical(ctx)
        fs.body.addView(content, Ui.lp(Ui.MATCH, Ui.WRAP))
        val results = Ui.vertical(ctx)
        results.visibility = View.GONE
        fs.body.addView(results, Ui.lp(Ui.MATCH, Ui.WRAP))

        val sectionViews = HashMap<String, View>()
        val entryViews = HashMap<String, EntryView>()

        fun actions(e: HelpEntry): List<TextView> {
            val out = ArrayList<TextView>()
            if (e.url != null) out += Ui.button(ctx, e.urlLabel, e.action != HelpAction.SETTINGS) { open(activity, e.url) }
            if (e.action == HelpAction.SETTINGS && e.providerId != null && openSettingsFor != null) {
                out += Ui.button(ctx, "Paste key in Settings", true) { fs.dialog.dismiss(); openSettingsFor(e.providerId) }
            }
            if (e.action == HelpAction.MODELS && openModels != null) {
                out += Ui.button(ctx, "Open Models", e.url == null) { fs.dialog.dismiss(); openModels() }
            }
            return out
        }

        fun buildEntry(parent: LinearLayout, s: HelpSection, e: HelpEntry): EntryView {
            val card = fs.card(parent, 8)
            val head = Ui.horizontal(ctx)
            val arrow = Ui.text(ctx, "▸", 14f, Ui.ACCENT)
            val title = Ui.text(ctx, e.title, 15f, Ui.TEXT)
            head.addView(title, Ui.lp(0, Ui.WRAP, 1f))
            head.addView(arrow, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(ctx, 8) })
            card.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP))
            val detail = Ui.vertical(ctx)
            detail.visibility = View.GONE
            val body = Ui.text(ctx, e.body, 14f, Ui.MUTED)
            body.setLineSpacing(0f, 1.15f)
            body.setTextIsSelectable(true)
            detail.addView(body, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 8) })
            for ((i, step) in e.steps.withIndex()) {
                val row = Ui.horizontal(ctx)
                row.gravity = android.view.Gravity.TOP
                val n = Ui.text(ctx, "${i + 1}", 12f, Ui.ACCENT)
                n.gravity = android.view.Gravity.CENTER
                n.background = Ui.rounded(Ui.SURFACE2, 11, ctx, Ui.LINE)
                row.addView(n, Ui.lp(Ui.dp(ctx, 22), Ui.dp(ctx, 22)))
                val st = Ui.text(ctx, step, 14f, Ui.TEXT)
                st.setTextIsSelectable(true)
                row.addView(st, Ui.lp(0, Ui.WRAP, 1f).also { it.leftMargin = Ui.dp(ctx, 10) })
                detail.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 8) })
            }
            val btns = actions(e)
            if (btns.isNotEmpty()) fs.buttonRow(detail, *btns.toTypedArray())
            card.addView(detail, Ui.lp(Ui.MATCH, Ui.WRAP))
            val ev = EntryView(card, detail, arrow)
            head.setOnClickListener { ev.toggle() }
            return ev
        }

        for (s in HelpContent.sections) {
            chipsRow.addView(Ui.chip(ctx, s.title) {
                sectionViews[s.id]?.let { v -> fs.scroll.post { fs.scroll.smoothScrollTo(0, v.top + content.top) } }
            }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
            val head = fs.heading(s.title)
            fs.body.removeView(head)
            content.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 20) })
            sectionViews[s.id] = head
            val intro = Ui.text(ctx, s.intro, 13f, Ui.MUTED)
            intro.setLineSpacing(0f, 1.1f)
            content.addView(intro, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
            for (e in s.entries) entryViews[e.id] = buildEntry(content, s, e)
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(ed: Editable?) {
                val q = ed?.toString()?.trim() ?: ""
                results.removeAllViews()
                if (q.length < 2) {
                    results.visibility = View.GONE
                    content.visibility = View.VISIBLE
                    chipsScroll.visibility = View.VISIBLE
                    return
                }
                content.visibility = View.GONE
                chipsScroll.visibility = View.GONE
                results.visibility = View.VISIBLE
                val hits = HelpContent.search(q)
                if (hits.isEmpty()) {
                    results.addView(Ui.text(ctx, "Nothing found for \"$q\". Try a shorter word, an error number like 401 or 429, or a provider name.", 14f, Ui.MUTED),
                        Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 16) })
                } else for ((s, e) in hits) {
                    results.addView(Ui.text(ctx, s.title, 12f, Ui.MUTED), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 12) })
                    buildEntry(results, s, e).toggle()
                }
            }
        })

        fs.show()

        // open the requested part
        HelpContent.locate(section)?.let { (s, e) ->
            val target: View? = if (e != null) entryViews[e.id]?.also { it.expand() }?.card else sectionViews[s.id]
            if (target != null) fs.scroll.post { fs.scroll.scrollTo(0, (target.top + content.top - Ui.dp(ctx, 12)).coerceAtLeast(0)) }
        }
    }

    private class EntryView(val card: View, val detail: View, val arrow: TextView) {
        fun expand() { detail.visibility = View.VISIBLE; arrow.text = "▾" }
        fun toggle() {
            if (detail.visibility == View.VISIBLE) { detail.visibility = View.GONE; arrow.text = "▸" } else expand()
        }
    }

    fun open(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(activity, "No browser found to open $url", Toast.LENGTH_LONG).show()
        }
    }
}
