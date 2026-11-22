package dev.pocketide.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.pocketide.app.core.Engine
import java.util.concurrent.Executors

/** The built-in engine (1.5.182): download a model, use it, start or stop the engine. */
object EngineScreen {
    fun show(activity: Activity, settings: AppSettings, onChanged: () -> Unit) {
        val ctx: Context = activity
        val fs = FullScreen(activity, "Built-in engine")
        val main = Handler(Looper.getMainLooper())
        val io = Executors.newSingleThreadExecutor()
        val dl = Executors.newSingleThreadExecutor()
        val ram: Long = try {
            val mi = ActivityManager.MemoryInfo(); (activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi); mi.totalMem
        } catch (e: Exception) { 0L }
        var downloading: String? = null
        val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        var closed = false
        val status = Ui.text(ctx, "", 13f, Ui.MUTED)
        val list = Ui.vertical(ctx)
        fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

        val chosen: () -> String = { if (settings.provider == Engine.PROVIDER) settings.model(Engine.PROVIDER) else "" }

        lateinit var paint: () -> Unit
        fun use(m: Engine.Model) {
            settings.provider = Engine.PROVIDER
            settings.setModel(Engine.PROVIDER, m.id)
            onChanged(); paint()
            toast("The AI tab now uses ${m.name} in the built-in engine. It starts with your next request.")
            io.execute { val err = EngineHost.ensure(ctx, m); main.post { if (!closed) { if (err != null) toast(err); paint() } } }
        }

        fun download(m: Engine.Model, line: TextView) {
            if (downloading != null) { toast("Wait for ${Engine.byId(downloading!!)?.name} to finish first."); return }
            downloading = m.id; cancelFlag.set(false); paint()
            dl.execute {
                val err = EngineHost.download(ctx, m, { done, total ->
                    main.post { if (!closed) line.text = "Downloading: ${Engine.size(done)} of ${Engine.size(total)} (${done * 100 / total}%)" }
                }, { cancelFlag.get() || closed })
                main.post {
                    downloading = null
                    if (closed) return@post
                    if (err == null) { toast("${m.name} downloaded."); use(m) } else { toast(err); paint() }
                }
            }
        }

        paint = paint@{
            if (closed) return@paint
            val running = EngineHost.running
            status.text = when {
                !EngineHost.available(ctx) -> "This build has no built-in engine. It needs an arm64 phone and an APK built with the engine (see engine/build-llama.sh)."
                running -> "Running ${Engine.byId(EngineHost.loadedModel)?.name ?: EngineHost.loadedModel} on this phone (${Engine.threads(Runtime.getRuntime().availableProcessors())} threads, 8K window)."
                else -> "Stopped. It starts by itself when the AI tab sends a request with a built-in model."
            }
            list.removeAllViews()
            val suggested = Engine.suggest(ram)
            for (m in Engine.MODELS) {
                val card = fs.card(list, 8)
                val has = EngineHost.downloaded(ctx, m)
                val tag = when {
                    m.id == chosen() -> " · in use"
                    m.id == suggested.id && !has -> " · suggested for this phone"
                    else -> ""
                }
                card.addView(Ui.text(ctx, "${m.name}  ${Engine.size(m.bytes)}$tag", 14f, Ui.TEXT))
                val gb = ram / 1e9
                val warn = if (ram > 0 && gb < m.minRamGb - 0.6) " This phone has ${"%.0f".format(gb)} GB, so it may be slow or close other apps." else ""
                val line = Ui.text(ctx, m.note + warn, 12f, Ui.MUTED)
                card.addView(line)
                val buttons = mutableListOf<TextView>()
                if (downloading == m.id) buttons += Ui.button(ctx, "Pause") { cancelFlag.set(true) }
                else if (!has) buttons += Ui.button(ctx, "Download", m.id == suggested.id) { download(m, line) }
                else {
                    if (m.id != chosen()) buttons += Ui.button(ctx, "Use", true) { use(m) }
                    buttons += Ui.button(ctx, "Delete") {
                        AlertDialog.Builder(ctx).setMessage("Delete ${m.name} (${Engine.size(m.bytes)})?")
                            .setPositiveButton("Delete") { _, _ -> EngineHost.delete(ctx, m); paint() }.setNegativeButton("Cancel", null).show()
                    }
                }
                fs.buttonRow(card, *buttons.toTypedArray())
            }
        }

        fs.body.addView(Ui.text(ctx, "Runs a model inside PocketIDE with llama.cpp, tuned for phone processors. No Linux environment, Termux or Ollama needed, " +
            "and it is usually faster than Ollama in the Linux environment. Models download from Hugging Face once and work offline after that.", 13f, Ui.MUTED))
        fs.heading("Engine")
        val engineCard = fs.card()
        engineCard.addView(status)
        fs.buttonRow(engineCard,
            Ui.button(ctx, "Stop") { io.execute { EngineHost.stop(); main.post { paint() } } },
            Ui.button(ctx, "Log") {
                val text = try { EngineHost.log(ctx).readLines().takeLast(40).joinToString("\n") } catch (e: Exception) { "No log yet." }
                AlertDialog.Builder(ctx).setTitle("Engine log").setMessage(text.ifBlank { "No log yet." }).setPositiveButton("OK", null).show()
            })
        fs.heading("Models")
        fs.body.addView(list, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        fs.dialog.setOnDismissListener { closed = true; cancelFlag.set(true); io.shutdown(); dl.shutdown() }
        fs.show()
        paint()
    }
}
