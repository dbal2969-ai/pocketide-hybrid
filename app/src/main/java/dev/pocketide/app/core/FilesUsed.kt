package dev.pocketide.app.core

/** "Used 4 files" line shown under a reply: which files the assistant read before answering. */
object FilesUsed {
    /** The distinct paths from tool labels like "Read app.js" or "Read app.js (unchanged)", in order. */
    fun paths(labels: List<String>): List<String> =
        labels.filter { it.startsWith("Read ") }.map { it.removePrefix("Read ").substringBefore(" (").trim() }.filter { it.isNotEmpty() }.distinct()

    /** The line, or null when nothing was read. */
    fun line(labels: List<String>, show: Int = 5): String? {
        val p = paths(labels)
        if (p.isEmpty()) return null
        val shown = p.take(show).joinToString(", ")
        val more = if (p.size > show) " and ${p.size - show} more" else ""
        return "Read ${p.size} file${if (p.size == 1) "" else "s"}: $shown$more"
    }
}
