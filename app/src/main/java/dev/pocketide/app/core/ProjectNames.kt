package dev.pocketide.app.core

/** Rules for project (folder) names. Pure, so it is JVM-testable. */
object ProjectNames {
    const val MAX = 48
    const val DEFAULT = "My project"
    private val BAD = Regex("""[/\\:*?"<>|\u0000-\u001f\u007f]""")

    /** A safe folder name from what the user typed, or null if nothing usable is left. */
    fun sanitize(raw: String): String? {
        var s = BAD.replace(raw, "-")
        s = s.replace(Regex("""\s+"""), " ").trim()
        s = s.trimStart('.', ' ', '-').trimEnd(' ', '.')
        if (s.length > MAX) s = s.substring(0, MAX).trimEnd(' ', '.', '-')
        if (s.isEmpty() || s == "." || s == "..") return null
        return s
    }

    /** name, or "name 2", "name 3", … so it does not clash with existing names (ignoring case). */
    fun unique(name: String, existing: Collection<String>): String {
        val taken = existing.map { it.lowercase() }.toSet()
        if (name.lowercase() !in taken) return name
        var n = 2
        while (true) {
            val suffix = " $n"
            val candidate = name.take(MAX - suffix.length).trimEnd() + suffix
            if (candidate.lowercase() !in taken) return candidate
            n++
        }
    }

    fun copyName(name: String, existing: Collection<String>): String = unique((name.take(MAX - 5).trimEnd() + " copy"), existing)

    /** A project name from a GitHub repository name, e.g. "Test-Coder2.2". */
    fun fromRepo(repo: String, existing: Collection<String>): String = unique(sanitize(repo) ?: DEFAULT, existing)
}
