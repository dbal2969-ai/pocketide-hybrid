package dev.pocketide.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import dev.pocketide.app.core.CatalogModel
import dev.pocketide.app.core.ModelCatalog
import dev.pocketide.app.core.ModelFit
import dev.pocketide.app.core.OllamaApi
import java.util.concurrent.Executors

/**
 * Local models: shows whether Ollama is running (and starts it when this build can),
 * downloads models with one tap, and switches the AI tab to one.
 */
object ModelsScreen {
    private const val PROVIDER = "ollama"

    fun show(activity: Activity, settings: AppSettings, hooks: LocalServerHooks?, onChanged: () -> Unit) {
        Screen(activity, settings, hooks, onChanged).open()
    }

    private class Screen(
        val activity: Activity, val settings: AppSettings, val hooks: LocalServerHooks?, val onChanged: () -> Unit
    ) {
        val ctx: Context = activity
        val fs = FullScreen(activity, "Local models")
        val main = Handler(Looper.getMainLooper())
        val io = Executors.newSingleThreadExecutor()
        val pullIo = Executors.newSingleThreadExecutor()
        val root = OllamaApi.root(settings.baseFor(PROVIDER))
        /** 1.5.180: Ollama on the person's computer over Wi-Fi (PcOllama), not on this phone. */
        val remote = dev.pocketide.app.core.PcOllama.isRemote(settings.baseFor(PROVIDER))
        val where = if (remote) "on your computer" else "on this phone"
        val ram: Long = try {
            val mi = ActivityManager.MemoryInfo()
            (activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            mi.totalMem
        } catch (e: Exception) { 0L }

        var closed = false
        var up = false
        var installed: List<OllamaApi.Installed> = emptyList()
        var serverVersion: String? = null
        var pulling: String? = null
        var pull: OllamaApi.PullHandle? = null
        var starting = false

        // server card
        val serverCard = fs.card(fs.body, 6)
        val serverTitle = Ui.text(ctx, "Checking Ollama…", 15f, Ui.TEXT)
        val serverNote = Ui.text(ctx, "", 13f, Ui.MUTED)
        val serverActions = Ui.vertical(ctx)

        // download progress
        val progressCard = fs.card(fs.body, 10)
        val progressTitle = Ui.text(ctx, "", 14f, Ui.TEXT)
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
        val progressText = Ui.text(ctx, "", 13f, Ui.MUTED)

        val installedBox = Ui.vertical(ctx)
        val catalogBox = Ui.vertical(ctx)

        fun open() {
            serverCard.addView(serverTitle)
            serverCard.addView(serverNote, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
            serverCard.addView(serverActions, Ui.lp(Ui.MATCH, Ui.WRAP))

            progressCard.addView(progressTitle)
            bar.max = 1000
            progressCard.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 8) })
            progressCard.addView(progressText, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
            fs.buttonRow(progressCard, Ui.button(ctx, "Cancel download") { pull?.cancel() })
            progressCard.visibility = View.GONE

            // 1.5.182: the built-in engine first: no Linux or Ollama to set up, and native speed
            fs.heading("Built-in engine")
            val eng = fs.card(fs.body, 6)
            eng.addView(Ui.text(ctx, "The fastest way to run a model on this phone: llama.cpp inside PocketIDE, nothing else to install.", 13f, Ui.MUTED))
            fs.buttonRow(eng, Ui.button(ctx, "Open built-in engine", true) { EngineScreen.show(activity, settings, onChanged) })

            // #BuildHybridAI: Gemini Nano on this phone with a cloud fallback (Firebase AI Logic)
            fs.heading("Hybrid AI")
            val hyb = fs.card(fs.body, 6)
            hyb.addView(Ui.text(ctx, "Gemini Nano on this phone for quick help, a cloud Gemini model when Nano is missing or the request is long.", 13f, Ui.MUTED))
            fs.buttonRow(hyb, Ui.button(ctx, "Open hybrid AI", true) { HybridScreen.show(activity) })

            fs.heading(if (remote) "On your computer" else "On this phone")
            fs.body.addView(installedBox, Ui.lp(Ui.MATCH, Ui.WRAP))

            computerCard()

            fs.heading("Download")
            val ramText = if (ram > 0 && !remote) "This phone has ${OllamaApi.formatSize(ram)} of memory. " else ""
            // 1.5.180: the advice follows PocketIDE's own runs on real Ollama (November 2026), where qwen2.5-coder did worst
            val guide = Ui.text(ctx, if (remote) "Downloads go to your computer. Ratings below are for phones; a computer with a graphics card " +
                "runs much bigger models. ${dev.pocketide.app.core.PcOllama.PULL_HINT.removePrefix("Then download a model on the computer, for example: ")}"
                else ramText + "Ratings are a rough guide: bigger models write better code but answer more slowly. " +
                "Start with Qwen3.5 4B, or Granite 4.2 8B on phones with 12 GB or more.", 13f, Ui.MUTED)
            fs.body.addView(guide, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
            fs.body.addView(catalogBox, Ui.lp(Ui.MATCH, Ui.WRAP))

            fs.heading("Other model")
            val custom = fs.card(fs.body, 8)
            custom.addView(Ui.text(ctx, "Any name from ollama.com/library, e.g. qwen2.5-coder:3b or llama3.2:1b", 13f, Ui.MUTED))
            val name = EditText(ctx)
            name.hint = "model:tag"
            name.setHintTextColor(Ui.MUTED)
            name.setTextColor(Ui.TEXT)
            name.setSingleLine()
            name.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            custom.addView(name, Ui.lp(Ui.MATCH, Ui.WRAP))
            fs.buttonRow(custom,
                Ui.button(ctx, "Download", true) {
                    val n = name.text.toString().trim()
                    if (!OllamaApi.isValidName(n)) toast("That doesn't look like an Ollama model name (lowercase, like qwen2.5-coder:3b).")
                    else startPull(n)
                },
                Ui.button(ctx, "Browse library") { HelpScreen.open(activity, "https://ollama.com/library") })

            fs.onCloseRequest = {
                if (pulling != null) {
                    AlertDialog.Builder(ctx).setTitle("Stop the download?")
                        .setMessage("Closing this screen stops downloading $pulling. You can start it again later.")
                        .setPositiveButton("Stop and close") { _, _ -> pull?.cancel(); fs.dialog.dismiss() }
                        .setNegativeButton("Keep downloading", null).show()
                    false
                } else true
            }
            fs.dialog.setOnDismissListener {
                closed = true
                pull?.cancel()
                io.shutdown()
                pullIo.shutdown()
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            renderCatalog()
            fs.show()
            refresh()
        }

        /** Use Ollama on the person's computer over Wi-Fi, or go back to this phone (PcOllama). */
        fun computerCard() {
            fs.heading("Use your computer")
            val card = fs.card(fs.body, 6)
            if (remote) {
                card.addView(Ui.text(ctx, "PocketIDE is using Ollama at $root. Its models run on that computer, so the phone stays cool and bigger models fit.", 13f, Ui.MUTED))
                fs.buttonRow(card, Ui.button(ctx, "Use this phone instead") { switchTo("") }, Ui.button(ctx, "Change address") { computerSetup() })
            } else {
                card.addView(Ui.text(ctx, "A computer with a graphics card on the same Wi-Fi can run much bigger models (14B-32B) than a phone, " +
                    "and they handle real projects far better. PocketIDE still edits and runs your code on the phone.", 13f, Ui.MUTED))
                fs.buttonRow(card, Ui.button(ctx, "Set up", true) { computerSetup() })
            }
        }

        fun computerSetup() {
            val box = Ui.vertical(ctx)
            box.setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 8), Ui.dp(ctx, 20), 0)
            val steps = Ui.vertical(ctx)
            fun showGuide(g: dev.pocketide.app.core.PcOllama.Guide) {
                steps.removeAllViews()
                g.steps.forEachIndexed { i, t -> steps.addView(Ui.text(ctx, "${i + 1}. $t", 13f, Ui.TEXT), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) }) }
                val cmds = g.commands.joinToString("\n")
                steps.addView(Ui.text(ctx, cmds, 12f, Ui.TEXT, true).also {
                    it.background = Ui.rounded(Ui.SURFACE2, 8, ctx, Ui.LINE); it.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6))
                    it.setTextIsSelectable(true)
                }, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
                steps.addView(Ui.button(ctx, "Copy commands") {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("commands", cmds))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
                steps.addView(Ui.text(ctx, dev.pocketide.app.core.PcOllama.PULL_HINT, 12f, Ui.MUTED), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
            }
            val chips = Ui.horizontal(ctx)
            for (g in dev.pocketide.app.core.PcOllama.GUIDES) chips.addView(Ui.chip(ctx, g.system) { showGuide(g) }, Ui.lp(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(ctx, 6) })
            box.addView(chips)
            box.addView(steps)
            showGuide(dev.pocketide.app.core.PcOllama.GUIDES[0])
            val addr = EditText(ctx)
            addr.hint = "Computer's address, e.g. 192.168.1.20"
            addr.setHintTextColor(Ui.MUTED); addr.setTextColor(Ui.TEXT); addr.setSingleLine()
            addr.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (remote) addr.setText(root.removePrefix("http://"))
            box.addView(addr, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 10) })
            val scroll = android.widget.ScrollView(ctx); scroll.addView(box)
            val d = AlertDialog.Builder(ctx).setTitle("Ollama on your computer").setView(scroll)
                .setPositiveButton("Connect", null).setNegativeButton("Cancel", null).create()
            d.setOnShowListener {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val base = dev.pocketide.app.core.PcOllama.baseFor(addr.text.toString())
                    if (base == null) { toast("Type the computer's address, like 192.168.1.20."); return@setOnClickListener }
                    toast("Checking $base…")
                    io.execute {
                        val v = OllamaApi.version(OllamaApi.root(base))
                        ui {
                            if (v == null) AlertDialog.Builder(ctx).setTitle("Couldn't reach Ollama")
                                .setMessage(dev.pocketide.app.core.PcOllama.whyNoAnswer(base)).setPositiveButton("OK", null).show()
                            else { d.dismiss(); toast("Connected to Ollama $v on your computer."); switchTo(base) }
                        }
                    }
                }
            }
            d.show()
        }

        /** Points the ollama provider at [base] ("" = this phone) and reopens the screen on it. */
        fun switchTo(base: String) {
            settings.setBaseFor(PROVIDER, base.ifEmpty { dev.pocketide.app.core.Providers.byId(PROVIDER).defaultBase })
            onChanged()
            fs.dialog.dismiss()
            main.postDelayed({ ModelsScreen.show(activity, settings, hooks, onChanged) }, 200)
        }

        fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

        fun ui(r: () -> Unit) = main.post { if (!closed) r() }

        /** Re-checks the server and the installed list. */
        fun refresh() {
            if (closed) return
            io.execute {
                val v = OllamaApi.version(root)
                val list = if (v != null) try { OllamaApi.tags(root) } catch (e: Exception) { emptyList() } else emptyList()
                ui {
                    up = v != null
                    serverVersion = v
                    installed = list
                    renderServer(v)
                    renderInstalled()
                    renderCatalog()
                }
            }
        }

        fun renderServer(version: String?) {
            serverActions.removeAllViews()
            if (version != null) {
                serverTitle.text = "● Ollama is running"
                serverTitle.setTextColor(Ui.GOOD)
                val inApp = hooks?.ollamaRunning == true
                serverNote.text = "Version $version at $root" + if (inApp) ". Running inside PocketIDE; it keeps running in the background until you stop it." else "."
                if (inApp) fs.buttonRow(serverActions, Ui.button(ctx, "Stop Ollama") { hooks?.stopOllama(); main.postDelayed({ refresh() }, 800) },
                    Ui.button(ctx, "Refresh") { refresh() })
                return
            }
            serverTitle.setTextColor(Ui.TEXT)
            if (remote) {
                serverTitle.text = "Your computer's Ollama doesn't answer"
                serverNote.text = dev.pocketide.app.core.PcOllama.whyNoAnswer(root)
                fs.buttonRow(serverActions, Ui.button(ctx, "Check again", true) { refresh() }, Ui.button(ctx, "Use this phone instead") { switchTo("") })
                return
            }
            if (hooks != null && hooks.canRunOllama) {
                serverTitle.text = if (starting) "Starting Ollama…" else "Ollama is not running"
                if (!starting) serverNote.text = hooks.ollamaStatus() + "\nTap Start: PocketIDE installs Ollama if needed (about 30 MB the first time) and runs it in the background."
                if (!starting) fs.buttonRow(serverActions, Ui.button(ctx, "Start Ollama", true) { start() }, Ui.button(ctx, "Check again") { refresh() })
                return
            }
            serverTitle.text = "Ollama is not running"
            serverNote.text = (hooks?.ollamaStatus()?.let { "$it\n" } ?: "") +
                "Run Ollama in the Termux app, then tap Check again. Leave Termux open while you use local models."
            for (cmd in listOf("pkg install ollama", "termux-wake-lock", dev.pocketide.app.core.LocalModels.serveCommand)) {
                val row = Ui.horizontal(ctx)
                row.background = Ui.rounded(Ui.SURFACE2, 8, ctx, Ui.LINE)
                row.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 2), Ui.dp(ctx, 2), Ui.dp(ctx, 2))
                row.addView(Ui.text(ctx, cmd, 13f, Ui.TEXT, true), Ui.lp(0, Ui.WRAP, 1f))
                val copy = Ui.text(ctx, "Copy", 13f, Ui.ACCENT)
                copy.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10))
                copy.setOnClickListener {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("command", cmd))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                }
                row.addView(copy)
                serverActions.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 8) })
            }
            fs.buttonRow(serverActions, Ui.button(ctx, "Check again", true) { refresh() },
                Ui.button(ctx, "Open Termux") { openTermux() })
        }

        fun openTermux() {
            val i = ctx.packageManager.getLaunchIntentForPackage("com.termux")
            if (i != null) activity.startActivity(i)
            else {
                AlertDialog.Builder(ctx).setTitle("Termux isn't installed")
                    .setMessage("Install Termux from F-Droid or its GitHub releases (the Play Store version is outdated), then run the three commands shown.")
                    .setPositiveButton("Open F-Droid page") { _, _ -> HelpScreen.open(activity, "https://f-droid.org/packages/com.termux/") }
                    .setNegativeButton("Close", null).show()
            }
        }

        fun start() {
            val h = hooks ?: return
            starting = true
            renderServer(null)
            h.ensureOllama(
                { s -> if (!closed) serverNote.text = s },
                { err ->
                    starting = false
                    if (closed) return@ensureOllama
                    if (err != null) {
                        serverNote.text = err
                        AlertDialog.Builder(ctx).setTitle("Ollama didn't start").setMessage(err).setPositiveButton("OK", null).show()
                    }
                    refresh()
                })
        }

        fun currentModel(): String? = if (settings.provider == PROVIDER) settings.model(PROVIDER).ifEmpty { null } else null

        fun renderInstalled() {
            installedBox.removeAllViews()
            if (!up) {
                installedBox.addView(Ui.text(ctx, if (remote) "Connect to your computer's Ollama to see its models." else "Start Ollama to see the models on this phone.", 13f, Ui.MUTED),
                    Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
                return
            }
            if (installed.isEmpty()) {
                installedBox.addView(Ui.text(ctx, "No models yet. Download one below.", 13f, Ui.MUTED),
                    Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 6) })
                return
            }
            val cur = currentModel()
            for (m in installed) {
                val card = fs.card(installedBox, 8)
                val inUse = cur != null && OllamaApi.sameModel(cur, m.name)
                card.addView(Ui.text(ctx, m.name + if (inUse) "  · in use" else "", 15f, if (inUse) Ui.GOOD else Ui.TEXT, true))
                val details = listOf(OllamaApi.formatSize(m.sizeBytes), m.parameterSize, m.quantization).filter { it.isNotBlank() }.joinToString(" · ")
                card.addView(Ui.text(ctx, details, 12f, Ui.MUTED), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 2) })
                fs.buttonRow(card,
                    Ui.button(ctx, if (inUse) "In use" else "Use", !inUse) { use(m.name) },
                    Ui.button(ctx, "Delete") { confirmDelete(m.name) })
            }
        }

        fun renderCatalog() {
            catalogBox.removeAllViews()
            for (m in ModelCatalog.models) catalogRow(m)
        }

        fun catalogRow(m: CatalogModel) {
            val card = fs.card(catalogBox, 8)
            val top = Ui.horizontal(ctx)
            top.addView(Ui.text(ctx, m.title + if (m.recommended) "  ★" else "", 15f, Ui.TEXT), Ui.lp(0, Ui.WRAP, 1f))
            top.addView(Ui.text(ctx, OllamaApi.formatSize(m.sizeMb * 1_000_000L), 12f, Ui.MUTED))
            card.addView(top, Ui.lp(Ui.MATCH, Ui.WRAP))
            card.addView(Ui.text(ctx, m.goodFor, 13f, Ui.MUTED), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 2) })
            val fit = if (remote) ModelFit.GOOD else ModelCatalog.fit(m, ram)
            val badge = Ui.text(ctx, fit.label + (if (m.coding) " · coding" else "") + " · " + m.name, 12f,
                when (fit) { ModelFit.GOOD -> Ui.GOOD; ModelFit.SLOW -> 0xFFFFB86B.toInt(); ModelFit.TOO_BIG -> Ui.BAD }, true)
            card.addView(badge, Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
            if (!ModelCatalog.ollamaAtLeast(serverVersion, m.minOllama))
                card.addView(Ui.text(ctx, "Needs Ollama ${m.minOllama} or newer; this Ollama is $serverVersion. Update it first (Termux: pkg upgrade ollama).", 12f, Ui.BAD),
                    Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 4) })
            val have = installed.any { OllamaApi.sameModel(it.name, m.name) }
            if (have) {
                val inUse = currentModel()?.let { OllamaApi.sameModel(it, m.name) } == true
                fs.buttonRow(card, Ui.button(ctx, if (inUse) "In use" else "Use", !inUse) { use(m.name) })
            } else if (pulling == m.name) {
                card.addView(Ui.text(ctx, "Downloading…", 13f, Ui.ACCENT), Ui.lp(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(ctx, 8) })
            } else {
                fs.buttonRow(card, Ui.button(ctx, "Download", fit != ModelFit.TOO_BIG) {
                    if (fit == ModelFit.TOO_BIG) {
                        AlertDialog.Builder(ctx).setTitle("Probably too big").setMessage(
                            "${m.title} needs more memory than this phone can usually spare, so it may fail to load or be very slow. Download anyway?")
                            .setPositiveButton("Download") { _, _ -> startPull(m.name) }.setNegativeButton("Cancel", null).show()
                    } else startPull(m.name)
                })
            }
        }

        fun use(name: String) {
            settings.provider = PROVIDER
            settings.setModel(PROVIDER, name)
            onChanged()
            toast("The AI tab now uses $name $where.")
            renderInstalled()
            renderCatalog()
        }

        fun confirmDelete(name: String) {
            AlertDialog.Builder(ctx).setTitle("Delete $name?").setMessage("It frees the storage it uses. You can download it again later.")
                .setPositiveButton("Delete") { _, _ ->
                    io.execute {
                        val err = try { OllamaApi.delete(root, name); null } catch (e: Exception) { e.message ?: "Could not delete." }
                        ui { if (err != null) toast(err); refresh() }
                    }
                }.setNegativeButton("Cancel", null).show()
        }

        fun startPull(name: String) {
            if (pulling != null) { toast("Wait for $pulling to finish, or cancel it first."); return }
            if (!up) { toast("Start Ollama first."); return }
            val h = OllamaApi.PullHandle()
            pull = h
            pulling = name
            progressCard.visibility = View.VISIBLE
            progressTitle.text = "Downloading $name"
            bar.isIndeterminate = true
            progressText.text = "Starting…"
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            renderCatalog()
            fs.scroll.post { fs.scroll.smoothScrollTo(0, progressCard.top) }
            var last = 0L
            pullIo.execute {
                val err = OllamaApi.pull(root, name, h) { p ->
                    val now = System.currentTimeMillis()
                    if (now - last > 250 || p.done) {
                        last = now
                        val pm = p.permille
                        val text = p.text()
                        ui {
                            if (pm < 0) bar.isIndeterminate = true else { bar.isIndeterminate = false; bar.progress = pm }
                            progressText.text = text
                        }
                    }
                }
                main.post {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    pulling = null
                    pull = null
                    if (closed) return@post
                    if (err == null) {
                        progressCard.visibility = View.GONE
                        toast("$name is ready. Tap Use to switch the AI tab to it.")
                    } else {
                        bar.isIndeterminate = false
                        progressText.text = if (err == "Cancelled") "Cancelled." else "Failed: $err"
                        if (err == "Cancelled") main.postDelayed({ if (pulling == null && !closed) progressCard.visibility = View.GONE }, 1500)
                    }
                    refresh()
                }
            }
        }
    }
}
