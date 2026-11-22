package dev.pocketide.app.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ollama's own HTTP API (not the OpenAI-style /v1 one): is it running, which models it has,
 * downloading ("pulling") and deleting models. Plain java.net, so it runs on a JVM for tests.
 */
object OllamaApi {
    const val DEFAULT_ROOT = "http://127.0.0.1:11434"

    /** The server root from whatever the user typed: strips /v1, /chat/completions, /api and slashes. */
    fun root(base: String): String {
        var b = base.trim()
        if (b.isEmpty()) return DEFAULT_ROOT
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(b)) b = "http://$b"
        b = b.trimEnd('/')
        for (suffix in listOf("/chat/completions", "/v1", "/api")) {
            if (b.endsWith(suffix, ignoreCase = true)) b = b.dropLast(suffix.length).trimEnd('/')
        }
        return b
    }

    fun versionUrl(root: String) = "$root/api/version"
    fun tagsUrl(root: String) = "$root/api/tags"
    fun pullUrl(root: String) = "$root/api/pull"
    fun deleteUrl(root: String) = "$root/api/delete"

    fun pullBody(model: String): String = MiniJson.write(mapOf("model" to model, "stream" to true))
    fun deleteBody(model: String): String = MiniJson.write(mapOf("model" to model))

    /** Ollama model names: name[:tag], optionally with a namespace like user/name. */
    fun isValidName(name: String): Boolean =
        name.length in 1..200 && Regex("^[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*)?(:[A-Za-z0-9][A-Za-z0-9._-]*)?$").matches(name)

    /** "qwen2.5-coder" and "qwen2.5-coder:latest" are the same model. */
    fun normalize(name: String): String = name.trim().let { if (':' in it) it else "$it:latest" }
    fun sameModel(a: String, b: String): Boolean = normalize(a) == normalize(b)

    data class Installed(val name: String, val sizeBytes: Long, val parameterSize: String, val quantization: String)

    fun parseVersion(json: String): String? =
        try { MiniJson.obj(MiniJson.parse(json))?.get("version") as? String } catch (e: MiniJson.JsonError) { null }

    fun parseTags(json: String): List<Installed> {
        val o = try { MiniJson.obj(MiniJson.parse(json)) } catch (e: MiniJson.JsonError) { null } ?: return emptyList()
        val out = ArrayList<Installed>()
        for (e in MiniJson.list(o["models"]) ?: emptyList()) {
            val m = MiniJson.obj(e) ?: continue
            val name = (m["name"] ?: m["model"]) as? String ?: continue
            val details = MiniJson.obj(m["details"])
            out.add(Installed(name, (m["size"] as? Double)?.toLong() ?: 0L,
                details?.get("parameter_size") as? String ?: "", details?.get("quantization_level") as? String ?: ""))
        }
        return out.sortedBy { it.name }
    }

    /** One line of the streamed /api/pull reply. */
    data class PullEvent(val status: String, val digest: String?, val total: Long, val completed: Long, val error: String?) {
        val success: Boolean get() = status == "success"
    }

    fun parsePullLine(line: String): PullEvent? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val o = try { MiniJson.obj(MiniJson.parse(t)) } catch (e: MiniJson.JsonError) { null } ?: return null
        return PullEvent(
            o["status"] as? String ?: "",
            o["digest"] as? String,
            (o["total"] as? Double)?.toLong() ?: 0L,
            (o["completed"] as? Double)?.toLong() ?: 0L,
            o["error"] as? String
        )
    }

    /** Adds up the per-layer progress of a pull into one bar. */
    class PullProgress {
        private val layers = LinkedHashMap<String, LongArray>()
        var phase = "Starting…"
            private set
        var error: String? = null
            private set
        var done = false
            private set

        fun add(e: PullEvent) {
            if (e.error != null) { error = e.error; return }
            if (e.success) { done = true; phase = "Done"; return }
            if (e.digest != null && e.total > 0) {
                layers[e.digest] = longArrayOf(e.total, e.completed.coerceAtMost(e.total))
                phase = "Downloading"
            } else phase = friendly(e.status)
        }

        val totalBytes: Long get() = layers.values.sumOf { it[0] }
        val completedBytes: Long get() = layers.values.sumOf { it[1] }

        /** 0..1000, or -1 while the size is not known yet. */
        val permille: Int get() = if (totalBytes <= 0) -1 else ((completedBytes * 1000) / totalBytes).toInt().coerceIn(0, 1000)

        fun text(): String = when {
            error != null -> "Failed: $error"
            done -> "Downloaded"
            phase == "Downloading" && totalBytes > 0 -> "Downloading ${formatSize(completedBytes)} of ${formatSize(totalBytes)} (${permille / 10}%)"
            else -> phase
        }

        private fun friendly(status: String): String = when {
            status.startsWith("pulling manifest") -> "Looking up the model…"
            status.startsWith("verifying") -> "Checking the download…"
            status.startsWith("writing manifest") -> "Finishing…"
            status.startsWith("removing") -> "Cleaning up…"
            status.isBlank() -> phase
            else -> status.replaceFirstChar { it.uppercase() }
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000L -> "${bytes / 1_000_000} MB"
        bytes >= 1_000L -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }

    // ---- network (call from a background thread) ----

    class OllamaError(message: String) : Exception(message)

    private fun open(url: String, method: String, connectMs: Int, readMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        c.setRequestProperty("Accept", "application/json")
        return c
    }

    private fun errorText(c: HttpURLConnection, code: Int): String {
        val raw = try { c.errorStream?.bufferedReader()?.use { it.readText() } } catch (e: Exception) { null }
        val msg = raw?.let { r -> try { MiniJson.obj(MiniJson.parse(r))?.get("error") as? String } catch (e: Exception) { null } ?: r.take(200) }
        return if (msg.isNullOrBlank()) "Ollama returned HTTP $code." else msg
    }

    /** The server's version, or null when nothing answers. */
    fun version(root: String): String? = try {
        val c = open(versionUrl(root), "GET", 2500, 4000)
        try { if (c.responseCode == 200) parseVersion(c.inputStream.bufferedReader().use { it.readText() }) ?: "unknown" else null }
        finally { c.disconnect() }
    } catch (e: Exception) { null }

    fun tags(root: String): List<Installed> {
        val c = open(tagsUrl(root), "GET", 4000, 15000)
        try {
            val code = c.responseCode
            if (code != 200) throw OllamaError(errorText(c, code))
            return parseTags(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    fun delete(root: String, model: String) {
        val c = open(deleteUrl(root), "DELETE", 4000, 30000)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(deleteBody(model).toByteArray()) }
            val code = c.responseCode
            if (code != 200) throw OllamaError(errorText(c, code))
        } finally { c.disconnect() }
    }

    /** A running download; cancel() stops it (Ollama stops pulling when the client goes away). */
    class PullHandle {
        @Volatile var cancelled = false
            private set
        @Volatile internal var conn: HttpURLConnection? = null
        fun cancel() {
            cancelled = true
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }

    /**
     * Pulls a model, reporting progress after every line. Blocks until done.
     * Returns null on success, or an error message (also "Cancelled").
     */
    fun pull(root: String, model: String, handle: PullHandle, onProgress: (PullProgress) -> Unit): String? {
        val progress = PullProgress()
        try {
            val c = open(pullUrl(root), "POST", 5000, 10 * 60 * 1000)
            handle.conn = c
            if (handle.cancelled) return "Cancelled"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(pullBody(model).toByteArray()) }
            val code = c.responseCode
            if (code != 200) return errorText(c, code)
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    // Android's disconnect() also interrupts a blocked read; a plain JVM only notices here.
                    if (handle.cancelled) return "Cancelled"
                    val e = parsePullLine(line) ?: continue
                    progress.add(e)
                    onProgress(progress)
                    if (progress.error != null) return progress.error
                    if (progress.done) return null
                }
            }
            return if (handle.cancelled) "Cancelled" else if (progress.done) null else "The download stopped before it finished."
        } catch (e: Exception) {
            return if (handle.cancelled) "Cancelled" else "Could not download: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            try { handle.conn?.disconnect() } catch (e: Exception) { }
        }
    }
}
