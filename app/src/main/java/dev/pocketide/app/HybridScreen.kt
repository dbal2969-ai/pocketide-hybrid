package dev.pocketide.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.pocketide.app.core.HybridSettings
import java.util.concurrent.Executors

/**
 * Hybrid AI (#BuildHybridAI 2026): Gemini Nano on this phone with a cloud Gemini fallback, through Firebase AI Logic.
 * The screen shows whether Nano is ready (and downloads it), lets the person pick the routing mode and try a prompt,
 * and labels every reply with where it ran and how long it took.
 */
object HybridScreen {
    private const val PREFS = "hybrid_ai"

    fun mode(ctx: Context): HybridAi.Mode =
        HybridAi.Mode.entries.firstOrNull { it.name == ctx.getSharedPreferences(PREFS, 0).getString("mode", null) } ?: HybridCloud.remoteMode()

    private fun setMode(ctx: Context, m: HybridAi.Mode) = ctx.getSharedPreferences(PREFS, 0).edit().putString("mode", m.name).apply()

    fun show(activity: Activity) {
        val ctx: Context = activity
        val fs = FullScreen(activity, "Hybrid AI (Gemini Nano + cloud)")
        val main = Handler(Looper.getMainLooper())
        val io = Executors.newSingleThreadExecutor()
        var closed = false
        fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

        fs.body.addView(Ui.text(ctx, "Quick coding help runs on this phone with Gemini Nano: offline, private and free. Long requests, " +
            "phones without Nano, and the cloud-first mode use a cloud Gemini model through Firebase AI Logic. Every reply says where it ran. " +
            "In the editor: ⋮ → Quick assist.", 13f, Ui.MUTED))

        if (!HybridAi.configured(ctx)) {
            fs.heading("Not set up in this build")
            fs.body.addView(Ui.text(ctx, "This APK was built without a Firebase config (app/google-services.json), so hybrid AI is off. " +
                "Everything else in PocketIDE works as before. See HACKATHON.md in the pocketide-hybrid repository.", 13f, Ui.TEXT))
            fs.show(); return
        }

        // ---- Gemini Nano on this phone ----
        fs.heading("Gemini Nano on this phone")
        val nanoCard = fs.card()
        val nanoLine = Ui.text(ctx, "Checking…", 13f, Ui.TEXT)
        nanoCard.addView(nanoLine)
        lateinit var refresh: () -> Unit
        val downloadBtn = Ui.button(ctx, "Download Gemini Nano", true) {
            nanoLine.text = "Starting the download (Android AICore)…"
            io.execute {
                val err = HybridAi.downloadNano(ctx) { done, total ->
                    main.post { if (!closed) nanoLine.text = if (total > 0) "Downloading: ${done / 1_000_000} of ${total / 1_000_000} MB" else "Downloading: ${done / 1_000_000} MB" }
                }
                main.post { if (!closed) { if (err != null) toast(err) else toast("Gemini Nano is ready on this phone."); refresh() } }
            }
        }
        fs.buttonRow(nanoCard, downloadBtn, Ui.button(ctx, "Check again") { refresh() })
        refresh = {
            nanoLine.text = "Checking…"
            io.execute {
                val s = HybridAi.nanoStatus(ctx)
                main.post {
                    if (closed) return@post
                    nanoLine.text = when {
                        s == "AVAILABLE" -> "Ready. Quick assist runs on this phone, even in airplane mode."
                        s == "DOWNLOADABLE" -> "This phone supports Gemini Nano. Download it once (through Android AICore) to work offline."
                        s == "DOWNLOADING" -> "Downloading through Android AICore…"
                        s.startsWith("UNAVAILABLE") -> "Gemini Nano is not available on this phone, so requests go to the cloud model.\n$s"
                        else -> s
                    }
                    downloadBtn.visibility = if (s == "DOWNLOADABLE") TextView.VISIBLE else TextView.GONE
                }
            }
        }

        // ---- Routing ----
        fs.heading("Where requests run")
        val modeCard = fs.card()
        val modeLine = Ui.text(ctx, "", 13f, Ui.TEXT)
        fun paintMode() { modeLine.text = mode(ctx).label + "\nRequests over ${HybridAi.settings(ctx).onDeviceMaxChars} characters go to the cloud in the on-phone-first mode." }
        paintMode()
        modeCard.addView(modeLine)
        fs.buttonRow(modeCard, Ui.button(ctx, "Change") {
            val all = HybridAi.Mode.entries
            AlertDialog.Builder(ctx).setTitle("Where requests run")
                .setSingleChoiceItems(all.map { it.label }.toTypedArray(), all.indexOf(mode(ctx))) { d, i -> setMode(ctx, all[i]); paintMode(); d.dismiss() }
                .show()
        })

        // ---- Model settings ----
        fs.heading("Model settings")
        val setCard = fs.card()
        val cur = HybridAi.settings(ctx)
        fun field(hint: String, value: String, decimal: Boolean = false): EditText = EditText(ctx).apply {
            this.hint = hint; setText(value); textSize = 14f; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
            inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_NUMBER
        }
        val tempBox = field("Temperature (0 to 2)", cur.temperature.toString(), true)
        val topKBox = field("Top-K (1 to 100)", cur.topK.toString())
        val outBox = field("Max output tokens (64 to 8192)", cur.maxOutputTokens.toString())
        val charsBox = field("On-phone limit, characters (1000 to 30000)", cur.onDeviceMaxChars.toString())
        val warn = Ui.text(ctx, HybridSettings.warning(cur) ?: "", 12f, Ui.BAD)
        val fullBox = CheckBox(ctx).apply {
            text = "Use the Full on-phone model (Gemma 4 E4B: slower, stronger) instead of Fast (E2B). It may need its own download."
            isChecked = cur.fullModel; setTextColor(Ui.TEXT)
        }
        setCard.addView(Ui.text(ctx, "Temperature: lower is more predictable. Top-K: how many likely words the model picks from. " +
            "Max output tokens: the longest reply. On-phone limit: longer requests go to the cloud in the on-phone-first mode. " +
            "Changes apply to the next request.", 12f, Ui.MUTED))
        for (box in listOf(tempBox, topKBox, outBox, charsBox)) setCard.addView(box, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        setCard.addView(fullBox)
        setCard.addView(warn)
        fun fill(s: HybridSettings) {
            tempBox.setText(s.temperature.toString()); topKBox.setText(s.topK.toString())
            outBox.setText(s.maxOutputTokens.toString()); charsBox.setText(s.onDeviceMaxChars.toString())
            warn.text = HybridSettings.warning(s) ?: ""
            fullBox.isChecked = s.fullModel
        }
        fs.buttonRow(setCard, Ui.button(ctx, "Save settings", true) {
            val s = HybridSettings.parse(HybridAi.settings(ctx), tempBox.text.toString(), topKBox.text.toString(),
                outBox.text.toString(), charsBox.text.toString()).copy(fullModel = fullBox.isChecked)
            HybridAi.saveSettings(ctx, s)
            fill(s); paintMode(); refresh()
            toast("Saved. The next request uses these settings.")
        }, Ui.button(ctx, "Reset to defaults") {
            HybridAi.saveSettings(ctx, HybridSettings.DEFAULT)
            fill(HybridSettings.DEFAULT); paintMode(); refresh()
            toast("Back to the defaults.")
        })

        // ---- Try it ----
        fs.heading("Try it")
        val tryCard = fs.card()
        val input = EditText(ctx).apply {
            hint = "Ask something short, e.g. \"What does Kotlin's ?: do?\""
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2; gravity = Gravity.TOP; textSize = 14f; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
        }
        tryCard.addView(input, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        val answer = Ui.text(ctx, "", 14f, Ui.TEXT)
        val where = Ui.text(ctx, "", 12f, Ui.MUTED)
        fs.buttonRow(tryCard, Ui.button(ctx, "Ask", true) {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return@button
            answer.text = "Thinking…"; where.text = ""
            io.execute {
                val r = try { Result.success(HybridAi.ask(ctx, q, mode(ctx))) } catch (e: Exception) { Result.failure(e) }
                main.post {
                    if (closed) return@post
                    r.onSuccess { answer.text = it.text.ifBlank { "(empty reply)" }; where.text = label(it)
                        HybridCloud.log(if (it.onDevice) "phone" else "cloud", it.seconds, "", "try") }
                        .onFailure { answer.text = HybridAi.explain(it); where.text = "" }
                }
            }
        })
        tryCard.addView(answer); tryCard.addView(where)

        // ---- Usage (Firebase Authentication + Firestore) and Remote Config ----
        fs.heading("Your hybrid usage")
        val useCard = fs.card()
        val useLine = Ui.text(ctx, "Loading…", 13f, Ui.TEXT)
        val cfgLine = Ui.text(ctx, "", 12f, Ui.MUTED)
        useCard.addView(useLine); useCard.addView(cfgLine)
        fun loadUsage() {
            useLine.text = "Loading…"
            Thread {
                val s = HybridCloud.summary()
                main.post {
                    if (closed) return@post
                    useLine.text = s
                    cfgLine.text = HybridCloud.authNote + "\n" + HybridCloud.configNote +
                        "\nStored in Firestore: where each request ran, its time and why it was passed on. Never prompts or code."
                }
            }.start()
        }
        fs.buttonRow(useCard, Ui.button(ctx, "Refresh") { loadUsage() })
        loadUsage()

        // ---- App Check (debug builds) ----
        if (AppCheckSetup.DEBUG) {
            fs.heading("App Check (debug build)")
            val ac = fs.card()
            ac.addView(Ui.text(ctx, "Firebase requires App Check. This debug build uses a debug token: after your first request, copy it and add it in the " +
                "Firebase console (App Check → Apps → Manage debug tokens).", 13f, Ui.MUTED))
            fs.buttonRow(ac, Ui.button(ctx, "Copy App Check debug token") {
                val t = HybridAi.appCheckDebugSecret(ctx)
                if (t == null) toast("No token yet. Send one request first (Try it), then copy it.") else {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("App Check debug token", t))
                    toast("Copied. Add it in the Firebase console → App Check → Manage debug tokens.")
                }
            })
        }

        fs.dialog.setOnDismissListener { closed = true; io.shutdown() }
        fs.show()
        refresh()
    }

    /** "On this phone (Gemini Nano) · 1.2 s" plus the routing note, if any. */
    fun label(r: HybridAi.Reply): String =
        (if (r.onDevice) "⚡ Ran on this phone (Gemini Nano)" else "☁ Ran in the cloud (${HybridCloud.cloudModel()})") +
            " · ${"%.1f".format(r.seconds)} s" + (if (r.note.isNotEmpty()) "\n" + r.note else "")

    /** Editor quick assist: one action on the selected lines, shown in a dialog with where it ran. */
    fun quickAssist(activity: Activity, lang: String, code: String, onInsert: ((String) -> Unit)?, onEscalate: ((String) -> Unit)?) {
        val ctx: Context = activity
        if (!HybridAi.configured(ctx)) { Toast.makeText(ctx, "Hybrid AI is not set up in this build (no Firebase config).", Toast.LENGTH_LONG).show(); return }
        val actions = HybridAi.Action.entries
        AlertDialog.Builder(ctx).setTitle("Quick assist (Gemini Nano first)").setItems(actions.map { it.label }.toTypedArray()) { _, i ->
            val a = actions[i]
            val prompt = a.prompt(lang, code)
            val dlg = AlertDialog.Builder(ctx).setTitle(a.label).setMessage("Working…").setPositiveButton("Close", null).create()
            dlg.show()
            val main = Handler(Looper.getMainLooper())
            Thread {
                val r = try { Result.success(HybridAi.ask(ctx, prompt, mode(ctx))) } catch (e: Exception) { Result.failure(e) }
                main.post {
                    if (!dlg.isShowing) return@post
                    dlg.dismiss()
                    val b = AlertDialog.Builder(ctx).setTitle(a.label).setPositiveButton("Close", null)
                    r.onSuccess { rep ->
                        HybridCloud.log(if (rep.onDevice) "phone" else "cloud", rep.seconds, "", "quick")
                        b.setMessage(rep.text.ifBlank { "(empty reply)" } + "\n\n" + label(rep))
                        if (onEscalate != null) b.setNegativeButton("Hand to the agent") { _, _ -> onEscalate(prompt) }
                        b.setNeutralButton(if (a == HybridAi.Action.DOC && onInsert != null) "Insert above" else "Copy") { _, _ ->
                            if (a == HybridAi.Action.DOC && onInsert != null) onInsert(rep.text.removePrefix("```").removeSuffix("```").trim() + "\n")
                            else (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("reply", rep.text))
                        }
                    }.onFailure { b.setMessage(HybridAi.explain(it)) }
                    b.show()
                }
            }.start()
        }.show()
    }
}
