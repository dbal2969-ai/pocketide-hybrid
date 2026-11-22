package dev.pocketide.app

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// A process runner with hard limits: a timeout, an output cap, and a kill that takes the whole process tree down.
// Ported from the author's MobileCoder app (BoundedProcessRunner.kt), rewritten without coroutines and without
// Android classes so it can be tested on a plain JVM.

enum class ExitReason { OK, EXIT_CODE, TIMEOUT, CANCELLED, OUTPUT_LIMIT, FAILED_TO_START }

data class RunResult(
    val code: Int,
    val reason: ExitReason,
    /** stdout and stderr merged in the order they arrived, up to the cap. */
    val output: String,
    val truncated: Boolean,
    val durationMs: Long
) {
    /** One line describing how the run ended, or null for a clean exit. */
    fun summary(): String? = when (reason) {
        ExitReason.OK -> null
        ExitReason.EXIT_CODE -> "exit $code"
        ExitReason.TIMEOUT -> "stopped: took longer than the time limit"
        ExitReason.CANCELLED -> "stopped"
        ExitReason.OUTPUT_LIMIT -> "stopped: too much output"
        ExitReason.FAILED_TO_START -> "could not start"
    }
}

class BoundedProcess internal constructor() {
    internal val cancelled = AtomicBoolean(false)
    @Volatile internal var process: Process? = null
    @Volatile internal var kill: (() -> Unit)? = null

    val isCancelled: Boolean get() = cancelled.get()

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        kill?.let { k -> Thread { k() }.start() }
    }
}

object BoundedProcessRunner {
    /** Characters kept from the end of a long output. */
    const val TAIL_CHARS = 6000

    /** Shell used to send signals; /system/bin/sh on Android, /bin/sh elsewhere. */
    private val shell: String = listOf("/system/bin/sh", "/bin/sh").firstOrNull { File(it).canExecute() } ?: "sh"

    /**
     * Starts a command. onOutput and onExit run on background threads (one at a time, never concurrently);
     * the caller moves them to the UI thread if needed.
     */
    fun start(
        command: List<String>,
        dir: File,
        env: Map<String, String>,
        timeoutMs: Long,
        maxOutputChars: Int,
        onOutput: (String) -> Unit,
        onExit: (RunResult) -> Unit
    ): BoundedProcess {
        val handle = BoundedProcess()
        val t = Thread({
            try {
                runBlocking(handle, command, dir, env, timeoutMs, maxOutputChars, onOutput, onExit)
            } catch (e: Throwable) {
                // never let a background failure close the app; report it as a failed run
                val msg = "Internal error while running the command: $e\n"
                try { onOutput(msg) } catch (ignored: Throwable) { }
                try { onExit(RunResult(-1, ExitReason.FAILED_TO_START, msg, false, 0)) } catch (ignored: Throwable) { }
            }
        }, "pocketide-run")
        t.isDaemon = true
        t.start()
        return handle
    }

    private fun runBlocking(
        handle: BoundedProcess, command: List<String>, dir: File, env: Map<String, String>,
        timeoutMs: Long, maxOutputChars: Int, onOutput: (String) -> Unit, onExit: (RunResult) -> Unit
    ) {
        val started = System.currentTimeMillis()
        val lock = Object()
        val out = StringBuilder()
        var truncated = false
        // after the cap, only the end of the output is kept (errors and summaries come last); the command keeps running
        val tail = StringBuilder()
        var dropped = 0L
        val overLimit = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)

        val proc: Process = try {
            val pb = ProcessBuilder(command)
            pb.directory(dir)
            pb.redirectErrorStream(false)
            pb.environment().putAll(env)
            pb.start()
        } catch (e: Exception) {
            val msg = "Could not run the command: ${e.message}\n"
            synchronized(lock) { onOutput(msg) }
            onExit(RunResult(127, ExitReason.FAILED_TO_START, msg, false, System.currentTimeMillis() - started))
            return
        }
        handle.process = proc
        handle.kill = { killTree(proc) }
        if (handle.isCancelled) killTree(proc)

        // Long output used to stop the command, which killed an unzip of a big archive halfway (one line per file).
        // Now the start is kept up to the cap, then only the last TAIL_CHARS; the command runs to its end or its time limit.
        fun sink(s: String) {
            synchronized(lock) {
                if (!truncated) {
                    val room = maxOutputChars - out.length
                    if (s.length <= room) { out.append(s); onOutput(s); return }
                    val part = if (room > 0) s.substring(0, room) else ""
                    out.append(part)
                    truncated = true
                    onOutput(part + "\n[long output: showing the start and the end]\n")
                    tail.append(s.substring(part.length))
                } else tail.append(s)
                if (tail.length > TAIL_CHARS) { val cut = tail.length - TAIL_CHARS; dropped += cut; tail.delete(0, cut) }
            }
        }

        val readers = listOf(proc.inputStream, proc.errorStream).map { stream -> pump(stream, ::sink) }
        try { proc.outputStream.close() } catch (e: Exception) { /* no stdin */ }

        val finished = try { proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { false }
        if (!finished) {
            if (!handle.isCancelled && !overLimit.get()) timedOut.set(true)
            killTree(proc)
            try { proc.waitFor(3, TimeUnit.SECONDS) } catch (e: InterruptedException) { /* give up */ }
        }
        // children that kept the pipes open could stall the readers; don't wait forever
        for (r in readers) r.join(2000)
        if (readers.any { it.isAlive }) {
            killTree(proc)
            for (r in readers) r.join(1000)
        }
        val code = try { proc.exitValue() } catch (e: IllegalThreadStateException) { -1 }
        val reason = when {
            handle.isCancelled -> ExitReason.CANCELLED
            timedOut.get() -> ExitReason.TIMEOUT
            overLimit.get() -> ExitReason.OUTPUT_LIMIT
            code == 0 -> ExitReason.OK
            else -> ExitReason.EXIT_CODE
        }
        val finalCode = when (reason) {
            ExitReason.CANCELLED -> 130
            ExitReason.TIMEOUT -> 124
            ExitReason.OUTPUT_LIMIT -> if (code == 0) 0 else code
            else -> code
        }
        val text = synchronized(lock) {
            if (!truncated) out.toString()
            else out.toString() + "\n[… ${dropped} characters left out …]\n" + tail.toString()
        }
        onExit(RunResult(finalCode, reason, text, truncated, System.currentTimeMillis() - started))
    }

    private fun pump(stream: InputStream, sink: (String) -> Unit): Thread {
        val t = Thread({
            val buf = ByteArray(4096)
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
            val bb = java.nio.ByteBuffer.allocate(8192)
            val cb = java.nio.CharBuffer.allocate(8192)
            try {
                stream.use { s ->
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        bb.put(buf, 0, n)
                        bb.flip()
                        dec.decode(bb, cb, false)
                        bb.compact()
                        cb.flip()
                        if (cb.hasRemaining()) sink(cb.toString())
                        cb.clear()
                    }
                }
            } catch (e: Exception) { /* stream closed by a kill */ }
        }, "pocketide-pipe")
        t.isDaemon = true
        t.start()
        return t
    }

    /** The process id. Process.pid() only exists on newer Android versions, so older ones are read by reflection. */
    fun pidOf(p: Process): Int? {
        try { return (Process::class.java.getMethod("pid").invoke(p) as Long).toInt() } catch (e: Throwable) { /* older runtime */ }
        try {
            val f = p.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            return f.getInt(p)
        } catch (e: Throwable) { /* not there either */ }
        return Regex("pid=(\\d+)").find(p.toString())?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Children of each process, from /proc/<pid>/stat (field 4 is the parent id). Only our own processes are visible. */
    private fun childrenMap(): Map<Int, List<Int>> {
        val out = HashMap<Int, MutableList<Int>>()
        val kids = File("/proc").listFiles() ?: return out
        for (d in kids) {
            val pid = d.name.toIntOrNull() ?: continue
            val stat = try { File(d, "stat").readText() } catch (e: Exception) { continue }
            val close = stat.lastIndexOf(')')
            if (close < 0) continue
            val fields = stat.substring(close + 2).split(' ')
            val ppid = fields.getOrNull(1)?.toIntOrNull() ?: continue
            out.getOrPut(ppid) { ArrayList() }.add(pid)
        }
        return out
    }

    /** All descendants of pid, deepest first. */
    fun descendants(pid: Int): List<Int> {
        val map = childrenMap()
        val out = ArrayList<Int>()
        fun walk(p: Int, depth: Int) {
            if (depth > 64) return
            for (c in map[p] ?: emptyList()) { walk(c, depth + 1); out.add(c) }
        }
        walk(pid, 0)
        return out
    }

    /** Kills the process and everything it started. */
    fun killTree(p: Process) {
        val pid = pidOf(p)
        if (pid != null) {
            val tree = descendants(pid)
            if (tree.isNotEmpty()) {
                try {
                    ProcessBuilder(shell, "-c", "kill -9 " + tree.joinToString(" ") + " 2>/dev/null").start().waitFor(2, TimeUnit.SECONDS)
                } catch (e: Exception) { /* best effort */ }
            }
        }
        try { p.destroyForcibly() } catch (e: Exception) { /* already gone */ }
    }
}
