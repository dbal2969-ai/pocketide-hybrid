package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

/**
 * Draws the ground of one 64x64 map square as a picture, lit by its hills: blended grass and dirt colours, paths and water on
 * top, and shading from the real tile heights. North is the top edge. The edges of paths and water are smoothed into diagonals
 * (an approximation of the client's tile shapes). Neighbouring squares, when the cache has them, keep colours and light
 * continuous across the border.
 */
object Ground {
    /** [terrain] gives the terrain of any square or null; the colour functions give 0xRRGGBB for a floor id or null when unknown. */
    class Source(val terrain: (Int, Int) -> Terrain?, val underColour: (Int) -> Int?, val overColour: (Int) -> Int?)

    private const val PAD = 4
    private const val W = 64 + 2 * PAD
    private const val FALLBACK = 0x3a3a3a

    private fun idx(wx: Int, wz: Int) = (wx + PAD) * W + (wz + PAD)

    fun render(x: Int, z: Int, scale: Int, src: Source): Png.Image {
        val s = scale.coerceIn(1, 8); val size = 64 * s
        val cache = HashMap<Long, Pair<Terrain, IntArray>?>()
        fun square(sx: Int, sz: Int) = cache.getOrPut(sx.toLong() * 100000 + sz) { src.terrain(sx, sz)?.let { it to it.heights() } }
        val own = square(x, z) ?: throw IllegalArgumentException("map square $x,$z has no terrain")

        val under = IntArray(W * W); val over = IntArray(W * W); val shape = IntArray(W * W); val h = IntArray(W * W)
        for (wx in -PAD until 64 + PAD) for (wz in -PAD until 64 + PAD) {
            val gx = Math.floorDiv(wx, 64); val gz = Math.floorDiv(wz, 64)
            val nb = if (gx == 0 && gz == 0) own else square(x + gx, z + gz)
            val t = (nb ?: own).first; val hs = (nb ?: own).second
            val lx = if (nb == null) wx.coerceIn(0, 63) else Math.floorMod(wx, 64)
            val lz = if (nb == null) wz.coerceIn(0, 63) else Math.floorMod(wz, 64)
            val i = idx(wx, wz); val k = t.at(0, lx, lz)
            under[i] = t.underlay[k]; over[i] = t.overlay[k]; shape[i] = t.overlayShape[k]; h[i] = hs[k]
        }

        // underlay colours blended over 5x5 tiles, so grass melts into dirt the way the client does
        val br = FloatArray(W * W); val bg = FloatArray(W * W); val bb = FloatArray(W * W)
        val fb = FALLBACK
        for (wx in -PAD + 2 until 64 + PAD - 2) for (wz in -PAD + 2 until 64 + PAD - 2) {
            var r = 0f; var g = 0f; var b = 0f; var n = 0
            for (dx in -2..2) for (dz in -2..2) {
                val u = under[idx(wx + dx, wz + dz)]; if (u == 0) continue
                val c = src.underColour(u) ?: continue
                r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++
            }
            val i = idx(wx, wz)
            if (n == 0) { br[i] = ((fb shr 16) and 255).toFloat(); bg[i] = ((fb shr 8) and 255).toFloat(); bb[i] = (fb and 255).toFloat() } else { br[i] = r / n; bg[i] = g / n; bb[i] = b / n }
        }

        // light: from the north-west and above; flat ground keeps its colour, slopes towards the light are brighter, away darker
        val lx = -0.5; val ly = 0.75; val lz = 0.5; val ll = Math.sqrt(lx * lx + ly * ly + lz * lz)
        val flat = 0.45 + 0.9 * (ly / ll)
        val shade = FloatArray(W * W)
        for (wx in -PAD + 1 until 64 + PAD - 1) for (wz in -PAD + 1 until 64 + PAD - 1) {
            val dx = (h[idx(wx + 1, wz)] - h[idx(wx - 1, wz)]) / 256.0
            val dz = (h[idx(wx, wz + 1)] - h[idx(wx, wz - 1)]) / 256.0
            val nl = Math.sqrt(dx * dx + 1 + dz * dz)
            val d = ((-dx) * lx + 1.0 * ly + (-dz) * lz) / (nl * ll)
            shade[idx(wx, wz)] = ((0.45 + 0.9 * Math.max(0.0, d)) / flat).coerceIn(0.5, 1.4).toFloat()
        }

        // how much of a tile the overlay covers: whole tiles 1, cut tiles (paths, shores) a half
        fun occupancy(i: Int) = if (over[i] == 0) 0f else if (shape[i] < 4) 1f else 0.5f

        val out = IntArray(size * size)
        for (py in 0 until size) for (px in 0 until size) {
            val u = (px + 0.5) / s; val v = (size - py - 0.5) / s    // tile units, v grows to the north
            // bilinear at tile centres
            val cu = u - 0.5; val cv = v - 0.5
            val ix = Math.floor(cu).toInt(); val iz = Math.floor(cv).toInt(); val fu = (cu - ix).toFloat(); val fv = (cv - iz).toFloat()
            fun mix(a: FloatArray): Float {
                val a00 = a[idx(ix, iz)]; val a10 = a[idx(ix + 1, iz)]; val a01 = a[idx(ix, iz + 1)]; val a11 = a[idx(ix + 1, iz + 1)]
                return (a00 * (1 - fu) + a10 * fu) * (1 - fv) + (a01 * (1 - fu) + a11 * fu) * fv
            }
            var r = mix(br); var g = mix(bg); var b = mix(bb)
            val occ = occupancy(idx(ix, iz)) * (1 - fu) * (1 - fv) + occupancy(idx(ix + 1, iz)) * fu * (1 - fv) + occupancy(idx(ix, iz + 1)) * (1 - fu) * fv + occupancy(idx(ix + 1, iz + 1)) * fu * fv
            if (occ >= 0.5f) {
                // the overlay of the nearest tile that has one
                val tx = Math.floor(u).toInt(); val tz = Math.floor(v).toInt()
                var best = -1; var bestD = Double.MAX_VALUE
                for (dx in -1..1) for (dz in -1..1) {
                    val j = idx(tx + dx, tz + dz); if (over[j] == 0) continue
                    val d = Math.hypot(tx + dx + 0.5 - u, tz + dz + 0.5 - v)
                    if (d < bestD) { bestD = d; best = j }
                }
                val c = if (best >= 0) src.overColour(over[best]) else null
                if (c != null) { r = ((c shr 16) and 255).toFloat(); g = ((c shr 8) and 255).toFloat(); b = (c and 255).toFloat() }
            }
            // shade, bilinear between tile corners
            val sx = Math.floor(u).toInt(); val sz = Math.floor(v).toInt(); val gu = (u - sx).toFloat(); val gv = (v - sz).toFloat()
            val sh = (shade[idx(sx, sz)] * (1 - gu) + shade[idx(sx + 1, sz)] * gu) * (1 - gv) + (shade[idx(sx, sz + 1)] * (1 - gu) + shade[idx(sx + 1, sz + 1)] * gu) * gv
            val rr = (r * sh).toInt().coerceIn(0, 255); val gg = (g * sh).toInt().coerceIn(0, 255); val bl = (b * sh).toInt().coerceIn(0, 255)
            out[py * size + px] = (255 shl 24) or (rr shl 16) or (gg shl 8) or bl
        }
        return Png.Image(size, size, out)
    }
}
