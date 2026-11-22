package dev.pocketide.app.core

/** Small pure helpers for importing, naming and saving files of any type. */
object FileKinds {
    private val MIME = mapOf(
        "txt" to "text/plain", "md" to "text/markdown", "html" to "text/html", "htm" to "text/html", "css" to "text/css",
        "js" to "text/javascript", "json" to "application/json", "csv" to "text/csv", "xml" to "application/xml",
        "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
        "webp" to "image/webp", "bmp" to "image/bmp", "pdf" to "application/pdf", "zip" to "application/zip",
        "gz" to "application/gzip", "tar" to "application/x-tar", "7z" to "application/x-7z-compressed",
        "apk" to "application/vnd.android.package-archive", "jar" to "application/java-archive",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text", "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation", "epub" to "application/epub+zip",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "mp4" to "video/mp4"
    )

    private val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    fun extension(path: String): String = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** The media type for a save dialog; unknown types are octet-stream so the phone keeps the file name as given. */
    fun mime(path: String): String = MIME[extension(path)] ?: "application/octet-stream"

    /** Pictures the phone can decode and the AI can look at. */
    fun isImage(path: String): Boolean = extension(path) in IMAGES

    /** A file name from another app made safe for the project: no folders, no control characters, never empty or hidden. */
    fun safeName(name: String): String {
        var n = name.substringAfterLast('/').substringAfterLast('\\')
        n = n.filter { it >= ' ' && it != '\u007F' }.trim()
        while (n.startsWith(".")) n = n.substring(1)
        if (n.length > 120) {
            val ext = n.substringAfterLast('.', "")
            n = n.take(110) + if (ext.isNotEmpty() && ext.length <= 8) ".$ext" else ""
        }
        return n.ifEmpty { "file" }
    }

    /** name, or "name (1).ext", "name (2).ext"… for the first one that does not exist yet. */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (i < 1000) { val c = "$stem ($i)$ext"; if (!exists(c)) return c; i++ }
        return "$stem (${System.currentTimeMillis()})$ext"
    }

    /** True if the first 4 KB has a zero byte, which text files do not have. */
    fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 4096)
        for (i in 0 until n) if (bytes[i].toInt() == 0) return true
        return false
    }

    /** Files worth a "save to phone" button after a command ran: not caches or dependency folders. */
    fun deliverable(path: String): Boolean =
        !path.startsWith("node_modules/") && !path.contains("/node_modules/") && !path.contains("__pycache__/") && !path.endsWith(".pyc") && !path.startsWith(".git/")

    /** Files that appeared or changed between two snapshots (path to a size/time signature), sorted. */
    fun changed(before: Map<String, Long>, after: Map<String, Long>): List<String> =
        after.filter { (p, sig) -> before[p] != sig }.keys.sorted()
}
