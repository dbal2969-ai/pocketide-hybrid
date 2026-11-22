package dev.pocketide.app

import dev.pocketide.app.addon.osrs.*

import dev.pocketide.app.core.AssetEnv
import dev.pocketide.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class Anim3DTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun sequencesWithSettingsBeforeTheFramesParse() {
        // 3: two interleave groups, 5: priority, 6: item in the left hand, then 1: two frames (lengths 4 and 6, ids 0x00010002 and 3)
        val s = Anim.seq(422, bytes(3, 2, 1, 2, 5, 10, 6, 0, 0, 1, 0, 2, 0, 4, 0, 6, 0, 2, 0, 3, 0, 1, 0, 0, 0))
        assertFalse(s.partial)
        assertEquals(listOf(4, 6), s.lengths.toList())
        assertEquals(listOf((1 shl 16) or 2, 3), s.frames.toList())
    }

    @Test fun skeletonsOfBothLayouts() {
        // Old School: count 2, types 0 1, label counts 1 2, labels 5 | 6 7
        val o = Anim.skeleton(1, bytes(2, 0, 1, 1, 2, 5, 6, 7), old = false)
        assertEquals(listOf(0, 1), o.types.toList()); assertEquals(listOf(6, 7), o.labels[1].toList())
        // 2009: the same with a shadow flag and a part number per transform between
        val n = Anim.skeleton(1, bytes(2, 0, 1, 0, 0, 0, 0, 0, 0, 1, 2, 5, 6, 7), old = true)
        assertEquals(o.labels.map { it.toList() }, n.labels.map { it.toList() })
    }

    @Test fun framesAddThePivotTheGameSetsAndMoveVertices() {
        val skel = Anim.Skeleton(7, intArrayOf(0, 1, 2), arrayOf(intArrayOf(1), intArrayOf(1), intArrayOf(1)))
        // skeleton 7, frame header: id 7, three transforms; transform 0 has no data, transform 1 moves x by 5 (smart byte 69 = 5 + 64)
        val f = Anim.frame(bytes(0, 7, 3, 0, 1, 0, 69)) { if (it == 7) skel else null }
        assertEquals(listOf(listOf(0, 0, 0, 0), listOf(1, 5, 0, 0)), f.moves.map { it.toList() })
        val x = intArrayOf(10, 0); val y = intArrayOf(0, 0); val z = intArrayOf(0, 0); val groups = intArrayOf(1, 2)
        Anim.apply(x, y, z, groups, skel, f)
        assertEquals(15, x[0]); assertEquals(0, x[1])   // only group 1 moves
        // a quarter turn about z (512 of 2048) around a pivot at the origin: (10, 0) goes to (0, -10) in the game's axes
        val x2 = intArrayOf(10); val y2 = intArrayOf(0); val z2 = intArrayOf(0)
        Anim.apply(x2, y2, z2, intArrayOf(1), Anim.Skeleton(1, intArrayOf(0, 2), arrayOf(intArrayOf(9), intArrayOf(1))), Anim.Frame(1, listOf(intArrayOf(0, 0, 0, 0), intArrayOf(1, 0, 0, 512))))
        assertEquals(0, x2[0]); assertEquals(-10, y2[0])
        assertEquals(1024, Anim.turn(128)); assertEquals(2047, Anim.turn(0x7ff))
    }

    @Test fun tooShortModelsAreRejectedNotCrashed() {
        // a real model is never under 30 bytes; the decoder must say so with a clear message
        val e = runCatching { Models.decode(5, ByteArray(12)) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException && e.message!!.contains("too short"))
    }

    @Test fun joinedModelsKeepTheirVertexGroups() {
        fun model(g: Int) = Models.Model(1, intArrayOf(0, 1, 2), intArrayOf(0, 0, 0), intArrayOf(0, 0, 1), intArrayOf(0), intArrayOf(1), intArrayOf(2), intArrayOf(5), intArrayOf(255), intArrayOf(-1), intArrayOf(g, g, g))
        val j = Models.merge(1, listOf(model(3), model(4)))
        assertEquals(listOf(3, 3, 3, 4, 4, 4), j.vertexGroup!!.toList())
        assertTrue(Models.toJson(j).contains("\"groups\":[3,3,3,4,4,4]"))
    }

    @Test fun aSquareBakesIntoChunksWithItsObjects() {
        val t = Terrain(50, 50)
        for (x in 0 until 64) for (z in 0 until 64) { t.underlay[t.at(0, x, z)] = 1; t.hasHeight[t.at(0, x, z)] = true; t.height[t.at(0, x, z)] = 1 }
        t.overlay[t.at(0, 5, 5)] = 1; t.overlayShape[t.at(0, 5, 5)] = 4   // shape 1 (a half tile)
        val sq = Scene3D.Square(50, 50, t, t.heights())
        val model = Models.Model(9, intArrayOf(0, 64, 0), intArrayOf(0, -100, 0), intArrayOf(0, 0, 64), intArrayOf(0), intArrayOf(1), intArrayOf(2), intArrayOf(0x1234), intArrayOf(255), intArrayOf(-1), null)
        val def = CacheDefs.Def(1276, linkedMapOf("name" to "Tree", "models" to listOf(9), "width" to 2, "length" to 2, "actions" to listOf("Chop down", null, null, null, null)), false)
        val gz = Scene3D.bake(sq, { _, _ -> null }, { 0x40a040 }, { 0x808080 }, listOf(Locs.Loc(1276, 10, 12, 0, 10, 0)), mapOf(1276 to def), { if (it == 9) model else null }, false) { null }
        val raw = java.util.zip.GZIPInputStream(gz.inputStream()).readBytes()
        val bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals("PS3D", String(raw, 0, 4)); bb.position(4)
        assertEquals(Scene3D.VERSION, bb.short.toInt()); assertEquals(50, bb.short.toInt()); assertEquals(50, bb.short.toInt())
        bb.position(bb.position() + 65 * 65 * 2)
        val chunks = bb.short.toInt()
        var groundTris = 0; var objectTris = 0
        repeat(chunks) {
            val section = bb.get().toInt(); bb.get(); val vc = bb.int; val ic = bb.int; val isz = bb.get().toInt()
            bb.position(bb.position() + vc * 9 + ic * isz)
            if (section == 0) groundTris += ic / 3 else objectTris += ic / 3
        }
        assertEquals(64 * 64 * 2, groundTris)   // every tile two triangles (the half overlay tile too)
        assertEquals(1, objectTris)
        assertEquals(1, bb.int)                 // the tree can be hidden when it is cut down
        assertEquals(1276, bb.int); assertEquals(10, bb.get().toInt()); assertEquals(12, bb.get().toInt())
    }

    // 1.5.174: each upper floor in its own section, so the view can draw the player's floor without the roof above it
    @Test fun eachFloorIsBakedInItsOwnSection() {
        val t = Terrain(50, 50)
        for (p in 0..2) for (x in 0 until 64) for (z in 0 until 64) { t.underlay[t.at(p, x, z)] = if (p == 0 || (x < 4 && z < 4)) 1 else 0; t.hasHeight[t.at(p, x, z)] = true }
        val sq = Scene3D.Square(50, 50, t, t.heights())
        val model = Models.Model(9, intArrayOf(0, 64, 0), intArrayOf(0, -100, 0), intArrayOf(0, 0, 64), intArrayOf(0), intArrayOf(1), intArrayOf(2), intArrayOf(0x1234), intArrayOf(255), intArrayOf(-1), null)
        val def = CacheDefs.Def(1747, linkedMapOf("name" to "Ladder", "models" to listOf(9), "actions" to listOf("Climb-up", null, null, null, null)), false)
        val gz = Scene3D.bake(sq, { _, _ -> null }, { 0x40a040 }, { 0x808080 }, listOf(Locs.Loc(1747, 2, 2, 1, 10, 0)), mapOf(1747 to def), { if (it == 9) model else null }, false) { null }
        val bb = java.nio.ByteBuffer.wrap(java.util.zip.GZIPInputStream(gz.inputStream()).readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.position(4 + 6 + 65 * 65 * 2)
        val tris = HashMap<Int, Int>()
        repeat(bb.short.toInt()) {
            val section = bb.get().toInt(); bb.get(); val vc = bb.int; val ic = bb.int; val isz = bb.get().toInt()
            bb.position(bb.position() + vc * 9 + ic * isz); tris.merge(section, ic / 3, Int::plus)
        }
        assertEquals(64 * 64 * 2, tris[0])
        assertEquals(16 * 2 + 1, tris[2])        // floor 1: its 16 tiles and the ladder on it
        assertEquals(16 * 2, tris[3])            // floor 2 apart from floor 1
        assertEquals(1, bb.int); bb.int; bb.get(); bb.get()
        assertEquals(1, bb.get().toInt()); assertEquals(2, bb.get().toInt())   // the ladder: floor 1, section 2
        assertEquals(listOf(0, 1, 2, 3, 4, 4), listOf(Scene3D.sectionOf(0, false), Scene3D.sectionOf(0, true), Scene3D.sectionOf(1, false), Scene3D.sectionOf(2, true), Scene3D.sectionOf(3, false), Scene3D.sectionOf(5, true)))
    }

    @Test fun combatAnimationsAreFoundByName() {
        val names = mapOf(58 to "cow_walk", 59 to "cow_attack", 60 to "cow_block", 61 to "cow_ready", 62 to "cow_death", 808 to "human_ready", 819 to "human_walk_f")
        assertEquals(mapOf("attack" to 59, "defend" to 60, "death" to 62), CacheTool.combatAnims(61, 58, names))
        assertEquals(mapOf("attack" to 422, "defend" to 424, "death" to 836), CacheTool.combatAnims(808, 819, names))
        assertEquals(mapOf("attack" to 422, "defend" to 424, "death" to 836), CacheTool.combatAnims(808, 819, null))
        assertTrue(CacheTool.combatAnims(5, 6, null).isEmpty())
    }

    @Test fun buildingBlocksAreListedAndCopiedWithWhatTheyNeed() {
        val root = java.nio.file.Files.createTempDirectory("blocks").toFile()
        val e = AssetEnv(resolve = { File(root, it) }, put = { p, b -> File(root, p).also { it.parentFile.mkdirs() }.writeBytes(b); true },
            read = { null }, files = { emptyList() }, asset = { n -> "// $n".toByteArray() })
        val list = GameAssets.run(mapOf("action" to "blocks"), e)
        assertTrue(list, list.contains("view3d.js") && list.contains("anim.js") && list.contains("pathfinder.js"))
        val r = GameAssets.run(mapOf("action" to "blocks", "files" to "view3d.js, nope.js", "out" to "mygame"), e)
        assertTrue(r, r.contains("Copied 3") && r.contains("nope.js"))
        assertTrue(File(root, "mygame/anim.js").isFile && File(root, "mygame/engine/three.module.min.js").isFile)
        assertEquals("// ../engine/three.module.min.js", File(root, "mygame/engine/three.module.min.js").readText())
    }
}
