package dev.pocketide.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.pocketide.app.core.AppLog
import dev.pocketide.app.core.Lsp
import dev.pocketide.app.core.LspHook
import dev.pocketide.app.core.ProotCommand
import java.io.File

/**
 * GPL edition: after the assistant writes a JavaScript, TypeScript or Python file, starts that language's server inside Linux,
 * asks it for the file's errors and gives them to the assistant, so it can fix them before its next round. Opt-in (Tools →
 * "Check ... with a language server"). A server is started for each check and stopped after it. When the server is not
 * installed in the Linux environment the check reports nothing.
 */
object LspCheck {
    private const val TIMEOUT_MS = 20_000L
    /** After a server fails to answer, checks with it are skipped this long, instead of each one waiting out the time limit. */
    private const val SKIP_AFTER_FAILURE_MS = 10 * 60_000L
    private val failedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun skipping(cmd: String) = (failedUntil[cmd] ?: 0L) > System.currentTimeMillis()

    /** The last lines the server wrote to its error output (kept in a file in the app's home folder), for the log. */
    private fun errTail(ctx: Context): String = try {
        File(File(ctx.filesDir, "home"), ERR_FILE).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.takeLast(3)?.joinToString(" | ")?.take(300).orEmpty()
    } catch (e: Exception) { "" }
    private const val ERR_FILE = ".lsp-stderr.log"

    /** Logs a server that did not answer, with its own error output, and pauses checks with it. */
    private fun failed(ctx: Context, cmd: String, what: String) {
        failedUntil[cmd] = System.currentTimeMillis() + SKIP_AFTER_FAILURE_MS
        val err = errTail(ctx)
        AppLog.add("lsp", "$what: no answer from `$cmd` within ${TIMEOUT_MS / 1000} s" +
            (if (err.isEmpty()) " (it printed no error)" else "; it printed: $err") + ". Checks with it are paused for ${SKIP_AFTER_FAILURE_MS / 60_000} minutes.")
    }
    private const val TS = "typescript-language-server --stdio"
    private val SERVERS = mapOf(
        "javascript" to TS, "javascriptreact" to TS, "typescript" to TS, "typescriptreact" to TS,
        "python" to "pyright-langserver --stdio")

    fun install(ctx: Context, linux: LinuxRuntime, settings: AppSettings) {
        LspHook.check = { path, text, done ->
            Thread {
                val note = try { check(ctx, linux, settings, path, text) } catch (e: Exception) { null }
                Handler(Looper.getMainLooper()).post { done(note) }
            }.apply { isDaemon = true }.start()
        }
        LspHook.find = { path, text, name, method, done ->
            Thread {
                val r = try { find(ctx, linux, settings, path, text, name, method) } catch (e: Exception) { "The language server failed: ${e.message}. Use find_symbol." }
                Handler(Looper.getMainLooper()).post { done(r) }
            }.apply { isDaemon = true }.start()
        }
        LspHook.enabled = { settings.lspOn && linux.active }
    }

    /** Starts [cmd] inside Linux in the current project, or null (logged) when it cannot start. */
    private fun start(ctx: Context, linux: LinuxRuntime, cmd: String): Process? {
        val project = Projects.current(ctx)
        val home = File(ctx.filesDir, "home").also { it.mkdirs() }
        linux.tmp.mkdirs()
        val argv = ProotCommand.args(linux.launcher.absolutePath, linux.rootfs.absolutePath, project.absolutePath,
            linux.guestTmp(project).absolutePath, cmd, "dumb", emptyMap())
        return try {
            ProcessBuilder(argv).apply {
                directory(project)
                redirectError(File(home, ERR_FILE))
                environment()["HOME"] = home.absolutePath
                environment()["TMPDIR"] = home.absolutePath
                environment().putAll(ProotCommand.env(linux.launcher.parentFile!!.absolutePath, linux.tmp.absolutePath))
            }.start()
        } catch (e: Exception) {
            AppLog.add("lsp", "could not start $cmd: ${e.message}")
            null
        }
    }

    /** The definition or the uses of [name], found from where it appears in [path], as the assistant reads them. Background thread. */
    fun find(ctx: Context, linux: LinuxRuntime, settings: AppSettings, path: String, text: String, name: String, method: String): String {
        if (!settings.lspOn || !linux.active) return "The language server is off (Tools → language server check). Use find_symbol."
        val cmd = Lsp.languageId(path)?.let { SERVERS[it] } ?: return "There is no language server for $path here. Use find_symbol."
        val at = Lsp.locate(text, name) ?: return "$name does not appear in $path. Give a file where it is defined or used."
        if (skipping(cmd)) return "The language server did not answer a few minutes ago, so it was not asked again. Use find_symbol."
        val proc = start(ctx, linux, cmd) ?: return "Could not start the language server. Use find_symbol."
        try {
            val locs = Lsp.locations(proc.inputStream, proc.outputStream, ProotCommand.GUEST_WORKDIR, path, text, method, at.first, at.second, TIMEOUT_MS)
            if (locs == null) {
                failed(ctx, cmd, "$name in $path")
                return "The language server did not answer (install it from Tools: Language server for ...). Use find_symbol."
            }
            AppLog.add("lsp", "$name: ${locs.size} place(s) from `$cmd` ($method)")
            val project = Projects.current(ctx)
            return Lsp.formatLocations(name, method, locs) { p, ln ->
                File(project, p).takeIf { it.isFile && it.length() < 2_000_000 }?.useLines { it.drop(ln - 1).firstOrNull() }
            }
        } finally { proc.destroy() }
    }

    /** The note for the assistant, or null. Runs on a background thread. */
    fun check(ctx: Context, linux: LinuxRuntime, settings: AppSettings, path: String, text: String): String? {
        if (!settings.lspOn || !linux.active) return null
        val lang = Lsp.languageId(path) ?: return null
        val cmd = SERVERS[lang] ?: return null
        if (skipping(cmd)) return null
        val proc = start(ctx, linux, cmd) ?: return null
        try {
            val lines = Lsp.diagnose(proc.inputStream, proc.outputStream, ProotCommand.GUEST_WORKDIR, path, text, TIMEOUT_MS)
            when {
                lines == null -> {
                    failed(ctx, cmd, path)
                    return null
                }
                lines.isEmpty() -> { AppLog.add("lsp", "$path: no errors (checked with `$cmd`)"); return null }
                else -> {
                    AppLog.add("lsp", "$path: ${lines.size} error(s) from `$cmd`")
                    return "Language server errors in $path (fix them):\n" + lines.take(10).joinToString("\n") { "- $it" }
                }
            }
        } finally { proc.destroy() }
    }
}
