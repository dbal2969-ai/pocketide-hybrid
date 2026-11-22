package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

import dev.pocketide.app.addon.osrs.Js5.Reader
import java.io.ByteArrayOutputStream

/**
 * Objects placed on a map square (cache group "l<x>_<z>" in index 5): trees, walls, doors, buildings. The group is encrypted
 * with a per-square key; OpenRS2 publishes those keys for each cache (keys.json).
 */
object Locs {
    class Loc(val id: Int, val x: Int, val z: Int, val plane: Int, val type: Int, val rot: Int)

    fun name(x: Int, z: Int) = "l${x}_$z"

    fun decode(d: ByteArray): List<Loc> {
        val r = Reader(d); val out = ArrayList<Loc>()
        var id = -1
        while (true) {
            var step = r.smart(); if (step == 0) break
            var more = 0
            while (step == 32767) { step = r.smart(); more += 32767 }
            id += step + more
            var pos = 0
            while (true) {
                val off = r.smart(); if (off == 0) break
                pos += off - 1
                val attr = r.u8()
                out.add(Loc(id, (pos shr 6) and 63, pos and 63, (pos shr 12) and 3, attr shr 2, attr and 3))
            }
        }
        return out
    }

    fun encode(locs: List<Loc>): ByteArray {
        val o = ByteArrayOutputStream()
        fun smart(v: Int) { if (v < 128) o.write(v) else { val x = v + 32768; o.write(x shr 8); o.write(x) } }
        var id = -1
        for ((lid, group) in locs.groupBy { it.id }.toSortedMap()) {
            var delta = lid - id
            while (delta >= 32767) { smart(32767); delta -= 32767 }
            smart(delta); id = lid
            var pos = 0
            for (l in group.sortedBy { (it.plane shl 12) or (it.x shl 6) or it.z }) {
                val p = (l.plane shl 12) or (l.x shl 6) or l.z
                smart(p - pos + 1); pos = p
                o.write((l.type shl 2) or l.rot)
            }
            smart(0)
        }
        smart(0)
        return o.toByteArray()
    }
}
