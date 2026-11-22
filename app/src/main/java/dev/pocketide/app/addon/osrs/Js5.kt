package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/**
 * Reader for the "main_file_cache" format used by RuneScape-style game caches (a .dat2 data file plus .idx0..idx255 indexes).
 * Written from the format as documented by the OpenRS2 and RuneLite communities; no code copied. Read-only, streams from disk
 * (the data file is 100+ MB), and never loads more than one group at a time.
 */
class Js5(val dir: File) : AutoCloseable {
    private val dat = RandomAccessFile(File(dir, "main_file_cache.dat2"), "r")
    private val idx = HashMap<Int, RandomAccessFile?>()
    private val tables = HashMap<Int, Table?>()

    class Table(val protocol: Int, val revision: Int, val groups: IntArray, val nameHash: IntArray?, val files: Array<IntArray>, val fileNames: Array<IntArray>?) {
        fun indexOf(group: Int) = groups.binarySearch(group).let { if (it >= 0) it else -1 }
        fun groupByName(name: String): Int? {
            val h = nameHash(name); val n = nameHash ?: return null
            val i = n.indexOf(h); return if (i >= 0) groups[i] else null
        }
    }

    /** Which indexes exist (0..254 with an idx file). */
    fun indexes(): List<Int> = (0..254).filter { File(dir, "main_file_cache.idx$it").isFile }

    private fun idxFile(i: Int): RandomAccessFile? = idx.getOrPut(i) { File(dir, "main_file_cache.idx$i").takeIf { it.isFile }?.let { RandomAccessFile(it, "r") } }

    /** The raw bytes of a group (still in its container), or null when absent. */
    fun raw(index: Int, group: Int): ByteArray? {
        val f = idxFile(index) ?: return null
        if (group < 0 || (group + 1).toLong() * 6 > f.length()) return null
        val e = ByteArray(6); f.seek(group.toLong() * 6); f.readFully(e)
        val size = ((e[0].toInt() and 0xff) shl 16) or ((e[1].toInt() and 0xff) shl 8) or (e[2].toInt() and 0xff)
        var sector = ((e[3].toInt() and 0xff) shl 16) or ((e[4].toInt() and 0xff) shl 8) or (e[5].toInt() and 0xff)
        if (size == 0 || sector == 0) return null
        val big = group > 65535
        val hdr = if (big) 10 else 8; val body = if (big) 510 else 512
        val out = ByteArray(size); var done = 0; var part = 0
        val buf = ByteArray(520)
        while (done < size) {
            dat.seek(sector.toLong() * 520)
            val want = minOf(size - done, body)
            dat.readFully(buf, 0, hdr + want)
            val id = if (big) ((buf[0].toInt() and 0xff) shl 24) or ((buf[1].toInt() and 0xff) shl 16) or ((buf[2].toInt() and 0xff) shl 8) or (buf[3].toInt() and 0xff)
                     else ((buf[0].toInt() and 0xff) shl 8) or (buf[1].toInt() and 0xff)
            val o = if (big) 4 else 2
            val chunk = ((buf[o].toInt() and 0xff) shl 8) or (buf[o + 1].toInt() and 0xff)
            val next = ((buf[o + 2].toInt() and 0xff) shl 16) or ((buf[o + 3].toInt() and 0xff) shl 8) or (buf[o + 4].toInt() and 0xff)
            val ix = buf[o + 5].toInt() and 0xff
            if (id != group || chunk != part || ix != index) throw IllegalStateException("cache is damaged at index $index group $group")
            System.arraycopy(buf, hdr, out, done, want)
            done += want; part++; sector = next
        }
        return out
    }

    /** A group unpacked from its container (gzip, bzip2 or stored). Groups that need an XTEA key return null. */
    fun group(index: Int, group: Int, key: IntArray? = null): ByteArray? = raw(index, group)?.let { container(it, key) }

    private fun container(c0: ByteArray, key: IntArray?): ByteArray? {
        if (c0.size < 5) return null
        val comp = c0[0].toInt()
        val len = int(c0, 1)
        if (len < 0) return null
        val c = if (key != null && key.size == 4 && key.any { it != 0 }) xtea(c0, 5, 5 + len + (if (comp == 0) 0 else 4), key) else c0
        if (comp == 0) return if (5 + len <= c.size) c.copyOfRange(5, 5 + len) else null
        if (c.size < 9 + len) return null
        val unlen = int(c, 5)
        val body = c.copyOfRange(9, 9 + len)
        val out = try {
            when (comp) {
                1 -> Bzip2.decompressHeaderless(body)
                2 -> GZIPInputStream(body.inputStream()).use { it.readBytes() }
                else -> return null
            }
        } catch (e: Exception) { return null }
        return if (out.size == unlen) out else null
    }

    /** The files of a group, by file id. A group of one file is the whole group. */
    fun files(index: Int, group: Int): Map<Int, ByteArray>? {
        val t = table(index) ?: return null
        val gi = t.indexOf(group); if (gi < 0) return null
        val data = group(index, group) ?: return null
        return split(data, t.files[gi])
    }

    fun table(index: Int): Table? = tables.getOrPut(index) {
        val d = try { group(255, index) } catch (e: Exception) { null } ?: return@getOrPut null
        try { parseTable(d) } catch (e: Exception) { null }
    }

    override fun close() { dat.close(); idx.values.forEach { it?.close() } }

    companion object {
        private fun int(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

        /** Jagex's name hash: the same as Java's String.hashCode. */
        fun nameHash(s: String): Int { var h = 0; for (c in s) h = h * 31 + c.code; return h }

        /** Decrypt c[from, to) in place of a copy with XTEA (32 rounds, 8-byte blocks; a trailing partial block stays as is). */
        fun xtea(c: ByteArray, from: Int, to: Int, key: IntArray): ByteArray {
            val out = c.copyOf(); val end = minOf(to, c.size)
            var p = from
            while (p + 8 <= end) {
                var v0 = int(c, p); var v1 = int(c, p + 4); var sum = -0x61c88647 * 32
                repeat(32) {
                    v1 -= (((v0 shl 4) xor (v0 ushr 5)) + v0) xor (sum + key[(sum ushr 11) and 3])
                    sum += 0x61c88647
                    v0 -= (((v1 shl 4) xor (v1 ushr 5)) + v1) xor (sum + key[sum and 3])
                }
                for (i in 0 until 4) { out[p + i] = (v0 ushr (24 - 8 * i)).toByte(); out[p + 4 + i] = (v1 ushr (24 - 8 * i)).toByte() }
                p += 8
            }
            return out
        }

        fun hasCache(dir: File) = File(dir, "main_file_cache.dat2").isFile

        fun parseTable(d: ByteArray): Table {
            val r = Reader(d)
            val protocol = r.u8()
            if (protocol !in 5..7) throw IllegalStateException("unknown index format $protocol")
            val rev = if (protocol >= 6) r.i32() else 0
            val flags = r.u8()
            val named = flags and 1 != 0; val whirl = flags and 2 != 0; val sizes = flags and 4 != 0; val hashes = flags and 8 != 0
            val n = if (protocol >= 7) r.bigSmart() else r.u16()
            val ids = IntArray(n); var prev = 0
            for (i in 0 until n) { prev += if (protocol >= 7) r.bigSmart() else r.u16(); ids[i] = prev }
            val names = if (named) IntArray(n) { r.i32() } else null
            repeat(n) { r.i32() }                       // crc
            if (hashes) repeat(n) { r.i32() }
            if (whirl) r.skip(64 * n)
            if (sizes) repeat(n) { r.i32(); r.i32() }
            repeat(n) { r.i32() }                       // version
            val counts = IntArray(n) { if (protocol >= 7) r.bigSmart() else r.u16() }
            val files = Array(n) { g -> var p = 0; IntArray(counts[g]) { p += if (protocol >= 7) r.bigSmart() else r.u16(); p } }
            val fnames = if (named) Array(n) { g -> IntArray(counts[g]) { r.i32() } } else null
            return Table(protocol, rev, ids, names, files, fnames)
        }

        /** Split a multi-file group into its files. */
        fun split(data: ByteArray, ids: IntArray): Map<Int, ByteArray> {
            val n = ids.size
            if (n <= 1) return if (n == 1) mapOf(ids[0] to data) else emptyMap()
            val chunks = data[data.size - 1].toInt() and 0xff
            if (chunks == 0) return emptyMap()
            var p = data.size - 1 - chunks * n * 4
            if (p < 0) return emptyMap()
            val sizes = Array(chunks) { IntArray(n) }
            val total = IntArray(n)
            for (c in 0 until chunks) { var run = 0; for (f in 0 until n) { run += int(data, p); p += 4; sizes[c][f] = run; total[f] += run } }
            val bufs = Array(n) { ByteArray(total[it]) }; val fill = IntArray(n)
            var pos = 0
            for (c in 0 until chunks) for (f in 0 until n) {
                val s = sizes[c][f]
                if (pos + s > data.size) return emptyMap()
                System.arraycopy(data, pos, bufs[f], fill[f], s); fill[f] += s; pos += s
            }
            return ids.indices.associate { ids[it] to bufs[it] }
        }
    }

    /** Big-endian reader over a byte array. */
    class Reader(val b: ByteArray, var p: Int = 0) {
        fun left() = b.size - p
        fun u8() = b[p++].toInt() and 0xff
        /** 1 byte below 128, else 2 bytes minus 32768. */
        fun smart(): Int = if ((b[p].toInt() and 0xff) < 128) u8() else u16() - 32768
        fun i8() = b[p++].toInt()
        fun u16() = (u8() shl 8) or u8()
        fun i16() = u16().toShort().toInt()
        fun u24() = (u8() shl 16) or (u8() shl 8) or u8()
        fun i32() = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun skip(n: Int) { p += n }
        fun bigSmart(): Int = if (b[p].toInt() < 0) i32() and 0x7fffffff else u16()
        fun string(): String { val s = p; while (b[p].toInt() != 0) p++; val r = String(b, s, p - s, Charsets.ISO_8859_1); p++; return r }
    }
}
