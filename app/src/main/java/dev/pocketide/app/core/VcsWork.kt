package dev.pocketide.app.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * The working folder side of a repository: git's index file (version 2, so real git reads it, and so unchanged files need not
 * be read again: their size and time are kept), ignore rules (.gitignore plus defaults for build output), and large files kept
 * outside the history in Git LFS's own format (a small pointer is committed, the content lives in .git/lfs/objects).
 */
object VcsWork {
    /** Files at least this big are stored as large files. */
    const val LARGE = 20L * 1024 * 1024

    /** What a new history ignores (written to .gitignore when history starts, so real git agrees). */
    val DEFAULT_IGNORE = listOf("node_modules/", "build/", "dist/", "target/", ".gradle/", "__pycache__/", "*.pyc", ".venv/", "venv/",
        ".DS_Store", ".pocketide/parts-state.json", "*.tmp")

    // ---------------------------------------------------------------- index

    data class IndexEntry(val path: String, val id: String, val mode: String, val size: Long, val mtime: Long)

    fun readIndex(store: VcsStore): Map<String, IndexEntry> {
        val f = File(store.dir, "index"); if (!f.isFile) return emptyMap()
        val b = f.readBytes()
        if (b.size < 12 || String(b, 0, 4, Charsets.ISO_8859_1) != "DIRC") return emptyMap()
        fun int(o: Int) = ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
        val version = int(4); val n = int(8); var o = 12
        val out = LinkedHashMap<String, IndexEntry>()
        if (version != 2) return emptyMap()
        repeat(n) {
            val mtimeS = int(o + 8).toLong() and 0xffffffffL; val mtimeN = int(o + 12).toLong() and 0xffffffffL
            val mode = Integer.toOctalString(int(o + 24)); val size = int(o + 36).toLong() and 0xffffffffL
            val id = VcsStore.toHex(b, o + 40, 20)
            val flags = ((b[o + 60].toInt() and 0xff) shl 8) or (b[o + 61].toInt() and 0xff)
            val nameLen = flags and 0xfff
            val nameEnd = if (nameLen < 0xfff) o + 62 + nameLen else VcsStore.indexOf(b, 0, o + 62)
            val path = String(b, o + 62, nameEnd - o - 62, Charsets.UTF_8)
            val stage = (flags shr 12) and 3
            if (stage == 0) out[path] = IndexEntry(path, id, mode, size, mtimeS * 1000 + mtimeN / 1_000_000)
            val len = nameEnd - o + 1
            o += (len + 7) / 8 * 8
        }
        return out
    }

    fun writeIndex(store: VcsStore, entries: Collection<IndexEntry>) {
        val body = ByteArrayOutputStream(); val d = DataOutputStream(body)
        d.write("DIRC".toByteArray()); d.writeInt(2)
        val sorted = entries.sortedWith { a, b -> VcsStore.compareBytes(a.path.toByteArray(Charsets.UTF_8), b.path.toByteArray(Charsets.UTF_8)) }
        d.writeInt(sorted.size)
        for (e in sorted) {
            val start = body.size()
            val s = (e.mtime / 1000).toInt(); val ns = ((e.mtime % 1000) * 1_000_000).toInt()
            d.writeInt(s); d.writeInt(ns); d.writeInt(s); d.writeInt(ns)
            d.writeInt(0); d.writeInt(0)
            d.writeInt(Integer.parseInt(e.mode.padStart(6, '0'), 8))
            d.writeInt(0); d.writeInt(0); d.writeInt(e.size.coerceAtMost(0xffffffffL).toInt())
            d.write(VcsStore.hex(e.id))
            val name = e.path.toByteArray(Charsets.UTF_8)
            d.writeShort(minOf(name.size, 0xfff))
            d.write(name)
            val len = body.size() - start
            val pad = 8 - (len % 8)
            repeat(pad) { d.write(0) }
        }
        d.flush()
        val bytes = body.toByteArray()
        val sum = MessageDigest.getInstance("SHA-1").digest(bytes)
        val f = File(store.dir, "index"); val tmp = File(store.dir, "index.tmp")
        tmp.writeBytes(bytes + sum); if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
    }

    // ---------------------------------------------------------------- ignore rules

    class Ignore(private val root: File) {
        private class Rule(val base: String, val regex: Regex, val dirOnly: Boolean, val negate: Boolean)
        private val rules = HashMap<String, List<Rule>>()   // folder -> its .gitignore rules
        private val defaults = parse("", DEFAULT_IGNORE.joinToString("\n"))

        private fun parse(base: String, text: String): List<Rule> = text.lines().mapNotNull { raw ->
            var l = raw.trimEnd()
            if (l.isEmpty() || l.startsWith("#")) return@mapNotNull null
            val neg = l.startsWith("!"); if (neg) l = l.substring(1)
            val dirOnly = l.endsWith("/"); if (dirOnly) l = l.trimEnd('/')
            val anchored = l.startsWith("/") || l.trimStart('/').contains('/')
            l = l.trimStart('/')
            Rule(base, Regex((if (anchored) "" else "(?:.*/)?") + glob(l)), dirOnly, neg)
        }

        private fun glob(g: String): String {
            val sb = StringBuilder(); var i = 0
            while (i < g.length) {
                val c = g[i]
                when {
                    g.startsWith("**/", i) -> { sb.append("(?:.*/)?"); i += 3; continue }
                    g.startsWith("**", i) -> { sb.append(".*"); i += 2; continue }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    c == '[' -> { val e = g.indexOf(']', i); if (e > i) { sb.append(g, i, e + 1); i = e + 1; continue } else sb.append("\\[") }
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return sb.toString()
        }

        private fun rulesFor(folder: String): List<Rule> = rules.getOrPut(folder) {
            val f = File(if (folder.isEmpty()) root else File(root, folder), ".gitignore")
            if (f.isFile) parse(folder, f.readText()) else emptyList()
        }

        /** Whether [path] (relative, "/"-separated) is ignored; [dir] for folders. */
        fun ignored(path: String, dir: Boolean): Boolean {
            if (path == ".git" || path.startsWith(".git/")) return true
            var result = false
            val parts = path.split('/')
            val folders = (0 until parts.size).map { parts.subList(0, it).joinToString("/") }
            for (r in defaults + folders.flatMap { rulesFor(it) }) {
                if (r.dirOnly && !dir) continue
                val rel = if (r.base.isEmpty()) path else if (path.startsWith(r.base + "/")) path.removePrefix(r.base + "/") else continue
                if (r.regex.matches(rel)) result = !r.negate
            }
            return result
        }
    }

    // ---------------------------------------------------------------- large files (Git LFS format)

    private const val LFS_HEAD = "version https://git-lfs.github.com/spec/v1"

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n <= 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun lfsFile(store: VcsStore, oid: String) = File(store.dir, "lfs/objects/${oid.substring(0, 2)}/${oid.substring(2, 4)}/$oid")

    /** Stores a big file's content and returns the small pointer that is committed instead. */
    fun lfsStore(store: VcsStore, f: File): ByteArray {
        val oid = sha256(f); val dest = lfsFile(store, oid)
        if (!dest.isFile) {
            dest.parentFile!!.mkdirs()
            try { java.nio.file.Files.createLink(dest.toPath(), f.toPath()) } catch (e: Throwable) { f.copyTo(dest, true) }
        }
        return "$LFS_HEAD\noid sha256:$oid\nsize ${f.length()}\n".toByteArray()
    }

    /** The oid and size of a pointer, or null when [data] is ordinary content. */
    fun lfsPointer(data: ByteArray): Pair<String, Long>? {
        if (data.size > 300 || data.size < LFS_HEAD.length) return null
        val t = String(data, Charsets.UTF_8)
        if (!t.startsWith(LFS_HEAD)) return null
        val oid = Regex("""oid sha256:([0-9a-f]{64})""").find(t)?.groupValues?.get(1) ?: return null
        val size = Regex("""size (\d+)""").find(t)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return oid to size
    }

    /** Patterns .gitattributes marks as large files (filter=lfs). */
    fun lfsPatterns(root: File): List<Regex> {
        val f = File(root, ".gitattributes"); if (!f.isFile) return emptyList()
        return f.readLines().filter { it.contains("filter=lfs") }.mapNotNull { l ->
            val p = l.trim().split(Regex("\\s+")).firstOrNull() ?: return@mapNotNull null
            Regex((if (p.contains('/')) "" else "(?:.*/)?") + p.trimStart('/').replace(".", "\\.").replace("**", "\u0000").replace("*", "[^/]*").replace("\u0000", ".*"))
        }
    }
}

/** Line diffs (Myers) and three-way merges for history: fast for long files with few changes. */
object VcsText {
    fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split("\n")

    /** Pairs of (line in a, line in b) that stay the same, in order. */
    fun matches(a: List<String>, b: List<String>, maxD: Int = 1000): List<Pair<Int, Int>> {
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val out = ArrayList<Pair<Int, Int>>()
        for (i in 0 until pre) out.add(i to i)
        val x = a.subList(pre, a.size - suf); val y = b.subList(pre, b.size - suf)
        out.addAll(myers(x, y, maxD).map { (i, j) -> (i + pre) to (j + pre) })
        for (k in suf - 1 downTo 0) out.add((a.size - 1 - k) to (b.size - 1 - k))
        return out
    }

    private fun myers(a: List<String>, b: List<String>, maxD: Int): List<Pair<Int, Int>> {
        val n = a.size; val m = b.size
        if (n == 0 || m == 0) return emptyList()
        val max = n + m; val off = max + 1
        val v = IntArray(2 * max + 4)
        val trace = ArrayList<IntArray>()
        var found = -1
        for (d in 0..minOf(max, maxD)) {
            trace.add(v.copyOfRange(off - d - 1, off + d + 2))
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) v[off + k + 1] else v[off + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[off + k] = x
                if (x >= n && y >= m) { found = d; break }
                k += 2
            }
            if (found >= 0) break
        }
        if (found < 0) return uniqueMatches(a, b)
        // walk back through the saved rows
        val out = ArrayList<Pair<Int, Int>>()
        var x = n; var y = m
        for (d in found downTo 1) {
            val row = trace[d]   // v before step d, covering k in [-d-1, d+1]
            fun vAt(k: Int) = row[k + d + 1]
            val k = x - y
            val prevK = if (k == -d || (k != d && vAt(k - 1) < vAt(k + 1))) k + 1 else k - 1
            val prevX = vAt(prevK); val prevY = prevX - prevK
            while (x > prevX && y > prevY) { x--; y--; out.add(x to y) }
            x = prevX; y = prevY
        }
        while (x > 0 && y > 0) { x--; y--; out.add(x to y) }
        out.reverse(); return out
    }

    /** For huge differences: match lines that appear once in each side, in order (patience style, no recursion). */
    private fun uniqueMatches(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val ca = a.groupingBy { it }.eachCount(); val cb = b.groupingBy { it }.eachCount()
        val posB = HashMap<String, Int>(); b.forEachIndexed { j, s -> if (cb[s] == 1) posB[s] = j }
        val out = ArrayList<Pair<Int, Int>>(); var lastJ = -1
        a.forEachIndexed { i, s -> if (ca[s] == 1) posB[s]?.let { j -> if (j > lastJ) { out.add(i to j); lastJ = j } } }
        return out
    }

    /** Diff rows: ' ' same, '-' only in a, '+' only in b. */
    fun diff(a: List<String>, b: List<String>): List<DiffOp> {
        val out = ArrayList<DiffOp>(); var i = 0; var j = 0
        for ((mi, mj) in matches(a, b)) {
            while (i < mi) out.add(DiffOp('-', a[i++]))
            while (j < mj) out.add(DiffOp('+', b[j++]))
            out.add(DiffOp(' ', a[i])); i++; j++
        }
        while (i < a.size) out.add(DiffOp('-', a[i++]))
        while (j < b.size) out.add(DiffOp('+', b[j++]))
        return out
    }

    fun stat(a: String, b: String): Pair<Int, Int> { val d = diff(lines(a), lines(b)); return d.count { it.tag == '+' } to d.count { it.tag == '-' } }

    class Merged(val text: String, val conflicts: Int)

    /**
     * Three-way merge of [base] with [ours] and [theirs]. Changes on one side only are taken; the same change on both is taken
     * once; different changes to the same lines become a conflict, written with git's markers.
     */
    fun merge3(base: String, ours: String, theirs: String, oursName: String = "yours", theirsName: String = "theirs"): Merged {
        val b = lines(base); val o = lines(ours); val t = lines(theirs)
        val oOf = IntArray(b.size) { -1 }; for ((i, j) in matches(b, o)) oOf[i] = j
        val tOf = IntArray(b.size) { -1 }; for ((i, j) in matches(b, t)) tOf[i] = j
        val out = ArrayList<String>(); var conflicts = 0
        var i = 0; var j = 0; var k = 0
        fun chunk(bEnd: Int, oEnd: Int, tEnd: Int) {
            val bc = b.subList(i, bEnd); val oc = o.subList(j, oEnd); val tc = t.subList(k, tEnd)
            when {
                oc == bc -> out.addAll(tc)
                tc == bc -> out.addAll(oc)
                oc == tc -> out.addAll(oc)
                else -> { conflicts++; out.add("<<<<<<< $oursName"); out.addAll(oc); out.add("======="); out.addAll(tc); out.add(">>>>>>> $theirsName") }
            }
        }
        while (true) {
            var s = i
            while (s < b.size && !(oOf[s] >= j && tOf[s] >= k)) s++
            if (s >= b.size) { chunk(b.size, o.size, t.size); break }
            chunk(s, oOf[s], tOf[s])
            out.add(b[s]); i = s + 1; j = oOf[s] + 1; k = tOf[s] + 1
        }
        val endNl = listOf(ours, theirs, base).firstOrNull { it.isNotEmpty() }?.endsWith("\n") ?: false
        return Merged(out.joinToString("\n") + if (endNl && out.isNotEmpty()) "\n" else "", conflicts)
    }

    /** Settles every conflict in [text] by keeping one side ([ours]: the part before =======). */
    fun resolve(text: String, ours: Boolean): String {
        val out = ArrayList<String>(); var state = 0   // 0 normal, 1 in ours, 2 in theirs
        for (l in text.split("\n")) {
            when {
                state == 0 && l.startsWith("<<<<<<< ") -> state = 1
                state == 1 && l == "=======" -> state = 2
                state == 2 && l.startsWith(">>>>>>> ") -> state = 0
                state == 0 || (state == 1 && ours) || (state == 2 && !ours) -> out.add(l)
            }
        }
        return out.joinToString("\n")
    }

    fun hasConflictMarkers(text: String): Boolean = text.lineSequence().any { it.startsWith("<<<<<<< ") } && text.contains("\n=======\n") && text.lineSequence().any { it.startsWith(">>>>>>> ") }
}
