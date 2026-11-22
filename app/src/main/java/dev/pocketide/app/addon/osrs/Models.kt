package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

import dev.pocketide.app.addon.osrs.Js5.Reader

/**
 * 3D models of a game cache (index 7, one group per model id): vertices, triangles, per-face colour and transparency.
 * Two storage versions exist in current caches (ending FF FD and FF FE). Layout learned from the BSD-licensed RuneLite cache
 * library's documentation of the format; this decoder is written independently and keeps only what drawing needs
 * (no animation skeleton or texture mapping). Textured faces get a plain colour.
 */
object Models {
    const val INDEX = 7

    class Model(val id: Int, val x: IntArray, val y: IntArray, val z: IntArray, val a: IntArray, val b: IntArray, val c: IntArray,
                val colour: IntArray, val alpha: IntArray, val texture: IntArray, val vertexGroup: IntArray?)

    fun decode(id: Int, d: ByteArray): Model {
        if (d.size < 30) throw IllegalArgumentException("model $id is too short")
        val t = d[d.size - 1].toInt(); val t2 = d[d.size - 2].toInt()
        if (t2 == -1 && t == -3) return decodeNew(id, d)
        if (t2 == -1 && t == -2) return decodeMid(id, d)
        if (t2 == -1 && t == -1) return decodeType1(id, d)
        return decodeOld(id, d)
    }

    // FF FF: 23-byte footer with texture types first (2009-era caches and older Old School models).
    private fun decodeType1(id: Int, d: ByteArray): Model {
        val h = at(d, d.size - 23)
        val vc = h.u16(); val fc = h.u16(); val tc = h.u8(); val info = h.u8(); val prio = h.u8(); val hasAlpha = h.u8()
        val hasFaceSkin = h.u8(); val hasTex = h.u8(); val hasVSkin = h.u8()
        val xl = h.u16(); val yl = h.u16(); val zl = h.u16(); val idxl = h.u16(); val texl = h.u16()
        var o = tc + vc
        val renderOff = o; if (info and 1 == 1) o += fc
        val compOff = o; o += fc
        if (prio == 255) o += fc
        if (hasFaceSkin == 1) o += fc
        val vskinOff = o; if (hasVSkin == 1) o += vc
        val alphaOff = o; if (hasAlpha == 1) o += fc
        val idxOff = o; o += idxl
        val texOff = o; if (hasTex == 1) o += fc * 2
        o += texl
        val colOff = o; o += fc * 2
        val xOff = o; o += xl
        val yOff = o; o += yl
        val zOff = o; o += zl
        if (o > d.size - 23) throw IllegalArgumentException("model $id layout does not fit")
        return build(id, d, vc, fc, hasTex == 1, hasAlpha == 1, hasVSkin == 1, tc, vskinOff, xOff, yOff, zOff, compOff, idxOff, colOff, alphaOff, texOff, true, if (info and 1 == 1) renderOff else -1)
    }

    // No marker: the oldest layout, an 18-byte footer.
    private fun decodeOld(id: Int, d: ByteArray): Model {
        if (d.size < 18) throw IllegalArgumentException("model $id is too short")
        val h = at(d, d.size - 18)
        val vc = h.u16(); val fc = h.u16(); val tc = h.u8(); val info = h.u8(); val prio = h.u8(); val hasAlpha = h.u8()
        val hasFaceSkin = h.u8(); val hasVSkin = h.u8()
        val xl = h.u16(); val yl = h.u16(); val zl = h.u16(); val idxl = h.u16()
        var o = vc
        val compOff = o; o += fc
        if (prio == 255) o += fc
        if (hasFaceSkin == 1) o += fc
        val infoOff = o; if (info == 1) o += fc
        val vskinOff = o; if (hasVSkin == 1) o += vc
        val alphaOff = o; if (hasAlpha == 1) o += fc
        val idxOff = o; o += idxl
        val colOff = o; o += fc * 2
        o += tc * 6
        val xOff = o; o += xl
        val yOff = o; o += yl
        val zOff = o; o += zl
        if (vc == 0 || o > d.size - 18) throw IllegalArgumentException("model $id uses a storage version that is not supported")
        return build(id, d, vc, fc, info == 1, hasAlpha == 1, hasVSkin == 1, tc, vskinOff, xOff, yOff, zOff, compOff, idxOff, colOff, alphaOff, infoOff, false)
    }

    private fun Reader.shortSmart(): Int = if ((b[p].toInt() and 0xff) < 128) u8() - 64 else u16() - 49152
    private fun at(d: ByteArray, p: Int) = Reader(d, p)

    // FF FD: 26-byte footer, texture types first.
    private fun decodeNew(id: Int, d: ByteArray): Model {
        val h = at(d, d.size - 26)
        val vc = h.u16(); val fc = h.u16(); val tc = h.u8(); val hasRender = h.u8(); val prio = h.u8(); val hasAlpha = h.u8()
        val hasFaceSkin = h.u8(); val hasTex = h.u8(); val hasVSkin = h.u8(); val hasAnim = h.u8()
        val xl = h.u16(); val yl = h.u16(); val zl = h.u16(); val idxl = h.u16(); val texl = h.u16(); val animLen = h.u16()
        var n0 = 0; var n1 = 0; var n2 = 0
        if (tc > 0) { val r = at(d, 0); repeat(tc) { val k = r.u8(); if (k == 0) n0++; if (k in 1..3) n1++; if (k == 2) n2++ } }
        var o = tc + vc
        val renderOff = o; if (hasRender == 1) o += fc
        val compOff = o; o += fc
        if (prio == 255) o += fc
        if (hasFaceSkin == 1) o += fc
        val vskinOff = o; o += animLen
        val alphaOff = o; if (hasAlpha == 1) o += fc
        val idxOff = o; o += idxl
        val texOff = o; if (hasTex == 1) o += fc * 2
        val coordOff = o; o += texl
        val colOff = o; o += fc * 2
        val xOff = o; o += xl
        val yOff = o; o += yl
        val zOff = o; o += zl
        o += n0 * 6 + n1 * 6 + n1 * 6 + n1 * 2 + n1 + n1 * 2 + n2 * 2
        if (o > d.size - 26) throw IllegalArgumentException("model $id layout does not fit")
        return build(id, d, vc, fc, hasTex == 1, hasAlpha == 1, hasVSkin == 1, tc, vskinOff, xOff, yOff, zOff, compOff, idxOff, colOff, alphaOff, texOff, true, if (hasRender == 1) renderOff else -1)
    }

    // FF FE: 23-byte footer.
    private fun decodeMid(id: Int, d: ByteArray): Model {
        val h = at(d, d.size - 23)
        val vc = h.u16(); val fc = h.u16(); val tc = h.u8(); val hasRender = h.u8(); val prio = h.u8(); val hasAlpha = h.u8()
        val hasFaceSkin = h.u8(); val hasVSkin = h.u8(); val hasAnim = h.u8()
        val xl = h.u16(); val yl = h.u16(); val zl = h.u16(); val idxl = h.u16(); val animLen = h.u16()
        var o = vc
        val compOff = o; o += fc
        if (prio == 255) o += fc
        if (hasFaceSkin == 1) o += fc
        val renderOff = o; if (hasRender == 1) o += fc
        val vskinOff = o; o += animLen
        val alphaOff = o; if (hasAlpha == 1) o += fc
        val idxOff = o; o += idxl
        val colOff = o; o += fc * 2
        val texCoordOff = o; o += tc * 6
        val xOff = o; o += xl
        val yOff = o; o += yl
        val zOff = o; o += zl
        if (o > d.size - 23) throw IllegalArgumentException("model $id layout does not fit")
        return build(id, d, vc, fc, hasRender == 1, hasAlpha == 1, hasVSkin == 1, tc, vskinOff, xOff, yOff, zOff, compOff, idxOff, colOff, alphaOff, renderOff, false)
    }

    private fun build(id: Int, d: ByteArray, vc: Int, fc: Int, hasTexOrRender: Boolean, hasAlpha: Boolean, hasVSkin: Boolean, tc: Int,
                      vskinOff: Int, xOff: Int, yOff: Int, zOff: Int, compOff: Int, idxOff: Int, colOff: Int, alphaOff: Int, texOrRenderOff: Int, newFormat: Boolean, renderTypesOff: Int = -1): Model {
        val flags = at(d, if (newFormat) tc else 0)
        val rx = at(d, xOff); val ry = at(d, yOff); val rz = at(d, zOff)
        val vs = at(d, vskinOff)
        val xs = IntArray(vc); val ys = IntArray(vc); val zs = IntArray(vc)
        val groups = if (hasVSkin) IntArray(vc) else null
        var px = 0; var py = 0; var pz = 0
        for (i in 0 until vc) {
            val f = flags.u8()
            if (f and 1 != 0) px += rx.shortSmart()
            if (f and 2 != 0) py += ry.shortSmart()
            if (f and 4 != 0) pz += rz.shortSmart()
            xs[i] = px; ys[i] = py; zs[i] = pz
            if (groups != null) groups[i] = vs.u8()
        }
        val colour = IntArray(fc); val alpha = IntArray(fc) { 255 }; val tex = IntArray(fc) { -1 }
        val cr = at(d, colOff); val ar = at(d, alphaOff); val tr = at(d, texOrRenderOff)
        for (i in 0 until fc) {
            colour[i] = cr.u16()
            if (newFormat) {
                if (hasTexOrRender) tex[i] = tr.u16() - 1
            } else if (hasTexOrRender) {
                val r = tr.u8()
                if (r and 2 == 2) { tex[i] = colour[i]; colour[i] = 127 }
            }
            if (hasAlpha) alpha[i] = 255 - (ar.u8())
        }
        // newer layouts: a face of render type 2 without a texture is not drawn by the game (it only shapes shadows and clicks)
        if (newFormat && renderTypesOff >= 0) { val rt = at(d, renderTypesOff); for (i in 0 until fc) if ((rt.u8() and 3) == 2 && tex[i] < 0) alpha[i] = 0 }
        val ir = at(d, idxOff); val cm = at(d, compOff)
        val a = IntArray(fc); val b = IntArray(fc); val c = IntArray(fc)
        var v1 = 0; var v2 = 0; var v3 = 0; var last = 0
        for (i in 0 until fc) {
            when (cm.u8()) {
                1 -> { v1 = ir.shortSmart() + last; v2 = ir.shortSmart() + v1; v3 = ir.shortSmart() + v2; last = v3 }
                2 -> { v2 = v3; v3 = ir.shortSmart() + last; last = v3 }
                3 -> { v1 = v3; v3 = ir.shortSmart() + last; last = v3 }
                4 -> { val t = v1; v1 = v2; v2 = t; v3 = ir.shortSmart() + last; last = v3 }
                else -> throw IllegalArgumentException("model $id has a face of unknown kind")
            }
            if (v1 !in 0 until vc || v2 !in 0 until vc || v3 !in 0 until vc) throw IllegalArgumentException("model $id has a face outside its vertices")
            a[i] = v1; b[i] = v2; c[i] = v3
        }
        return Model(id, xs, ys, zs, a, b, c, colour, alpha, tex, groups)
    }

    /** 16-bit face colour (6-bit hue, 3-bit saturation, 7-bit lightness) to 0xRRGGBB. */
    fun rgb(hsl: Int, brightness: Double = 0.6): Int {
        val h = ((hsl shr 10) and 63) / 64.0 + 0.0078125
        val s = ((hsl shr 7) and 7) / 8.0 + 0.0625
        val l = (hsl and 127) / 128.0
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun ch(t0: Double): Double { var t = t0; if (t < 0) t += 1.0; if (t > 1) t -= 1.0
            return when { 6 * t < 1 -> p + (q - p) * 6 * t; 2 * t < 1 -> q; 3 * t < 2 -> p + (q - p) * (2.0 / 3 - t) * 6; else -> p } }
        fun fin(v: Double) = (Math.pow(v, brightness) * 256).toInt().coerceIn(0, 255)
        return (fin(ch(h + 1.0 / 3)) shl 16) or (fin(ch(h)) shl 8) or fin(ch(h - 1.0 / 3))
    }

    /**
     * JSON for a drawing page: vertices as flat x,y,z (y up, sign flipped from the cache), triangles as flat vertex indices,
     * one "#rrggbb" per face, optional per-face opacity (only when some face is see-through).
     */
    fun toJson(m: Model, recolour: Map<Int, Int> = emptyMap(), textureColour: (Int) -> Int? = { null }): String {
        val sb = StringBuilder("{\"id\":${m.id},\"vertices\":[")
        for (i in m.x.indices) { if (i > 0) sb.append(','); sb.append(m.x[i]).append(',').append(-m.y[i]).append(',').append(m.z[i]) }
        sb.append("],\"faces\":[")
        for (i in m.a.indices) { if (i > 0) sb.append(','); sb.append(m.a[i]).append(',').append(m.b[i]).append(',').append(m.c[i]) }
        sb.append("],\"colors\":[")
        for (i in m.colour.indices) { if (i > 0) sb.append(','); val tc = if (m.texture[i] >= 0) textureColour(m.texture[i]) else null
            sb.append('"').append(String.format("#%06x", tc ?: rgb(recolour[m.colour[i]] ?: m.colour[i]))).append('"') }
        sb.append(']')
        if (m.vertexGroup != null) { sb.append(",\"groups\":["); for (i in m.vertexGroup.indices) { if (i > 0) sb.append(','); sb.append(m.vertexGroup[i]) }; sb.append(']') }
        if (m.alpha.any { it != 255 }) { sb.append(",\"opacity\":["); for (i in m.alpha.indices) { if (i > 0) sb.append(','); sb.append(String.format("%.2f", m.alpha[i] / 255.0)) }; sb.append(']') }
        sb.append('}')
        return sb.toString()
    }

    /** Join several models (an NPC is made of parts) into one; ids come from the first. */
    fun merge(id: Int, parts: List<Model>): Model {
        if (parts.size == 1) return parts[0]
        val vo = IntArray(parts.size); var v = 0; for ((i, p) in parts.withIndex()) { vo[i] = v; v += p.x.size }
        fun cat(sel: (Model) -> IntArray) = parts.flatMap { sel(it).toList() }.toIntArray()
        val a = ArrayList<Int>(); val b = ArrayList<Int>(); val c = ArrayList<Int>()
        for ((i, p) in parts.withIndex()) for (k in p.a.indices) { a.add(p.a[k] + vo[i]); b.add(p.b[k] + vo[i]); c.add(p.c[k] + vo[i]) }
        // vertex groups stay with their vertices, so the joined model can still be animated
        val groups = if (parts.any { it.vertexGroup != null }) parts.flatMap { p -> (p.vertexGroup ?: IntArray(p.x.size)).toList() }.toIntArray() else null
        return Model(id, cat { it.x }, cat { it.y }, cat { it.z }, a.toIntArray(), b.toIntArray(), c.toIntArray(), cat { it.colour }, cat { it.alpha }, cat { it.texture }, groups)
    }
}
