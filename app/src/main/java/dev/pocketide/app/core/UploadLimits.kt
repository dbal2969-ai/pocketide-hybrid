package dev.pocketide.app.core

/** How big an upload may be. Files are copied to storage as they arrive, so these are storage limits, not memory limits. */
object UploadLimits {
    /** One file added from the phone (file picker, chat attachment added to the project). */
    const val FILE = 512L * 1024 * 1024
    /** Everything in one imported zip, unpacked. */
    const val ZIP_TOTAL = 1024L * 1024 * 1024
    /** Files in one imported zip. */
    const val ZIP_ENTRIES = 100_000
    /** A chat attachment that is read into the message itself (its text goes to the model). Bigger files can still be added to the project. */
    const val ATTACH_READ = 25L * 1024 * 1024

    fun mb(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) "${bytes / (1024L * 1024 * 1024)} GB" else "${bytes / (1024 * 1024)} MB"

    /** A zip entry name that is safe to unpack: relative, no "..", no drive letters. */
    fun safeEntry(name: String): String? {
        val n = name.replace('\\', '/').trimStart('/')
        if (n.isEmpty() || n.endsWith("/")) return null
        if (n.split('/').any { it == ".." } || Regex("^[A-Za-z]:").containsMatchIn(n)) return null
        return n
    }
}
