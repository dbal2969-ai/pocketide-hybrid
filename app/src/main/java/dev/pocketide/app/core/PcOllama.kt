package dev.pocketide.app.core

/**
 * Ollama on the person's own computer, reached over Wi-Fi (1.5.180). Acode's AI agent lists LAN servers as "local endpoints"; a
 * desktop GPU runs 14B-32B models, where agents start to manage well-scoped tasks, while 4B-8B on a phone pass about half of the hard
 * benchmark tasks. Pure helpers: the address the person types becomes the OpenAI-style base, and the setup text for each system.
 */
object PcOllama {
    /** The window the setup asks the computer's Ollama to use (OLLAMA_CONTEXT_LENGTH); a server left on its default is learned from its error. */
    const val CONTEXT = 32768

    /** What the computer's Ollama is started with: listening on the network, a 32K window, flash attention, a q8_0 cache. */
    val SETTINGS: Map<String, String> = linkedMapOf("OLLAMA_HOST" to "0.0.0.0:11434", "OLLAMA_CONTEXT_LENGTH" to "$CONTEXT",
        "OLLAMA_FLASH_ATTENTION" to "1", "OLLAMA_KV_CACHE_TYPE" to "q8_0")

    class Guide(val system: String, val steps: List<String>, val commands: List<String>)

    val GUIDES: List<Guide> = listOf(
        Guide("Windows", listOf("Install Ollama from ollama.com and quit it from the tray icon.",
            "Open PowerShell and run these lines, then start Ollama again from the Start menu.",
            "Allow Ollama through Windows Firewall on private networks when asked.",
            "Find the computer's address: run ipconfig and look for IPv4 Address (like 192.168.1.20)."),
            SETTINGS.map { (k, v) -> "setx $k \"$v\"" }),
        Guide("Mac", listOf("Install Ollama from ollama.com and quit it from the menu bar.",
            "Open Terminal and run these lines, then start Ollama again.",
            "Find the computer's address: System Settings → Wi-Fi → Details → IP address."),
            SETTINGS.map { (k, v) -> "launchctl setenv $k \"$v\"" }),
        Guide("Linux", listOf("Install Ollama (curl -fsSL https://ollama.com/install.sh | sh).",
            "Run sudo systemctl edit ollama.service, add these lines under [Service], save, then run the two commands after them.",
            "Find the computer's address: hostname -I."),
            SETTINGS.map { (k, v) -> "Environment=\"$k=$v\"" } + listOf("sudo systemctl daemon-reload", "sudo systemctl restart ollama")))

    /** Then, on the computer: a model that fits its graphics card (about 1 GB of video memory per billion parameters at 4 bits, plus the window). */
    val PULL_HINT = "Then download a model on the computer, for example: ollama pull qwen3.5:27b (24 GB graphics card) or ollama pull qwen3.5:9b (8-12 GB)."

    /**
     * The base URL for what the person typed: "192.168.1.20", "192.168.1.20:11434", "pc.local" or a full http address; null for
     * text that is not an address. A missing port is Ollama's 11434; a missing scheme is http (Ollama has no TLS of its own).
     */
    fun baseFor(typed: String): String? {
        var t = typed.trim().trimEnd('/')
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val scheme = Regex("""^(https?)://""", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.lowercase() ?: "http"
        t = t.replace(Regex("""^https?://""", RegexOption.IGNORE_CASE), "")
        t = t.removeSuffix("/v1").removeSuffix("/api").trimEnd('/')
        if (t.contains('/')) return null
        val m = Regex("""^([A-Za-z0-9.-]+)(?::(\d{1,5}))?$""").find(t) ?: return null
        val host = m.groupValues[1]
        val port = m.groupValues[2].ifEmpty { "11434" }.toInt()
        if (port !in 1..65535 || host.startsWith('.') || host.endsWith('.') || host.startsWith('-')) return null
        if (host.all { it.isDigit() || it == '.' } && !isIpv4(host)) return null
        return "$scheme://$host:$port/v1"
    }

    private fun isIpv4(h: String): Boolean = h.split('.').let { p -> p.size == 4 && p.all { it.isNotEmpty() && it.length <= 3 && it.toInt() in 0..255 } }

    /** True for a server on another machine (not this phone). */
    fun isRemote(base: String): Boolean {
        val host = Regex("""^[a-z]+://([^/:]+)""", RegexOption.IGNORE_CASE).find(base.trim())?.groupValues?.get(1)?.lowercase() ?: return false
        return host != "localhost" && host != "127.0.0.1" && host != "::1" && host != "0.0.0.0"
    }

    /**
     * A hint for an address that did not answer: the usual reasons, most likely first (the server listening only on localhost is
     * Ollama's default, so it comes first).
     */
    fun whyNoAnswer(base: String): String =
        "No answer from $base. Check that: Ollama on the computer was restarted after setting OLLAMA_HOST=0.0.0.0:11434 (by default it " +
            "only answers the computer itself); the phone is on the same Wi-Fi; the computer's firewall allows port 11434; the address is current " +
            "(home routers can change it, so reserve one in the router's settings)."
}
