package dev.pocketide.app.core

/**
 * A short record of recent problems (model errors, failed edits, failed commands) and of each finished request ("run" lines), for
 * the Diagnostics report. Once [attach]ed it is also kept in a file, so the record survives an app restart or a crash.
 * Keys are masked before anything is written.
 */
object AppLog {
    private const val MAX = 80
    private const val MAX_FILE_BYTES = 32 * 1024
    private val lines = ArrayList<String>()
    @Volatile private var file: java.io.File? = null

    /** Keeps the record in [f] as well, and loads what an earlier run of the app left there. */
    @Synchronized fun attach(f: java.io.File) {
        file = f
        try { if (f.exists()) lines.addAll(0, f.readLines().filter { it.isNotBlank() }) } catch (e: Exception) { }
        while (lines.size > MAX) lines.removeAt(0)
    }

    @Synchronized fun add(kind: String, message: String, time: Long = System.currentTimeMillis()) {
        val clean = SecretMask.mask(message).replace(Regex("""(?i)(bearer\s+|api[_-]?key[=:\s]+|sk-|gsk_|AIza)[A-Za-z0-9_\-]{8,}"""), "$1…").replace('\n', ' ').take(300)
        val entry = "${time / 1000}|$kind|$clean"
        lines.add(entry)
        while (lines.size > MAX) lines.removeAt(0)
        try {
            val f = file ?: return
            if (f.exists() && f.length() > MAX_FILE_BYTES) f.writeText(lines.joinToString("\n", postfix = "\n"))
            else f.appendText(entry + "\n")
        } catch (e: Exception) { }
    }

    /** One line for a finished request: which model, how it ended, how long, tokens used, and files it changed. */
    fun run(provider: String, model: String, outcome: String, tokens: Long, filesChanged: Int, seconds: Long) =
        add("run", "$provider/$model · $outcome · ${seconds}s · $tokens tokens · $filesChanged ${if (filesChanged == 1) "file" else "files"} changed")

    @Synchronized fun clear() { lines.clear() }

    @Synchronized fun entries(): List<String> = lines.toList()

    /** Newest last; times as seconds ago relative to [now]. */
    @Synchronized fun format(now: Long = System.currentTimeMillis(), max: Int = 30): String =
        lines.takeLast(max).joinToString("\n") { l ->
            val (t, k, m) = l.split("|", limit = 3)
            val ago = (now / 1000 - (t.toLongOrNull() ?: 0L)).coerceAtLeast(0)
            val w = if (ago < 60) "${ago}s ago" else if (ago < 3600) "${ago / 60}m ago" else "${ago / 3600}h ago"
            "[$w] $k: $m"
        }
}

/** Setup help for using a model server on another computer or a rented GPU. */
object RemoteGuide {
    val TITLE = "Use a bigger model on another computer or GPU"

    fun steps(): String = """
Your phone sends the chat to a server that runs the model. Any server that speaks the OpenAI chat format works.

Option A: your own PC or Mac (free)
Menu ⋮ → Models (local AI) → Use your computer → Set up walks you through it: the settings for Ollama on Windows, Mac or Linux (OLLAMA_HOST=0.0.0.0 lets the phone reach it),
then the computer's address. On the same Wi-Fi use its home address (like 192.168.1.20). Away from home, install Tailscale (free)
on both and use the computer's Tailscale address (like 100.64.0.5). Good models for a computer: qwen3.5:27b (24 GB graphics card)
or qwen3.5:9b (8-12 GB).

Option B: a rented GPU (RunPod, Vast.ai)
1. Start an Ollama or vLLM container on the GPU and expose its port.
2. Put the public address ending in /v1 in the address field. If the server needs a key, paste it in the key field.
3. Rented GPUs bill by the hour, so stop them when you are done.

Safety: do not expose a server to the whole internet without a key. Tailscale keeps it private.
""".trim()

    /** A plain-language reason for a failed connection to a remote server. */
    fun explain(error: String, base: String): String {
        val e = error.lowercase()
        return when {
            base.isBlank() -> "Enter the server address first, for example http://100.64.0.5:11434/v1."
            !base.startsWith("http") -> "The address should start with http:// or https://."
            "401" in e || "403" in e -> "The server wants a key. Paste it in the key field."
            "timed out" in e || "timeout" in e -> "The server did not answer. Check that it is on, and that Tailscale or your VPN is connected on this phone."
            "refused" in e -> "The computer is reachable but nothing is listening on that port. Start the server with OLLAMA_HOST=0.0.0.0."
            "unknown host" in e || "unable to resolve" in e -> "That address could not be found. Check the spelling and that Tailscale is connected."
            else -> "Could not reach the server. Check that it is running, the address ends in /v1, and the phone can reach it."
        }
    }
}
