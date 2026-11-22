package dev.pocketide.app.core

import java.io.ByteArrayOutputStream

/**
 * A bzip2 decoder, because Android has none and game caches store many pieces in it. Written from the public bzip2 format
 * description (no library code copied). Cache containers drop the 4-byte "BZh1" header; [decompressHeaderless] puts it back.
 */
object Bzip2 {
    fun decompressHeaderless(data: ByteArray): ByteArray = decompress(byteArrayOf('B'.code.toByte(), 'Z'.code.toByte(), 'h'.code.toByte(), '1'.code.toByte()) + data)

    private class Bits(val b: ByteArray) {
        var pos = 0; var buf = 0L; var n = 0
        fun read(c: Int): Int {
            while (n < c) {
                if (pos >= b.size) throw IllegalStateException("bzip2: data ends early")
                buf = (buf shl 8) or (b[pos++].toLong() and 0xff); n += 8
            }
            val v = ((buf shr (n - c)) and ((1L shl c) - 1)).toInt()
            n -= c; buf = buf and ((1L shl n) - 1)
            return v
        }
        fun bit(): Int = read(1)
    }

    fun decompress(src: ByteArray): ByteArray {
        val r = Bits(src)
        if (r.read(8) != 'B'.code || r.read(8) != 'Z'.code || r.read(8) != 'h'.code) throw IllegalStateException("bzip2: bad header")
        val level = r.read(8) - '0'.code
        if (level !in 1..9) throw IllegalStateException("bzip2: bad block size")
        val out = ByteArrayOutputStream()
        while (true) {
            val m1 = r.read(24); val m2 = r.read(24)
            if (m1 == 0x314159 && m2 == 0x265359) { r.read(32); block(r, out, level * 100000) }
            else if (m1 == 0x177245 && m2 == 0x385090) break
            else throw IllegalStateException("bzip2: bad block marker")
        }
        return out.toByteArray()
    }

    private fun block(r: Bits, out: ByteArrayOutputStream, maxBlock: Int) {
        if (r.bit() == 1) throw IllegalStateException("bzip2: randomised blocks are not supported")
        val origPtr = r.read(24)
        val used16 = r.read(16)
        val seqToUnseq = IntArray(256); var inUse = 0
        for (i in 0 until 16) if ((used16 shr (15 - i)) and 1 != 0) {
            val bits = r.read(16)
            for (j in 0 until 16) if ((bits shr (15 - j)) and 1 != 0) seqToUnseq[inUse++] = i * 16 + j
        }
        if (inUse == 0) throw IllegalStateException("bzip2: empty symbol map")
        val alpha = inUse + 2
        val groups = r.read(3)
        if (groups !in 2..6) throw IllegalStateException("bzip2: bad table count")
        val nSel = r.read(15)
        if (nSel < 1) throw IllegalStateException("bzip2: no selectors")
        val sel = IntArray(nSel)
        for (i in 0 until nSel) { var j = 0; while (r.bit() == 1) { j++; if (j >= groups) throw IllegalStateException("bzip2: bad selector") }; sel[i] = j }
        val pos = IntArray(groups) { it }
        for (i in 0 until nSel) { val v = sel[i]; val t = pos[v]; for (k in v downTo 1) pos[k] = pos[k - 1]; pos[0] = t; sel[i] = t }
        val limit = Array(groups) { IntArray(24) }; val base = Array(groups) { IntArray(24) }; val perm = Array(groups) { IntArray(alpha) }
        val minLen = IntArray(groups)
        for (t in 0 until groups) {
            val len = IntArray(alpha); var cur = r.read(5)
            for (i in 0 until alpha) {
                while (true) {
                    if (cur < 1 || cur > 20) throw IllegalStateException("bzip2: bad code length")
                    if (r.bit() == 0) break
                    if (r.bit() == 0) cur++ else cur--
                }
                len[i] = cur
            }
            var mn = 32; var mx = 0
            for (l in len) { if (l > mx) mx = l; if (l < mn) mn = l }
            minLen[t] = mn
            var pp = 0
            for (i in mn..mx) for (j in 0 until alpha) if (len[j] == i) perm[t][pp++] = j
            val bs = base[t]; val lm = limit[t]
            for (i in 0 until alpha) bs[len[i] + 1]++
            for (i in 1 until 24) bs[i] += bs[i - 1]
            var vec = 0
            for (i in mn..mx) { vec += bs[i + 1] - bs[i]; lm[i] = vec - 1; vec = vec shl 1 }
            for (i in mn + 1..mx) bs[i] = ((lm[i - 1] + 1) shl 1) - bs[i]
            for (i in mx + 1 until 24) lm[i] = Int.MAX_VALUE
        }
        val mtf = IntArray(inUse) { it }
        val tt = IntArray(maxBlock)
        val counts = IntArray(256)
        var nblock = 0
        var groupNo = -1; var groupPos = 0; var table = 0
        val eob = inUse + 1
        fun next(): Int {
            if (groupPos == 0) { groupNo++; if (groupNo >= nSel) throw IllegalStateException("bzip2: ran out of selectors"); groupPos = 50; table = sel[groupNo] }
            groupPos--
            var zn = minLen[table]; var zvec = r.read(zn)
            val lm = limit[table]
            while (zvec > lm[zn]) { zn++; if (zn > 20) throw IllegalStateException("bzip2: bad code"); zvec = (zvec shl 1) or r.bit() }
            val idx = zvec - base[table][zn]
            if (idx < 0 || idx >= alpha) throw IllegalStateException("bzip2: bad code")
            return perm[table][idx]
        }
        var sym = next()
        while (sym != eob) {
            if (sym <= 1) {
                var es = 0; var n = 1
                while (sym <= 1) { es += if (sym == 0) n else 2 * n; n = n shl 1; if (es > maxBlock) throw IllegalStateException("bzip2: run too long"); sym = next() }
                val uc = seqToUnseq[mtf[0]]
                if (nblock + es > maxBlock) throw IllegalStateException("bzip2: block too big")
                counts[uc] += es
                java.util.Arrays.fill(tt, nblock, nblock + es, uc); nblock += es
            } else {
                if (nblock >= maxBlock) throw IllegalStateException("bzip2: block too big")
                val i = sym - 1
                val v = mtf[i]; for (k in i downTo 1) mtf[k] = mtf[k - 1]; mtf[0] = v
                val uc = seqToUnseq[v]
                tt[nblock++] = uc; counts[uc]++
                sym = next()
            }
        }
        if (origPtr >= nblock && nblock > 0) throw IllegalStateException("bzip2: bad start pointer")
        val cf = IntArray(256); var sum = 0
        for (i in 0 until 256) { cf[i] = sum; sum += counts[i] }
        for (i in 0 until nblock) { val uc = tt[i] and 0xff; tt[cf[uc]] = tt[cf[uc]] or (i shl 8); cf[uc]++ }
        if (nblock == 0) return
        var tPos = tt[origPtr] ushr 8
        var prev = -1; var run = 0
        var i = 0
        while (i < nblock) {
            tPos = tt[tPos]; val ch = tPos and 0xff; tPos = tPos ushr 8; i++
            if (run == 4) { for (k in 0 until ch) out.write(prev); run = 0; prev = -1; continue }
            if (ch == prev) run++ else { run = 1; prev = ch }
            out.write(ch)
        }
    }
}
