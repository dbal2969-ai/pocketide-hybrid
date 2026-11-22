package dev.pocketide.app.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A git repository's object and reference store, written for PocketIDE with no library: the .git folder it writes is a normal
 * git repository (real git reads it), and it reads what real git wrote, packed objects and packed refs included. Loose objects
 * are zlib-compressed "type size\0content" files named by their SHA-1. Pure JVM code, so it is tested against the git program.
 */
class VcsStore(val root: File) {
    val dir = File(root, ".git")
    private val objects = File(dir, "objects")

    fun exists(): Boolean = File(dir, "HEAD").isFile && objects.isDirectory

    fun init(branch: String = "main") {
        for (d in listOf("objects/info", "objects/pack", "refs/heads", "refs/tags", "info", "pocketide")) File(dir, d).mkdirs()
        if (!File(dir, "HEAD").isFile) File(dir, "HEAD").writeText("ref: refs/heads/$branch\n")
        if (!File(dir, "config").isFile) File(dir, "config").writeText("[core]\n\trepositoryformatversion = 0\n\tfilemode = false\n\tbare = false\n\tlogallrefupdates = true\n")
        if (!File(dir, "description").isFile) File(dir, "description").writeText("PocketIDE project\n")
    }

    // ---------------------------------------------------------------- objects

    class Obj(val type: String, val data: ByteArray)

    fun id(type: String, data: ByteArray): String = sha1(header(type, data.size) + data)

    private fun header(type: String, size: Int) = "$type $size\u0000".toByteArray(Charsets.ISO_8859_1)

    private fun looseFile(id: String) = File(objects, id.substring(0, 2) + "/" + id.substring(2))

    fun has(id: String): Boolean = looseFile(id).isFile || packs().any { it.offset(id) != null }

    /** Stores an object (once) and returns its id. */
    fun write(type: String, data: ByteArray): String {
        val id = id(type, data)
        val f = looseFile(id)
        if (f.isFile || packs().any { it.offset(id) != null }) return id
        f.parentFile!!.mkdirs()
        val tmp = File(f.parentFile, "tmp_" + id.substring(2, 10) + "_" + System.nanoTime())
        tmp.writeBytes(deflate(header(type, data.size) + data))
        if (!tmp.renameTo(f)) { if (!f.isFile) { tmp.copyTo(f, true) }; tmp.delete() }
        return id
    }

    fun read(id: String): Obj? {
        if (!ID.matches(id)) return null
        val f = looseFile(id)
        if (f.isFile) {
            val raw = inflate(f.readBytes())
            val nul = raw.indexOf(0)
            val h = String(raw, 0, nul, Charsets.ISO_8859_1)
            return Obj(h.substringBefore(' '), raw.copyOfRange(nul + 1, raw.size))
        }
        for (p in packs()) p.read(id)?.let { return it }
        return null
    }

    fun readBlob(id: String): ByteArray? = read(id)?.takeIf { it.type == "blob" }?.data

    // ---------------------------------------------------------------- trees

    /** One tree entry: mode "100644" (file), "100755" (program), "120000" (link), "40000" (folder), "160000" (submodule). */
    data class Entry(val mode: String, val name: String, val id: String) { val isTree: Boolean get() = mode == "40000" }

    fun writeTree(entries: List<Entry>): String {
        val out = ByteArrayOutputStream()
        // git orders names as if folders ended with "/"
        for (e in entries.sortedWith { a, b -> compareBytes(sortKey(a), sortKey(b)) }) {
            out.write((e.mode + " " + e.name).toByteArray(Charsets.UTF_8)); out.write(0); out.write(hex(e.id))
        }
        return write("tree", out.toByteArray())
    }

    private fun sortKey(e: Entry): ByteArray = (if (e.isTree) e.name + "/" else e.name).toByteArray(Charsets.UTF_8)

    fun readTree(id: String): List<Entry> {
        val o = read(id)?.takeIf { it.type == "tree" } ?: return emptyList()
        val d = o.data; val out = ArrayList<Entry>(); var i = 0
        while (i < d.size) {
            val sp = indexOf(d, ' '.code.toByte(), i); val nul = indexOf(d, 0, sp)
            val mode = String(d, i, sp - i, Charsets.ISO_8859_1)
            val name = String(d, sp + 1, nul - sp - 1, Charsets.UTF_8)
            out.add(Entry(mode, name, toHex(d, nul + 1, 20)))
            i = nul + 21
        }
        return out
    }

    /** Every file in a tree, path to (mode, blob id). */
    fun flatten(treeId: String?, prefix: String = "", into: MutableMap<String, Pair<String, String>> = LinkedHashMap()): MutableMap<String, Pair<String, String>> {
        if (treeId == null) return into
        for (e in readTree(treeId)) {
            if (e.isTree) flatten(e.id, prefix + e.name + "/", into)
            else if (e.mode != "160000") into[prefix + e.name] = e.mode to e.id
        }
        return into
    }

    /** Builds the trees for a flat list of files and returns the root tree id. */
    fun writeFlat(files: Map<String, Pair<String, String>>): String {
        class Node { val files = LinkedHashMap<String, Pair<String, String>>(); val dirs = LinkedHashMap<String, Node>() }
        val top = Node()
        for ((path, v) in files) {
            val parts = path.split('/')
            var n = top
            for (k in 0 until parts.size - 1) n = n.dirs.getOrPut(parts[k]) { Node() }
            n.files[parts.last()] = v
        }
        fun build(n: Node): String = writeTree(n.files.map { (name, v) -> Entry(v.first, name, v.second) } + n.dirs.map { (name, d) -> Entry("40000", name, build(d)) })
        return build(top)
    }

    // ---------------------------------------------------------------- commits and tags

    /** "Name <email>" with a time in seconds and a zone like +0000. */
    data class Person(val name: String, val email: String, val time: Long, val zone: String = "+0000") {
        fun line(): String = "$name <$email> $time $zone"
        companion object {
            fun parse(s: String): Person {
                val lt = s.indexOf('<'); val gt = s.indexOf('>')
                if (lt < 0 || gt < 0) return Person(s.trim(), "", 0)
                val rest = s.substring(gt + 1).trim().split(' ')
                return Person(s.substring(0, lt).trim(), s.substring(lt + 1, gt), rest.getOrNull(0)?.toLongOrNull() ?: 0, rest.getOrNull(1) ?: "+0000")
            }
        }
    }

    data class Commit(val id: String, val tree: String, val parents: List<String>, val author: Person, val committer: Person, val message: String) {
        val title: String get() = message.trim().lineSequence().firstOrNull().orEmpty()
        val short: String get() = id.take(7)
    }

    fun writeCommit(tree: String, parents: List<String>, author: Person, committer: Person, message: String): String {
        val sb = StringBuilder("tree $tree\n")
        for (p in parents) sb.append("parent ").append(p).append('\n')
        sb.append("author ").append(author.line()).append('\n').append("committer ").append(committer.line()).append("\n\n")
        sb.append(if (message.endsWith("\n")) message else message + "\n")
        return write("commit", sb.toString().toByteArray(Charsets.UTF_8))
    }

    fun readCommit(id: String): Commit? {
        val o = read(id) ?: return null
        if (o.type == "tag") return tagTarget(id)?.let { readCommit(it) }
        if (o.type != "commit") return null
        val s = String(o.data, Charsets.UTF_8)
        val head = s.substringBefore("\n\n"); val msg = s.substringAfter("\n\n", "")
        var tree = ""; val parents = ArrayList<String>(); var author = Person("", "", 0); var committer = author
        for (l in head.lines()) when {
            l.startsWith("tree ") -> tree = l.substring(5).trim()
            l.startsWith("parent ") -> parents.add(l.substring(7).trim())
            l.startsWith("author ") -> author = Person.parse(l.substring(7))
            l.startsWith("committer ") -> committer = Person.parse(l.substring(10))
        }
        return Commit(id, tree, parents, author, committer, msg)
    }

    /** An annotated tag's object, or null for a lightweight one. */
    fun tagTarget(id: String): String? {
        val o = read(id)?.takeIf { it.type == "tag" } ?: return null
        return String(o.data, Charsets.UTF_8).lines().firstOrNull { it.startsWith("object ") }?.substring(7)?.trim()
    }

    fun tagMessage(id: String): String? = read(id)?.takeIf { it.type == "tag" }?.let { String(it.data, Charsets.UTF_8).substringAfter("\n\n", "").trim() }

    fun writeTag(name: String, target: String, tagger: Person, message: String): String =
        write("tag", ("object $target\ntype commit\ntag $name\ntagger ${tagger.line()}\n\n" + (if (message.endsWith("\n")) message else message + "\n")).toByteArray(Charsets.UTF_8))

    // ---------------------------------------------------------------- references

    /** The branch HEAD points at ("main"), or null when HEAD holds a commit id (detached). */
    fun headBranch(): String? = File(dir, "HEAD").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.startsWith("ref: refs/heads/") }?.removePrefix("ref: refs/heads/")

    fun setHead(branch: String) = File(dir, "HEAD").writeText("ref: refs/heads/$branch\n")

    fun headId(): String? {
        val h = File(dir, "HEAD").takeIf { it.isFile }?.readText()?.trim() ?: return null
        return if (h.startsWith("ref: ")) resolve(h.removePrefix("ref: ")) else h.takeIf { ID.matches(it) }
    }

    private fun packedRefs(): Map<String, String> {
        val f = File(dir, "packed-refs"); if (!f.isFile) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (l in f.readLines()) { if (l.startsWith("#") || l.startsWith("^") || l.isBlank()) continue; val p = l.split(' ', limit = 2); if (p.size == 2) out[p[1].trim()] = p[0] }
        return out
    }

    /** A full ref name ("refs/heads/main") or a short one ("main", "v1") to a commit or tag id. */
    fun resolve(name: String): String? {
        if (ID.matches(name)) return name
        val candidates = if (name.startsWith("refs/")) listOf(name) else listOf("refs/heads/$name", "refs/tags/$name", "refs/remotes/$name", name)
        val packed by lazy { packedRefs() }
        for (c in candidates) {
            val f = File(dir, c)
            if (f.isFile) { val t = f.readText().trim(); return if (t.startsWith("ref: ")) resolve(t.removePrefix("ref: ")) else t }
            packed[c]?.let { return it }
        }
        if (name.length in 4..39 && name.all { it in "0123456789abcdef" }) return allIds().firstOrNull { it.startsWith(name) }
        return null
    }

    fun setRef(name: String, id: String) { val f = File(dir, name); f.parentFile!!.mkdirs(); f.writeText(id + "\n"); log(name, id) }

    fun deleteRef(name: String) {
        File(dir, name).delete()
        val f = File(dir, "packed-refs")
        if (f.isFile) f.writeText(f.readLines().filter { !it.endsWith(" $name") }.joinToString("\n", postfix = "\n"))
    }

    /** Refs under a prefix ("refs/heads/"), short name to id. */
    fun refs(prefix: String): Map<String, String> {
        val out = java.util.TreeMap<String, String>()
        for ((k, v) in packedRefs()) if (k.startsWith(prefix)) out[k.removePrefix(prefix)] = v
        val base = File(dir, prefix)
        if (base.isDirectory) base.walkTopDown().filter { it.isFile }.forEach { f -> out[f.relativeTo(base).path.replace('\\', '/')] = f.readText().trim() }
        return out
    }

    private fun log(ref: String, id: String) {
        try { val f = File(dir, "logs/$ref"); f.parentFile!!.mkdirs(); f.appendText("0000000000000000000000000000000000000000 $id PocketIDE <pocketide@localhost> ${System.currentTimeMillis() / 1000} +0000\tupdate\n") } catch (e: Exception) { }
    }

    /** Ids of every object (loose and packed), for short-id lookups and copying. */
    fun allIds(): List<String> {
        val out = ArrayList<String>()
        objects.listFiles()?.filter { it.isDirectory && it.name.length == 2 }?.forEach { d -> d.listFiles()?.forEach { f -> if (f.name.length == 38) out.add(d.name + f.name) } }
        for (p in packs()) out.addAll(p.ids())
        return out
    }

    // ---------------------------------------------------------------- packs (what real git writes after gc or a clone)

    private var packCache: List<VcsPack>? = null
    private var packStamp = -1L

    fun packs(): List<VcsPack> {
        val pd = File(objects, "pack")
        val stamp = pd.lastModified() + (pd.list()?.size ?: 0)
        if (packCache == null || stamp != packStamp) {
            packCache = pd.listFiles { f -> f.name.endsWith(".idx") }?.mapNotNull { idx ->
                val pack = File(pd, idx.name.removeSuffix(".idx") + ".pack"); if (pack.isFile) VcsPack(idx, pack) { id -> read(id) } else null
            } ?: emptyList()
            packStamp = stamp
        }
        return packCache!!
    }

    companion object {
        val ID = Regex("^[0-9a-f]{40}$")
        val EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"

        fun sha1(b: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }
        fun hex(id: String): ByteArray = ByteArray(20) { i -> id.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        fun toHex(b: ByteArray, off: Int, len: Int): String { val sb = StringBuilder(len * 2); for (i in off until off + len) sb.append("%02x".format(b[i])); return sb.toString() }
        fun indexOf(b: ByteArray, v: Byte, from: Int): Int { var i = from; while (i < b.size && b[i] != v) i++; return i }
        fun compareBytes(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) { val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff); if (c != 0) return c }
            return a.size - b.size
        }
        fun deflate(b: ByteArray): ByteArray {
            val d = Deflater(Deflater.DEFAULT_COMPRESSION); d.setInput(b); d.finish()
            val out = ByteArrayOutputStream(maxOf(64, b.size / 2)); val buf = ByteArray(65536)
            while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
            d.end(); return out.toByteArray()
        }
        fun inflate(b: ByteArray, off: Int = 0, expected: Int = -1): ByteArray {
            val inf = Inflater(); inf.setInput(b, off, b.size - off)
            val out = ByteArrayOutputStream(if (expected > 0) expected else maxOf(64, b.size * 3)); val buf = ByteArray(65536)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (expected > 0 && out.size() >= expected) break
            }
            inf.end(); return out.toByteArray()
        }
    }
}

/** Reads one pack file through its version 2 index: plain objects and both kinds of deltas. */
class VcsPack(private val idx: File, private val pack: File, private val readBase: (String) -> VcsStore.Obj?) {
    private val index: ByteArray by lazy { idx.readBytes() }
    private val count: Int by lazy { int(index, 8 + 255 * 4) }

    private fun int(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    fun ids(): List<String> { val base = 8 + 256 * 4; return (0 until count).map { VcsStore.toHex(index, base + it * 20, 20) } }

    fun offset(id: String): Long? {
        if (index.size < 8 || int(index, 0) != 0xff744f63.toInt()) return null
        val want = VcsStore.hex(id); val first = want[0].toInt() and 0xff
        val fan = 8
        var lo = if (first == 0) 0 else int(index, fan + (first - 1) * 4); var hi = int(index, fan + first * 4)
        val names = 8 + 256 * 4
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            var c = 0
            for (k in 0 until 20) { c = (index[names + mid * 20 + k].toInt() and 0xff) - (want[k].toInt() and 0xff); if (c != 0) break }
            if (c == 0) {
                val offs = names + count * 20 + count * 4
                val o = int(index, offs + mid * 4)
                return if (o and 0x80000000.toInt() == 0) o.toLong() else {
                    val big = offs + count * 4 + (o and 0x7fffffff) * 8
                    (int(index, big).toLong() shl 32) or (int(index, big + 4).toLong() and 0xffffffffL)
                }
            }
            if (c < 0) lo = mid + 1 else hi = mid
        }
        return null
    }

    fun read(id: String): VcsStore.Obj? = offset(id)?.let { readAt(it) }

    private val TYPES = arrayOf("", "commit", "tree", "blob", "tag")

    private fun readAt(at: Long): VcsStore.Obj? {
        java.io.RandomAccessFile(pack, "r").use { f ->
            f.seek(at)
            var c = f.read(); val type = (c shr 4) and 7; var size = (c and 15).toLong(); var shift = 4
            while (c and 0x80 != 0) { c = f.read(); size = size or ((c and 0x7f).toLong() shl shift); shift += 7 }
            when (type) {
                1, 2, 3, 4 -> return VcsStore.Obj(TYPES[type], inflateFrom(f, size.toInt()))
                6 -> {
                    c = f.read(); var off = (c and 0x7f).toLong()
                    while (c and 0x80 != 0) { c = f.read(); off = ((off + 1) shl 7) or (c and 0x7f).toLong() }
                    val delta = inflateFrom(f, size.toInt())
                    val base = readAt(at - off) ?: return null
                    return VcsStore.Obj(base.type, apply(base.data, delta))
                }
                7 -> {
                    val b = ByteArray(20); f.readFully(b)
                    val delta = inflateFrom(f, size.toInt())
                    val base = readBase(VcsStore.toHex(b, 0, 20)) ?: return null
                    return VcsStore.Obj(base.type, apply(base.data, delta))
                }
                else -> return null
            }
        }
    }

    private fun inflateFrom(f: java.io.RandomAccessFile, size: Int): ByteArray {
        val inf = Inflater(); val out = ByteArrayOutputStream(maxOf(16, size)); val inBuf = ByteArray(8192); val buf = ByteArray(65536)
        while (!inf.finished()) {
            if (inf.needsInput()) { val n = f.read(inBuf); if (n <= 0) break; inf.setInput(inBuf, 0, n) }
            val n = inf.inflate(buf); out.write(buf, 0, n)
        }
        inf.end(); return out.toByteArray()
    }

    private fun apply(base: ByteArray, d: ByteArray): ByteArray {
        var i = 0
        fun varint(): Long { var r = 0L; var s = 0; while (true) { val c = d[i++].toInt() and 0xff; r = r or ((c and 0x7f).toLong() shl s); s += 7; if (c and 0x80 == 0) break }; return r }
        varint(); val outSize = varint().toInt()
        val out = ByteArray(outSize); var o = 0
        while (i < d.size) {
            val op = d[i++].toInt() and 0xff
            if (op and 0x80 != 0) {
                var off = 0; var len = 0
                if (op and 1 != 0) off = off or (d[i++].toInt() and 0xff)
                if (op and 2 != 0) off = off or ((d[i++].toInt() and 0xff) shl 8)
                if (op and 4 != 0) off = off or ((d[i++].toInt() and 0xff) shl 16)
                if (op and 8 != 0) off = off or ((d[i++].toInt() and 0xff) shl 24)
                if (op and 16 != 0) len = len or (d[i++].toInt() and 0xff)
                if (op and 32 != 0) len = len or ((d[i++].toInt() and 0xff) shl 8)
                if (op and 64 != 0) len = len or ((d[i++].toInt() and 0xff) shl 16)
                if (len == 0) len = 0x10000
                System.arraycopy(base, off, out, o, len); o += len
            } else if (op != 0) { System.arraycopy(d, i, out, o, op); i += op; o += op }
        }
        return out
    }
}
