package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

/**
 * Walking collision for one map square, from its terrain flags and placed objects. Each tile gets a value of wall bits
 * N=1 E=2 S=4 W=8 (a wall on that edge of the tile) and FULL=16 (the tile cannot be entered). Bridges are handled by moving
 * the upper level's data down one level, as the game does. Diagonal wall pieces are not modelled.
 */
object Collision {
    const val N = 1; const val E = 2; const val S = 4; const val W = 8; const val FULL = 16
    const val CHARS = "0123456789abcdefghijklmnopqrstuv"

    /** [solid] says whether an object id blocks walking (an unknown id blocks); [size] gives its width and length. */
    fun build(t: Terrain, locs: List<Locs.Loc>, solid: (Int) -> Boolean, size: (Int) -> Pair<Int, Int>): Array<IntArray> {
        val flags = Array(4) { IntArray(4096) }
        fun bridge(x: Int, z: Int) = t.settings[t.at(1, x, z)] and 2 != 0
        for (p in 0 until 4) for (x in 0 until 64) for (z in 0 until 64) {
            if (t.settings[t.at(p, x, z)] and 1 != 0) {
                val target = if (p == 1 && bridge(x, z)) 0 else if (p >= 1 && bridge(x, z) && p == 1) 0 else p
                if (p == 0 && bridge(x, z)) continue            // the ground under a bridge is not the walkway
                if (p == 1 && bridge(x, z)) flags[0][x * 64 + z] = flags[0][x * 64 + z] or FULL
                else flags[target][x * 64 + z] = flags[target][x * 64 + z] or FULL
            }
        }
        fun add(p: Int, x: Int, z: Int, bits: Int) { if (x in 0..63 && z in 0..63) flags[p][x * 64 + z] = flags[p][x * 64 + z] or bits }
        for (l in locs) {
            var p = l.plane
            if (p == 0 && bridge(l.x, l.z)) continue
            if (p == 1 && bridge(l.x, l.z)) p = 0
            if (!solid(l.id)) continue
            when (l.type) {
                0 -> when (l.rot) { 0 -> add(p, l.x, l.z, W); 1 -> add(p, l.x, l.z, N); 2 -> add(p, l.x, l.z, E); else -> add(p, l.x, l.z, S) }
                2 -> when (l.rot) { 0 -> add(p, l.x, l.z, W or N); 1 -> add(p, l.x, l.z, N or E); 2 -> add(p, l.x, l.z, E or S); else -> add(p, l.x, l.z, S or W) }
                9, 10, 11, in 12..21 -> {
                    val (w0, h0) = size(l.id)
                    val swap = l.rot == 1 || l.rot == 3
                    val w = if (swap) h0 else w0; val h = if (swap) w0 else h0
                    for (dx in 0 until w) for (dz in 0 until h) add(p, l.x + dx, l.z + dz, FULL)
                }
            }
        }
        // walls are stored on both sides of the edge so a mover only has to look at the tiles it touches
        for (p in 0 until 4) for (x in 0 until 64) for (z in 0 until 64) {
            val f = flags[p][x * 64 + z]
            if (f and N != 0 && z < 63) flags[p][x * 64 + z + 1] = flags[p][x * 64 + z + 1] or S
            if (f and E != 0 && x < 63) flags[p][(x + 1) * 64 + z] = flags[p][(x + 1) * 64 + z] or W
            if (f and S != 0 && z > 0) flags[p][x * 64 + z - 1] = flags[p][x * 64 + z - 1] or N
            if (f and W != 0 && x > 0) flags[p][(x - 1) * 64 + z] = flags[p][(x - 1) * 64 + z] or E
        }
        return flags
    }

    /** One plane as 64 rows (north edge first) of 64 characters from [CHARS]. */
    fun rows(flags: IntArray): List<String> = (63 downTo 0).map { z -> String(CharArray(64) { x -> CHARS[flags[x * 64 + z] and 31] }) }
}
