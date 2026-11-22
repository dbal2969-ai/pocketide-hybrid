package dev.pocketide.app

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.pocketide.app.core.Plan
import dev.pocketide.app.core.Plans
import dev.pocketide.app.core.Requirements
import dev.pocketide.app.core.StepStatus

/** v0.4 W2: the plan card in the chat: approve / edit / cancel, then a live checklist while the steps run. */
class PlanCard(
    private val ctx: Context,
    var plan: Plan,
    private val onApprove: (Plan) -> Unit,
    private val onSkipPlan: () -> Unit,
    private val onCancel: () -> Unit,
    private val requirements: Boolean = false,
    private val parts: Boolean = false
) : LinearLayout(ctx) {
    private val title = Ui.text(ctx, "", 14f, Ui.TEXT)
    // 1.5.127: the steps' Markdown is shown formatted (the emulator test showed raw `index.html` marks in a requirements list)
    private val list = ReplyText(ctx).also { it.textSize = 13f; it.setTextColor(Ui.TEXT); it.typeface = android.graphics.Typeface.MONOSPACE }
    private val status = Ui.text(ctx, "", 12f, Ui.MUTED)
    private val buttons = Ui.horizontal(ctx)
    private val created = System.currentTimeMillis()

    /** How many messages the person has sent since this card was made; a stale plan asks before it runs. */
    var newerMessages: () -> Int = { 0 }

    /** Runs [action], first asking when the person has asked other things since this plan was made. */
    private fun guard(action: () -> Unit) {
        val n = newerMessages()
        if (n <= 0) { action(); return }
        val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(created))
        android.app.AlertDialog.Builder(ctx).setTitle("Run this older plan?")
            .setMessage("This ${if (requirements) "list" else if (parts) "parts plan" else "plan"} (\"${plan.title.take(60)}\") is from $at, and you have sent $n message${if (n == 1) "" else "s"} since. It may not be what you want now.")
            .setPositiveButton("Run it") { _, _ -> action() }
            .setNegativeButton("Not now", null).show()
    }

    init {
        orientation = VERTICAL
        setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10))
        background = Ui.rounded(Ui.SURFACE, 10, ctx, Ui.ACCENT)
        title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        addView(title)
        list.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6))
        addView(list)
        addView(status)
        buttons.setPadding(0, Ui.dp(ctx, 8), 0, 0)
        addView(buttons)
        showReview()
    }

    private fun btn(label: String, primary: Boolean = false, action: () -> Unit): TextView =
        Ui.button(ctx, label, primary, action).also { buttons.addView(it, Ui.lp(Ui.WRAP, Ui.WRAP).also { p -> p.rightMargin = Ui.dp(ctx, 8) }) }

    fun update() {
        title.text = (if (requirements) "Requirements: " else if (parts) "Parts: " else "Plan: ") + plan.title
        list.text = plan.checklist()
    }

    /** Waiting for the user to approve. */
    fun showReview() {
        update()
        status.text = if (parts) "The job is split into parts. Each part is built in its own pass, then everything is checked against your request. Approve, edit or skip." else if (requirements) "This is what will be built. Approve it, or edit the list first. When the work is done it is checked against this list." else "Review the plan. Each step runs as its own turn, with a checkpoint before it."
        buttons.removeAllViews()
        btn("Approve", true) { guard { buttons.removeAllViews(); onApprove(plan) } }
        btn("Edit") { edit() }
        btn(if (parts) "Build in one go" else if (requirements) "Skip list" else "Skip plan") { buttons.removeAllViews(); status.text = if (parts) "Building everything in one pass." else if (requirements) "List skipped; running the request directly." else "Plan skipped; running the request directly."; onSkipPlan() }
        btn("Cancel") { buttons.removeAllViews(); status.text = "Cancelled."; onCancel() }
    }

    private fun edit() {
        val box = Ui.vertical(ctx)
        box.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), 0)
        box.addView(Ui.text(ctx, if (requirements) "One requirement per line." else if (parts) "One part per line." else "One step per line.", 12f, Ui.MUTED))
        val et = EditText(ctx)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        et.setText(plan.steps.joinToString("\n"))
        et.minLines = 4
        box.addView(et)
        android.app.AlertDialog.Builder(ctx).setTitle(if (requirements) "Edit requirements" else "Edit plan").setView(box)
            .setPositiveButton("Save") { _, _ -> (if (requirements) Requirements.fromEdited(plan.title, et.text.toString()) else Plans.fromEdited(plan.title, et.text.toString()))?.let { plan = it; showReview() } }
            .setNegativeButton("Cancel", null).show()
    }

    /** While running: the checklist plus a line of status and optional buttons. */
    fun running(line: String) {
        update()
        status.text = line
        buttons.removeAllViews()
    }

    /** Agent mode off, or a step failed: ask before going on. */
    fun pause(line: String, actions: List<Pair<String, () -> Unit>>) {
        update()
        status.text = line
        buttons.removeAllViews()
        for ((i, a) in actions.withIndex()) btn(a.first, i == 0) { val go = { buttons.removeAllViews(); a.second() }; if (a.first.startsWith("Stop") || a.first.startsWith("Accept")) go() else guard(go) }
    }

    fun done(line: String) {
        update()
        status.text = line
        status.setTextColor(if (plan.status.all { it == StepStatus.DONE || it == StepStatus.SKIPPED }) Ui.GOOD else Ui.MUTED)
        buttons.removeAllViews()
        buttons.visibility = View.GONE
    }
}
