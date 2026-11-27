package dev.pocketide.app

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pocketide.app.core.ApkDb
import dev.pocketide.app.core.LinuxDistro
import dev.pocketide.app.core.Tool
import dev.pocketide.app.core.ToolCatalog
import java.io.File

/**
 * GPL route: the Tools screen. Sets up the Linux environment and installs languages and tools
 * with one tap (apk on Alpine, apt on Ubuntu), shows what is installed, and lists background servers.
 */
object ToolsScreen {

    /** Adds Alpine's community repository if the rootfs lacks it (Ollama, Go and npm live there). */
    fun ensureCommunityRepo(linux: LinuxRuntime) {
        val f = File(linux.rootfs, "etc/apk/repositories")
        try {
            if (!f.isFile) return
            val updated = ToolCatalog.withCommunity(f.readText()) ?: return
            f.writeText(updated + "\n")
        } catch (e: Exception) { /* apk will report what is missing */ }
    }

    fun installedPackages(linux: LinuxRuntime): Set<String> {
        val system = if (ToolCatalog.isUbuntu(linux.distro().id)) {
            val status = File(linux.rootfs, "var/lib/dpkg/status")
            try { if (status.isFile) dev.pocketide.app.core.DpkgDb.installed(status.readText()) else emptySet() } catch (e: Exception) { emptySet() }
        } else {
            val db = File(linux.rootfs, "lib/apk/db/installed")
            try { if (db.isFile) ApkDb.installed(db.readText()) else emptySet() } catch (e: Exception) { emptySet() }
        }
        if (system.isEmpty()) return system
        return system + npmGlobals(linux)
    }

    /** Global npm packages as "npm:<name>": npm install -g uses /usr/local/lib/node_modules or /usr/lib/node_modules (the prefix differs by build). */
    private fun npmGlobals(linux: LinuxRuntime): Set<String> = listOf("usr/local/lib/node_modules", "usr/lib/node_modules").flatMap { rel ->
        try {
            val dir = File(linux.rootfs, rel)
            val top = dir.list()?.toList() ?: emptyList()
            val scoped = top.filter { it.startsWith("@") }.associateWith { File(dir, it).list()?.toList() ?: emptyList() }
            ToolCatalog.npmInstalled(top, scoped)
        } catch (e: Exception) { emptySet() }
    }.toSet()

    private class Card(
        val tool: Tool,
        val view: LinearLayout,
        val state: TextView,
        val action: TextView,
        val remove: TextView,
        val test: TextView,
        val log: TextView,
        /** Ticked to install several tools in one go. */
        val pick: CheckBox
    )

    /**
     * [autoSetup] (1.5.211): one tap does the whole setup: the Linux add-on (Android asks to install it), then Linux, then the
     * basics (Python, Node, git), each step starting when the one before ends. Before, it took a tap for each, with a return to
     * this screen in between.
     */
    fun show(activity: Activity, linux: LinuxRuntime, settings: AppSettings, onChanged: () -> Unit, autoSetup: Boolean = false) {
        val act = activity
        val project = Projects.current(act)
        val home = File(act.filesDir, "home").also { it.mkdirs() }
        val pad = Ui.dp(act, 16)
        val box = Ui.vertical(act)
        box.setPadding(pad, Ui.dp(act, 8), pad, Ui.dp(act, 16))

        var job: CommandCall? = null
        var jobCard: Card? = null
        var installed: Set<String> = installedPackages(linux)
        val cards = ArrayList<Card>()

        fun section(title: String) {
            val t = Ui.text(act, title, 12f, Ui.MUTED)
            t.typeface = Typeface.DEFAULT_BOLD
            t.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 6))
            box.addView(t)
        }

        fun cardBox(): LinearLayout {
            val c = Ui.vertical(act)
            c.background = Ui.rounded(Ui.SURFACE, 10, act)
            c.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10))
            return c
        }

        fun link(label: String, onClick: () -> Unit): TextView {
            val t = Ui.text(act, label, 13f, Ui.ACCENT)
            t.setPadding(Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 10), Ui.dp(act, 8))
            t.setOnClickListener { onClick() }
            return t
        }

        fun spaced(v: View): LinearLayout.LayoutParams = Ui.lp(Ui.MATCH, Ui.WRAP).also { it.bottomMargin = Ui.dp(act, 8) }

        // ---- Linux environment ----
        section("LINUX ENVIRONMENT")
        val lx = cardBox()
        val lxStatus = Ui.text(act, "", 14f, Ui.TEXT)
        val lxProgress = Ui.text(act, "", 12f, Ui.MUTED, true)
        val lxRow = Ui.horizontal(act)
        val lxMain = link("Install") {}
        val lxRemove = link("Remove") {}
        val lxTerm = link("Open terminal") { act.startActivity(Intent(act, TerminalActivity::class.java)) }
        lxRow.addView(lxMain); lxRow.addView(lxTerm); lxRow.addView(lxRemove)
        val use = CheckBox(act).also {
            it.text = "Run commands in Linux"
            it.setTextColor(Ui.TEXT)
            it.isChecked = settings.useLinux
        }
        val lsp = CheckBox(act).also {
            it.text = "Check JavaScript, TypeScript and Python files with a language server after each write (slower)"
            it.setTextColor(Ui.TEXT)
            it.isChecked = settings.lspOn
        }
        val lxAbout = Ui.text(act, "${linux.distro().name}, run with PRoot (no root needed). Your project is /work inside it. Source: ${linux.distro().url}", 11f, Ui.MUTED)
        val lxChoose = Ui.chip(act, "Base: ${linux.distro().name.substringBefore(' ')}") { }
        lxChoose.setOnClickListener {
            if (linux.installed) { Toast.makeText(act, "Remove the current Linux first to change the base.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            val all = dev.pocketide.app.core.Distros.all
            AlertDialog.Builder(act).setTitle("Which Linux base?")
                .setItems(all.map { d -> (if (d.id == settings.linuxDistro) "● " else "") + d.name + " — about " + d.approxMb + " MB" }.toTypedArray()) { _, i2 ->
                    settings.linuxDistro = all[i2].id
                    Toast.makeText(act, "Next install will use " + all[i2].name + ". One-tap tools work on both; Ollama and llama.cpp need the Alpine base.", Toast.LENGTH_LONG).show()
                    onChanged()
                }.setNegativeButton("Close", null).show()
        }
        for (v in listOf(lxStatus, lxProgress, lxRow, use, lsp, lxChoose, lxAbout)) lx.addView(v)
        box.addView(lx, spaced(lx))

        // ---- background servers ----
        val bg = cardBox()
        val bgTitle = Ui.text(act, "Background servers", 14f, Ui.TEXT)
        val bgList = Ui.vertical(act)
        bg.addView(bgTitle); bg.addView(bgList)
        box.addView(bg, spaced(bg))

        // ---- tools ----
        section("ONE-TAP INSTALLS")
        val basicsRow = Ui.horizontal(act)
        val basics = link("Install the basics (Python, Node, Git)") {}
        basicsRow.addView(basics)
        box.addView(basicsRow)
        // several at once: everything still missing, or the tools ticked below
        val multiRow = Ui.horizontal(act)
        val allBtn = link("Install all") {}
        val selBtn = link("Install selected") {}
        val clearBtn = link("Clear") {}
        multiRow.addView(allBtn); multiRow.addView(selBtn); multiRow.addView(clearBtn)
        box.addView(multiRow)

        for (tool in ToolCatalog.all) {
            val c = cardBox()
            val top = Ui.horizontal(act)
            val pick = CheckBox(act).also { it.contentDescription = "Select ${tool.name}" }
            top.addView(pick)
            val name = Ui.text(act, tool.name, 15f, Ui.TEXT)
            name.typeface = Typeface.DEFAULT_BOLD
            top.addView(name, Ui.lp(0, Ui.WRAP, 1f))
            val state = Ui.text(act, "", 12f, Ui.MUTED)
            top.addView(state)
            c.addView(top)
            c.addView(Ui.text(act, tool.description + "  " + ToolCatalog.sizeLabel(tool.approxMb), 12f, Ui.MUTED))
            val row = Ui.horizontal(act)
            val action = link("Install") {}
            val test = link("Test") {}
            val remove = link("Remove") {}
            row.addView(action); row.addView(test); row.addView(remove)
            c.addView(row)
            val log = Ui.text(act, "", 11f, Ui.TEXT, true)
            log.maxLines = 8
            log.visibility = View.GONE
            log.setTextIsSelectable(true)
            c.addView(log)
            box.addView(c, spaced(c))
            cards.add(Card(tool, c, state, action, remove, test, log, pick))
        }

        val scroll = ScrollView(act)
        scroll.addView(box)
        val dlg = AlertDialog.Builder(act).setTitle("Tools").setView(scroll).setPositiveButton("Close", null).create()

        fun linuxReady() = linux.prootPresent && linux.installed

        lateinit var paint: () -> Unit

        fun runJob(card: Card?, label: String, cmd: String, timeout: Long, after: (Int) -> Unit) {
            if (job != null) return
            ensureCommunityRepo(linux)
            val target = card?.log ?: lxProgress
            target.visibility = View.VISIBLE
            target.text = "$ $label\n"
            val (argv, env) = linux.command(project, cmd)
            val out = StringBuilder()
            jobCard = card
            job = CommandRunner.run(project, home, cmd, timeout, { s ->
                out.append(s)
                if (out.length > 8000) out.delete(0, out.length - 6000)
                target.text = "$ $label\n" + out.toString().lines().takeLast(8).joinToString("\n")
            }, { code ->
                job = null
                jobCard = null
                target.append(if (code == 0) "\nDone." else "\nFailed (exit $code).")
                installed = installedPackages(linux)
                after(code)
                paint()
                onChanged()
            }, argv, env)
            paint()
        }

        fun install(tools: List<Tool>, card: Card?) {
            if (!linuxReady()) return
            runJob(card, "Installing " + tools.joinToString(", ") { it.name }, ToolCatalog.installCommand(tools, linux.distro().id), 1800) {}
        }

        paint = {
            val ready = linuxReady()
            lxStatus.text = linux.status()
            lxMain.text = when { linux.busy -> "Cancel"; ready -> ""; !linux.prootPresent -> "Get the Linux add-on"; else -> "Install Linux" }
            lxMain.visibility = if (lxMain.text.isEmpty()) View.GONE else View.VISIBLE
            lxTerm.visibility = if (ready) View.VISIBLE else View.GONE
            lxRemove.visibility = if (ready && !linux.busy && job == null) View.VISIBLE else View.GONE
            use.visibility = if (ready) View.VISIBLE else View.GONE

            bgList.removeAllViews()
            val running = LinuxService.running()
            if (running.isEmpty()) bgList.addView(Ui.text(act, "None running. Ollama starts from the Models screen.", 12f, Ui.MUTED))
            for (r in running) {
                val line = Ui.horizontal(act)
                line.addView(Ui.text(act, "● ${r.title}", 13f, Ui.GOOD), Ui.lp(0, Ui.WRAP, 1f))
                line.addView(link("Log") {
                    val tv = Ui.text(act, LinuxService.logTail(act, r.name, 6000).ifBlank { "(empty)" }, 11f, Ui.TEXT, true)
                    tv.setTextIsSelectable(true)
                    tv.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 8))
                    val sv = ScrollView(act); sv.addView(tv)
                    AlertDialog.Builder(act).setTitle("${r.title} log").setView(sv).setPositiveButton("Close", null).show()
                })
                line.addView(link("Stop") { LinuxService.stop(act, r.name) })
                bgList.addView(line)
            }

            val missingBasics = ToolCatalog.basics.mapNotNull { ToolCatalog.byId(it) }.filter { !ToolCatalog.isInstalled(it, installed, linux.distro().id) }
            basics.visibility = if (ready && missingBasics.isNotEmpty()) View.VISIBLE else View.GONE
            basics.isEnabled = job == null
            basics.alpha = if (job == null) 1f else 0.4f

            // install all / install selected: only tools this Linux offers and that are not installed yet
            val did0 = linux.distro().id
            val installable = if (!ready) emptyList() else cards.filter { ToolCatalog.available(it.tool, did0) && !ToolCatalog.isInstalled(it.tool, installed, did0) }
            for (c in cards) if (c !in installable && c.pick.isChecked) c.pick.isChecked = false
            val picked = installable.filter { it.pick.isChecked }
            allBtn.text = "Install all (${installable.size}, ${ToolCatalog.sizeLabel(installable.sumOf { it.tool.approxMb })})"
            allBtn.visibility = if (installable.size > 1) View.VISIBLE else View.GONE
            selBtn.text = "Install selected (${picked.size})"
            selBtn.visibility = if (picked.isNotEmpty()) View.VISIBLE else View.GONE
            clearBtn.visibility = if (picked.isNotEmpty()) View.VISIBLE else View.GONE
            for (b in listOf(allBtn, selBtn)) { b.isEnabled = job == null; b.alpha = if (job == null) 1f else 0.4f }

            for (c in cards) {
                c.pick.visibility = if (c in installable && job == null) View.VISIBLE else View.GONE
                val did = linux.distro().id
                val offered = ToolCatalog.available(c.tool, did)
                val has = ready && ToolCatalog.isInstalled(c.tool, installed, did)
                val mine = jobCard === c && job != null
                c.state.text = when { !ready -> "Set up Linux first"; !offered -> "Not on ${linux.distro().name.substringBefore(' ')}"; mine -> "Working…"; has -> "Installed ✓"; else -> "" }
                c.state.setTextColor(if (has) Ui.GOOD else Ui.MUTED)
                c.action.text = if (mine) "Cancel" else "Install"
                c.action.visibility = if (ready && offered && (!has || mine)) View.VISIBLE else View.GONE
                c.action.isEnabled = mine || job == null
                c.action.alpha = if (c.action.isEnabled) 1f else 0.4f
                c.test.visibility = if (has && !mine) View.VISIBLE else View.GONE
                c.remove.visibility = if (has && !mine && job == null) View.VISIBLE else View.GONE
                c.test.isEnabled = job == null
                c.test.alpha = if (job == null) 1f else 0.4f
            }
        }

        // 1.5.128: coming back from Android's installer (the Linux add-on) shows the next step; 1.5.211: and takes it, in a one-tap setup
        var auto = autoSetup
        var addonAsked = false
        fun nextStep() {
            if (!auto || !dlg.isShowing || linux.busy || job != null) return
            when {
                !linux.prootPresent -> if (!addonAsked) { addonAsked = true; LinuxAddon.fetch(act, settings) }
                !linux.installed -> lxMain.performClick()
                else -> {
                    auto = false
                    if (ToolCatalog.basics.mapNotNull { ToolCatalog.byId(it) }.any { !ToolCatalog.isInstalled(it, installed, linux.distro().id) }) basics.performClick()
                }
            }
        }
        dlg.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { focused -> if (focused && dlg.isShowing) { paint(); nextStep() } }

        // ---- actions ----
        lxMain.setOnClickListener {
            if (linux.busy) { linux.cancelInstall(); return@setOnClickListener }
            // 1.5.128: without PRoot in this APK (the MIT edition), get the Linux add-on first
            // 1.5.211: a tap here sets up the rest too (Linux, then the basics), each step when the one before ends
            auto = true
            if (!linux.prootPresent) { addonAsked = true; LinuxAddon.fetch(act, settings); return@setOnClickListener }
            if (linux.installed) return@setOnClickListener
            lxProgress.visibility = View.VISIBLE
            linux.install({ s -> lxProgress.text = s }) { err ->
                lxProgress.text = err ?: "Linux is ready. Pick tools below."
                installed = installedPackages(linux)
                if (dlg.isShowing) paint()
                onChanged()
                if (err == null) nextStep() else auto = false
            }
            paint()
        }
        lxRemove.setOnClickListener {
            AlertDialog.Builder(act).setTitle("Remove Linux?")
                .setMessage("This deletes the Linux files and every package and model installed in them. Your project files are kept.")
                .setPositiveButton("Remove") { _, _ ->
                    LinuxService.stopAll(act)
                    lxProgress.visibility = View.VISIBLE
                    lxProgress.text = "Removing Linux…"
                    Thread {
                        Thread.sleep(500)
                        // never let a stubborn file take the app down: report what was left instead
                        val left = try { linux.remove() } catch (e: Throwable) { dev.pocketide.app.core.AppLog.add("linux", "remove failed: ${e.message}"); -1 }
                        act.runOnUiThread {
                            lxProgress.text = when {
                                left == 0 -> "Removed."
                                left > 0 -> "Removed, except $left file${if (left == 1) "" else "s"} Android would not let go of. Restart the phone and remove again to clear them."
                                else -> "Could not remove Linux completely. Restart the phone and try again."
                            }
                            installed = emptySet(); paint(); onChanged()
                        }
                    }.start()
                }.setNegativeButton("Cancel", null).show()
        }
        use.setOnCheckedChangeListener { _, on -> settings.useLinux = on; paint(); onChanged() }
        lsp.setOnCheckedChangeListener { _, on -> settings.lspOn = on }
        for (c in cards) c.pick.setOnCheckedChangeListener { _, _ -> paint() }
        fun installMany(tools: List<Tool>) {
            if (tools.isEmpty() || job != null) return
            AlertDialog.Builder(act).setTitle("Install ${tools.size} tool${if (tools.size == 1) "" else "s"}?")
                .setMessage(tools.joinToString(", ") { it.name } + "\n\nAbout ${ToolCatalog.sizeLabel(tools.sumOf { it.approxMb })} to download. They install one after another in a single run; the progress shows above.")
                .setPositiveButton("Install") { _, _ -> for (c in cards) c.pick.isChecked = false; install(tools, null) }
                .setNegativeButton("Cancel", null).show()
        }
        allBtn.setOnClickListener {
            val did = linux.distro().id
            installMany(cards.filter { ToolCatalog.available(it.tool, did) && !ToolCatalog.isInstalled(it.tool, installed, did) }.map { it.tool })
        }
        selBtn.setOnClickListener { installMany(cards.filter { it.pick.isChecked && it.pick.visibility == View.VISIBLE }.map { it.tool }) }
        clearBtn.setOnClickListener { for (c in cards) c.pick.isChecked = false; paint() }
        basics.setOnClickListener {
            val tools = ToolCatalog.basics.mapNotNull { ToolCatalog.byId(it) }.filter { !ToolCatalog.isInstalled(it, installed, linux.distro().id) }
            if (tools.isNotEmpty()) install(tools, cards.firstOrNull { it.tool.id == tools.first().id })
        }
        for (c in cards) {
            c.action.setOnClickListener {
                if (jobCard === c && job != null) { job?.cancel(); return@setOnClickListener }
                install(listOf(c.tool), c)
            }
            c.test.setOnClickListener { runJob(c, "Testing ${c.tool.name}", c.tool.verify, 60) {} }
            c.remove.setOnClickListener {
                AlertDialog.Builder(act).setTitle("Remove ${c.tool.name}?")
                    .setMessage("Packages: ${(ToolCatalog.packages(c.tool, linux.distro().id) ?: c.tool.packages).joinToString(", ")}")
                    .setPositiveButton("Remove") { _, _ ->
                        if (c.tool.id == "ollama") LinuxService.stop(act, LinuxServerHooks.NAME)
                        runJob(c, "Removing ${c.tool.name}", ToolCatalog.removeCommand(c.tool, linux.distro().id), 600) {}
                    }.setNegativeButton("Cancel", null).show()
            }
        }

        val listener: () -> Unit = { if (dlg.isShowing) paint() }
        LinuxService.listeners.add(listener)
        dlg.setOnDismissListener {
            LinuxService.listeners.remove(listener)
            // an install keeps running if the screen is closed; it just is not shown
        }
        dlg.setOnShowListener { paint(); nextStep() }
        dlg.show()
    }
}
