package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

import dev.pocketide.app.addon.osrs.Js5.Reader
import java.io.ByteArrayOutputStream

/** Sprites of a game cache (index 8): palette-indexed pictures, one group per sprite id, each with one or more frames. */
object Sprites {
    const val INDEX = 8
    class Frame(val width: Int, val height: Int, val offsetX: Int, val offsetY: Int, val argb: IntArray)
    class Sheet(val maxWidth: Int, val maxHeight: Int, val frames: List<Frame>)

    fun decode(d: ByteArray): Sheet {
        if (d.size < 9) throw IllegalArgumentException("sprite data too short")
        val r = Reader(d, d.size - 2)
        val count = r.u16()
        if (count <= 0 || 7 + count * 8 > d.size) throw IllegalArgumentException("sprite frame count is not valid")
        r.p = d.size - 7 - count * 8
        val maxW = r.u16(); val maxH = r.u16(); val palLen = r.u8() + 1
        val ox = IntArray(count) { r.u16() }; val oy = IntArray(count) { r.u16() }
        val w = IntArray(count) { r.u16() }; val h = IntArray(count) { r.u16() }
        r.p = d.size - 7 - count * 8 - (palLen - 1) * 3
        val pal = IntArray(palLen)
        for (i in 1 until palLen) { var c = r.u24(); if (c == 0) c = 1; pal[i] = c }
        r.p = 0
        val frames = ArrayList<Frame>()
        for (i in 0 until count) {
            val dim = w[i] * h[i]
            val idx = IntArray(dim); var alpha: IntArray? = null
            val flags = r.u8()
            if (flags and 1 == 0) for (j in 0 until dim) idx[j] = r.u8()
            else for (x in 0 until w[i]) for (y in 0 until h[i]) idx[w[i] * y + x] = r.u8()
            if (flags and 2 != 0) {
                alpha = IntArray(dim)
                if (flags and 1 == 0) for (j in 0 until dim) alpha[j] = r.u8()
                else for (x in 0 until w[i]) for (y in 0 until h[i]) alpha[w[i] * y + x] = r.u8()
            }
            val px = IntArray(dim)
            for (j in 0 until dim) {
                val k = idx[j]; if (k >= palLen) throw IllegalArgumentException("sprite palette index out of range")
                val a = alpha?.get(j) ?: if (k == 0) 0 else 255
                px[j] = if (k == 0 && alpha == null) 0 else (a shl 24) or pal[k]
            }
            frames.add(Frame(w[i], h[i], ox[i], oy[i], px))
        }
        return Sheet(maxW, maxH, frames)
    }

    /** Pack frames into sprite data. More than 255 colours are reduced by dropping low bits until they fit. */
    fun encode(sheet: Sheet): ByteArray {
        var shift = 0
        var palette: LinkedHashMap<Int, Int>
        while (true) {
            palette = LinkedHashMap()
            var ok = true
            loop@ for (f in sheet.frames) for (p in f.argb) {
                if (p ushr 24 == 0) continue
                val c = reduce(p, shift)
                if (!palette.containsKey(c)) { if (palette.size >= 255) { ok = false; break@loop }; palette[c] = palette.size + 1 }
            }
            if (ok) break
            shift++
            if (shift > 7) throw IllegalArgumentException("too many colours for a sprite")
        }
        val out = ByteArrayOutputStream()
        for (f in sheet.frames) {
            val hasAlpha = f.argb.any { val a = it ushr 24; a != 0 && a != 255 }
            out.write(if (hasAlpha) 2 else 0)
            for (p in f.argb) out.write(if (p ushr 24 == 0) 0 else palette.getValue(reduce(p, shift)))
            if (hasAlpha) for (p in f.argb) out.write(p ushr 24)
        }
        for (c in palette.keys) { out.write(c shr 16); out.write(c shr 8); out.write(c) }
        fun u16(v: Int) { out.write(v shr 8); out.write(v) }
        for (f in sheet.frames) u16(f.offsetX)
        for (f in sheet.frames) u16(f.offsetY)
        for (f in sheet.frames) u16(f.width)
        for (f in sheet.frames) u16(f.height)
        // layout read back by decode: maxW, maxH, palette length - 1 sit before the per-frame arrays
        val body = out.toByteArray()
        val frameArrays = sheet.frames.size * 8
        val head = ByteArrayOutputStream()
        val arrStart = body.size - frameArrays
        head.write(body, 0, arrStart)
        head.write(sheet.maxWidth shr 8); head.write(sheet.maxWidth); head.write(sheet.maxHeight shr 8); head.write(sheet.maxHeight); head.write(palette.size)
        head.write(body, arrStart, frameArrays)
        head.write(sheet.frames.size shr 8); head.write(sheet.frames.size)
        return head.toByteArray()
    }

    private fun reduce(p: Int, shift: Int): Int {
        if (shift == 0) return p and 0xffffff
        val m = (0xff shl shift) and 0xff
        val r = (p shr 16) and m; val g = (p shr 8) and m; val b = p and m
        return (r shl 16) or (g shl 8) or b
    }

    fun toPng(f: Frame): ByteArray = Png.encode(Png.Image(f.width, f.height, f.argb))
}
