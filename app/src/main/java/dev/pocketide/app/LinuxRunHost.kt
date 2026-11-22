package dev.pocketide.app

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import dev.pocketide.app.core.ToolCatalog

/**
 * v0.4 W3, GPL route: RunHost backed by the Linux environment. Tools install with apk or apt (progress in the
 * Terminal tab), servers run through LinuxService so they keep running in the background.
 */
class LinuxRunHost(
    private val act: Activity,
    private val linux: LinuxRuntime,
    private val store: ProjectStore,
    private val terminal: TerminalPane,
    private val openToolsScreen: () -> Unit
) : RunHost {
    override val canUseLinux: Boolean get() = linux.prootPresent
    override val linuxReady: Boolean get() = linux.active

    override fun installedTools(): Set<String>? {
        if (!linux.installed) return emptySet()
        val pkgs = ToolsScreen.installedPackages(linux)
        if (pkgs.isEmpty()) return null  // database unreadable: don't block running
        return ToolCatalog.all.filter { ToolCatalog.isInstalled(it, pkgs, linux.distro().id) }.map { it.id }.toSet()
    }

    override fun installTool(toolId: String, onDone: (Boolean) -> Unit) {
        val tool = ToolCatalog.byId(toolId) ?: run { onDone(false); return }
        ToolsScreen.ensureCommunityRepo(linux)
        terminal.exec(ToolCatalog.installCommand(listOf(tool), linux.distro().id), 1800) { code, _ -> onDone(code == 0) }
    }

    override fun openTools() = openToolsScreen()

    override fun startServer(name: String, title: String, command: String): String? {
        askNotificationPermission()
        val secrets = try { dev.pocketide.app.core.Secrets(java.io.File(Projects.metaDir(act, store.root.name), "secrets.json")).all() } catch (e: Exception) { emptyMap() }
        return LinuxService.start(act, linux, name, title, command, secrets, projectDir = store.root)
    }

    override fun stopServer(name: String) = LinuxService.stop(act, name)
    override fun isServerRunning(name: String) = LinuxService.isRunning(name)
    override fun serverLog(name: String, chars: Int): String {
        val secrets = try { dev.pocketide.app.core.Secrets(java.io.File(Projects.metaDir(act, store.root.name), "secrets.json")).all() } catch (e: Exception) { emptyMap() }
        return dev.pocketide.app.core.Secrets.mask(LinuxService.logTail(act, name, chars), secrets)
    }
    override fun runningServers(): List<Pair<String, String>> = LinuxService.running().map { it.name to it.title }

    /** The "running in the background" notification needs this on Android 13+. Asked once (shared with Ollama). */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (act.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return
        val sp = act.getSharedPreferences("pocketide", android.content.Context.MODE_PRIVATE)
        if (sp.getBoolean("askedNotify", false)) return
        sp.edit().putBoolean("askedNotify", true).apply()
        act.requestPermissions(arrayOf(perm), 7302)
    }
}
