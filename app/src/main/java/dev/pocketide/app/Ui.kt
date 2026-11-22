package dev.pocketide.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Colours and small view helpers. The whole interface is built in code, so there are no layout files. */
object Ui {
    /** The light theme (Settings → Light theme, 1.5.113): read when the screen is built, so a change restarts the screen. */
    @Volatile var light = false
    private fun c(dark: Long, lightColor: Long): Int = (if (light) lightColor else dark).toInt()

    val BG get() = c(0xFF0E1013, 0xFFF6F7F9)
    val SURFACE get() = c(0xFF161A20, 0xFFFFFFFF)
    val SURFACE2 get() = c(0xFF1E242C, 0xFFEEF1F4)
    val LINE get() = c(0xFF2A313A, 0xFFD8DDE3)
    val TEXT get() = c(0xFFE6E9EE, 0xFF1F2328)
    val MUTED get() = c(0xFF8B94A1, 0xFF5F6B7A)
    val ACCENT get() = c(0xFF7C93FF, 0xFF3451E0)
    val GOOD get() = c(0xFF4CC38A, 0xFF1A7F37)
    val BAD get() = c(0xFFFF6B6B, 0xFFCF222E)
    val ADD_BG get() = c(0xFF12301F, 0xFFDAFBE1)
    val DEL_BG get() = c(0xFF3A1A1E, 0xFFFFEBE9)
    /** The person's own chat bubble, and a picked card or row. */
    val USER_BUBBLE get() = c(0xFF26335F, 0xFFDCE4FF)
    val SELECTED get() = c(0xFF1D2547, 0xFFE3E8FF)

    // syntax colours
    val SYN_COMMENT get() = c(0xFF6B7585, 0xFF6A737D)
    val SYN_STRING get() = c(0xFFA5D6A7, 0xFF22863A)
    val SYN_NUMBER get() = c(0xFFFFB86B, 0xFFB35900)
    val SYN_KEYWORD get() = c(0xFFC792EA, 0xFF8250DF)
    val SYN_TAG get() = c(0xFF7FB4FF, 0xFF0550AE)

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, radiusDp: Int, ctx: Context, stroke: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(ctx, radiusDp).toFloat()
        if (stroke != 0) d.setStroke(dp(ctx, 1), stroke)
        return d
    }

    fun text(ctx: Context, s: CharSequence, sizeSp: Float = 14f, color: Int = TEXT, mono: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = sizeSp
        t.setTextColor(color)
        if (mono) t.typeface = Typeface.MONOSPACE
        return t
    }

    /** A flat tappable label that looks like a button. */
    fun button(ctx: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView {
        val t = text(ctx, label, 13f, if (primary) 0xFFFFFFFF.toInt() else TEXT)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(ctx, 14), dp(ctx, 9), dp(ctx, 14), dp(ctx, 9))
        t.minHeight = dp(ctx, 48)   // 48 dp: Android's smallest comfortable touch target (1.5.133, analysis U3)
        t.minWidth = dp(ctx, 48)
        describe(t, label)
        t.background = if (primary) rounded(0xFF4C63E0.toInt(), 10, ctx) else rounded(SURFACE2, 10, ctx, LINE)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    fun chip(ctx: Context, label: String, onClick: () -> Unit): TextView {
        val t = text(ctx, label, 12f, MUTED)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
        t.minHeight = dp(ctx, 48)
        t.minWidth = dp(ctx, 48)
        describe(t, label)
        t.background = rounded(SURFACE, 16, ctx, LINE)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    /** Gives a symbol-only control a spoken name ([name], else one from A11yNames) so screen readers can say what it does. */
    fun describe(v: android.view.View, label: CharSequence, name: String? = null) {
        val n = name ?: dev.pocketide.app.core.A11yNames.name(label) ?: return
        if (v.contentDescription.isNullOrEmpty() || name != null) v.contentDescription = n
    }

    /** A symbol used as a tap target: padded to at least 48 dp and given a spoken name. */
    fun tapTarget(v: TextView, name: String) {
        v.minHeight = dp(v.context, 48); v.minWidth = dp(v.context, 48)
        v.gravity = Gravity.CENTER
        v.contentDescription = name
    }

    fun vertical(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    fun horizontal(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

    val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun gone(v: View) { v.visibility = View.GONE }
    fun show(v: View) { v.visibility = View.VISIBLE }
}
