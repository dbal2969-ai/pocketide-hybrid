package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

import dev.pocketide.app.addon.osrs.Js5.Reader
import java.io.ByteArrayOutputStream

/**
 * One 64x64 map square (4 height levels) of a game cache's terrain (index 5, group "m<x>_<z>"): heights, floor types and the
 * per-tile flags (field codes are 16-bit in current revisions). Objects (trees, walls) are in a separate encrypted "l" file and are not part of this.
 */
class Terrain(val regionX: Int, val regionZ: Int) {
    val height = IntArray(4 * 4096)       // 0 = not set
    val hasHeight = BooleanArray(4 * 4096)
    val settings = IntArray(4 * 4096)     // bit 0 = blocked, bit 1 = bridge
    val overlay = IntArray(4 * 4096)
    val overlayShape = IntArray(4 * 4096) // path*4 + rotation
    val underlay = IntArray(4 * 4096)

    fun at(plane: Int, x: Int, z: Int) = plane * 4096 + x * 64 + z
    fun blocked(plane: Int, x: Int, z: Int) = settings[at(plane, x, z)] and 1 != 0

    /** Rows of '#' (blocked), '=' (bridge), '.' (open) for one plane. Row 0 is the north edge (z = 63). */
    fun grid(plane: Int): List<String> = (63 downTo 0).map { z ->
        String(CharArray(64) { x -> val s = settings[at(plane, x, z)]; if (s and 1 != 0) '#' else if (s and 2 != 0) '=' else '.' })
    }

    /**
     * Real tile heights for the four floors, y up, in client units (128 units are one tile). A tile without a stored height gets the
     * client's value-noise height on the ground floor and sits one storey (240 units) above the floor below on the others; a stored
     * height is added to the floor below (the ground floor starts from zero). Index = plane * 4096 + x * 64 + z.
     */
    fun heights(): IntArray {
        val out = IntArray(4 * 4096)
        for (plane in 0 until 4) for (x in 0 until 64) for (z in 0 until 64) {
            val i = at(plane, x, z)
            val below = if (plane == 0) 0 else out[at(plane - 1, x, z)]
            out[i] = if (hasHeight[i]) below + (if (height[i] == 1) 0 else height[i]) * 8
            else if (plane == 0) Noise.height(regionX * 64 + x + 932731, regionZ * 64 + z + 556238) * 8
            else below + 240
        }
        return out
    }

    fun encode(): ByteArray {
        val o = ByteArrayOutputStream()
        for (i in 0 until 4 * 4096) {
            fun u16(v: Int) { o.write(v shr 8); o.write(v) }
            if (overlay[i] != 0) { u16(2 + overlayShape[i]); u16(overlay[i]) }
            if (settings[i] != 0) u16(49 + settings[i])
            if (underlay[i] != 0) u16(81 + underlay[i])
            if (hasHeight[i]) { u16(1); o.write(height[i]) } else u16(0)
        }
        return o.toByteArray()
    }

    companion object {
        const val INDEX = 5
        fun name(x: Int, z: Int) = "m${x}_$z"

        /** Current caches use two-byte opcodes; 2009-era caches (build 530) one byte, with one-byte overlay ids. */
        fun decode(x: Int, z: Int, d: ByteArray): Terrain =
            try { decodeWith(x, z, d, true) } catch (e: Exception) { try { decodeWith(x, z, d, false) } catch (e2: Exception) { throw e } }

        private fun decodeWith(x: Int, z: Int, d: ByteArray, wide: Boolean): Terrain {
            val t = Terrain(x, z); val r = Reader(d)
            for (plane in 0 until 4) for (xx in 0 until 64) for (zz in 0 until 64) {
                val i = t.at(plane, xx, zz)
                while (true) {
                    val op = if (wide) r.u16() else r.u8()
                    when {
                        op == 0 -> break
                        op == 1 -> { t.height[i] = r.u8(); t.hasHeight[i] = true; break }
                        op <= 49 -> { t.overlay[i] = if (wide) r.u16() else r.u8(); t.overlayShape[i] = op - 2 }
                        op <= 81 -> t.settings[i] = op - 49
                        else -> t.underlay[i] = op - 81
                    }
                }
            }
            // 2009-era squares end with lighting, occlusion and environment blocks that the tiles do not need
            if (r.left() != 0 && wide) throw IllegalArgumentException("map data has ${r.left()} bytes left over")
            return t
        }

        /** Rebuild from the readable grid form: planes of 64 rows of '#', '=' and '.'. */
        fun fromGrids(x: Int, z: Int, planes: List<List<String>>): Terrain {
            val t = Terrain(x, z)
            for ((plane, rows) in planes.withIndex().take(4)) {
                if (rows.size != 64 || rows.any { it.length != 64 }) throw IllegalArgumentException("each plane needs 64 rows of 64 characters")
                for ((row, line) in rows.withIndex()) for (xx in 0 until 64) {
                    val i = t.at(plane, xx, 63 - row)
                    t.settings[i] = when (line[xx]) { '#' -> 1; '=' -> 2; '.' -> 0; else -> throw IllegalArgumentException("only # = . are allowed in a map grid") }
                }
            }
            return t
        }
    }
}

/** The client's smooth value noise, which gives hills to tiles whose height is not stored (a port of its integer arithmetic). */
object Noise {
    private val COS = IntArray(2048) { (65536.0 * Math.cos(it * 0.0030679615)).toInt() }

    private fun noise(x: Int, y: Int): Int {
        var n = x + y * 57
        n = n xor (n shl 13)
        return (((n * n * 15731 + 789221) * n + 1376312589) and Int.MAX_VALUE) shr 19 and 255
    }

    private fun smooth(x: Int, y: Int): Int {
        val corners = noise(x - 1, y - 1) + noise(x + 1, y - 1) + noise(x - 1, y + 1) + noise(x + 1, y + 1)
        val sides = noise(x - 1, y) + noise(x + 1, y) + noise(x, y - 1) + noise(x, y + 1)
        return corners / 16 + sides / 8 + noise(x, y) / 4
    }

    private fun interpolate(a: Int, b: Int, x: Int, scale: Int): Int {
        val t = (65536 - COS[x * 1024 / scale]) shr 1
        return ((a * (65536 - t)) shr 16) + ((b * t) shr 16)
    }

    private fun interpolated(x: Int, y: Int, scale: Int): Int {
        val xi = x / scale; val xf = x and (scale - 1); val yi = y / scale; val yf = y and (scale - 1)
        val v1 = smooth(xi, yi); val v2 = smooth(xi + 1, yi); val v3 = smooth(xi, yi + 1); val v4 = smooth(xi + 1, yi + 1)
        return interpolate(interpolate(v1, v2, xf, scale), interpolate(v3, v4, xf, scale), yf, scale)
    }

    /** 10..60: the stored height of an open tile, times 8 for client units. */
    fun height(x: Int, y: Int): Int {
        var h = interpolated(x + 45365, y + 91923, 4) - 128 + ((interpolated(x + 10294, y + 37821, 2) - 128) shr 1) + ((interpolated(x, y, 1) - 128) shr 2)
        h = (h * 0.3).toInt() + 35
        return h.coerceIn(10, 60)
    }
}
