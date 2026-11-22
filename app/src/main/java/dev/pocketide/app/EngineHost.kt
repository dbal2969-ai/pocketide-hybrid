package dev.pocketide.app

import android.content.Context
import dev.pocketide.app.core.Engine
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Runs the built-in engine (Engine): downloads a model, starts llama-server from nativeLibraryDir with that model, and stops it.
 * One server at a time; it keeps running while the app's process lives. Blocking calls: run them off the main thread.
 */
object EngineHost {
    @Volatile private var process: Process? = null
    @Volatile private var loaded: String = ""
    private val lock = Any()

    fun binary(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, Engine.BINARY)

    /** True when this build carries the engine (arm64 builds made with engine/build-llama.sh). */
    fun available(ctx: Context): Boolean = binary(ctx).let { it.isFile && it.length() > 0 }

    fun modelDir(ctx: Context): File = File(ctx.filesDir, "engine-models").also { it.mkdirs() }

    fun modelFile(ctx: Context, m: Engine.Model): File = File(modelDir(ctx), m.file)

    fun downloaded(ctx: Context, m: Engine.Model): Boolean = modelFile(ctx, m).let { it.isFile && it.length() == m.bytes }

    val running: Boolean get() = process?.isAlive == true

    /** The model id the server has loaded, or "" when stopped. */
    val loadedModel: String get() = if (running) loaded else ""

    fun log(ctx: Context): File = File(ctx.filesDir, "engine.log")

    /**
     * Starts the server with [m] (stopping one that serves another model) and waits until it answers. Returns null when ready, or a
     * readable reason. Loading a 4B model takes a few seconds; the wait allows two minutes for large models on slow storage.
     */
    fun ensure(ctx: Context, m: Engine.Model): String? = synchronized(lock) {
        if (running && loaded == m.id && healthy()) return null
        if (!available(ctx)) return "This build has no built-in engine (it needs an arm64 phone and an APK built with the engine)."
        if (!downloaded(ctx, m)) return "${m.name} is not downloaded yet. Download it on the Built-in engine screen."
        stopLocked()
        val cmd = Engine.args(binary(ctx).absolutePath, modelFile(ctx, m).absolutePath, Runtime.getRuntime().availableProcessors())
        val p = try {
            ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log(ctx)).directory(ctx.filesDir).start()
        } catch (e: Exception) { return "The engine did not start: ${e.message}" }
        process = p; loaded = m.id
        val until = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < until) {
            if (!p.isAlive) { process = null; loaded = ""; return "The engine stopped while loading ${m.name}: ${tail(ctx)}" }
            if (healthy()) return null
            Thread.sleep(500)
        }
        stopLocked()
        "The engine did not get ready in two minutes: ${tail(ctx)}"
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        process?.let { p -> p.destroy(); if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }
        process = null; loaded = ""
    }

    private fun healthy(): Boolean = try {
        val c = URL("http://127.0.0.1:${Engine.PORT}/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1000; c.readTimeout = 2000
        val ok = c.responseCode == 200
        c.disconnect(); ok
    } catch (e: Exception) { false }

    private fun tail(ctx: Context): String = try { log(ctx).readLines().takeLast(4).joinToString(" | ").take(400) } catch (e: Exception) { "" }

    /**
     * Downloads [m] with resume (a .part file and an HTTP Range request), then checks its SHA-256 against Hugging Face's.
     * [progress] gets bytes done and total; [cancelled] is polled. Returns null on success or a readable reason.
     */
    fun download(ctx: Context, m: Engine.Model, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): String? {
        val part = File(modelDir(ctx), Engine.partName(m))
        val free = modelDir(ctx).usableSpace
        val have = if (part.isFile) part.length() else 0L
        if (free < m.bytes - have + 200_000_000L) return "Not enough free space: ${m.name} needs ${Engine.size(m.bytes - have)} and the phone has ${Engine.size(free)} free."
        var url = m.url
        try {
            var c: HttpURLConnection
            var hops = 0
            while (true) {   // Hugging Face redirects to its file store
                c = URL(url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000; c.readTimeout = 60_000
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                if (code in 300..399 && hops++ < 5) { url = URL(URL(url), c.getHeaderField("Location")).toString(); c.disconnect(); continue }
                break
            }
            val code = c.responseCode
            if (code != 200 && code != 206) return "The download failed (HTTP $code)."
            val start = if (code == 206) have else 0L
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(start); out.seek(start)
                var done = start
                val buf = ByteArray(1 shl 16)
                c.inputStream.use { ins ->
                    var last = 0L
                    while (true) {
                        if (cancelled()) return "Download paused. Tap Download again to carry on."
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (done - last > 4_000_000) { progress(done, m.bytes); last = done }
                    }
                }
                progress(done, m.bytes)
                if (done != m.bytes) return "The download stopped early (${Engine.size(done)} of ${Engine.size(m.bytes)}). Tap Download to carry on."
            }
        } catch (e: Exception) { return "The download stopped: ${e.message}. Tap Download to carry on." }
        if (sha256(part) != m.sha256) { part.delete(); return "The downloaded file was damaged (its checksum did not match), so it was removed. Try again." }
        return if (part.renameTo(modelFile(ctx, m))) null else "Could not save the model file."
    }

    fun delete(ctx: Context, m: Engine.Model) {
        if (loaded == m.id) stop()
        modelFile(ctx, m).delete(); File(modelDir(ctx), Engine.partName(m)).delete()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val buf = ByteArray(1 shl 20); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
