package dev.pocketide.app

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GPL route: runs Ollama inside the app's Linux environment for the Models screen.
 * Installs Linux and the ollama package when missing, starts `ollama serve` through LinuxService
 * so it keeps running in the background, and waits until it answers on 127.0.0.1:11434.
 */
class LinuxServerHooks(
    private val activity: Activity,
    private val linux: LinuxRuntime,
    private val projectDir: () -> File,  // read at use time: the open project can change
    private val home: File
) : LocalServerHooks {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    companion object {
        const val NAME = "ollama"
        const val HOST = "127.0.0.1:11434"
        private const val REQ_NOTIFY = 7102

        /** True when something (ours, or Ollama in Termux) answers on the Ollama port. */
        fun answering(): Boolean = try {
            val c = URL("http://$HOST/api/version").openConnection() as HttpURLConnection
            c.connectTimeout = 1500
            c.readTimeout = 1500
            val ok = c.responseCode == 200
            c.disconnect()
            ok
        } catch (e: Exception) { false }
    }

    override val canRunOllama: Boolean get() = linux.prootPresent

    override val ollamaRunning: Boolean get() = LinuxService.isRunning(NAME)

    private val ollamaInstalled: Boolean
        get() = File(linux.rootfs, "usr/bin/ollama").let { it.exists() || java.nio.file.Files.isSymbolicLink(it.toPath()) }

    override fun ollamaStatus(): String = when {
        !linux.prootPresent -> "This build cannot run Linux, so Ollama has to run outside the app."
        !linux.installed -> "Linux is not installed yet (it installs automatically, about 4 MB)."
        !ollamaInstalled -> "Ollama is not installed yet (it installs automatically, about 36 MB)."
        ollamaRunning -> "Ollama is running in the background."
        else -> "Ollama is installed and stopped."
    }

    override fun ensureOllama(onStatus: (String) -> Unit, onDone: (String?) -> Unit) {
        if (busy) { onStatus("Already starting…"); return }
        busy = true
        val done: (String?) -> Unit = { err -> busy = false; onDone(err) }
        if (!linux.prootPresent) { done("This build has no Linux runtime (PRoot), so it cannot run Ollama itself."); return }
        askNotificationPermission()
        stepLinux(onStatus, done)
    }

    private fun stepLinux(onStatus: (String) -> Unit, done: (String?) -> Unit) {
        if (linux.installed) { stepPackage(onStatus, done); return }
        onStatus("Setting up Linux…")
        linux.install({ s -> onStatus(s) }) { err ->
            if (err != null) done("Linux setup failed: $err") else stepPackage(onStatus, done)
        }
    }

    private fun stepPackage(onStatus: (String) -> Unit, done: (String?) -> Unit) {
        if (ollamaInstalled) { stepServe(onStatus, done); return }
        if (linux.distro().id != "alpine") {
            done("Ollama is installed from Alpine's packages, so it cannot be set up on the Ubuntu base. Remove Linux and install the Alpine base, or run Ollama on a PC or in Termux and use the Remote server option.")
            return
        }
        onStatus("Installing Ollama (about 36 MB)…")
        ToolsScreen.ensureCommunityRepo(linux)
        val cmd = "apk update && apk add ollama"
        val (argv, env) = linux.command(projectDir(), cmd)
        val out = StringBuilder()
        CommandRunner.run(projectDir(), home, cmd, 1800, { s ->
            out.append(s)
            val line = out.lines().lastOrNull { it.isNotBlank() }
            if (line != null) onStatus(line.take(120))
        }, { code ->
            if (code == 0 && ollamaInstalled) stepServe(onStatus, done)
            else done("Installing Ollama failed (exit $code).\n" + out.toString().takeLast(800))
        }, argv, env)
    }

    private fun stepServe(onStatus: (String) -> Unit, done: (String?) -> Unit) {
        Thread {
            if (answering()) { main.post { done(null) }; return@Thread }
            main.post { onStatus("Starting the Ollama server…") }
            var err: String? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            main.post {
                err = LinuxService.start(activity, linux, NAME, "Ollama", "exec ollama serve",
                    mapOf("OLLAMA_HOST" to HOST, "OLLAMA_MODELS" to "/root/.ollama/models") + dev.pocketide.app.core.LocalModels.OLLAMA_SETTINGS)
                latch.countDown()
            }
            latch.await()
            if (err != null) { main.post { done(err) }; return@Thread }
            val until = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < until) {
                if (answering()) { main.post { onStatus("Ollama is running."); done(null) }; return@Thread }
                if (!LinuxService.isRunning(NAME)) break
                Thread.sleep(1000)
            }
            val tail = LinuxService.logTail(activity, NAME)
            val why = if (LinuxService.isRunning(NAME)) "Ollama did not answer within a minute." else "Ollama stopped while starting."
            if (LinuxService.isRunning(NAME)) LinuxService.stop(activity, NAME)
            main.post { done(why + if (tail.isNotBlank()) "\n\nLast lines of its log:\n$tail" else "") }
        }.start()
    }

    override fun stopOllama() { LinuxService.stop(activity, NAME) }

    /** The ongoing "Ollama running" notification needs this permission on Android 13+. Asked once. */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (activity.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return
        val sp = activity.getSharedPreferences("pocketide", android.content.Context.MODE_PRIVATE)
        if (sp.getBoolean("askedNotify", false)) return
        sp.edit().putBoolean("askedNotify", true).apply()
        activity.requestPermissions(arrayOf(perm), REQ_NOTIFY)
    }
}
