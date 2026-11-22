package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream

/**
 * Writes a new cache in the same file format [Js5] reads: main_file_cache.dat2 plus idx files, with a reference table per index
 * stored in idx255. Meant for packing a game's own data (definitions, sprites, maps) into one file; it builds a fresh cache and
 * does not patch an existing Jagex cache, which a game client would reject anyway because its checksums would no longer match.
 */
class Js5Writer(private val dir: File) : AutoCloseable {
    class Group(val files: Map<Int, ByteArray>, val nameHash: Int? = null)

    private val dat: RandomAccessFile
    private var sector = 1                               // sector 0 is left empty, as in real caches
    private val idxEntries = HashMap<Int, java.util.TreeMap<Int, LongArray>>()

    init {
        dir.mkdirs()
        File(dir, "main_file_cache.dat2").delete()
        dat = RandomAccessFile(File(dir, "main_file_cache.dat2"), "rw")
        dat.write(ByteArray(520))
    }

    /** Add a whole index (groups by id). */
    fun addIndex(index: Int, groups: Map<Int, Group>, revision: Int = 1) {
        val ids = groups.keys.sorted()
        val named = groups.values.any { it.nameHash != null }
        val crcs = IntArray(ids.size)
        for ((n, id) in ids.withIndex()) {
            val g = groups.getValue(id)
            val container = container(join(g.files), revision)
            crcs[n] = CRC32().also { it.update(container, 0, container.size - 2) }.value.toInt()
            store(index, id, container)
        }
        val ref = referenceTable(ids, groups, named, crcs, revision)
        store(255, index, container(ref, revision))
    }

    private fun store(index: Int, group: Int, data: ByteArray) {
        val big = group > 65535
        val hdr = if (big) 10 else 8; val body = if (big) 510 else 512
        val start = sector
        var done = 0; var part = 0
        while (done < data.size) {
            val n = minOf(body, data.size - done)
            val next = if (done + n < data.size) sector + 1 else 0
            val b = ByteArray(520)
            var o = 0
            if (big) { b[o++] = (group ushr 24).toByte(); b[o++] = (group ushr 16).toByte() }
            b[o++] = (group ushr 8).toByte(); b[o++] = group.toByte()
            b[o++] = (part ushr 8).toByte(); b[o++] = part.toByte()
            b[o++] = (next ushr 16).toByte(); b[o++] = (next ushr 8).toByte(); b[o++] = next.toByte()
            b[o++] = index.toByte()
            System.arraycopy(data, done, b, hdr, n)
            dat.seek(sector.toLong() * 520); dat.write(b)
            sector++; done += n; part++
        }
        idxEntries.getOrPut(index) { java.util.TreeMap() }[group] = longArrayOf(data.size.toLong(), start.toLong())
    }

    override fun close() {
        for ((index, entries) in idxEntries) {
            val last = entries.lastKey()
            val out = ByteArray((last + 1) * 6)
            for ((g, e) in entries) {
                val o = g * 6; val s = e[0].toInt(); val sec = e[1].toInt()
                out[o] = (s ushr 16).toByte(); out[o + 1] = (s ushr 8).toByte(); out[o + 2] = s.toByte()
                out[o + 3] = (sec ushr 16).toByte(); out[o + 4] = (sec ushr 8).toByte(); out[o + 5] = sec.toByte()
            }
            File(dir, "main_file_cache.idx$index").writeBytes(out)
        }
        dat.close()
    }

    companion object {
        fun container(data: ByteArray, revision: Int): ByteArray {
            val z = ByteArrayOutputStream()
            GZIPOutputStream(z).use { it.write(data) }
            val c = z.toByteArray()
            val o = ByteArrayOutputStream()
            o.write(2); i32(o, c.size); i32(o, data.size); o.write(c)
            o.write(revision shr 8); o.write(revision)
            return o.toByteArray()
        }

        private fun i32(o: ByteArrayOutputStream, v: Int) { o.write(v ushr 24); o.write(v ushr 16); o.write(v ushr 8); o.write(v) }

        /** Several files in one group: bodies one after another, then the size changes, then the chunk count (always 1 here). */
        fun join(files: Map<Int, ByteArray>): ByteArray {
            val ids = files.keys.sorted()
            if (ids.size == 1) return files.getValue(ids[0])
            val o = ByteArrayOutputStream()
            for (id in ids) o.write(files.getValue(id))
            var prev = 0
            for (id in ids) { val s = files.getValue(id).size; i32(o, s - prev); prev = s }
            o.write(1)
            return o.toByteArray()
        }

        private fun referenceTable(ids: List<Int>, groups: Map<Int, Group>, named: Boolean, crcs: IntArray, revision: Int): ByteArray {
            val o = ByteArrayOutputStream()
            val protocol = 6
            o.write(protocol); i32(o, revision)
            o.write(if (named) 1 else 0)
            fun u16(v: Int) { if (v > 65535) throw IllegalArgumentException("too many entries for one index"); o.write(v shr 8); o.write(v) }
            u16(ids.size)
            var prev = 0
            for (id in ids) { u16(id - prev); prev = id }
            if (named) for (id in ids) i32(o, groups.getValue(id).nameHash ?: 0)
            for (c in crcs) i32(o, c)
            repeat(ids.size) { i32(o, revision) }
            for (id in ids) u16(groups.getValue(id).files.size)
            for (id in ids) { var p = 0; for (f in groups.getValue(id).files.keys.sorted()) { u16(f - p); p = f } }
            if (named) for (id in ids) repeat(groups.getValue(id).files.size) { i32(o, 0) }
            return o.toByteArray()
        }
    }
}
