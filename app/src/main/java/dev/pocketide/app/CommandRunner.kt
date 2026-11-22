package dev.pocketide.app

import android.os.Handler
import android.os.Looper
import java.io.File

class CommandCall internal constructor(internal val bp: BoundedProcess) {
    // Kills the whole process tree, which includes PRoot's guest processes in the GPL route.
    fun cancel() = bp.cancel()
}

/**
 * Runs a line in the project folder with the limits of BoundedProcessRunner. Callbacks arrive on the main thread.
 * By default the line runs in Android's own shell; in the GPL route argv carries the PRoot command instead.
 */
object CommandRunner {
    private val main = Handler(Looper.getMainLooper())
    private const val MAX_OUTPUT = 200_000

    fun run(
        dir: File, home: File, command: String, timeoutSeconds: Long,
        onOutput: (String) -> Unit, onExit: (Int) -> Unit,
        // GPL route: when set, run this argument list (PRoot) instead of /system/bin/sh -c command.
        argv: List<String>? = null, extraEnv: Map<String, String> = emptyMap()
    ): CommandCall = runDetailed(dir, home, command, timeoutSeconds, onOutput, argv, extraEnv) { onExit(it.code) }

    /** Like run, but reports how the command ended (timeout, output limit, cancelled). */
    fun runDetailed(
        dir: File, home: File, command: String, timeoutSeconds: Long,
        onOutput: (String) -> Unit,
        argv: List<String>? = null, extraEnv: Map<String, String> = emptyMap(),
        onResult: (RunResult) -> Unit
    ): CommandCall {
        val env = HashMap<String, String>()
        env["HOME"] = home.absolutePath
        env["TMPDIR"] = home.absolutePath
        env["TERM"] = "dumb"
        env.putAll(extraEnv)
        val bp = BoundedProcessRunner.start(
            argv ?: listOf("/system/bin/sh", "-c", command), dir, env, timeoutSeconds * 1000, MAX_OUTPUT,
            { s -> main.post { onOutput(s) } },
            { r ->
                main.post {
                    if (r.reason == ExitReason.TIMEOUT) onOutput("\nStopped after $timeoutSeconds seconds.\n")
                    onResult(r)
                }
            }
        )
        return CommandCall(bp)
    }
}
