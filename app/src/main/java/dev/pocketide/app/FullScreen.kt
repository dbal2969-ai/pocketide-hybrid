package dev.pocketide.app

import android.app.Activity
import android.app.Dialog
import android.os.Build
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A full-screen dialog with a title bar and a Close button, used by the Models and Help screens. */
class FullScreen(val activity: Activity, title: String) {
    val dialog = Dialog(activity, android.R.style.Theme_Material_NoActionBar)
    val root: LinearLayout = Ui.vertical(activity)
    val bar: LinearLayout = Ui.horizontal(activity)
    val titleView: TextView = Ui.text(activity, title, 16f, Ui.TEXT)
    val scroll = ScrollView(activity)
    /** Put the screen's content here; it scrolls. */
    val body: LinearLayout = Ui.vertical(activity)
    /** Runs before the dialog closes; return false to keep it open. */
    var onCloseRequest: () -> Boolean = { true }

    init {
        root.setBackgroundColor(Ui.BG)
        bar.setBackgroundColor(Ui.SURFACE)
        bar.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 2), Ui.dp(activity, 4), Ui.dp(activity, 2))
        titleView.typeface = android.graphics.Typeface.DEFAULT_BOLD
        bar.addView(titleView, Ui.lp(0, Ui.WRAP, 1f))
        val close = Ui.text(activity, "Close", 14f, Ui.ACCENT)
        close.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 14), Ui.dp(activity, 14), Ui.dp(activity, 14))
        close.setOnClickListener { if (onCloseRequest()) dialog.dismiss() }
        bar.addView(close)
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))
        body.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 8), Ui.dp(activity, 16), Ui.dp(activity, 24))
        scroll.addView(body, ViewGroup.LayoutParams(Ui.MATCH, Ui.WRAP))
        root.addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))
        dialog.setContentView(root)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                if (onCloseRequest()) dialog.dismiss()
                true
            } else false
        }
        if (Build.VERSION.SDK_INT >= 30) {
            dialog.window?.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                WindowInsets.CONSUMED
            }
        }
    }

    fun show() = dialog.show()

    // small building blocks shared by the screens

    fun heading(s: String, topDp: Int = 18): TextView {
        val t = Ui.text(activity, s, 13f, Ui.MUTED)
        t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        t.isAllCaps = true
        t.letterSpacing = 0.06f
        body.addView(t, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(activity, topDp) })
        return t
    }

    fun card(parent: LinearLayout = body, topDp: Int = 10): LinearLayout {
        val c = Ui.vertical(activity)
        c.background = Ui.rounded(Ui.SURFACE, 12, activity, Ui.LINE)
        c.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 12), Ui.dp(activity, 14), Ui.dp(activity, 12))
        parent.addView(c, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(activity, topDp) })
        return c
    }

    /** A row of buttons; keep it to two or three short labels so it fits a phone screen. */
    fun buttonRow(parent: LinearLayout, vararg buttons: TextView): LinearLayout {
        val row = Ui.horizontal(activity)
        for ((i, b) in buttons.withIndex()) {
            row.addView(b, Ui.lp(Ui.WRAP, Ui.WRAP).also { if (i > 0) it.leftMargin = Ui.dp(activity, 8) })
        }
        parent.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(activity, 10) })
        return row
    }
}
