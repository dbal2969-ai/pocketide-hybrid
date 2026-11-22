package dev.pocketide.app

import dev.pocketide.app.addon.osrs.*

import dev.pocketide.app.core.AssetEnv
import dev.pocketide.app.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CacheLooksTest {
    private fun tmp(): File = java.nio.file.Files.createTempDirectory("cachelooks").toFile()

    private fun env(root: File): AssetEnv = AssetEnv(
        resolve = { p -> if (p.isEmpty()) root else File(root, p).takeIf { !p.contains("..") } },
        put = { p, b -> val f = File(root, p); f.parentFile.mkdirs(); f.writeBytes(b); true },
        read = { p -> File(root, p).takeIf { it.isFile }?.readBytes() },
        files = { d -> val base = if (d.isEmpty()) root else File(root, d); base.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path.replace('\\', '/') }.toList() })

    @Test fun heightsFollowTheClientRules() {
        val t = Terrain(50, 50)
        val a = t.at(0, 5, 5); t.hasHeight[a] = true; t.height[a] = 10
        val b = t.at(0, 6, 5); t.hasHeight[b] = true; t.height[b] = 1          // a stored 1 means no height
        val up = t.at(1, 5, 5); t.hasHeight[up] = true; t.height[up] = 3
        val h = t.heights()
        assertEquals(80, h[a]); assertEquals(0, h[b])
        assertEquals("a stored height is added to the floor below", 80 + 24, h[up])
        assertEquals("no stored height on a floor above: one storey up", h[t.at(0, 9, 9)] + 240, h[t.at(1, 9, 9)])
        for (x in 0 until 64 step 7) for (z in 0 until 64 step 5) { val n = h[t.at(0, x, z)]; assertTrue("noise $n", n in 80..480 && n % 8 == 0) }
        assertEquals(Noise.height(932731, 556238), Noise.height(932731, 556238))
        // neighbouring tiles differ little: the noise is smooth
        var worst = 0; for (x in 0 until 63) worst = maxOf(worst, Math.abs(h[t.at(0, x, 20)] - h[t.at(0, x + 1, 20)]))
        assertTrue("worst step $worst", worst <= 80)
    }

    private fun groundCache(root: File) {
        val t = Terrain(50, 50)
        for (x in 0 until 64) for (z in 0 until 64) {
            val i = t.at(0, x, z)
            t.underlay[i] = 1; t.hasHeight[i] = true
            t.height[i] = when { x < 20 -> 100; x < 44 -> 100 + 4 * (x - 20); else -> 196 - 4 * (x - 44) }
        }
        for (x in 40..42) for (z in 10..12) { t.overlay[t.at(0, x, z)] = 1; t.overlayShape[t.at(0, x, z)] = 0 }
        val under = byteArrayOf(1, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0)
        val over = byteArrayOf(1, 0xcc.toByte(), 0, 0, 0)
        Js5Writer(File(root, "cache")).use { w ->
            // map tiles store the definition id + 1 (0 = none): tile value 1 is definition 0
            w.addIndex(2, mapOf(CacheDefs.UNDERLAYS to Js5Writer.Group(mapOf(0 to under)), CacheDefs.OVERLAYS to Js5Writer.Group(mapOf(0 to over))))
            w.addIndex(5, mapOf(1 to Js5Writer.Group(mapOf(0 to t.encode()), Js5.nameHash("m50_50"))))
        }
    }

    @Test fun groundIsColouredAndLitByTheHills() {
        val root = tmp(); groundCache(root); val e = env(root)
        val r = CacheTool.run(mapOf("action" to "export", "dir" to "cache", "kind" to "ground", "ids" to "50,50", "scale" to 2, "out" to "o"), e)
        assertTrue(r, r.startsWith("Wrote 1 ground picture"))
        val img = Png.decode(File(root, "o/ground/g50_50.png").readBytes())
        assertEquals(128, img.width); assertEquals(128, img.height)
        fun px(tx: Int, tz: Int): Int = img.argb[(128 - 1 - tz * 2) * 128 + tx * 2]       // north is the top row
        fun red(c: Int) = (c shr 16) and 255
        val flat = red(px(10, 30)); val rising = red(px(30, 30)); val falling = red(px(52, 30))
        assertTrue("flat ground keeps its colour: $flat", flat in 124..132)
        assertTrue("ground facing the light is brighter: $rising vs $flat", rising > flat + 3)
        assertTrue("ground facing away is darker: $falling vs $flat", falling < flat - 3)
        val patch = px(41, 11)
        assertTrue("the overlay patch is red", red(patch) > 150 && ((patch shr 8) and 255) < 80)
        assertTrue(CacheTool.run(mapOf("action" to "export", "dir" to "cache", "kind" to "ground", "ids" to "9,9", "out" to "o"), e).contains("Not in this cache"))
        assertTrue(CacheTool.run(mapOf("action" to "export", "dir" to "cache", "kind" to "ground", "ids" to "", "out" to "o"), e).contains("needs ids"))
        val floors = CacheTool.run(mapOf("action" to "export", "dir" to "cache", "kind" to "floors", "out" to "o"), e)
        assertTrue(floors, floors.contains("1 underlay and 1 overlay"))
    }

    @Test fun mapsCarryEveryFloor() {
        val root = tmp(); groundCache(root); val e = env(root)
        CacheTool.run(mapOf("action" to "export", "dir" to "cache", "kind" to "maps", "ids" to "50,50", "out" to "o"), e)
        val m = CacheDefs.parseJson(File(root, "o/maps/m50_50.json").readText()) as Map<*, *>
        val heights = m["heights"] as List<*>
        assertEquals(4, heights.size); assertEquals(4096, (heights[0] as List<*>).size)
        assertEquals("floors above that are one storey up are left out", 0, (heights[1] as List<*>).size + (heights[2] as List<*>).size)
        assertEquals(4096, ((m["underlays"] as List<*>)[0] as List<*>).size); assertEquals(0, ((m["underlays"] as List<*>)[1] as List<*>).size)
        assertTrue((m["legend"] as String).contains("under a roof"))
    }

    @Test fun itemCameraFieldsSurvive() {
        val f = mapOf("name" to "Thing", "model" to 7, "zoom2d" to 800, "xan2d" to 300, "yan2d" to 1000, "zan2d" to 50, "xoff2d" to -3, "yoff2d" to 4)
        val d = CacheDefs.item(1, CacheDefs.encode("items", f))
        for ((k, v) in f) assertEquals(k, v, d.fields[k])
        assertEquals(7, CacheDefs.floorTexture(byteArrayOf(2, 7, 0))); assertEquals(-1, CacheDefs.floorTexture(byteArrayOf(2, -1, 0))); assertEquals(-1, CacheDefs.floorTexture(byteArrayOf(1, 1, 2, 3, 0)))
    }

    private fun tinyModel(): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        b.write(byteArrayOf(0, 1, 2)); b.write(1); b.write(byteArrayOf(64, 65, 65)); b.write(byteArrayOf(0x12, 0x34)); b.write(74); b.write(74)
        b.write(byteArrayOf(0, 3, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 0, 3, 0, 0, -1, -2))
        return b.toByteArray()
    }

    @Test fun iconsAndPlayerComeFromModels() {
        val root = tmp()
        fun kit(part: Int, vararg models: Int, recolor: Pair<Int, Int>? = null): ByteArray {
            val o = java.io.ByteArrayOutputStream(); o.write(1); o.write(part); o.write(2); o.write(models.size)
            for (m in models) { o.write(m shr 8); o.write(m) }
            if (recolor != null) { o.write(40); o.write(1); o.write(recolor.first shr 8); o.write(recolor.first); o.write(recolor.second shr 8); o.write(recolor.second) }
            o.write(0); return o.toByteArray()
        }
        Js5Writer(File(root, "cache")).use { w ->
            w.addIndex(2, mapOf(
                10 to Js5Writer.Group(mapOf(
                    1 to CacheDefs.encode("items", mapOf("name" to "Pyramid", "model" to 7, "zoom2d" to 900, "xan2d" to 100, "yan2d" to 0)),
                    2 to CacheDefs.encode("items", mapOf("name" to "Pyramid note", "noteTemplate" to 799, "model" to 7)))),
                IdKit.GROUP to Js5Writer.Group(mapOf(0 to kit(0, 7), 10 to kit(1, 7, recolor = 0x1234 to 0x2222), 18 to kit(2, 7)))))
            w.addIndex(7, mapOf(7 to Js5Writer.Group(mapOf(0 to tinyModel()))))
        }
        val e = env(root)
        val r = CacheTool.run(mapOf("action" to "export", "dir" to "cache", "kind" to "icons", "ids" to "1,2,3", "size" to 32, "out" to "o"), e)
        assertTrue(r, r.startsWith("Wrote 1 icons") && r.contains("2: is a bank note") && r.contains("3: does not exist"))
        val icon = Png.decode(File(root, "o/icons/1.png").readBytes())
        assertEquals(32, icon.width); assertTrue("icon has solid pixels", icon.argb.any { (it ushr 24) == 255 })
        val p = CacheTool.run(mapOf("action" to "export", "dir" to "cache", "kind" to "player", "ids" to "0,10,18", "out" to "o"), e)
        assertTrue(p, p.startsWith("Wrote o/models/player.json") && p.contains("3 models"))
        val mesh = Mesh.fromJson(File(root, "o/models/player.json").readText())
        assertEquals(9, mesh.vertexCount())
        val bad = CacheTool.run(mapOf("action" to "export", "dir" to "cache", "kind" to "player", "ids" to "5", "out" to "o"), e); assertTrue(bad, bad.contains("kit 5 does not exist"))
        assertEquals(listOf(0, 10, 18, 26, 33, 36, 42), IdKit.choose("male")); assertEquals(IdKit.FEMALE, IdKit.choose("Female")); assertEquals(listOf(3, 4, 5), IdKit.choose("3-5"))
        val k = IdKit.parse(10, kit(1, 7, 8, recolor = 5 to 6))
        assertEquals(1, k.part); assertEquals(listOf(7, 8), k.models); assertEquals(mapOf(5 to 6), k.recolor)
    }

    @Test fun rollTurnsTheModelInThePicture() {
        val mesh = Primitives.build(listOf(mapOf("type" to "box", "size" to listOf(4.0, 1.0, 1.0), "pos" to listOf(0, 0, 0))))
        val flat = ModelRender.trim(ModelRender.render(listOf(mesh), 64, 0.0, 0.0))
        val turned = ModelRender.trim(ModelRender.render(listOf(mesh), 64, 0.0, 0.0, 90.0))
        assertTrue("${flat.width}x${flat.height}", flat.width > flat.height)
        assertTrue("${turned.width}x${turned.height}", turned.height > turned.width)
        assertFalse(flat.width == turned.width && flat.height == turned.height)
    }
}
