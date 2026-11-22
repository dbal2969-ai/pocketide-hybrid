package dev.pocketide.app

import android.app.AlertDialog
import android.content.Context
import android.widget.ScrollView
import dev.pocketide.app.core.Providers
import dev.pocketide.app.core.Workflow

/** Choose how big builds use more than one model: single, cascade or critique, with a fast draft model and a strong model. */
object WorkflowScreen {
    private fun usable() = Providers.all.filter { it.id != "demo" }

    fun show(ctx: Context, settings: AppSettings) {
        val col = Ui.vertical(ctx)
        col.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 8))
        lateinit var dlg: AlertDialog
        fun again() { dlg.dismiss(); show(ctx, settings) }

        col.addView(Ui.text(ctx, "Big builds can use two models: a fast one to draft and a strong one to check and fix. Each needs its own provider key (Settings). Reviews are read-only.", 12f, Ui.MUTED))
        val cur = settings.workflow
        for (w in Workflow.ALL) {
            val row = Ui.vertical(ctx)
            row.background = Ui.rounded(if (w == cur) Ui.SELECTED else Ui.SURFACE2, 10, ctx, if (w == cur) Ui.ACCENT else Ui.LINE)
            row.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10))
            row.addView(Ui.text(ctx, (if (w == cur) "● " else "○ ") + Workflow.title(w), 15f, Ui.TEXT))
            row.addView(Ui.text(ctx, Workflow.explain(w), 12f, Ui.MUTED))
            row.setOnClickListener { settings.workflow = w; again() }
            col.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 8) })
        }

        fun slotRow(title: String, get: () -> Workflow.Slot?, put: (Workflow.Slot) -> Unit) {
            val s = get()
            col.addView(Ui.text(ctx, title, 13f, Ui.ACCENT).also { it.setPadding(0, Ui.dp(ctx, 14), 0, Ui.dp(ctx, 2)) })
            col.addView(Ui.text(ctx, s?.let { Providers.byId(it.provider).name + " · " + it.model.ifBlank { Providers.byId(it.provider).model.ifBlank { "default model" } } } ?: "not set (add a second provider key)", 14f, Ui.TEXT))
            col.addView(Ui.chip(ctx, "Change") {
                val list = usable().filter { settings.key(it.id).isNotBlank() || it.keyless }
                if (list.isEmpty()) { android.widget.Toast.makeText(ctx, "Add a provider key in Settings first.", android.widget.Toast.LENGTH_SHORT).show(); return@chip }
                AlertDialog.Builder(ctx).setTitle("Provider").setItems(list.map { it.name }.toTypedArray()) { _, i ->
                    val p = list[i]
                    ModelPicker.pick(ctx, p, settings.key(p.id), settings.baseFor(p.id), s?.takeIf { it.provider == p.id }?.model ?: settings.model(p.id)) { m ->
                        put(Workflow.Slot(p.id, m)); again()
                    }
                }.setNegativeButton("Cancel", null).show()
            }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
        }
        slotRow("DRAFT MODEL (fast, cheap)", { settings.draftSlot() }) { settings.setSlot(true, it) }
        slotRow("STRONG MODEL (checks and fixes)", { settings.strongSlot() }) { settings.setSlot(false, it) }

        val d = settings.draftSlot(); val s = settings.strongSlot()
        if (cur != Workflow.SINGLE && !Workflow.distinct(d, s))
            col.addView(Ui.text(ctx, "Both slots use the same model, so builds run as Single until you pick two different ones.", 12f, Ui.BAD).also { it.setPadding(0, Ui.dp(ctx, 12), 0, 0) })
        // ---- advisor ----
        col.addView(Ui.text(ctx, "ADVISOR", 13f, Ui.ACCENT).also { it.setPadding(0, Ui.dp(ctx, 20), 0, Ui.dp(ctx, 2)) })
        col.addView(Ui.text(ctx, "A stronger model the working model can consult, with any provider. It sees the whole conversation and gives short guidance. Needs a model with tool support.", 12f, Ui.MUTED))
        val am = settings.advisorMode
        for (m in dev.pocketide.app.core.Advisor.ALL) {
            val row = Ui.vertical(ctx)
            row.background = Ui.rounded(if (m == am) Ui.SELECTED else Ui.SURFACE2, 10, ctx, if (m == am) Ui.ACCENT else Ui.LINE)
            row.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10))
            row.addView(Ui.text(ctx, (if (m == am) "● " else "○ ") + dev.pocketide.app.core.Advisor.title(m), 15f, Ui.TEXT))
            row.addView(Ui.text(ctx, dev.pocketide.app.core.Advisor.explain(m), 12f, Ui.MUTED))
            row.setOnClickListener { settings.advisorMode = m; again() }
            col.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 8) })
        }
        if (am != dev.pocketide.app.core.Advisor.OFF) {
            slotRow("ADVISOR MODEL (strongest you have)", { settings.advisorSlot() }) { settings.setAdvisorSlot(it) }
            col.addView(Ui.chip(ctx, "Advisor calls per request: ${settings.advisorCalls}  ▾") {
                val choices = dev.pocketide.app.core.Advisor.CALL_CHOICES
                AlertDialog.Builder(ctx).setTitle("Advisor calls per request").setItems(choices.map { it.toString() }.toTypedArray()) { _, i -> settings.advisorCalls = choices[i]; again() }.show()
            }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 10) })
            if (settings.advisorPlan() == null)
                col.addView(Ui.text(ctx, "The advisor is the same model as the one doing the work, or none is set, so it is off for now. Pick a different model above.", 12f, Ui.BAD).also { it.setPadding(0, Ui.dp(ctx, 8), 0, 0) })
        }
        col.addView(Ui.text(ctx, "Usage & cost shows which model did what.", 12f, Ui.MUTED).also { it.setPadding(0, Ui.dp(ctx, 12), 0, 0) })
        val sv = ScrollView(ctx); sv.addView(col)
        dlg = AlertDialog.Builder(ctx).setTitle("Multi-model and advisor").setView(sv).setPositiveButton("Done", null).create()
        dlg.show()
    }
}
