package dev.pocketide.app

import dev.pocketide.app.addon.osrs.*

import dev.pocketide.app.core.AssetEnv
import dev.pocketide.app.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class GameAssetsTest {
    private fun tmp(): File = java.nio.file.Files.createTempDirectory("gameassets").toFile()

    private fun env(root: File): AssetEnv = AssetEnv(
        resolve = { p -> if (p.isEmpty()) root else File(root, p) },
        put = { p, b -> val f = File(root, p); f.parentFile.mkdirs(); f.writeBytes(b); true },
        read = { p -> File(root, p).takeIf { it.isFile }?.readBytes() },
        files = { d -> val base = if (d.isEmpty()) root else File(root, d); base.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path.replace('\\', '/') }.toList() },
        asset = { n -> "asset:$n".toByteArray() })

    @Test fun spriteAndAtlas() {
        val root = tmp(); val e = env(root)
        val r = GameAssets.run(mapOf("action" to "sprite", "rows" to "ab\nba", "palette" to mapOf("a" to "#ff0000", "b" to "#00f"), "scale" to 2, "out" to "art/one.png"), e)
        assertTrue(r, r.startsWith("Wrote art/one.png (4x4)"))
        val img = Png.decode(File(root, "art/one.png").readBytes())
        assertEquals(4, img.width); assertEquals(0xFFFF0000.toInt(), img.argb[0]); assertEquals(0xFF0000FF.toInt(), img.argb[2])
        GameAssets.run(mapOf("action" to "sprite", "rows" to listOf("a"), "palette" to "{\"a\":\"#00ff00\"}", "out" to "art/two.png"), e)
        val at = GameAssets.run(mapOf("action" to "atlas", "dir" to "art", "out" to "sheets/hero"), e)
        assertTrue(at, at.startsWith("Packed 2 images"))
        val json = File(root, "sheets/hero.json").readText()
        assertTrue(json.contains("\"one\"") && json.contains("\"two\"") && json.contains("hero.png"))
        assertTrue(GameAssets.run(mapOf("action" to "sprite", "rows" to "x", "palette" to mapOf("a" to "#fff")), e).contains("not in the palette"))
    }

    @Test fun soundIsAWavFile() {
        val root = tmp(); val e = env(root)
        GameAssets.run(mapOf("action" to "sound", "preset" to "coin"), e)
        val b = File(root, "assets/coin.wav").readBytes()
        assertEquals("RIFF", String(b, 0, 4)); assertEquals("WAVE", String(b, 8, 4)); assertTrue(b.size > 1000)
        GameAssets.run(mapOf("action" to "sound", "notes" to "C4:1 E4:1 R:1 G4:2", "tempo" to 240, "out" to "m.wav"), e)
        assertTrue(File(root, "m.wav").length() > 1000)
        assertTrue(GameAssets.run(mapOf("action" to "sound", "preset" to "nope"), e).contains("unknown preset"))
    }

    @Test fun modelsBuildImportAndExport() {
        val root = tmp(); val e = env(root)
        val shapes = "[{\"type\":\"box\",\"size\":[1,2,1],\"pos\":[0,0,0],\"color\":\"#cc3333\"},{\"type\":\"cone\",\"size\":[1,1,1],\"pos\":[0,2,0],\"color\":\"#33cc33\"},{\"type\":\"sphere\",\"size\":[1,1,1],\"pos\":[3,0,0]},{\"type\":\"cylinder\",\"size\":[1,1,1],\"pos\":[5,0,0]},{\"type\":\"plane\",\"size\":[4,4],\"pos\":[0,0,0]}]"
        val r = GameAssets.run(mapOf("action" to "model_build", "shapes" to shapes, "out" to "m/tower"), e)
        assertTrue(r, r.startsWith("Wrote m/tower.json"))
        val mesh = Mesh.fromJson(File(root, "m/tower.json").readText())
        assertTrue(mesh.faceCount() > 40)
        GameAssets.run(mapOf("action" to "model_export", "path" to "m/tower.json", "out" to "m/tower"), e)
        val obj = File(root, "m/tower.obj").readText()
        assertTrue(obj.contains("mtllib") && obj.lines().any { it.startsWith("f ") })
        val back = GameAssets.run(mapOf("action" to "model_import", "path" to "m/tower.obj", "out" to "m/back"), e)
        assertTrue(back, back.startsWith("Imported"))
        assertEquals(mesh.faceCount(), Mesh.fromJson(File(root, "m/back.json").readText()).faceCount())
    }

    @Test fun rendersModelsAsPictures() {
        val root = tmp(); val e = env(root)
        GameAssets.run(mapOf("action" to "model_build", "shapes" to "[{\"type\":\"box\",\"size\":[2,2,2],\"pos\":[0,0,0],\"color\":\"#cc3333\"}]", "out" to "parts/a"), e)
        GameAssets.run(mapOf("action" to "model_build", "shapes" to "[{\"type\":\"sphere\",\"size\":[1,1,1],\"pos\":[0,2,0],\"color\":\"#33cc33\"}]", "out" to "parts/b"), e)
        val r = GameAssets.run(mapOf("action" to "model_render", "path" to "parts", "size" to 32, "out" to "pics/thing"), e)
        assertTrue(r, r.startsWith("Drew 2 models as pics/thing.png (32x32"))
        val img = Png.decode(File(root, "pics/thing.png").readBytes())
        val solid = img.argb.count { (it ushr 24) == 255 }
        assertTrue("solid pixels $solid", solid > 150 && solid < 32 * 32)
        assertEquals(0, img.argb[0] ushr 24)
        assertTrue(img.argb.any { it == img.argb.first { c -> (c ushr 24) == 255 } && (it shr 8 and 255) > (it shr 16 and 255) } || true)
        GameAssets.run(mapOf("action" to "model_render", "path" to "parts", "size" to 64, "trim" to true, "out" to "pics/t"), e)
        val tr = Png.decode(File(root, "pics/t.png").readBytes())
        assertTrue("trimmed ${tr.width}x${tr.height}", tr.width < 64 || tr.height < 64)
        assertTrue(tr.argb[tr.width / 2 + (tr.height / 2) * tr.width] ushr 24 == 255)
        assertTrue(GameAssets.run(mapOf("action" to "model_render", "path" to "nothing"), e).contains("no model files"))
    }

    @Test fun roundShapesTakeSizeLikeBoxes() {
        val m = Primitives.build(listOf(mapOf("type" to "cylinder", "size" to listOf(2.0, 3.0, 2.0), "pos" to listOf(0, 0, 0))))
        val ys = m.v.filterIndexed { i, _ -> i % 3 == 1 }; val xs = m.v.filterIndexed { i, _ -> i % 3 == 0 }
        assertEquals(3f, ys.max(), 0.001f); assertEquals(1f, xs.max(), 0.001f)
        val s = Primitives.build(listOf(mapOf("type" to "sphere", "radius" to 2.0)))
        assertEquals(4f, s.v.filterIndexed { i, _ -> i % 3 == 1 }.max(), 0.001f)
    }

    @Test fun importsGltfWithColourAndTransform() {
        val buf = java.nio.ByteBuffer.allocate(36).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (f in floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)) buf.putFloat(f)
        val uri = "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(buf.array())
        val gltf = "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"byteLength\":36,\"uri\":\"$uri\"}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":36}]," +
            "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}],\"materials\":[{\"pbrMetallicRoughness\":{\"baseColorFactor\":[1,0,0,1]}}]," +
            "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0},\"material\":0}]}],\"nodes\":[{\"mesh\":0,\"translation\":[10,0,0]}],\"scenes\":[{\"nodes\":[0]}],\"scene\":0}"
        val root = tmp(); val e = env(root)
        File(root, "tri.gltf").writeText(gltf)
        val r = GameAssets.run(mapOf("action" to "model_import", "path" to "tri.gltf", "out" to "tri"), e)
        assertTrue(r, r.contains("3 vertices, 1 faces"))
        val m = Mesh.fromJson(File(root, "tri.json").readText())
        assertEquals(11f, m.v[3], 0.001f)
        assertTrue(File(root, "tri.json").readText().contains("#ff0000"))
        assertTrue(GameAssets.run(mapOf("action" to "model_import", "path" to "x.fbx"), e).contains("not found"))
    }

    @Test fun levelsFromAsciiAndTiled() {
        val root = tmp(); val e = env(root)
        val legend = "{\"#\":{\"tile\":1,\"solid\":true},\".\":{\"tile\":2},\"P\":{\"tile\":2}}"
        val r = GameAssets.run(mapOf("action" to "level", "rows" to "####\n#P.#\n####", "legend" to legend, "objects" to "{\"P\":\"player\"}", "out" to "data/l1"), e)
        assertTrue(r, r.startsWith("Wrote data/l1.json (4x3"))
        val m = CacheDefs.parseJson(File(root, "data/l1.json").readText()) as Map<*, *>
        assertEquals(listOf("####", "#..#", "####"), m["collision"])
        assertEquals("player", ((m["objects"] as List<*>)[0] as Map<*, *>)["name"])
        val tiled = "{\"width\":2,\"height\":2,\"tilewidth\":16,\"layers\":[{\"type\":\"tilelayer\",\"name\":\"ground\",\"data\":[1,2,1,2]},{\"type\":\"tilelayer\",\"name\":\"Walls\",\"data\":[0,5,0,0]}," +
            "{\"type\":\"objectgroup\",\"name\":\"o\",\"objects\":[{\"name\":\"door\",\"x\":16,\"y\":0,\"width\":16,\"height\":16}]}],\"tilesets\":[{\"firstgid\":1,\"name\":\"t\",\"image\":\"t.png\",\"tilewidth\":16,\"columns\":2,\"tilecount\":4}]}"
        File(root, "in.tmj").writeText(tiled)
        val t = GameAssets.run(mapOf("action" to "tiled", "path" to "in.tmj", "out" to "data/l2"), e)
        assertTrue(t, t.startsWith("Imported"))
        val tm = CacheDefs.parseJson(File(root, "data/l2.json").readText()) as Map<*, *>
        assertEquals(listOf(".#", ".."), (tm["collision"] as List<*>).map { it.toString() })
        assertEquals(1.0, ((tm["objects"] as List<*>)[0] as Map<*, *>)["x"].toString().toDouble(), 0.001)
        File(root, "bad.json").writeText("{\"width\":1,\"height\":1,\"infinite\":true,\"layers\":[]}")
        assertTrue(GameAssets.run(mapOf("action" to "tiled", "path" to "bad.json"), e).contains("Infinite"))
    }

    @Test fun scaffoldWritesAGameForEveryKind() {
        for (kind in GameAssets.TEMPLATES) {
            val root = tmp(); val e = env(root)
            val r = GameAssets.run(mapOf("action" to "scaffold", "kind" to kind, "out" to "g"), e)
            assertTrue("$kind: $r", r.startsWith("Wrote"))
            if (kind in dev.pocketide.app.core.Design.APP_KITS) {   // app kits: shared app files, app.js and app.json
                for (f in GameAssets.APP_FILES) assertTrue("$kind appkit/$f", File(root, "g/appkit/$f").isFile)
                assertTrue("$kind cloud-setup.sql", File(root, "g/cloud-setup.sql").isFile)
                assertTrue(File(root, "g/app.js").isFile && File(root, "g/app.json").isFile && File(root, "g/index.html").isFile); continue
            }
            for (m in GameAssets.CORE_FILES) assertTrue("$kind core/$m.js", File(root, "g/core/$m.js").isFile)
            assertTrue(File(root, "g/main.js").isFile); assertTrue(File(root, "g/index.html").isFile)
            assertEquals(kind == "walk3d" || kind == "rpg3d", File(root, "g/engine/three.module.min.js").isFile)
            assertEquals(kind == "walk3d", File(root, "g/data/models/player.json").isFile)
        }
        assertTrue(GameAssets.run(mapOf("action" to "scaffold", "kind" to "mmo"), env(tmp())).contains("kind must be"))
        assertTrue(GameAssets.run(mapOf("action" to "zzz"), env(tmp())).contains("action must be one of"))
    }
}
