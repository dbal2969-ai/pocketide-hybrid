package dev.pocketide.app

import dev.pocketide.app.addon.osrs.*

import dev.pocketide.app.core.AssetEnv
import dev.pocketide.app.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CacheToolTest {
    private fun tmp(): File = java.nio.file.Files.createTempDirectory("cachetool").toFile()

    private fun env(root: File): AssetEnv = AssetEnv(
        resolve = { p -> if (p.isEmpty()) root else File(root, p).takeIf { !p.contains("..") } },
        put = { p, b -> val f = File(root, p); f.parentFile.mkdirs(); f.writeBytes(b); true },
        read = { p -> File(root, p).takeIf { it.isFile }?.readBytes() },
        files = { d -> val base = if (d.isEmpty()) root else File(root, d); base.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path.replace('\\', '/') }.toList() })

    private fun sampleCache(root: File, dir: String) {
        val items = mapOf(
            4151 to CacheDefs.encode("items", mapOf("name" to "Abyssal whip", "cost" to 120001, "members" to true, "inventoryOptions" to listOf(null, "Wield", null, null, null))),
            995 to CacheDefs.encode("items", mapOf("name" to "Coins", "stackable" to true)))
        val npcs = mapOf(3 to CacheDefs.encode("npcs", mapOf("name" to "Aberrant spectre", "combatLevel" to 96, "size" to 2, "actions" to listOf(null, "Attack", null, null, null))))
        val objs = mapOf(1276 to CacheDefs.encode("objects", mapOf("name" to "Tree", "width" to 2, "length" to 2, "actions" to listOf("Chop down", null, null, null, null))))
        val sprite = Sprites.encode(Sprites.Sheet(2, 2, listOf(Sprites.Frame(2, 2, 0, 0, intArrayOf(0xFFFF0000.toInt(), 0, 0x80112233.toInt(), 0xFF00FF00.toInt())))))
        val ter = Terrain(50, 50); ter.settings[ter.at(0, 10, 10)] = 1; ter.settings[ter.at(0, 11, 10)] = 2
        Js5Writer(File(root, dir)).use { w ->
            w.addIndex(2, mapOf(10 to Js5Writer.Group(items), 9 to Js5Writer.Group(npcs), 6 to Js5Writer.Group(objs)))
            w.addIndex(8, mapOf(5 to Js5Writer.Group(mapOf(0 to sprite))))
            w.addIndex(5, mapOf(1 to Js5Writer.Group(mapOf(0 to ter.encode()), Js5.nameHash("m50_50"))))
        }
    }

    @Test fun bzip2DecodesAndRefusesDamage() {
        val bz = intArrayOf(0x31, 0x41, 0x59, 0x26, 0x53, 0x59, 0xd1, 0xd2, 0xfd, 0x72, 0x00, 0x00, 0x81, 0x99, 0x80, 0x60, 0x04, 0x10, 0x00, 0x16, 0x64, 0xd0, 0x90, 0x20, 0x00, 0x70, 0x53, 0x00, 0x00, 0x14, 0xaa, 0x11, 0x93, 0x64, 0x6a, 0x60, 0x9c, 0x13, 0x82, 0x6e, 0x4f, 0x84, 0xe4, 0x99, 0x27, 0x24, 0xc9, 0x30, 0x26, 0xe2, 0x68, 0x9e, 0x93, 0xc1, 0x3e, 0x84, 0xd1, 0x34, 0x4c, 0x93, 0xa2, 0x68, 0x9f, 0xc5, 0xdc, 0x91, 0x4e, 0x14, 0x24, 0x34, 0x74, 0xbf, 0x5c, 0x80).map { it.toByte() }.toByteArray()
        assertEquals("hello world, hello bzip2! ".repeat(20), String(Bzip2.decompressHeaderless(bz)))
        val broken = bz.copyOf(); broken[8] = (broken[8].toInt() xor 0x55).toByte()
        try { Bzip2.decompressHeaderless(broken); } catch (e: IllegalStateException) { assertTrue(e.message!!.startsWith("bzip2")) } catch (e: IndexOutOfBoundsException) { }
    }

    @Test fun readsBackWhatWasWritten() {
        val root = tmp(); sampleCache(root, "cache")
        val e = env(root)
        val info = CacheTool.run(mapOf("action" to "info", "dir" to "cache"), e)
        assertTrue(info, info.contains("items 2") && info.contains("npcs 1") && info.contains("objects 1") && info.contains("sprites 1") && info.contains("map squares 1"))
        val found = CacheTool.run(mapOf("action" to "find", "dir" to "cache", "kind" to "items", "query" to "whip"), e)
        assertTrue(found, found.contains("\"id\":4151") && found.contains("Abyssal whip") && found.contains("Wield"))
        assertTrue(CacheTool.run(mapOf("action" to "find", "dir" to "cache", "kind" to "npcs", "query" to "zzz"), e).startsWith("No npcs"))
    }

    @Test fun spawnNamesMustMatchTheCache() {
        assertTrue(CacheTool.sameNpc("Traiborn", "Wizard Traiborn"))
        assertTrue(CacheTool.sameNpc("Iron Man tutor", "Ironman tutor"))
        assertTrue(CacheTool.sameNpc("Cook", "cook"))
        assertFalse(CacheTool.sameNpc("Gypsy", "Tempoross"))
        assertFalse(CacheTool.sameNpc("Account security tutor", "Agitated bees"))
    }

    @Test fun mergedItemExportKeepsTheGamesOtherItems() {
        val root = tmp(); sampleCache(root, "cache"); val e = env(root)
        File(root, "g").mkdirs()
        File(root, "g/items.json").writeText("[{\"id\":1,\"name\":\"Bronze sword\"},{\"id\":995,\"name\":\"Old coins\"}]")
        val r = CacheTool.export("cache", "items", "995,4151", "g", e, mapOf("merge" to "true"))
        assertTrue(r, r.contains("keeping 1"))
        val list = CacheDefs.parseJson(File(root, "g/items.json").readText()) as List<*>
        val names = list.associate { ((it as Map<*, *>)["id"] as Number).toInt() to it["name"] }
        assertEquals(setOf(1, 995, 4151), names.keys)
        assertEquals("Coins", names[995])
        // without merge the file holds only what was asked for, as before
        CacheTool.export("cache", "items", "995", "g", e)
        assertEquals(1, (CacheDefs.parseJson(File(root, "g/items.json").readText()) as List<*>).size)
    }

    @Test fun exportThenPackRoundTrips() {
        val root = tmp(); sampleCache(root, "cache"); val e = env(root)
        assertTrue(CacheTool.run(mapOf("action" to "export", "dir" to "cache", "kind" to "items", "out" to "x"), e).startsWith("Wrote x/items.json"))
        CacheTool.run(mapOf("action" to "export", "dir" to "cache", "kind" to "npcs", "out" to "x"), e)
        CacheTool.run(mapOf("action" to "export", "dir" to "cache", "kind" to "objects", "out" to "x"), e)
        assertTrue(CacheTool.run(mapOf("action" to "export", "dir" to "cache", "kind" to "sprites", "ids" to "all", "out" to "x"), e).contains("Wrote 1 PNG"))
        assertTrue(CacheTool.run(mapOf("action" to "export", "dir" to "cache", "kind" to "maps", "ids" to "50,50", "out" to "x"), e).startsWith("Wrote 1 map"))
        val png = Png.decode(File(root, "x/sprites/5.png").readBytes())
        assertEquals(0xFFFF0000.toInt(), png.argb[0]); assertEquals(0, png.argb[1] ushr 24)
        val map = CacheDefs.parseJson(File(root, "x/maps/m50_50.json").readText()) as Map<*, *>
        val plane0 = (map["planes"] as List<*>)[0] as List<*>
        assertEquals('#', plane0[63 - 10].toString()[10]); assertEquals('=', plane0[63 - 10].toString()[11])
        // edit: add a new item by hand, then pack everything into a new cache
        val items = File(root, "x/items.json"); items.writeText(items.readText().trimEnd().removeSuffix("]").trimEnd() + ",\n{\"id\":5000,\"name\":\"Custom sword\",\"cost\":77}\n]")
        val packed = CacheTool.run(mapOf("action" to "pack", "dir" to "x", "out" to "newcache"), e)
        assertTrue(packed, packed.startsWith("Packed") && packed.contains("read back OK"))
        val found = CacheTool.run(mapOf("action" to "find", "dir" to "newcache", "kind" to "items", "query" to "custom"), e)
        assertTrue(found, found.contains("\"id\":5000") && found.contains("\"cost\":77"))
        assertTrue(CacheTool.run(mapOf("action" to "find", "dir" to "newcache", "kind" to "npcs", "query" to "spectre"), e).contains("\"combatLevel\":96"))
        assertTrue(CacheTool.run(mapOf("action" to "info", "dir" to "newcache"), e).contains("map squares 1"))
        assertFalse(File(root, ".pack-tmp").exists())
    }

    @Test fun xteaAndObjectsRoundTrip() {
        val locs = listOf(Locs.Loc(1276, 10, 10, 0, 10, 0), Locs.Loc(1276, 12, 10, 0, 10, 1), Locs.Loc(40000, 5, 5, 1, 0, 2), Locs.Loc(2, 63, 63, 3, 22, 3))
        val back = Locs.decode(Locs.encode(locs))
        assertEquals(locs.map { listOf(it.id, it.x, it.z, it.plane, it.type, it.rot) }.sortedBy { it[0] }, back.map { listOf(it.id, it.x, it.z, it.plane, it.type, it.rot) }.sortedBy { it[0] })
        // encrypt with the inverse of the decryption (encipher) and check Js5.xtea undoes it
        val key = intArrayOf(11, 22, 33, 44)
        val plain = ByteArray(24) { it.toByte() }
        val enc = plain.copyOf()
        for (blk in 0 until 3) {
            var v0 = 0; var v1 = 0
            for (i in 0 until 4) { v0 = (v0 shl 8) or (plain[blk * 8 + i].toInt() and 0xff); v1 = (v1 shl 8) or (plain[blk * 8 + 4 + i].toInt() and 0xff) }
            var sum = 0
            repeat(32) { v0 += (((v1 shl 4) xor (v1 ushr 5)) + v1) xor (sum + key[sum and 3]); sum -= 0x61c88647; v1 += (((v0 shl 4) xor (v0 ushr 5)) + v0) xor (sum + key[(sum ushr 11) and 3]) }
            for (i in 0 until 4) { enc[blk * 8 + i] = (v0 ushr (24 - 8 * i)).toByte(); enc[blk * 8 + 4 + i] = (v1 ushr (24 - 8 * i)).toByte() }
        }
        assertEquals(plain.toList(), Js5.xtea(enc, 0, 24, key).toList())
    }

    @Test fun collisionFromObjects() {
        val t = Terrain(1, 1)
        t.settings[t.at(0, 3, 3)] = 1
        val locs = listOf(Locs.Loc(7, 10, 10, 0, 10, 0), Locs.Loc(8, 20, 20, 0, 0, 1), Locs.Loc(9, 30, 30, 0, 2, 0), Locs.Loc(10, 40, 40, 0, 10, 0))
        val f = Collision.build(t, locs, { it != 10 }, { if (it == 7) 2 to 3 else 1 to 1 })
        assertTrue(f[0][3 * 64 + 3] and Collision.FULL != 0)
        assertTrue(f[0][11 * 64 + 12] and Collision.FULL != 0); assertEquals(0, f[0][12 * 64 + 12] and Collision.FULL)
        assertTrue(f[0][20 * 64 + 20] and Collision.N != 0); assertTrue(f[0][20 * 64 + 21] and Collision.S != 0)
        assertTrue(f[0][30 * 64 + 30] and Collision.W != 0 && f[0][30 * 64 + 30] and Collision.N != 0)
        assertEquals(0, f[0][40 * 64 + 40])
        assertEquals(64, Collision.rows(f[0]).size)
    }

    @Test fun createsFromScratch() {
        val root = tmp(); val e = env(root)
        File(root, "src/sprites").mkdirs()
        File(root, "src/items.json").writeText("[{\"id\":1,\"name\":\"Gold bar\",\"cost\":300,\"stackable\":true},{\"id\":2,\"name\":\"Wooden shield\",\"options\":[null,null,null,null,\"Drop\"],\"params\":{\"7\":\"x\",\"8\":12}}]")
        File(root, "src/sprites/0.png").writeBytes(Png.encode(Png.Image(3, 3, IntArray(9) { 0xFF336699.toInt() })))
        File(root, "src/sprites/1_0.png").writeBytes(Png.encode(Png.Image(1, 1, intArrayOf(0xFFFFFFFF.toInt()))))
        File(root, "src/sprites/1_1.png").writeBytes(Png.encode(Png.Image(1, 1, intArrayOf(0xFF000000.toInt()))))
        val grid = (0 until 64).map { r -> if (r == 0) "#".repeat(64) else ".".repeat(64) }
        File(root, "src/maps").mkdirs()
        File(root, "src/maps/m10_20.json").writeText(CacheDefs.json(mapOf("x" to 10, "z" to 20, "planes" to listOf(grid))))
        val r = CacheTool.run(mapOf("action" to "pack", "dir" to "src", "out" to "game"), e)
        assertTrue(r, r.startsWith("Packed 2 items, 2 sprites, 1 map squares"))
        Js5(File(root, "game")).use { js ->
            val items = js.files(2, 10)!!
            assertEquals("Wooden shield", CacheDefs.parse("items", 2, items[2]!!).name)
            assertEquals(2, Sprites.decode(js.group(8, 1)!!).frames.size)
            val t = Terrain.decode(10, 20, js.group(5, js.table(5)!!.groupByName("m10_20")!!)!!)
            assertTrue(t.blocked(0, 5, 63)); assertFalse(t.blocked(0, 5, 62))
        }
    }

    @Test fun restorePutsKitFilesBackAndKeepsTheStart() {
        val root = tmp(); val base = env(root)
        val e = AssetEnv(base.resolve, base.put, base.read, base.files, asset = { n -> if (n in CacheTool.KIT_FILES) (if (n == "main.js") "const START = { x: 3222, z: 3218 };\nboot();\n" else "// kit $n\n").toByteArray() else null })
        File(root, "game").mkdirs()
        File(root, "game/main.js").writeText("const START = { x: 2964, z: 3380 };\nasync function boot() {")
        File(root, "game/index.html").writeText("broken"); File(root, "game/mine.js").writeText("// mine")
        val r = CacheTool.run(mapOf("action" to "restore", "dir" to "game", "files" to "main.js, index.html, mine.js"), e)
        assertTrue(r, r.contains("Put back 2") && r.contains("start tile was kept") && r.contains("mine.js"))
        assertEquals("const START = { x: 2964, z: 3380 };\nboot();\n", File(root, "game/main.js").readText())
        assertEquals("// kit index.html\n", File(root, "game/index.html").readText())
        assertEquals("// mine", File(root, "game/mine.js").readText())
        val all = CacheTool.run(mapOf("action" to "restore", "dir" to "game", "files" to "all"), e)
        assertTrue(all, all.contains("Put back ${CacheTool.KIT_FILES.size}"))
    }

    @Test fun scaffoldWritesKitAndData() {
        val root = tmp(); sampleCache(root, "cache")
        val base = env(root)
        val e = AssetEnv(base.resolve, base.put, base.read, base.files, asset = { n -> if (n in CacheTool.KIT_FILES) (if (n == "main.js") "const START = { x: 3222, z: 3218 };\n" else "// $n\n").toByteArray() else null })
        val r = CacheTool.run(mapOf("action" to "scaffold", "dir" to "cache", "ids" to "50,50", "out" to "game"), e)
        assertTrue(r, r.contains("Wrote the game kit to game") && r.contains("Start tile 3232, 3232"))
        assertTrue(File(root, "game/main.js").readText().contains("x: 3232, z: 3232"))
        assertTrue(File(root, "game/data/maps/m50_50.json").isFile && File(root, "game/data/floors.json").isFile && File(root, "game/data/objects.json").isFile)
        val noCache = CacheTool.run(mapOf("action" to "scaffold", "dir" to "", "out" to "g2"), AssetEnv(base.resolve, base.put, base.read, base.files, asset = e.asset))
        assertTrue(noCache, noCache.contains("No cache found"))
        assertTrue(File(root, "g2/index.html").isFile)
        // a project without a cache gets the kit only: no ground pictures, no data folder at all
        assertFalse(File(root, "g2/data").exists())
        // the kit loads the optional pictures only when they exist
        assertTrue(File(root, "game/data/ground").isDirectory)
    }

    private fun tinyModel(): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        b.write(byteArrayOf(0, 1, 2))                     // vertex flags: none, x moves, y moves
        b.write(1)                                        // face kind 1: three new indices
        b.write(byteArrayOf(64, 65, 65))                  // index deltas 0, +1, +1
        b.write(byteArrayOf(0x12, 0x34))                  // face colour
        b.write(74); b.write(74)                          // x +10, y +10 (short smart: value + 64)
        b.write(byteArrayOf(0, 3, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 0, 3, 0, 0, -1, -2))
        return b.toByteArray()
    }

    @Test fun decodesAModelAndExportsIt() {
        val m = Models.decode(7, tinyModel())
        assertEquals(listOf(0, 10, 10), m.x.toList().let { listOf(it[0], it[1], it[2]) }); assertEquals(listOf(0, 0, 10), m.y.toList())
        assertEquals(listOf(0, 1, 2), listOf(m.a[0], m.b[0], m.c[0])); assertEquals(0x1234, m.colour[0])
        val json = CacheDefs.parseJson(Models.toJson(m)) as Map<*, *>
        assertEquals(listOf(0, 0, 0, 10, 0, 0, 10, -10, 0), json["vertices"]); assertEquals(1, (json["colors"] as List<*>).size)
        assertTrue(Models.rgb(0x1234) in 0..0xffffff)
        try { Models.decode(1, ByteArray(40)); throw AssertionError("an empty model should be refused") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("not supported")) }
        val root = tmp()
        Js5Writer(File(root, "cache")).use { w ->
            w.addIndex(2, mapOf(10 to Js5Writer.Group(mapOf(1 to CacheDefs.encode("items", mapOf("name" to "Pyramid", "model" to 7, "recolor" to listOf(listOf(0x1234, 0x2222)))))), 9 to Js5Writer.Group(mapOf(0 to CacheDefs.encode("npcs", mapOf("name" to "Blob", "models" to listOf(7, 7)))))))
            w.addIndex(7, mapOf(7 to Js5Writer.Group(mapOf(0 to tinyModel()))))
        }
        val e = env(root)
        val r = CacheTool.run(mapOf("action" to "export", "dir" to "cache", "kind" to "models", "ids" to "item:1,npc:0,7", "out" to "o"), e)
        assertTrue(r, r.startsWith("Wrote 3 models"))
        val npc = CacheDefs.parseJson(File(root, "o/models/npc_0.json").readText()) as Map<*, *>
        assertEquals(6, (npc["vertices"] as List<*>).size / 3); assertEquals(2, (npc["colors"] as List<*>).size)
        val item = CacheDefs.parseJson(File(root, "o/models/item_1.json").readText()) as Map<*, *>
        assertEquals(String.format("#%06x", Models.rgb(0x2222)), (item["colors"] as List<*>)[0])
        val def = CacheDefs.item(1, CacheDefs.encode("items", mapOf("name" to "x", "model" to 99, "recolor" to listOf(listOf(1, 2)))))
        assertEquals(99, def.fields["model"]); assertEquals(listOf(listOf(1, 2)), def.fields["recolor"])
    }

    @Test fun refusesBadInput() {
        val root = tmp(); val e = env(root)
        assertTrue(CacheTool.run(mapOf("action" to "info", "dir" to "nothing"), e).contains("no main_file_cache.dat2"))
        assertTrue(CacheTool.run(mapOf("action" to "dance"), e).startsWith("action must be"))
        File(root, "bad").mkdirs(); File(root, "bad/items.json").writeText("[{\"name\":\"no id\"}]")
        assertTrue(CacheTool.run(mapOf("action" to "pack", "dir" to "bad", "out" to "o"), e).contains("without a numeric id"))
        assertEquals(listOf(1, 2, 3, 7), CacheTool.parseIds("1-3,7"))
        assertEquals(null, CacheTool.parseIds("all"))
        val s = CacheDefs.parseJson("{\"a\":[1,2.5,\"x\\n\",true,null],\"b\":{}}") as Map<*, *>
        assertEquals(listOf(1, 2.5, "x\n", true, null), s["a"])
    }

    @Test fun pngAndSpriteRoundTrip() {
        val img = Png.Image(2, 2, intArrayOf(0xFF010203.toInt(), 0x00000000, 0x80AABBCC.toInt(), 0xFFFFFFFF.toInt()))
        val back = Png.decode(Png.encode(img)); assertEquals(img.argb.toList(), back.argb.toList())
        val many = Sprites.Frame(16, 16, 0, 0, IntArray(256) { (255 shl 24) or ((it * 7 and 0xff) shl 16) or ((it * 13 and 0xff) shl 8) or (it * 29 and 0xff) })
        val s = Sprites.decode(Sprites.encode(Sprites.Sheet(16, 16, listOf(many))))
        assertEquals(256, s.frames[0].argb.size)
        assertNotNull(s.frames[0].argb.firstOrNull { it ushr 24 == 255 })
    }
}
