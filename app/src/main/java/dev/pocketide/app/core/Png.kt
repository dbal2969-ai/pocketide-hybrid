package dev.pocketide.app.core

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Minimal PNG reader and writer (8-bit, not interlaced) that works without Android classes, so it can run in tests. */
object Png {
    class Image(val width: Int, val height: Int, val argb: IntArray)

    fun encode(img: Image): ByteArray {
        val raw = ByteArray((img.width * 4 + 1) * img.height)
        var o = 0
        for (y in 0 until img.height) {
            raw[o++] = 0
            for (x in 0 until img.width) {
                val p = img.argb[y * img.width + x]
                raw[o++] = (p shr 16).toByte(); raw[o++] = (p shr 8).toByte(); raw[o++] = p.toByte(); raw[o++] = (p ushr 24).toByte()
            }
        }
        val d = Deflater(6); d.setInput(raw); d.finish()
        val z = ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (!d.finished()) z.write(buf, 0, d.deflate(buf))
        d.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArray(13)
        put(ihdr, 0, img.width); put(ihdr, 4, img.height); ihdr[8] = 8; ihdr[9] = 6
        chunk(out, "IHDR", ihdr); chunk(out, "IDAT", z.toByteArray()); chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun put(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte() }
    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val h = ByteArray(4); put(h, 0, data.size); out.write(h)
        val t = type.toByteArray(Charsets.ISO_8859_1); out.write(t); out.write(data)
        val c = CRC32(); c.update(t); c.update(data); put(h, 0, c.value.toInt()); out.write(h)
    }

    fun decode(b: ByteArray): Image {
        if (b.size < 33 || b[1] != 'P'.code.toByte() || b[2] != 'N'.code.toByte()) throw IllegalArgumentException("not a PNG file")
        var p = 8; var w = 0; var h = 0; var depth = 0; var type = 0
        var pal: ByteArray? = null; var trns: ByteArray? = null
        val idat = ByteArrayOutputStream()
        while (p + 8 <= b.size) {
            val len = ((b[p].toInt() and 0xff) shl 24) or ((b[p + 1].toInt() and 0xff) shl 16) or ((b[p + 2].toInt() and 0xff) shl 8) or (b[p + 3].toInt() and 0xff)
            val name = String(b, p + 4, 4, Charsets.ISO_8859_1); val s = p + 8
            if (len < 0 || s + len > b.size) throw IllegalArgumentException("PNG is cut off")
            fun i32(o: Int) = ((b[s + o].toInt() and 0xff) shl 24) or ((b[s + o + 1].toInt() and 0xff) shl 16) or ((b[s + o + 2].toInt() and 0xff) shl 8) or (b[s + o + 3].toInt() and 0xff)
            when (name) {
                "IHDR" -> { w = i32(0); h = i32(4); depth = b[s + 8].toInt(); type = b[s + 9].toInt(); if (b[s + 12].toInt() != 0) throw IllegalArgumentException("interlaced PNG is not supported") }
                "PLTE" -> pal = b.copyOfRange(s, s + len)
                "tRNS" -> trns = b.copyOfRange(s, s + len)
                "IDAT" -> idat.write(b, s, len)
            }
            if (name == "IEND") break
            p = s + len + 4
        }
        if (depth != 8) throw IllegalArgumentException("only 8-bit PNG is supported (got $depth-bit)")
        if (w <= 0 || h <= 0 || w.toLong() * h > 16_000_000L) throw IllegalArgumentException("PNG size is not usable")
        val ch = when (type) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> throw IllegalArgumentException("unknown PNG colour type") }
        val stride = w * ch
        val raw = ByteArray((stride + 1) * h)
        val inf = Inflater(); inf.setInput(idat.toByteArray())
        var got = 0
        while (got < raw.size) { val n = inf.inflate(raw, got, raw.size - got); if (n == 0 && (inf.finished() || inf.needsInput())) break; got += n }
        inf.end()
        if (got < raw.size) throw IllegalArgumentException("PNG data is incomplete")
        val px = ByteArray(stride * h)
        for (y in 0 until h) {
            val f = raw[y * (stride + 1)].toInt()
            for (x in 0 until stride) {
                val v = raw[y * (stride + 1) + 1 + x].toInt() and 0xff
                val a = if (x >= ch) px[y * stride + x - ch].toInt() and 0xff else 0
                val up = if (y > 0) px[(y - 1) * stride + x].toInt() and 0xff else 0
                val c = if (x >= ch && y > 0) px[(y - 1) * stride + x - ch].toInt() and 0xff else 0
                val r = when (f) {
                    0 -> v; 1 -> v + a; 2 -> v + up; 3 -> v + (a + up) / 2
                    4 -> { val pp = a + up - c; val pa = Math.abs(pp - a); val pb = Math.abs(pp - up); val pc = Math.abs(pp - c); v + if (pa <= pb && pa <= pc) a else if (pb <= pc) up else c }
                    else -> throw IllegalArgumentException("bad PNG filter")
                }
                px[y * stride + x] = r.toByte()
            }
        }
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val o = i * ch
            fun u(k: Int) = px[o + k].toInt() and 0xff
            out[i] = when (type) {
                0 -> { val g = u(0); (255 shl 24) or (g shl 16) or (g shl 8) or g }
                2 -> (255 shl 24) or (u(0) shl 16) or (u(1) shl 8) or u(2)
                3 -> { val k = u(0); val pl = pal ?: throw IllegalArgumentException("PNG palette missing"); val al = trns?.let { t -> if (k < t.size) t[k].toInt() and 0xff else 255 } ?: 255
                    (al shl 24) or ((pl[k * 3].toInt() and 0xff) shl 16) or ((pl[k * 3 + 1].toInt() and 0xff) shl 8) or (pl[k * 3 + 2].toInt() and 0xff) }
                4 -> { val g = u(0); (u(1) shl 24) or (g shl 16) or (g shl 8) or g }
                else -> (u(3) shl 24) or (u(0) shl 16) or (u(1) shl 8) or u(2)
            }
        }
        return Image(w, h, out)
    }
}
