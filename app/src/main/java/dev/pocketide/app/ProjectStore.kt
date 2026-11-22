package dev.pocketide.app

import dev.pocketide.app.core.DocText
import dev.pocketide.app.core.FileKinds
import dev.pocketide.app.core.ProjectFile
import dev.pocketide.app.core.Sample
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The project's files live in a real folder, so shell commands see exactly what the editor sees.
 * root is the current project's folder; it changes when the user switches projects (v0.4 W1), so
 * code holding this store always reads root at the moment it needs it.
 */
class ProjectStore(root: File, seed: Boolean = true) {
    private val SKIP_FIND = setOf("node_modules", "src", "build", "target", "out", "libs", "gradle", "__pycache__")
    @Volatile var root: File = root
        private set
    private val binaryExt = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "pdf", "zip", "docx", "xlsx", "pptx", "mp3", "mp4", "wav", "ogg",
        "woff", "woff2", "ttf", "otf", "apk", "so", "jar", "class", "gz", "tar", "7z",
        "pyc", "pyo", "o", "a", "dll", "exe", "dylib", "bin", "db", "sqlite", "sqlite3", "pkl", "pickle", "dex", "wasm", "odt", "ods", "odp", "epub",
        "doc", "xls", "ppt", "docm", "xlsm", "pptm", "rar", "bz2", "xz", "iso", "dmg", "heic", "tif", "tiff", "mov", "avi", "mkv", "flac", "m4a", "aac"
    )

    init {
        root.mkdirs()
        if (seed && list().isEmpty()) Sample.files.forEach { (p, t) -> write(p, t) }
    }

    /** Points the store at another project folder (v0.4 W1). */
    fun switchTo(dir: File) {
        dir.mkdirs()
        root = dir
    }

    /** Folders in the project (relative paths, sorted), including empty ones, for the file tree. */
    fun listDirs(): List<String> {
        val out = ArrayList<String>()
        fun walk(dir: File, prefix: String) {
            val kids = dir.listFiles() ?: return
            for (k in kids.sortedBy { it.name }) {
                if (out.size >= 2000) return
                if (k.name.startsWith(".") || !k.isDirectory) continue
                out.add(prefix + k.name)
                walk(k, prefix + k.name + "/")
            }
        }
        walk(root, "")
        return out
    }

    fun isDir(path: String): Boolean = resolve(path)?.isDirectory == true

    fun mkdir(path: String): Boolean {
        if (archived) return false
        val f = resolve(path) ?: return false
        if (f.exists()) return f.isDirectory
        return f.mkdirs()
    }

    /** Deletes a folder and everything in it. Returns the files that were removed. */
    fun deleteDir(path: String): List<String> {
        val f = resolve(path) ?: return emptyList()
        if (!f.isDirectory) return emptyList()
        val prefix = path.trimEnd('/') + "/"
        val gone = list().filter { it.startsWith(prefix) }
        f.deleteRecursively()
        return gone
    }

    /** Moves or renames a file or folder. Returns false if the target exists or a path is invalid. */
    fun move(from: String, to: String): Boolean {
        if (archived) return false
        val a = resolve(from) ?: return false
        val b = resolve(to) ?: return false
        if (!a.exists() || b.exists()) return false
        if (a.isDirectory && (b.path + File.separator).startsWith(a.path + File.separator)) return false
        b.parentFile?.mkdirs()
        return a.renameTo(b)
    }

    /** Moves the file [from] onto the file [to], replacing it (1.5.117: the file tree asks first). Files only; false if either path is invalid. */
    fun replace(from: String, to: String): Boolean {
        if (archived) return false
        val a = resolve(from) ?: return false
        val b = resolve(to) ?: return false
        if (!a.isFile || !b.isFile || a.canonicalPath == b.canonicalPath) return false
        return try { java.nio.file.Files.move(a.toPath(), b.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); true } catch (e: Exception) { false }
    }

    private val sniffCache = HashMap<String, Boolean>()

    /** Known binary extensions, or any existing file whose first 4 KB contains a zero byte (compiled output, databases, unknown formats). */
    fun isBinary(path: String): Boolean {
        if (path.substringAfterLast('.', "").lowercase() in binaryExt) return true
        val f = resolve(path) ?: return false
        if (!f.isFile) return false
        val len = f.length()
        if (len == 0L) return false
        val key = "$path|$len|${f.lastModified()}"
        synchronized(sniffCache) { sniffCache[key]?.let { return it } }
        val bin = try {
            f.inputStream().use { st ->
                val b = ByteArray(4096)
                val n = st.read(b)
                n > 0 && (0 until n).any { b[it].toInt() == 0 }
            }
        } catch (e: Exception) { false }
        synchronized(sniffCache) { if (sniffCache.size > 3000) sniffCache.clear(); sniffCache[key] = bin }
        return bin
    }

    /** A path inside the project, or null if it is empty, absolute or tries to leave the folder. */
    @Volatile private var canon: Pair<File, String>? = null
    private val rootCanonical: String get() { val r = root; canon?.let { if (it.first === r) return it.second }; return r.canonicalPath.also { canon = r to it } }

    fun resolve(path: String): File? {
        if (path.isEmpty() || path.startsWith("/") || path.contains('\u0000') || path.split("/").contains("..")) return null
        val f = File(root, path).canonicalFile
        val base = rootCanonical
        return if (f.path.startsWith(base + File.separator)) f else null
    }

    /** Heavy folders skipped by the last list(): path to its folder. */
    private val skippedDirs = LinkedHashMap<String, File>()
    @Volatile private var truncated = false
    /** Folders whose files the last list() left out because the project is too big to list in full: folder to file count. */
    private val omittedDirs = LinkedHashMap<String, Int>()

    /**
     * The project's files for the assistant and the context search, at most LIST_LIMIT. In a big project, text and code come
     * first and bulky binary files (sprites, caches) are left out by folder, so 5,000 sprites cannot hide index.html.
     */
    fun list(): List<String> {
        val all = ArrayList<String>()
        val skip = LinkedHashMap<String, File>()
        fun walk(dir: File, prefix: String) {
            val kids = dir.listFiles() ?: return
            for (k in kids.sortedBy { it.name }) {
                if (all.size >= WALK_LIMIT) return
                if (k.name.startsWith(".")) continue
                if (k.isDirectory) {
                    if (dev.pocketide.app.core.ProjectGuard.isHeavy(k.name)) skip[prefix + k.name] = k else walk(k, prefix + k.name + "/")
                } else all.add(prefix + k.name)
            }
        }
        walk(root, "")
        synchronized(skippedDirs) { skippedDirs.clear(); skippedDirs.putAll(skip) }
        val picked = dev.pocketide.app.core.ProjectGuard.pickListed(all, LIST_LIMIT) { it.substringAfterLast('.', "").lowercase() in binaryExt }   // by name only: reading every file here froze the screen in big projects
        synchronized(omittedDirs) { omittedDirs.clear(); omittedDirs.putAll(picked.second) }
        truncated = picked.second.isNotEmpty() || all.size >= WALK_LIMIT
        return picked.first
    }

    /** The folder (relative, "" for the project root) that holds a game cache's main_file_cache.dat2, if the project has one. */
    fun findCache(): String? = findFolderWith("main_file_cache.dat2")

    /** The folder holding 2009scape's server data (npc_spawns.json beside npc_configs.json), if the project has one. */
    fun findServerData(): String? = findFolderWith("npc_spawns.json") { File(it, "npc_configs.json").isFile }

    /**
     * The shallowest folder (searched level by level, up to 6 deep) that holds [file]: imported repositories put caches and data
     * several folders down (2009scape/Server/data/cache). Source, build and hidden folders are skipped.
     */
    /** The folder that holds [file] (and [sibling] beside it, when given), or null; for add-ons. */
    fun findFolderWith(file: String, sibling: String?): String? = findFolderWith(file) { sibling == null || File(it, sibling).isFile }

    private fun findFolderWith(file: String, also: (File) -> Boolean = { true }): String? = try {
        var level = listOf(root to "")
        var found: String? = null
        for (depth in 0..6) {
            for ((d, rel) in level) if (File(d, file).isFile && also(d)) { found = rel; break }
            if (found != null) break
            level = level.flatMap { (d, rel) -> d.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") && it.name !in SKIP_FIND }
                .sortedBy { it.name }.map { it to (if (rel.isEmpty()) it.name else rel + "/" + it.name) } }
            if (level.size > 20000) break
        }
        found
    } catch (e: Exception) { null }

    /** Every file under [dir] ("" = the whole project), as project paths, without the usual list limits. */
    fun filesUnder(dir: String): List<String> {
        val base = if (dir.isEmpty()) root else resolve(dir)?.takeIf { it.isDirectory } ?: return emptyList()
        val prefix = if (dir.isEmpty()) "" else dir.trim('/') + "/"
        val out = ArrayList<String>()
        fun walk(d: File, pre: String) { for (k in d.listFiles().orEmpty()) { if (out.size >= 200_000) return; if (k.isDirectory) walk(k, pre + k.name + "/") else out.add(pre + k.name) } }
        walk(base, prefix)
        return out
    }

    /**
     * The files under one folder, walked on their own (1.5.167): in a big project the capped list() leaves whole folders out, and
     * list_files or search on such a folder used to answer "No files under X" for a folder with 1,914 source files in it (an imported
     * 2009scape server). Installed-package and hidden folders are skipped as in list(). Empty when [dir] is not a folder.
     */
    fun listUnder(dir: String, limit: Int = WALK_LIMIT): List<String> {
        val d = dir.trim().removePrefix("./").trim('/')
        if (d.isEmpty() || d == ".") return emptyList()
        val base = resolve(d)?.takeIf { it.isDirectory } ?: return emptyList()
        val out = ArrayList<String>()
        fun walk(f: File, pre: String) {
            for (k in f.listFiles().orEmpty().sortedBy { it.name }) {
                if (out.size >= limit) return
                if (k.name.startsWith(".")) continue
                if (k.isDirectory) { if (!dev.pocketide.app.core.ProjectGuard.isHeavy(k.name)) walk(k, pre + k.name + "/") } else out.add(pre + k.name)
            }
        }
        walk(base, "$d/")
        return out
    }

    /** Every image file under [dir] (empty for the whole project), however many: the normal list leaves bulky pictures out. */
    fun imagesIn(dir: String): List<String> {
        val base = if (dir.isEmpty()) root else resolve(dir)?.takeIf { it.isDirectory } ?: return emptyList()
        val prefix = if (dir.isEmpty()) "" else dir.trim('/') + "/"
        val out = ArrayList<String>()
        fun walk(d: File, pre: String) {
            for (k in d.listFiles().orEmpty().sortedBy { it.name }) {
                if (out.size >= WALK_LIMIT) return
                if (k.name.startsWith(".")) continue
                if (k.isDirectory) { if (!dev.pocketide.app.core.ProjectGuard.isHeavy(k.name)) walk(k, pre + k.name + "/") }
                else if (dev.pocketide.app.core.ContactSheet.isImage(k.name)) out.add(pre + k.name)
            }
        }
        walk(base, prefix)
        return out
    }

    /** How many files the last list() left out of the overview and the assistant's list (0 when the list is complete). */
    fun omittedCount(): Int = synchronized(omittedDirs) { omittedDirs.values.sum() }

    /** What the last file list left out, as a sentence for the assistant (empty when the list is complete). */
    fun listNote(): String {
        val dirs = synchronized(skippedDirs) { LinkedHashMap(skippedDirs) }
        val counts = LinkedHashMap<String, Int>()
        for ((p, f) in dirs) { var n = 0; f.walkTopDown().forEach { if (it.isFile && n < 50000) n++ }; counts[p] = n }
        val omitted = synchronized(omittedDirs) { LinkedHashMap(omittedDirs) }
        return dev.pocketide.app.core.ProjectGuard.listNote(counts, truncated, LIST_LIMIT, omitted)
    }

    companion object {
        /** Most characters of file text held at once (the phone's heap is small). */
        const val TEXT_BUDGET = 8_000_000
        /** Name of the list of left-out files inside an export zip. */
        const val LEFT_OUT = "LEFT-OUT-OF-THIS-ZIP.txt"

        /** "12.3 MB" style size. */
        fun sizeText(n: Long): String = when {
            n >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", n / 1073741824.0)
            n >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0)
            n >= 1L shl 10 -> String.format(java.util.Locale.US, "%.1f KB", n / 1024.0)
            else -> "$n bytes"
        }

        /** The list of files an export left out, largest first. */
        fun leftOutNote(skipped: List<Pair<String, Long>>, limit: Long): String =
            "These ${skipped.size} file${if (skipped.size == 1) " was" else "s were"} left out of this zip because each is over ${sizeText(limit)}:\n\n" +
                skipped.sortedByDescending { it.second }.joinToString("\n") { "${it.first}  (${sizeText(it.second)})" } + "\n"

        const val LIST_LIMIT = 2000
        /** How many files are looked at in all, so a huge project does not stall the app. */
        const val WALK_LIMIT = 50_000
    }

    fun exists(path: String): Boolean = resolve(path)?.isFile == true

    /**
     * The page the preview opens: index.html at the top of the project, else the shallowest page (index.html first in each
     * folder). It reads the folders itself, so a project too big for the file list (which shows at most [LIST_LIMIT] files)
     * still finds its page. Dot folders and installed-package folders are skipped.
     */
    fun findPage(): String? {
        if (exists("index.html")) return "index.html"
        val queue = ArrayDeque<Pair<File, String>>()
        queue.add(root to "")
        var seen = 0
        while (queue.isNotEmpty() && seen < WALK_LIMIT) {
            val (dir, prefix) = queue.removeFirst()
            val kids = (dir.listFiles() ?: continue).sortedBy { it.name }
            kids.firstOrNull { it.isFile && it.name == "index.html" }?.let { return prefix + it.name }
            kids.firstOrNull { it.isFile && it.name.endsWith(".html", ignoreCase = true) }?.let { return prefix + it.name }
            for (k in kids) {
                seen++
                if (k.isDirectory && !k.name.startsWith(".") && !dev.pocketide.app.core.ProjectGuard.isHeavy(k.name)) queue.add(k to prefix + k.name + "/")
            }
        }
        return null
    }

    fun size(path: String): Long = resolve(path)?.length() ?: 0L

    /** The text of a file, or null if it is missing, binary or over 2 MB. */
    fun read(path: String): String? {
        val f = resolve(path) ?: return null
        if (!f.isFile || isBinary(path) || f.length() > 2_000_000) return null
        return try { f.readText(Charsets.UTF_8) } catch (e: Exception) { null }
    }

    /** The text of a Word, Excel, PowerPoint, OpenDocument, EPUB, PDF or archive file (see DocText), or null. Read-only view. */
    fun readDoc(path: String): String? {
        if (!DocText.canRead(path)) return null
        val f = resolve(path) ?: return null
        if (!f.isFile || f.length() > 30_000_000) return null
        val bytes = try { f.readBytes() } catch (e: Exception) { return null }
        return DocText.extract(path, bytes)
    }

    /** What the AI may read: a text file's text, or the extracted text of a document. */
    fun readForAi(path: String): String? = read(path) ?: readDoc(path)

    fun openStream(path: String): InputStream? {
        val f = resolve(path) ?: return null
        return try { if (f.isFile) f.inputStream() else null } catch (e: Exception) { null }
    }

    /** Copies a stream into the project without holding it in memory. False if it is over [limit] bytes or cannot be written. */
    fun writeStream(path: String, input: InputStream, limit: Long = dev.pocketide.app.core.UploadLimits.FILE): Boolean {
        if (archived) return false
        val f = resolve(path) ?: return false
        val tmp = File(f.parentFile, "." + f.name + ".part")
        return try {
            f.parentFile?.mkdirs()
            var total = 0L
            var tooBig = false
            tmp.outputStream().use { o ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) { tooBig = true; break }
                    o.write(buf, 0, n)
                }
            }
            if (tooBig) { tmp.delete(); false } else { if (f.exists()) f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { tmp.delete(); false }
    }

    /** Size and time of a file folded into one number; changes when the file changes. */
    fun stamp(path: String): Long { val f = resolve(path) ?: return 0L; return f.length() * 31 + f.lastModified() }

    /** A path in [dir] for a file called [name] that does not clash with an existing file. */
    fun uniquePath(dir: String, name: String): String {
        val pre = if (dir.isEmpty()) "" else dir.trimEnd('/') + "/"
        return pre + FileKinds.uniqueName(FileKinds.safeName(name)) { exists(pre + it) }
    }

    /** Every file with a signature of its size and time, to tell afterwards what a command created or changed. */
    fun snapshot(): Map<String, Long> {
        val m = HashMap<String, Long>()
        for (p in list()) { val f = resolve(p) ?: continue; m[p] = f.length() * 31 + f.lastModified() }
        return m
    }

    /** An archived project is read-only: nothing is written until it is unarchived (Projects screen). */
    val archived: Boolean get() = java.io.File(root.parentFile?.parentFile, "meta/${root.name}/archived").isFile

    fun write(path: String, text: String): Boolean {
        if (archived) return false
        val f = resolve(path) ?: return false
        return try {
            f.parentFile?.mkdirs()
            dev.pocketide.app.core.SafeWrite.write(f, text)
            true
        } catch (e: Exception) { false }
    }

    fun readBytes(path: String): ByteArray? {
        val f = resolve(path) ?: return null
        return try { if (f.isFile) f.readBytes() else null } catch (e: Exception) { null }
    }

    fun writeBytes(path: String, data: ByteArray): Boolean {
        if (archived) return false
        val f = resolve(path) ?: return false
        return try {
            f.parentFile?.mkdirs()
            dev.pocketide.app.core.SafeWrite.write(f, data)
            true
        } catch (e: Exception) { false }
    }

    fun delete(path: String): Boolean {
        if (archived) return false
        val f = resolve(path) ?: return false
        if (!f.isFile) return false
        val ok = f.delete()
        var dir = f.parentFile
        while (ok && dir != null && dir != root.canonicalFile && dir.isDirectory && (dir.list()?.isEmpty() == true)) {
            dir.delete()
            dir = dir.parentFile
        }
        return ok
    }

    fun rename(from: String, to: String): Boolean {
        if (archived) return false
        val a = resolve(from) ?: return false
        val b = resolve(to) ?: return false
        if (!a.isFile || b.exists()) return false
        b.parentFile?.mkdirs()
        return a.renameTo(b)
    }

    /**
     * Text of the files a reply names, for matching the assistant's edits against. Only files whose path appears in the reply
     * are read, and at most [TEXT_BUDGET] characters in all: reading the whole project at once ran the phone out of memory.
     */
    fun textMapFor(reply: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        var total = 0
        for (p in list()) {
            if (!reply.contains(p)) continue
            val t = read(p) ?: continue
            if (total + t.length > TEXT_BUDGET) break
            m[p] = t
            total += t.length
        }
        return m
    }

    /**
     * Files for building prompt context. Files over 256 KB are listed without their text, and once [TEXT_BUDGET] characters of
     * text are read the rest are listed without it too.
     */
    fun projectFiles(): List<ProjectFile> {
        var total = 0
        return list().map { p ->
            val bin = isBinary(p)
            val text = if (bin || size(p) > 256_000 || p.startsWith(dev.pocketide.app.core.ChatExport.DIR + "/") || total >= TEXT_BUDGET) null
                else read(p)?.also { total += it.length }
            ProjectFile(p, text, bin)
        }
    }


    /** Every file including dot-folders such as .github (a workflow file must travel with the project), but not .git. */
    private fun listWithHidden(): List<String> {
        val out = ArrayList<String>()
        fun walk(dir: File, prefix: String) {
            for (k in (dir.listFiles() ?: return).sortedBy { it.name }) {
                // no file limit: an export that silently leaves files out loses work
                if (k.name == ".git") continue
                if (k.isDirectory) walk(k, prefix + k.name + "/") else if (k.isFile) out.add(prefix + k.name)
            }
        }
        walk(root, "")
        return out.sorted()
    }

    /** Zips the whole project, or only the folder [only] (its files then sit at the top of the zip). */
    /**
     * Writes the project (or the folder [only]) as a zip. Files larger than [skipOver] bytes (when it is above 0) are left out;
     * they are returned with their sizes, and listed in [LEFT_OUT] inside the zip so whoever opens it knows what is missing.
     */
    fun exportZip(out: OutputStream, only: String = "", skipOver: Long = 0L): List<Pair<String, Long>> {
        val pre = if (only.isEmpty()) "" else only.trimEnd('/') + "/"
        val skipped = ArrayList<Pair<String, Long>>()
        ZipOutputStream(out).use { z ->
            for (p in listWithHidden()) {
                if (!p.startsWith(pre)) continue
                val f = resolve(p) ?: continue
                if (skipOver > 0 && f.length() > skipOver) { skipped.add(p.removePrefix(pre) to f.length()); continue }
                z.putNextEntry(ZipEntry(p.removePrefix(pre)))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            if (skipped.isNotEmpty()) {
                z.putNextEntry(ZipEntry(LEFT_OUT))
                z.write(leftOutNote(skipped, skipOver).toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return skipped
    }


    /**
     * Adds the files of a zip to the project. A single top-level folder, as in GitHub downloads, is removed.
     * Entries are unpacked to a staging folder as they are read (nothing big is held in memory), then moved in.
     * Returns the number of files written, -1 if the zip is over UploadLimits.ZIP_TOTAL, -2 if it has too many files.
     */
    /** Unpacks the zip file at [path] into a folder named like it (without .zip). Returns the file count, -1 too big, -2 too many files, -3 unreadable. */
    fun unzip(path: String): Int {
        val f = resolve(path)?.takeIf { it.isFile } ?: return -3
        val into = path.substringBeforeLast('.').ifEmpty { path + "-files" }
        return try { f.inputStream().use { importZip(it, into, stripTop = false) } } catch (e: Exception) { -3 }
    }

    fun importZip(input: InputStream, into: String = "", stripTop: Boolean = true): Int {
        val lim = dev.pocketide.app.core.UploadLimits
        val staging = File(root.parentFile ?: root, ".pocketide-import-" + System.nanoTime())
        try {
            staging.mkdirs()
            val names = ArrayList<String>()
            var total = 0L
            ZipInputStream(input).use { z ->
                val buf = ByteArray(65536)
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = lim.safeEntry(e.name) ?: continue
                    if (names.size >= lim.ZIP_ENTRIES) return -2
                    val out = File(staging, name)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        while (true) {
                            val n = z.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > lim.ZIP_TOTAL) return -1
                            o.write(buf, 0, n)
                        }
                    }
                    names.add(name)
                }
            }
            val firsts = names.map { it.substringBefore('/', "") }.toSet()
            val strip = stripTop && names.isNotEmpty() && firsts.size == 1 && firsts.first().isNotEmpty() && names.all { it.contains('/') }
            var n = 0
            for (name in names) {
                val inner = if (strip) name.substringAfter('/') else name
                if (inner.isEmpty() || inner.startsWith(".git/")) continue
                val rel = if (into.isEmpty()) inner else "$into/$inner"
                val target = resolve(rel) ?: continue
                val src = File(staging, name)
                target.parentFile?.mkdirs()
                if (target.exists()) target.delete()
                val moved = src.renameTo(target) || try { src.copyTo(target, overwrite = true); true } catch (e: Exception) { false }
                if (moved) n++
            }
            return n
        } finally {
            staging.deleteRecursively()
        }
    }
}
