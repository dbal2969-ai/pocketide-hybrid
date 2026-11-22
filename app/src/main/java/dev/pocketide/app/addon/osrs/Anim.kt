package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

import dev.pocketide.app.addon.osrs.Js5.Reader

/**
 * Animations of a game cache: sequences (which frames, how long each shows), frames (moves of vertex groups) and skeletons
 * (which vertex groups each move acts on, and whether it sets the pivot, moves, turns or scales them). Works for Old School
 * caches (sequences in config index 2 group 12) and 2009-era caches (sequences in index 20, 128 per group). Frames live in
 * index 0 (group = frame set, file = frame), skeletons in index 1. Written from the documented data layout.
 *
 * The exported form is plain JSON that any page can play with the kit's anim.js: a model with a vertex group per vertex,
 * a skeleton { types, labels } and a sequence { lengths, frames: [{ skeleton, moves: [[transform, x, y, z], ...] }] }.
 */
object Anim {
    const val FRAMES_INDEX = 0
    const val SKELETONS_INDEX = 1
    const val SEQ_GROUP = 12
    const val OLD_SEQ_INDEX = 20

    /** frames: frame set << 16 | frame; lengths in client ticks of 20 ms. */
    class Seq(val id: Int, val frames: IntArray, val lengths: IntArray, val loopFrom: Int, val partial: Boolean)

    class Skeleton(val id: Int, val types: IntArray, val labels: Array<IntArray>)

    /** moves: [transform index, x, y, z]; a turn's angles are already in 2048ths of a circle. */
    class Frame(val skeleton: Int, val moves: List<IntArray>)

    fun seq(id: Int, data: ByteArray): Seq {
        val r = Reader(data); var frames = IntArray(0); var lengths = IntArray(0); var loop = -1; var partial = false
        try {
            loop@ while (r.left() > 0) {
                when (r.u8()) {
                    0 -> break@loop
                    1 -> { val n = r.u16(); lengths = IntArray(n) { r.u16() }; frames = IntArray(n) { r.u16() }; for (i in 0 until n) frames[i] += r.u16() shl 16 }
                    2 -> loop = r.u16()
                    3 -> r.skip(r.u8())                       // which skeleton groups this one plays over another
                    4 -> {}
                    5, 8, 9, 10, 11 -> r.u8()                 // priorities, loop counts, how it mixes with walking
                    6, 7 -> r.u16()                           // items shown in the hands
                    12 -> { val n = r.u8(); r.skip(n * 4) }   // a second frame list for the chat head
                    else -> { if (frames.isEmpty()) partial = true; break@loop }   // sounds and newer data: not needed to play it
                }
            }
        } catch (e: Exception) { partial = true }
        return Seq(id, frames, lengths, loop, partial)
    }

    /** Sequence bytes by id, from either cache layout. */
    fun seqData(js: Js5, id: Int, old: Boolean): ByteArray? = try {
        if (old) js.files(OLD_SEQ_INDEX, id ushr 7)?.get(id and 127) else js.files(CacheDefs.CONFIG_INDEX, SEQ_GROUP)?.get(id)
    } catch (e: Exception) { null }

    /** Old School skeletons: count, types, label counts, labels. 2009-era ones add a shadow flag and a part number per transform. */
    fun skeleton(id: Int, data: ByteArray, old: Boolean): Skeleton {
        val r = Reader(data)
        val n = r.u8()
        val types = IntArray(n) { r.u8() }
        if (old) { r.skip(n); r.skip(n * 2) }
        val counts = IntArray(n) { r.u8() }
        val labels = Array(n) { i -> IntArray(counts[i]) { r.u8() } }
        return Skeleton(id, types, labels)
    }

    private fun Reader.signedSmart(): Int = if ((b[p].toInt() and 0xff) < 128) u8() - 64 else u16() - 49152

    /** One frame; [skel] gives the skeleton named in the frame's first two bytes. Pivots the game sets implicitly are added. */
    fun frame(data: ByteArray, skel: (Int) -> Skeleton?): Frame {
        val head = Reader(data)
        val sid = head.u16()
        val s = skel(sid) ?: throw IllegalArgumentException("skeleton $sid is missing")
        val n = head.u8()
        val body = Reader(data, 3 + n)
        val moves = ArrayList<IntArray>()
        var lastOrigin = -1; var usedOrigin = -1
        for (i in 0 until n) {
            val type = s.types.getOrElse(i) { 0 }
            if (type == 0) lastOrigin = i
            val attr = head.u8()
            if (attr == 0) continue
            if (type == 0) usedOrigin = i
            val def = if (type == 3) 128 else 0
            var x = if (attr and 1 != 0) body.signedSmart() else def
            var y = if (attr and 2 != 0) body.signedSmart() else def
            var z = if (attr and 4 != 0) body.signedSmart() else def
            if (type == 2) { x = turn(x); y = turn(y); z = turn(z) }
            // a move, turn or scale uses the pivot of the last pivot transform before it, even when that one had no data
            if (type in 1..3 && lastOrigin > usedOrigin) { moves.add(intArrayOf(lastOrigin, 0, 0, 0)); usedOrigin = lastOrigin }
            moves.add(intArrayOf(i, x, y, z))
        }
        return Frame(sid, moves)
    }

    /** A stored angle to 2048ths of a circle (the low byte is the coarse angle, the high bits add precision). */
    fun turn(v: Int): Int = (((v and 0xff) shl 3) + ((v shr 8) and 7)) and 2047

    /** Loads a sequence with every frame and skeleton it needs. Null when it is not in the cache. */
    class Loaded(val seq: Seq, val frames: List<Frame?>, val skeletons: Map<Int, Skeleton>)

    fun load(js: Js5, id: Int, old: Boolean, skelCache: MutableMap<Int, Skeleton?> = HashMap(), setCache: MutableMap<Int, Map<Int, ByteArray>?> = HashMap()): Loaded? {
        val s = seq(id, seqData(js, id, old) ?: return null)
        if (s.frames.isEmpty()) return null
        val skels = LinkedHashMap<Int, Skeleton>()
        fun skeletonOf(sid: Int): Skeleton? = skelCache.getOrPut(sid) {
            try { js.files(SKELETONS_INDEX, sid)?.values?.firstOrNull()?.let { skeleton(sid, it, old) } ?: js.group(SKELETONS_INDEX, sid)?.let { skeleton(sid, it, old) } } catch (e: Exception) { null }
        }?.also { skels[sid] = it }
        val frames = s.frames.map { f ->
            val set = setCache.getOrPut(f ushr 16) { try { js.files(FRAMES_INDEX, f ushr 16) } catch (e: Exception) { null } }
            val bytes = set?.get(f and 0xffff) ?: return@map null
            try { frame(bytes) { skeletonOf(it) } } catch (e: Exception) { null }
        }
        return Loaded(s, frames, skels)
    }

    /** The JSON a page plays: { id, lengths, loopFrom, skeletons: { id: { types, labels } }, frames: [{ skeleton, moves }] } (missing frames are null). */
    fun toJson(l: Loaded): String = CacheDefs.json(linkedMapOf(
        "id" to l.seq.id, "lengths" to l.seq.lengths.toList(), "loopFrom" to l.seq.loopFrom,
        "skeletons" to l.skeletons.entries.associate { (k, v) -> k.toString() to linkedMapOf("types" to v.types.toList(), "labels" to v.labels.map { it.toList() }) },
        "frames" to l.frames.map { f -> f?.let { linkedMapOf("skeleton" to it.skeleton, "moves" to it.moves.map { m -> m.toList() }) } }))

    /**
     * Moves the model's vertices to one frame, in the cache's own axes (y down). [groups]: the vertex group of each vertex.
     * Same rules as the game: 0 sets the pivot to the middle of its groups plus an offset, 1 moves, 2 turns about the pivot
     * (z first, then x, then y), 3 scales about the pivot (128 = unchanged).
     */
    fun apply(x: IntArray, y: IntArray, z: IntArray, groups: IntArray, skel: Skeleton, frame: Frame) {
        val byGroup = HashMap<Int, MutableList<Int>>()
        for (v in groups.indices) byGroup.getOrPut(groups[v]) { ArrayList() }.add(v)
        var ox = 0; var oy = 0; var oz = 0
        for (m in frame.moves) {
            val t = m[0]; val type = skel.types.getOrElse(t) { -1 }; val labels = skel.labels.getOrElse(t) { IntArray(0) }
            val verts = labels.flatMap { byGroup[it] ?: emptyList() }
            when (type) {
                0 -> { if (verts.isEmpty()) { ox = m[1]; oy = m[2]; oz = m[3] } else { ox = verts.sumOf { x[it] } / verts.size + m[1]; oy = verts.sumOf { y[it] } / verts.size + m[2]; oz = verts.sumOf { z[it] } / verts.size + m[3] } }
                1 -> for (v in verts) { x[v] += m[1]; y[v] += m[2]; z[v] += m[3] }
                2 -> for (v in verts) {
                    var px = (x[v] - ox).toDouble(); var py = (y[v] - oy).toDouble(); var pz = (z[v] - oz).toDouble()
                    if (m[3] != 0) { val a = m[3] * Math.PI / 1024; val s = Math.sin(a); val c = Math.cos(a); val nx = py * s + px * c; py = py * c - px * s; px = nx }
                    if (m[1] != 0) { val a = m[1] * Math.PI / 1024; val s = Math.sin(a); val c = Math.cos(a); val ny = py * c - pz * s; pz = py * s + pz * c; py = ny }
                    if (m[2] != 0) { val a = m[2] * Math.PI / 1024; val s = Math.sin(a); val c = Math.cos(a); val nx = pz * s + px * c; pz = pz * c - px * s; px = nx }
                    x[v] = Math.round(px).toInt() + ox; y[v] = Math.round(py).toInt() + oy; z[v] = Math.round(pz).toInt() + oz
                }
                3 -> for (v in verts) { x[v] = (x[v] - ox) * m[1] / 128 + ox; y[v] = (y[v] - oy) * m[2] / 128 + oy; z[v] = (z[v] - oz) * m[3] / 128 + oz }
            }
        }
    }
}
