package dev.pocketide.app.core

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

// GPL route. Plain JVM code (no Android imports) so it runs in unit tests.
// The extraction rules follow MobileCoder's RootfsArchiveExtractor (same author), rewritten
// without Apache Commons Compress or coroutines.

/** The Linux userland PocketIDE downloads on the phone. Pinned so the checksum can be checked. */
/** A Linux base the app can install under PRoot. Each one is pinned to an exact file and checksum. */
class Distro(
    val id: String,
    val name: String,
    val url: String,
    val sha256: String,
    val approxMb: Int,
    /** How to install packages, for the first-run hint and the help text. */
    val installHint: String,
    val maxEntries: Int,
    val maxBytes: Long
)

object Distros {
    val ALPINE = Distro(
        "alpine", "Alpine Linux 3.24.2 (aarch64)",
        "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz",
        "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773", 4,
        "apk add python3 nodejs npm git", 20_000, 256L * 1024 * 1024
    )

    /** Bigger, but with Ubuntu's package set. The one-tap tools install with apt here (all but Ollama and llama.cpp, which are not Ubuntu packages). */
    val UBUNTU = Distro(
        "ubuntu", "Ubuntu 24.04.5 base (arm64)",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
        "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2", 29,
        "apt-get update && apt-get install -y python3 nodejs npm git", 60_000, 768L * 1024 * 1024
    )

    val all = listOf(ALPINE, UBUNTU)

    fun byId(id: String): Distro = all.firstOrNull { it.id == id } ?: ALPINE

    /**
     * Which base is actually installed, judged by what is in the unpacked files rather than by the setting, because the setting can be
     * changed after installing. [fallback] (the setting) is used when the files say nothing.
     */
    fun detect(hasAlpineRelease: Boolean, hasApt: Boolean, fallback: String): Distro = when {
        hasAlpineRelease -> ALPINE
        hasApt -> UBUNTU
        else -> byId(fallback)
    }
}

object LinuxDistro {
    const val NAME = "Alpine Linux 3.24.2 (aarch64)"
    const val URL = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz"
    const val SHA256 = "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773"
    const val APPROX_MB = 4

    /** Limits that stop a corrupt or hostile archive from filling the phone. */
    const val MAX_ENTRIES = 20_000
    const val MAX_BYTES = 256L * 1024 * 1024
}

object Sha256 {
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun ofFile(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }
}

/** One tar entry. type is the ustar type flag: '0' file, '5' dir, '2' symlink, '1' hard link. */
class TarEntry(val name: String, val type: Char, val mode: Int, val size: Long, val linkName: String)

/**
 * A small streaming tar reader: ustar headers, GNU long names ('L', 'K') and pax path/linkpath ('x').
 * Call next() for each entry; while an entry is current, read its data from data().
 */
class TarReader(input: InputStream) {
    private val inp = input
    private var remaining = 0L   // unread data bytes of the current entry
    private var padding = 0L     // bytes after the data up to the 512-byte boundary

    private fun readFully(buf: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val n = inp.read(buf, off, len - off)
            if (n < 0) { if (off == 0) return false else throw IOException("Truncated archive") }
            off += n
        }
        return true
    }

    private fun skip(n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val k = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (k < 0) throw IOException("Truncated archive")
            left -= k
        }
    }

    private fun readBody(size: Long): ByteArray {
        if (size < 0 || size > 1024 * 1024) throw IOException("Archive metadata entry is too large")
        val b = ByteArray(size.toInt())
        if (!readFully(b, b.size)) throw IOException("Truncated archive")
        skip((512 - size % 512) % 512)
        return b
    }

    private fun str(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end] != 0.toByte()) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    private fun octal(h: ByteArray, off: Int, len: Int): Long {
        // GNU base-256 for large numbers
        if (h[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (h[i].toLong() and 0xff)
            return v
        }
        val s = str(h, off, len).trim { it == ' ' || it == '\u0000' }
        if (s.isEmpty()) return 0
        return s.toLong(8)
    }

    private fun checksumOk(h: ByteArray): Boolean {
        val stored = try { octal(h, 148, 8) } catch (e: NumberFormatException) { return false }
        var sum = 0L
        for (i in 0 until 512) sum += if (i in 148 until 156) 32 else (h[i].toInt() and 0xff)
        return sum == stored
    }

    private fun paxValue(body: ByteArray, key: String): String? {
        // records look like "<len> <key>=<value>\n"
        val text = String(body, Charsets.UTF_8)
        var i = 0
        while (i < text.length) {
            val sp = text.indexOf(' ', i)
            if (sp < 0) break
            val len = text.substring(i, sp).toIntOrNull() ?: break
            if (len <= 0 || i + len > text.length) break
            val rec = text.substring(sp + 1, i + len).removeSuffix("\n")
            val eq = rec.indexOf('=')
            if (eq > 0 && rec.substring(0, eq) == key) return rec.substring(eq + 1)
            i += len
        }
        return null
    }

    /** The next entry, or null at the end of the archive. */
    fun next(): TarEntry? {
        if (remaining > 0) skip(remaining)
        if (padding > 0) skip(padding)
        remaining = 0; padding = 0
        var longName: String? = null
        var longLink: String? = null
        val h = ByteArray(512)
        while (true) {
            if (!readFully(h, 512)) return null
            if (h.all { it == 0.toByte() }) return null
            if (!checksumOk(h)) throw IOException("Corrupt archive header")
            val type = if (h[156] == 0.toByte()) '0' else h[156].toInt().toChar()
            val size = octal(h, 124, 12)
            when (type) {
                'L' -> { longName = String(readBody(size), Charsets.UTF_8).trimEnd('\u0000'); continue }
                'K' -> { longLink = String(readBody(size), Charsets.UTF_8).trimEnd('\u0000'); continue }
                'x' -> {
                    val body = readBody(size)
                    paxValue(body, "path")?.let { longName = it }
                    paxValue(body, "linkpath")?.let { longLink = it }
                    continue
                }
                'g' -> { readBody(size); continue }
            }
            var name = str(h, 0, 100)
            val magic = str(h, 257, 6)
            if (magic.startsWith("ustar")) {
                val prefix = str(h, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
            }
            val entry = TarEntry(longName ?: name, type, octal(h, 100, 8).toInt(), size, longLink ?: str(h, 157, 100))
            remaining = if (type == '0' || type == '7') size else 0
            padding = if (remaining > 0) (512 - size % 512) % 512 else 0
            if (type != '0' && type != '7' && size > 0) { skip(size + (512 - size % 512) % 512) }
            return entry
        }
    }

    /** The current entry's data; read it before calling next(). */
    fun data(): InputStream = object : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val v = inp.read()
            if (v < 0) throw IOException("Truncated archive")
            remaining--
            return v
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = inp.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw IOException("Truncated archive")
            remaining -= n
            return n
        }
    }
}

/** Unpacks a rootfs .tar (already un-gzipped) into a new directory, refusing anything that escapes it. */
class RootfsExtractor(
    private val maxEntries: Int = LinuxDistro.MAX_ENTRIES,
    private val maxBytes: Long = LinuxDistro.MAX_BYTES,
    private val hardLink: (Path, Path) -> Unit = { link, target -> Files.createLink(link, target) }
) {
    class Result(val entries: Int, val bytes: Long)
    private class Link(val path: Path, val target: String, val symbolic: Boolean)

    /** Turns an archive name into a path inside root, or throws if it would land outside. */
    fun entryPath(root: Path, name: String): Path {
        if (name.isBlank() || name.startsWith("/") || '\\' in name || '\u0000' in name) throw IOException("Unsafe archive path: $name")
        var rel = name
        while (rel.startsWith("./")) rel = rel.removePrefix("./")
        rel = rel.trimEnd('/')
        if (rel.isEmpty() || rel == ".") return root
        if (rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) throw IOException("Unsafe archive path: $name")
        val p = root.resolve(rel).normalize()
        if (!p.startsWith(root)) throw IOException("Archive path escapes the folder: $name")
        return p
    }

    /** Symlinks may point anywhere inside the rootfs (absolute targets are read relative to it). */
    fun linkInside(root: Path, at: Path, target: String, symbolic: Boolean): Boolean {
        if (target.isBlank() || target.length > 4096 || '\u0000' in target || '\\' in target) return false
        if (!symbolic) return try { entryPath(root, target) != at } catch (e: IOException) { false }
        val resolved = if (target.startsWith("/")) root.resolve(target.trimStart('/')).normalize()
        else at.parent.resolve(target).normalize()
        return resolved.startsWith(root) && resolved != at
    }

    fun extract(tarStream: InputStream, dest: File, onProgress: (Long, Int) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): Result {
        val root = dest.absoluteFile.toPath()
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) throw IOException("Destination already exists")
        Files.createDirectories(root)
        val tar = TarReader(tarStream)
        val links = ArrayList<Link>()
        val files = HashSet<Path>()
        var entries = 0
        var written = 0L
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                if (cancelled()) throw IOException("Cancelled")
                val e = tar.next() ?: break
                if (++entries > maxEntries) throw IOException("Archive has too many entries")
                val path = entryPath(root, e.name)
                if (path == root) continue
                if (links.any { path.startsWith(it.path) }) throw IOException("Archive writes through a link: ${e.name}")
                parents(root, path.parent)
                when (e.type) {
                    '5' -> if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("Conflicting entry: ${e.name}")
                        Files.createDirectory(path)
                    }
                    '0', '7' -> {
                        if (e.size > maxBytes - written) throw IOException("Archive is larger than allowed")
                        Files.deleteIfExists(path)
                        Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { out ->
                            val data = tar.data()
                            while (true) {
                                val n = data.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                written += n
                            }
                        }
                        applyMode(path.toFile(), e.mode)
                        files.add(path)
                        onProgress(written, entries)
                    }
                    '2', '1' -> {
                        if (!linkInside(root, path, e.linkName, e.type == '2')) throw IOException("Link points outside the folder: ${e.name}")
                        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw IOException("Conflicting entry: ${e.name}")
                        links.add(Link(path, e.linkName, e.type == '2'))
                    }
                    // device nodes, fifos and the like: PRoot binds the real /dev, so these are skipped
                    else -> {}
                }
            }
            if (entries == 0) throw IOException("The archive is empty")
            for (l in links.filter { !it.symbolic }) {
                val target = entryPath(root, l.target)
                if (target !in files) throw IOException("Hard link to a missing file: ${l.target}")
                try { hardLink(l.path, target) } catch (e: Exception) {
                    // Android app storage usually refuses link(2); a copy behaves the same for a rootfs
                    Files.copy(target, l.path, LinkOption.NOFOLLOW_LINKS)
                    target.toFile().let { t -> l.path.toFile().setExecutable(t.canExecute(), false) }
                }
            }
            for (l in links.filter { it.symbolic }) Files.createSymbolicLink(l.path, Paths.get(l.target))
            return Result(entries, written)
        } catch (t: Throwable) {
            try { deleteTree(dest) } catch (e: Exception) { t.addSuppressed(e) }
            throw t
        }
    }

    private fun parents(root: Path, parent: Path) {
        var cur = root
        for (part in root.relativize(parent)) {
            cur = cur.resolve(part)
            if (Files.isSymbolicLink(cur)) throw IOException("Archive parent is a link")
            if (Files.exists(cur, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(cur, LinkOption.NOFOLLOW_LINKS)) throw IOException("Archive parent is not a folder")
            } else Files.createDirectory(cur)
        }
    }

    private fun applyMode(f: File, mode: Int) {
        f.setReadable(true, true)
        f.setWritable(true, true)
        // any execute bit makes it executable for the app user (the only user that matters here)
        f.setExecutable(mode and 0b001_001_001 != 0, true)
    }

    companion object {
        /**
         * Deletes a folder without following symlinks out of it. Linux systems have read-only folders, so each folder is made
         * writable before its contents go, and a file that still cannot be deleted is skipped rather than stopping the rest.
         * Returns how many entries were left behind (0 when everything went).
         */
        fun deleteTree(dir: File): Int {
            val p = dir.toPath()
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return 0
            var left = 0
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                dir.setReadable(true, true); dir.setWritable(true, true); dir.setExecutable(true, true)
                try { Files.newDirectoryStream(p).use { s -> for (c in s) left += deleteTree(c.toFile()) } } catch (e: Exception) { left++ }
            }
            try { Files.delete(p) } catch (e: Exception) { left++ }
            return left
        }
    }
}

/** Counts bytes read, for download/unpack progress. */
class CountingInput(input: InputStream, private val onCount: (Long) -> Unit) : FilterInputStream(input) {
    var count = 0L
        private set
    override fun read(): Int { val v = super.read(); if (v >= 0) { count++; onCount(count) }; return v }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) { count += n; onCount(count) }
        return n
    }
}

/**
 * The PRoot command line. Kept here so it is unit-tested.
 * guest = null opens an interactive login shell; otherwise the line runs with sh -c.
 */
object ProotCommand {
    const val GUEST_WORKDIR = "/work"
    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Guest variable names that may be passed with guestEnv. */
    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun args(
        launcher: String, rootfs: String, project: String, tmp: String, guest: String?, term: String = "dumb",
        guestEnv: Map<String, String> = emptyMap()
    ): List<String> {
        for (p in listOf(rootfs, project, tmp)) require(':' !in p && '\u0000' !in p) { "Unsupported path: $p" }
        for ((k, v) in guestEnv) require(ENV_NAME.matches(k) && '\u0000' !in v) { "Unsupported variable: $k" }
        val a = arrayListOf(
            launcher, "--kill-on-exit", "--root-id", "--link2symlink",
            "--rootfs=$rootfs",
            "--bind=/dev", "--bind=/proc", "--bind=/sys",
            "--bind=$tmp:/tmp",
            "--bind=$project:$GUEST_WORKDIR",
            "--cwd=$GUEST_WORKDIR",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "TMPDIR=/tmp", "TERM=$term", "LANG=C.UTF-8", "PATH=$GUEST_PATH"
        )
        for ((k, v) in guestEnv) a += "$k=$v"
        if (guest == null) a += listOf("/bin/sh", "-l") else a += listOf("/bin/sh", "-c", guest)
        return a
    }

    /** Environment for PRoot itself (on the Android side). */
    fun env(nativeLibDir: String, tmp: String): Map<String, String> = mapOf(
        "PROOT_LOADER" to "$nativeLibDir/libproot-loader.so",
        "PROOT_TMP_DIR" to tmp,
        "LD_LIBRARY_PATH" to nativeLibDir,
        "PATH" to "/system/bin"
    )
}
