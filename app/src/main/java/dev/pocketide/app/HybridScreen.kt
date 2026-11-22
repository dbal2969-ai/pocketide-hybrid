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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Hybrid AI (#BuildHybridAI 2026): Gemini Nano on this phone with a cloud Gemini fallback, through Firebase AI Logic.
 * The screen shows whether Nano is ready (and downloads it), lets the person pick the routing mode and try a prompt,
 * and labels every reply with where it ran and how long it took.
 */
object HybridScreen {
    private const val PREFS = "hybrid_ai"

    fun mode(ctx: Context): HybridAi.Mode =
        HybridAi.Mode.entries.firstOrNull { it.name == ctx.getSharedPreferences(PREFS, 0).getString("mode", null) } ?: HybridAi.Mode.PREFER_ON_DEVICE

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
        fun paintMode() { modeLine.text = mode(ctx).label + "\nRequests over ${HybridAi.ON_DEVICE_MAX_CHARS} characters go to the cloud in the on-phone-first mode." }
        paintMode()
        modeCard.addView(modeLine)
        fs.buttonRow(modeCard, Ui.button(ctx, "Change") {
            val all = HybridAi.Mode.entries
            AlertDialog.Builder(ctx).setTitle("Where requests run")
                .setSingleChoiceItems(all.map { it.label }.toTypedArray(), all.indexOf(mode(ctx))) { d, i -> setMode(ctx, all[i]); paintMode(); d.dismiss() }
                .show()
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
                    r.onSuccess { answer.text = it.text.ifBlank { "(empty reply)" }; where.text = label(it) }
                        .onFailure { answer.text = HybridAi.explain(it); where.text = "" }
                }
            }
        })
        tryCard.addView(answer); tryCard.addView(where)

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
        (if (r.onDevice) "⚡ Ran on this phone (Gemini Nano)" else "☁ Ran in the cloud (${HybridAi.CLOUD_MODEL})") +
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
