package dev.pocketide.app.core

/**
 * A remote dev machine (1.5.112), like a Codespace: the project is copied to a server over SSH, and commands (yours from the Remote
 * screen, and the assistant's when "Run commands there" is on) run on that machine. Files the command changed come back to the phone.
 * The phone keeps editing; heavy builds, big npm installs, Android or Rust toolchains run on the server.
 * Pure logic: the SSH connection is RemoteClient.
 */
object RemoteDev {
    class Config(val host: String, val port: Int, val user: String, val dir: String) {
        val label: String get() = "$user@$host" + (if (port != 22) ":$port" else "")
    }

    /** "user@host", "user@host:2222" or "ssh -p 2222 user@host"; [dir] defaults to ~/pocketide/<project>. */
    fun parse(address: String, dir: String, project: String): Config? {
        var a = address.trim().removePrefix("ssh ").trim()
        var port = 22
        Regex("""-p\s*(\d{1,5})\s*""").find(a)?.let { port = it.groupValues[1].toInt(); a = a.replace(it.value, "").trim() }
        val m = Regex("""^([A-Za-z0-9._-]{1,64})@([A-Za-z0-9.-]{1,253}|\[[0-9a-fA-F:]+])(?::(\d{1,5}))?$""").find(a) ?: return null
        m.groupValues[3].takeIf { it.isNotEmpty() }?.let { port = it.toInt() }
        if (port !in 1..65535) return null
        val d = dir.trim().ifEmpty { "~/pocketide/" + safeName(project) }
        if (!Regex("""^[~A-Za-z0-9._/ -]{1,200}$""").matches(d) || d.contains("..")) return null
        return Config(m.groupValues[2].trim('[', ']'), port, m.groupValues[1], d.trimEnd('/'))
    }

    fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifEmpty { "project" }

    /** The folder as the shell sees it: ~ stays unquoted so it expands, the rest is quoted. */
    fun shellDir(dir: String): String = if (dir == "~") "~" else if (dir.startsWith("~/")) "~/" + quote(dir.removePrefix("~/")) else quote(dir)
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** A command run in the project's folder on the server, with a login shell's PATH (nvm, cargo, sdkman). */
    fun wrap(c: Config, command: String): String =
        "mkdir -p ${shellDir(c.dir)} && cd ${shellDir(c.dir)} && touch .pocketide-mark && " + "bash -lc " + quote(command)

    /** Files the last command changed or made (newer than the mark it left), skipping folders that are not project files. */
    fun changedSince(c: Config): String = "cd ${shellDir(c.dir)} && find . -type f -newer .pocketide-mark " +
        SKIP.joinToString(" ") { "-not -path './$it/*'" } + " -not -name .pocketide-mark -size -2M | head -200"

    val SKIP = listOf(".git", "node_modules", "target", "build", "dist", ".gradle", "__pycache__", ".venv", "venv", ".next")

    fun skipped(path: String): Boolean = path.split('/').any { it in SKIP }

    /** What to upload and delete: local files whose hash differs from the last sync, and synced files that are gone. */
    class SyncPlan(val upload: List<String>, val delete: List<String>)
    fun plan(local: Map<String, String>, synced: Map<String, String>): SyncPlan =
        SyncPlan(local.filter { (p, h) -> !skipped(p) && synced[p] != h }.keys.sorted(), synced.keys.filter { it !in local && !skipped(it) }.sorted())

    /** The record of what the server holds: path to hash, one per line. */
    fun manifest(m: Map<String, String>): String = m.entries.sortedBy { it.key }.joinToString("\n") { it.value + " " + it.key }
    fun manifestParse(text: String?): Map<String, String> = text.orEmpty().lines().mapNotNull { l -> val i = l.indexOf(' '); if (i <= 0) null else l.substring(i + 1) to l.substring(0, i) }.toMap()

    fun hash(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Paths from changedSince's output, as project paths. */
    fun changedPaths(out: String): List<String> = out.lines().map { it.trim().removePrefix("./") }.filter { it.isNotEmpty() && !it.startsWith("/") && !it.contains("..") && !skipped(it) }

    /** The setup steps shown on the Remote screen. */
    fun setupSteps(publicKey: String): String =
        "1. On the server, add this line to ~/.ssh/authorized_keys (Copy key):\n${publicKey.take(80)}…\n\n" +
            "2. Type the address (user@host, or user@host:port) and tap Connect.\n\n" +
            "Any machine you can reach by SSH works: a home computer, a cloud server (many have a free tier), or a Raspberry Pi. It needs bash; rsync is not needed."
}
