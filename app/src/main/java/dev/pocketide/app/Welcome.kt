package dev.pocketide.app

import android.app.Activity
import android.app.AlertDialog
import android.widget.ScrollView
import android.widget.TextView

/** First-run choice of how the AI runs. Shown once; everything is reachable later under ⋮. */
object Welcome {
    fun showIfFirstRun(activity: Activity, settings: AppSettings, openKeys: () -> Unit, openModels: () -> Unit) {
        if (settings.welcomed) return
        settings.welcomed = true
        show(activity, openKeys, openModels)
    }

    fun show(activity: Activity, openKeys: () -> Unit, openModels: () -> Unit) {
        val ctx = activity
        val box = Ui.vertical(ctx)
        val pad = Ui.dp(ctx, 20)
        box.setPadding(pad, Ui.dp(ctx, 4), pad, Ui.dp(ctx, 8))
        box.addView(Ui.text(ctx, "PocketIDE writes and fixes code with an AI model. Pick how that AI runs; you can change it any time in Settings.", 14f, Ui.MUTED))

        lateinit var dlg: AlertDialog
        fun option(title: String, detail: String, primary: Boolean, action: () -> Unit) {
            val card = Ui.vertical(ctx)
            card.background = Ui.rounded(if (primary) Ui.SELECTED else Ui.SURFACE2, 12, ctx, if (primary) Ui.ACCENT else Ui.LINE)
            card.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12))
            card.addView(Ui.text(ctx, title, 15f, Ui.TEXT))
            card.addView(Ui.text(ctx, detail, 13f, Ui.MUTED), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 2) })
            card.isClickable = true
            card.setOnClickListener { dlg.dismiss(); action() }
            box.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 10) })
        }
        option("Free cloud AI (recommended)", "Get a free key from Groq, Gemini and others in about two minutes. Best answers.", true, openKeys)
        option("Local model on this phone", "Download a model and run it offline with Ollama. Private, but slower and weaker.", false, openModels)
        option("Just try the demo", "Sample replies, no key needed, so you can see how changes are reviewed.", false) {}
        val hint: TextView = Ui.text(ctx, "Find this again under ⋮ > Help & FAQ.", 12f, Ui.MUTED)
        box.addView(hint, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 12) })
        val scroll = ScrollView(ctx)
        scroll.addView(box)
        dlg = AlertDialog.Builder(ctx).setTitle("Welcome to PocketIDE").setView(scroll).create()
        dlg.show()
    }
}
