package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bakes map squares into ready-to-draw 3D files for the game kit's 3D view (view3d.js): the ground as a lit, colour-blended
 * height field, and every object (walls, buildings, trees, decorations) as its real cache model, turned, mirrored, scaled
 * and lit the way the game places it. One binary file per square, so a phone only uploads triangles and draws.
 *
 * File layout (little endian, the whole file gzip-compressed; units are the game's: 128 per tile, y up, x east, z north, from the
 * square's south-west corner):
 *   "PS3D", u16 version 3, i16 square x, i16 square z
 *   65 x 65 i16 ground heights of floor 0 at tile corners (index x * 65 + z)
 *   u16 chunk count, then per chunk (16 x 16 tiles; sections: 0 ground, 1 objects on floor 0, then floor 1, 2 and 3 with their objects
 *   as sections 2, 3 and 4, so a view can draw the floors up to the player's and leave the ones above out; version 2 put all
 *   three upper floors in section 2, so the floor the player stood on could not be shown without the roof above it):
 *     u8 section, u8 chunk (cx * 4 + cz), u32 vertices, u32 indices, u8 index size (2 or 4),
 *     i16 x,y,z per vertex, u8 r,g,b per vertex, then the triangle indices
 *   u32 object count, then per object that has an action (trees, booths, doors): i32 id, u8 x, u8 z, u8 floor, u8 section,
 *     u8 chunk, u32 first index, u32 index count (its triangles inside that chunk, to hide it when it is cut down)
 */
object Scene3D {
    const val VERSION = 3

    /** The section a floor's ground ([obj] false) or objects go in: floor 0 keeps ground and objects apart, floors 1-3 are 2-4. */
    fun sectionOf(plane: Int, obj: Boolean): Int = when { plane <= 0 -> if (obj) 1 else 0; else -> 1 + plane.coerceAtMost(3) }

    /** Direction the light comes from (west, above, north), as in the game's scenes. */
    private val L = doubleArrayOf(-0.45, 0.75, 0.48).let { v -> val n = Math.sqrt(v.sumOf { it * it }); DoubleArray(3) { v[it] / n } }

    /** Triangles of one section, sorted into 16 x 16-tile chunks by where they lie, with shared corners stored once. */
    class Tris {
        class Chunk { val verts = HashMap<Long, Int>(); val pos = ArrayList<Short>(); val col = ArrayList<Byte>(); val idx = ArrayList<Int>() }
        val chunks = HashMap<Int, Chunk>()
        private val pend = IntArray(12); private var n = 0
        var count = 0; private set
        /** When set, every triangle goes to this chunk (an object stays whole in the chunk of its tile). */
        var forced = -1
        /** Index position the next triangle of [chunk] gets (for object ranges). */
        fun mark(chunk: Int) = chunks[chunk]?.idx?.size ?: 0
        fun chunkOf(x: Int, z: Int) = (x / 2048).coerceIn(0, 3) * 4 + (z / 2048).coerceIn(0, 3)
        fun add(x: Int, y: Int, z: Int, rgb: Int) {
            pend[n * 4] = x.coerceIn(-32768, 32767); pend[n * 4 + 1] = y.coerceIn(-32768, 32767); pend[n * 4 + 2] = z.coerceIn(-32768, 32767); pend[n * 4 + 3] = rgb and 0xffffff
            if (++n < 3) return
            n = 0; count++
            val c = chunks.getOrPut(if (forced >= 0) forced else chunkOf((pend[0] + pend[4] + pend[8]) / 3, (pend[2] + pend[6] + pend[10]) / 3)) { Chunk() }
            for (k in 0 until 3) {
                val px = pend[k * 4]; val py = pend[k * 4 + 1]; val pz = pend[k * 4 + 2]; val col = pend[k * 4 + 3]
                // corners at the same place with (almost) the same colour are one vertex
                val c565 = ((col shr 19) and 31 shl 11) or ((col shr 10) and 63 shl 5) or ((col shr 3) and 31)
                val key = ((px.toLong() and 0xffff) shl 48) or ((py.toLong() and 0xffff) shl 32) or ((pz.toLong() and 0xffff) shl 16) or c565.toLong()
                val i = c.verts.getOrPut(key) {
                    c.pos.add(px.toShort()); c.pos.add(py.toShort()); c.pos.add(pz.toShort())
                    c.col.add((col shr 16).toByte()); c.col.add((col shr 8).toByte()); c.col.add(col.toByte())
                    c.pos.size / 3 - 1
                }
                c.idx.add(i)
            }
        }
    }

    class Square(val sx: Int, val sz: Int, val terrain: Terrain, val heights: IntArray)

    // The game's tile shapes: which points of a tile each shape uses (1-8 corners and edge middles, 9-16 inner points)
    // and its triangles as (1 = overlay, 0 = underlay, then three points; points below 4 are corners that turn with the tile).
    private val SHAPE_VERTS = arrayOf(intArrayOf(1, 3, 5, 7), intArrayOf(1, 3, 5, 7), intArrayOf(1, 3, 5, 7), intArrayOf(1, 3, 5, 7, 6), intArrayOf(1, 3, 5, 7, 6),
        intArrayOf(1, 3, 5, 7, 6), intArrayOf(1, 3, 5, 7, 6), intArrayOf(1, 3, 5, 7, 2, 6), intArrayOf(1, 3, 5, 7, 2, 8), intArrayOf(1, 3, 5, 7, 2, 8),
        intArrayOf(1, 3, 5, 7, 11, 12), intArrayOf(1, 3, 5, 7, 11, 12), intArrayOf(1, 3, 5, 7, 13, 14))
    private val SHAPE_TRIS = arrayOf(intArrayOf(0, 1, 2, 3, 0, 0, 1, 3), intArrayOf(1, 1, 2, 3, 1, 0, 1, 3), intArrayOf(0, 1, 2, 3, 1, 0, 1, 3),
        intArrayOf(0, 0, 1, 2, 0, 0, 2, 4, 1, 0, 4, 3), intArrayOf(0, 0, 1, 4, 0, 0, 4, 3, 1, 1, 2, 4), intArrayOf(0, 0, 4, 3, 1, 0, 1, 2, 1, 0, 2, 4),
        intArrayOf(0, 1, 2, 4, 1, 0, 1, 4, 1, 0, 4, 3), intArrayOf(0, 4, 1, 2, 0, 4, 2, 5, 1, 0, 4, 5, 1, 0, 5, 3), intArrayOf(0, 4, 1, 2, 0, 4, 2, 3, 0, 4, 3, 5, 1, 0, 4, 5),
        intArrayOf(0, 0, 4, 5, 1, 4, 1, 2, 1, 4, 2, 3, 1, 4, 3, 5), intArrayOf(0, 0, 1, 5, 0, 1, 4, 5, 0, 1, 2, 4, 1, 0, 5, 3, 1, 5, 4, 3, 1, 4, 2, 3),
        intArrayOf(1, 0, 1, 5, 1, 1, 4, 5, 1, 1, 2, 4, 0, 0, 5, 3, 0, 5, 4, 3, 0, 4, 2, 3), intArrayOf(1, 0, 5, 4, 1, 0, 1, 5, 0, 0, 4, 3, 0, 4, 5, 3, 0, 5, 2, 3, 0, 1, 2, 5))

    private fun shade(rgb: Int, k: Double): Int {
        fun c(v: Int) = (v * k).toInt().coerceIn(0, 255)
        return (c((rgb shr 16) and 255) shl 16) or (c((rgb shr 8) and 255) shl 8) or c(rgb and 255)
    }
    /** Brightness for a surface facing [n] (unit, y up): flat ground keeps its colour, slopes towards the light are brighter. */
    private fun light(nx: Double, ny: Double, nz: Double): Double = (1.0 + 0.55 * ((nx * L[0] + ny * L[1] + nz * L[2]) - L[1])).coerceIn(0.45, 1.35)

    /**
     * [at]: the squares around, by "x,z", for heights and colours across edges. [under]/[over]: floor colour by the id stored in a
     * tile (already shifted: tile value 1 is definition 0). [objects]: the square's objects; [model] gives a model by id.
     */
    fun bake(sq: Square, at: (Int, Int) -> Square?, under: (Int) -> Int?, over: (Int) -> Int?, objects: List<Locs.Loc>,
             defs: Map<Int, CacheDefs.Def>, model: (Int) -> Models.Model?, old: Boolean, texture: (Int) -> Int?): ByteArray {
        val baseX = sq.sx * 64; val baseZ = sq.sz * 64
        fun sqOf(wx: Int, wz: Int) = if (wx shr 6 == sq.sx && wz shr 6 == sq.sz) sq else at(wx shr 6, wz shr 6)
        fun h(plane: Int, wx: Int, wz: Int): Int { val s = sqOf(wx, wz) ?: return sqOf(wx.coerceIn(baseX, baseX + 63), wz.coerceIn(baseZ, baseZ + 63))!!.heights[plane * 4096 + (wx.coerceIn(baseX, baseX + 63) and 63) * 64 + (wz.coerceIn(baseZ, baseZ + 63) and 63)]
            return s.heights[plane * 4096 + (wx and 63) * 64 + (wz and 63)] }
        fun underAt(plane: Int, wx: Int, wz: Int): Int { val s = sqOf(wx, wz) ?: return 0; return s.terrain.underlay[s.terrain.at(plane, wx and 63, wz and 63)] }

        // underlay colours blended over the tiles around (radius 5, as the game does), per tile of this square plus a border
        val blend = HashMap<Long, Int>()
        fun blended(plane: Int, wx: Int, wz: Int): Int? = blend.getOrPut((plane.toLong() shl 40) or (wx.toLong() shl 20) or wz.toLong()) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (dx in -5..5) for (dz in -5..5) { val u = underAt(plane, wx + dx, wz + dz); if (u == 0) continue; val c = under(u - 1) ?: continue; r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++ }
            if (n == 0) -1 else ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }.takeIf { it >= 0 }

        val sections = Array(5) { Tris() }
        // ground: two triangles per tile, corner colours blended and lit by the slope
        for (plane in 0 until 4) {
            val out = sections[sectionOf(plane, false)]
            for (x in 0 until 64) for (z in 0 until 64) {
                val i = sq.terrain.at(plane, x, z)
                val u = sq.terrain.underlay[i]; val o = sq.terrain.overlay[i]
                if (u == 0 && (o == 0 || over(o - 1) == null)) continue   // nothing to draw: no floor, or an overlay the game leaves empty
                if (plane > 0 && (sq.terrain.settings[sq.terrain.at(0, x, z)] and 2) != 0) continue   // a bridge tile: drawn on the floor below in the game
                val wx = baseX + x; val wz = baseZ + z
                val hs = intArrayOf(h(plane, wx, wz), h(plane, wx + 1, wz), h(plane, wx + 1, wz + 1), h(plane, wx, wz + 1))
                val cx = intArrayOf(0, 128, 128, 0); val cz = intArrayOf(0, 0, 128, 128)
                fun cornerLight(k: Int): Double {
                    val px = wx + (cx[k] / 128); val pz = wz + (cz[k] / 128)
                    val nx = (h(plane, px - 1, pz) - h(plane, px + 1, pz)).toDouble(); val nz = (h(plane, px, pz - 1) - h(plane, px, pz + 1)).toDouble(); val ny = 256.0
                    val len = Math.sqrt(nx * nx + ny * ny + nz * nz); return light(nx / len, ny / len, nz / len)
                }
                val colours = IntArray(4) { k ->
                    val ov = if (o != 0) over(o - 1) else null
                    val base = ov ?: run {
                        // a corner shares the blended colour of the four tiles that meet there
                        var r = 0; var g = 0; var b = 0; var n = 0
                        for ((ddx, ddz) in listOf(0 to 0, -1 to 0, 0 to -1, -1 to -1)) { val c = blended(plane, wx + cx[k] / 128 + ddx, wz + cz[k] / 128 + ddz) ?: continue; r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++ }
                        if (n == 0) 0x3a5a2a else ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                    }
                    shade(base, cornerLight(k))
                }
                if (o == 0) { fun tri(a: Int, b: Int, c: Int) { for (k in intArrayOf(a, b, c)) out.add(x * 128 + cx[k], hs[k], z * 128 + cz[k], colours[k]) }; tri(0, 2, 1); tri(0, 3, 2); continue }
                // an overlay tile: the game's tile shapes (paths, shores, corners) split it into overlay and underlay triangles
                val uCols = IntArray(4) { k ->
                    var r = 0; var g = 0; var b = 0; var n = 0
                    for ((ddx, ddz) in listOf(0 to 0, -1 to 0, 0 to -1, -1 to -1)) { val c = blended(plane, wx + cx[k] / 128 + ddx, wz + cz[k] / 128 + ddz) ?: continue; r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++ }
                    shade(if (n == 0) colours[k] else ((r / n) shl 16) or ((g / n) shl 8) or (b / n), cornerLight(k))
                }
                val shape = ((sq.terrain.overlayShape[i] shr 2) + 1).coerceIn(0, 12); val rot = sq.terrain.overlayShape[i] and 3
                val codes = SHAPE_VERTS[shape]
                val vx = IntArray(codes.size); val vy = IntArray(codes.size); val vz = IntArray(codes.size); val vu = IntArray(codes.size); val vo = IntArray(codes.size)
                fun mix(a: Int, b: Int) = (((a shr 16 and 255) + (b shr 16 and 255)) / 2 shl 16) or (((a shr 8 and 255) + (b shr 8 and 255)) / 2 shl 8) or (((a and 255) + (b and 255)) / 2)
                for ((v, code0) in codes.withIndex()) {
                    var code = code0
                    if (code and 1 == 0 && code <= 8) code = ((code - rot - rot - 1) and 7) + 1
                    if (code in 9..12) code = ((code - rot - 9) and 3) + 9
                    if (code in 13..16) code = ((code - rot - 13) and 3) + 13
                    // SW=0, SE=1, NE=2, NW=3 corners; mid points take the mean of the two corners they sit between
                    val (px, pz, a, b) = when (code) {
                        1 -> listOf(0, 0, 0, 0); 2 -> listOf(64, 0, 0, 1); 3 -> listOf(128, 0, 1, 1); 4 -> listOf(128, 64, 1, 2)
                        5 -> listOf(128, 128, 2, 2); 6 -> listOf(64, 128, 2, 3); 7 -> listOf(0, 128, 3, 3); 8 -> listOf(0, 64, 3, 0)
                        9 -> listOf(64, 32, 0, 1); 10 -> listOf(96, 64, 1, 2); 11 -> listOf(64, 96, 2, 3); 12 -> listOf(32, 64, 3, 0)
                        13 -> listOf(32, 32, 0, 0); 14 -> listOf(96, 32, 1, 1); 15 -> listOf(96, 96, 2, 2); else -> listOf(32, 96, 3, 3)
                    }
                    vx[v] = px; vz[v] = pz; vy[v] = (hs[a] + hs[b]) / 2; vu[v] = mix(uCols[a], uCols[b]); vo[v] = mix(colours[a], colours[b])
                }
                val t = SHAPE_TRIS[shape]
                var k = 0
                while (k + 3 < t.size) {
                    val overlayFace = t[k] == 1
                    for (q in 1..3) { var c = t[k + q]; if (c < 4) c = (c - rot) and 3; out.add(x * 128 + vx[c], vy[c], z * 128 + vz[c], if (overlayFace) vo[c] else vu[c]) }
                    k += 4
                }
            }
        }

        // objects
        class Range(val id: Int, val x: Int, val z: Int, val plane: Int, val section: Int, val chunk: Int, val first: Int, val count: Int)
        val ranges = ArrayList<Range>()
        for (l in objects) {
            if (l.x !in 0..63 || l.z !in 0..63) continue
            val d = defs[l.id] ?: continue
            val ids = (d.fields["models"] as? List<*>)?.map { (it as Number).toInt() } ?: continue
            val types = (d.fields["modelTypes"] as? List<*>)?.map { (it as Number).toInt() }
            val section = sections[sectionOf(l.plane, true)]
            val chunk = section.chunkOf(l.x * 128 + 64, l.z * 128 + 64)
            section.forced = chunk
            val before = section.mark(chunk)
            fun place(modelType: Int, rot: Int, mirror: Boolean, extraTurn: Double = 0.0, dx: Int = 0, dz: Int = 0) {
                val chosen = if (types != null) ids.filterIndexed { k, _ -> types.getOrNull(k) == modelType }.ifEmpty { if (modelType == 10 || modelType == 11) ids.filterIndexed { k, _ -> types.getOrNull(k) == 10 } else emptyList() }
                    else if (modelType == 10 || modelType == 11 || modelType == 22) ids else ids
                if (chosen.isEmpty()) return
                val parts = chosen.mapNotNull { model(it) }; if (parts.isEmpty()) return
                val m = Models.merge(l.id, parts)
                addModel(section, m, d, l, rot, mirror xor (d.fields["mirror"] == true), extraTurn, dx, dz, baseX, baseZ, ::h, old, texture)
            }
            val r = l.rot and 3
            when (l.type) {
                0, 1, 3 -> place(l.type, r, false)
                2 -> { place(2, r, true); place(2, (r + 1) and 3, false) }   // an L corner: one piece mirrored, one turned
                4 -> place(4, r, false)
                5 -> { val off = 16; val (ox, oz) = when (r) { 0 -> -off to 0; 1 -> 0 to off; 2 -> off to 0; else -> 0 to -off }; place(4, r, false, 0.0, ox, oz) }
                6, 7, 8 -> place(4, r, false, Math.PI / 4)
                9 -> place(9, r, false)
                10 -> place(10, r, false)
                11 -> place(10, r, false, Math.PI / 4)
                in 12..21 -> place(l.type, r, false)
                22 -> place(22, r, false)
            }
            section.forced = -1
            val made = section.mark(chunk) - before
            val acts = (d.fields["actions"] as? List<*>)?.filterIsInstance<String>().orEmpty()
            if (made > 0 && acts.isNotEmpty()) ranges.add(Range(l.id, l.x, l.z, l.plane, sectionOf(l.plane, true), chunk, before, made))
        }

        // write
        val o = ByteArrayOutputStream()
        fun buf(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        o.write("PS3D".toByteArray()); o.write(buf(6).putShort(VERSION.toShort()).putShort(sq.sx.toShort()).putShort(sq.sz.toShort()).array())
        val hb = buf(65 * 65 * 2); for (x in 0..64) for (z in 0..64) hb.putShort(h(0, baseX + x, baseZ + z).coerceIn(-32768, 32767).toShort()); o.write(hb.array())
        val all = sections.withIndex().flatMap { (kind, t) -> t.chunks.entries.sortedBy { it.key }.map { Triple(kind, it.key, it.value) } }.filter { it.third.idx.isNotEmpty() }
        o.write(buf(2).putShort(all.size.toShort()).array())
        for ((kind, ck, c) in all) {
            val vc = c.pos.size / 3; val wide = vc > 65535
            o.write(kind); o.write(ck); o.write(buf(9).putInt(vc).putInt(c.idx.size).put((if (wide) 4 else 2).toByte()).array())
            val pb = buf(c.pos.size * 2); for (v in c.pos) pb.putShort(v); o.write(pb.array())
            o.write(c.col.toByteArray())
            val ib = buf(c.idx.size * (if (wide) 4 else 2)); for (v in c.idx) if (wide) ib.putInt(v) else ib.putShort(v.toShort()); o.write(ib.array())
        }
        o.write(buf(4).putInt(ranges.size).array())
        for (r in ranges) o.write(buf(17).putInt(r.id).put(r.x.toByte()).put(r.z.toByte()).put(r.plane.toByte()).put(r.section.toByte()).put(r.chunk.toByte()).putInt(r.first).putInt(r.count).array())
        // the strongest level: a square is baked once and read many times, and it saved 17% on the 93 squares of a big project
        val gz = ByteArrayOutputStream(); object : java.util.zip.GZIPOutputStream(gz) { init { def.setLevel(java.util.zip.Deflater.BEST_COMPRESSION) } }.use { it.write(o.toByteArray()) }
        return gz.toByteArray()
    }

    /** Turns, mirrors, scales and moves one object model to its tile and adds its lit triangles. Cache models have y pointing down. */
    private fun addModel(out: Tris, m: Models.Model, d: CacheDefs.Def, l: Locs.Loc, rot: Int, mirror: Boolean, extraTurn: Double, dx: Int, dz: Int,
                         baseX: Int, baseZ: Int, h: (Int, Int, Int) -> Int, old: Boolean, texture: (Int) -> Int?) {
        val w0 = ((d.fields["width"] as? Number)?.toInt() ?: 1).coerceIn(1, 16); val l0 = ((d.fields["length"] as? Number)?.toInt() ?: 1).coerceIn(1, 16)
        val (w, len) = if (rot % 2 == 1) l0 to w0 else w0 to l0
        val sxs = ((d.fields["scaleX"] as? Number)?.toInt() ?: 128); val sys = ((d.fields["scaleY"] as? Number)?.toInt() ?: 128); val szs = ((d.fields["scaleZ"] as? Number)?.toInt() ?: 128)
        val offX = (d.fields["offsetX"] as? Number)?.toInt() ?: 0; val offY = (d.fields["offsetY"] as? Number)?.toInt() ?: 0; val offZ = (d.fields["offsetZ"] as? Number)?.toInt() ?: 0
        val big = l.type == 10 || l.type == 11 || l.type == 22
        // centre of the footprint, in square units; walls and decorations sit on their own tile
        val cx = l.x * 128 + (if (big) w * 64 else 64) + dx; val cz = l.z * 128 + (if (big) len * 64 else 64) + dz
        val wx0 = baseX + l.x; val wz0 = baseZ + l.z
        val gw = if (big) w else 1; val gl = if (big) len else 1
        val ground = (h(l.plane, wx0, wz0) + h(l.plane, wx0 + gw, wz0) + h(l.plane, wx0, wz0 + gl) + h(l.plane, wx0 + gw, wz0 + gl)) / 4
        val contour = l.type == 22 || (d.fields["contour"] as? Number)?.toInt()?.let { it >= 0 } == true
        val recol = HashMap<Int, Int>()
        (d.fields["recolor"] as? List<*>)?.forEach { p -> val q = p as? List<*>; if (q != null && q.size == 2) recol[(q[0] as Number).toInt()] = (q[1] as Number).toInt() }
        val n = m.x.size
        val px = DoubleArray(n); val py = DoubleArray(n); val pz = DoubleArray(n)
        val turn = extraTurn
        val ct = Math.cos(turn); val st = Math.sin(turn)
        for (i in 0 until n) {
            var x = m.x[i] * sxs / 128.0; val y = -m.y[i] * sys / 128.0; var z = m.z[i] * szs / 128.0   // y up from here on
            if (mirror) z = -z
            // the game turns objects by quarter turns counter-clockwise seen from above (cache z grows to the north)
            repeat(rot) { val t = x; x = z; z = -t }
            if (turn != 0.0) { val t = x * ct + z * st; z = -x * st + z * ct; x = t }
            px[i] = cx + x + offX; pz[i] = cz + z + offZ; py[i] = y - offY
        }
        // vertex normals (sum of the faces around each vertex) for smooth light, like the game's shading of objects
        val nxs = DoubleArray(n); val nys = DoubleArray(n); val nzs = DoubleArray(n)
        val fc = m.a.size
        for (f in 0 until fc) {
            val a = m.a[f]; val b = m.b[f]; val c = m.c[f]
            val ux = px[b] - px[a]; val uy = py[b] - py[a]; val uz = pz[b] - pz[a]
            val vx = px[c] - px[a]; val vy = py[c] - py[a]; val vz = pz[c] - pz[a]
            // cache faces wind clockwise seen from outside with y down; with y flipped (and z north) the cross product points outwards
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            if (mirror) { nx = -nx; ny = -ny; nz = -nz }
            for (v in intArrayOf(a, b, c)) { nxs[v] += nx; nys[v] += ny; nzs[v] += nz }
        }
        for (f in 0 until fc) {
            if (m.alpha[f] < 40) continue   // all but invisible
            val hsl = m.colour[f]
            val tex = m.texture[f]
            // a textured face: Old School faces take the texture's colour; 2009 faces keep their own unless it is black
            val own = recol[hsl] ?: hsl
            val base = (if (tex >= 0 && (!old || (own and 127) < 10)) texture(tex) else null) ?: Models.rgb(own)
            val vs = if (mirror) intArrayOf(m.a[f], m.c[f], m.b[f]) else intArrayOf(m.a[f], m.b[f], m.c[f])
            for (v in vs) {
                val len = Math.sqrt(nxs[v] * nxs[v] + nys[v] * nys[v] + nzs[v] * nzs[v]).takeIf { it > 0 } ?: 1.0
                val k = light(nxs[v] / len, nys[v] / len, nzs[v] / len)
                var y = py[v] + ground
                if (contour) { val gx = baseX + Math.floorDiv(px[v].toInt(), 128); val gz = baseZ + Math.floorDiv(pz[v].toInt(), 128); y = py[v] + h(l.plane, gx, gz) }
                out.add(Math.round(px[v]).toInt(), Math.round(y).toInt(), Math.round(pz[v]).toInt(), shade(base, k))
            }
        }
    }
}
