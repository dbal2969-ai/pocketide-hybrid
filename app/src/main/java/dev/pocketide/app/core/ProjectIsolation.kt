package dev.pocketide.app.core

/** Keeps projects apart: each one gets its own web origin in the preview and its own /tmp in Linux. */
object ProjectIsolation {
    private fun slug(name: String, max: Int): String {
        val s = name.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("").trim('-').replace(Regex("-+"), "-")
        return s.take(max).trim('-').ifEmpty { "p" }
    }

    /** A short stable tag from the exact name, so "Snake" and "snake", or "a b" and "a-b", never share anything. */
    private fun tag(name: String): String {
        var h = 1125899906842597L
        for (c in name) h = 31 * h + c.code
        return java.lang.Long.toHexString(h and 0xFFFFFFL).padStart(6, '0')
    }

    /**
     * The address the preview uses for a project: localStorage, IndexedDB and cookies belong to the address, so
     * projects with their own address cannot read each other's saved data.
     */
    fun previewHost(project: String): String = slug(project, 40) + "-" + tag(project) + ".pocketide.local"

    /** Folder name for a project's private /tmp inside the Linux environment. */
    fun guestTmpName(project: String): String = slug(project, 40) + "-" + tag(project)
}
