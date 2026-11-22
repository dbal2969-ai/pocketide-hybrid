package dev.pocketide.app

import dev.pocketide.app.addon.osrs.*

import dev.pocketide.app.core.AssetEnv
import dev.pocketide.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ServerDataTest {
    private val C = "2009scape/Server/data/configs"
    private val files = linkedMapOf(
        "$C/npc_configs.json" to """[{"id":"100","name":"Goblin","attack_level":"1","strength_level":"1","defence_level":"1","lifepoints":"5","attack_speed":"4","examine":"An ugly green creature."},
            {"id":"0","name":"Hans","examine":"Servant."},{"id":"520","name":"Shopkeeper"}]""",
        "$C/npc_spawns.json" to """[{"npc_id":"100","loc_data":"{3240,3250,0,1,3}-{3243,3248,0,1,0}-{2000,2000,0,1,0}-"},{"npc_id":"0","loc_data":"{3221,3218,0,1,0}-"},{"npc_id":"520","loc_data":"{3212,3247,0,0,0}-"}]""",
        "$C/item_configs.json" to """[{"id":"526","name":"Bones","weight":"0.5"},{"id":"1351","name":"Bronze axe","equipment_slot":"3","bonuses":"-2,4,2,0,0,0,1,0,0,0,0,5,0,0,0","shop_price":"20"},
            {"id":"1931","name":"Empty pot","shop_price":"1"},{"id":"199","name":"Grimy guam"},{"id":"995","name":"Coins"}]""",
        "$C/drop_tables.json" to """[{"ids":"100,101","description":"","default":[{"id":"526","minAmount":"1","maxAmount":"1","weight":"100.0"}],
            "main":[{"id":"1351","minAmount":"1","maxAmount":"1","weight":"3.0"},{"id":"0","minAmount":"1","maxAmount":"1","weight":"9.0"},{"id":"14424","minAmount":"1","maxAmount":"1","weight":"1.0"}],"charm":[]}]""",
        "$C/shared_tables/HDT.xml" to """<HDT><item id="199" minAmt="1" maxAmt="1" weight="32"/></HDT>""",
        "$C/shops.json" to """[{"id":"25","title":"Lumbridge General Store","npcs":"520","currency":"995","general_store":"true","stock":"{1931,30,100}-{1351,5,100}"}]""",
        "$C/ground_spawns.json" to """[{"item_id":"1351","loc_data":"{1,3229,3223,0,90}-"}]""",
        "$C/music_regions.json" to """[{"region":"12850","id":"76"}]""",
        "2009scape/Server/src/main/content/global/skill/gather/woodcutting/WoodcuttingListener.kt" to "// rates",
        "2009scape/Server/src/main/content/region/misthalin/lumbridge/LumbridgeGuide.kt" to "// guide")

    private fun env(map: MutableMap<String, String>) = ServerData.Env(files = { map.keys.toList() }, read = { map[it] }, put = { p, b -> map[p] = String(b); true })
    private fun run(map: MutableMap<String, String>, vararg kv: Pair<String, String>, names: ((String, String) -> Map<Int, Pair<String, Int>>)? = null) =
        ServerData.run(kv.toMap(), env(map), names)

    @Test fun tuplesAndAreas() {
        assertEquals(listOf(listOf(3240, 3250, 0, 1, 3), listOf(1, 2)), ServerData.tuples("{3240,3250,0,1,3}-{1,2}-"))
        assertArrayEquals(intArrayOf(3190, 3190, 3260, 3260), ServerData.area("Lumbridge"))
        assertArrayEquals(intArrayOf(3200, 3200, 3263, 3263), ServerData.area("50,50"))
        assertNull(ServerData.area(""))
    }

    @Test fun findsTheDataAndLooksThingsUp() {
        val m = LinkedHashMap(files)
        assertTrue(run(m, "action" to "info").contains("3 NPCs with stats"))
        val npc = run(m, "action" to "npc", "query" to "goblin")
        assertTrue(npc, npc.contains("100 Goblin") && npc.contains("lifepoints=5") && npc.contains("3 spawns") && npc.contains("Bones"))
        val drops = run(m, "action" to "drops", "id" to "goblin")
        assertTrue(drops, drops.contains("Bronze axe") && drops.contains("23.1%") && drops.contains("herb table") && drops.contains("HDT"))
        val shop = run(m, "action" to "shop", "query" to "shopkeeper")
        assertTrue(shop, shop.contains("Lumbridge General Store") && shop.contains("Empty pot x30 1gp"))
        val spawns = run(m, "action" to "spawns", "area" to "Lumbridge")
        assertTrue(spawns, spawns.contains("Goblin x2") && spawns.contains("3212,3247(stays)") && spawns.contains("Bronze axe x1"))
        assertTrue(run(m, "action" to "source", "query" to "woodcutting").contains("WoodcuttingListener.kt"))
        assertTrue(run(mutableMapOf("a.txt" to ""), "action" to "info").contains("No 2009scape server data"))
    }

    @Test fun exportWritesTheTownForTheKit() {
        val m = LinkedHashMap(files)
        val r = run(m, "action" to "export", "area" to "Lumbridge", "out" to "game/data")
        assertTrue(r, r.contains("3 NPCs, 4 spawn points"))
        @Suppress("UNCHECKED_CAST") val sp = CacheDefs.parseJson(m["game/data/server/spawns.json"]!!) as List<Map<String, Any?>>
        val gob = sp.first { it["name"] == "Goblin" }
        assertEquals(2, (gob["spawns"] as List<*>).size)   // the one far away is outside the town
        assertEquals(5, (gob["hitpoints"] as Number).toInt())
        val main = (gob["drops"] as Map<*, *>)["main"] as List<*>
        assertTrue(main.any { (it as Map<*, *>)["table"] == "HDT" })
        assertTrue(m["game/data/server/drop_tables.json"]!!.contains("Grimy guam"))
        assertTrue(m["game/data/server/items.json"]!!.contains("\"slot\":3"))
        assertTrue(m["game/data/server/music.json"]!!.contains("\"track\":76"))
    }

    @Test fun idsMoveToAnotherCacheByName() {
        val m = LinkedHashMap(files)
        val target = mapOf("npcs" to mapOf(3029 to ("Goblin" to 2), 3030 to ("Goblin" to 5), 520 to ("Shopkeeper" to 0), 3105 to ("Hans" to 0)),
            "items" to mapOf(526 to ("Bones" to 0), 1351 to ("Bronze axe" to 0), 1931 to ("Pot" to 0), 199 to ("Grimy guam leaf" to 0), 995 to ("Coins" to 0)))
        val r = run(m, "action" to "export", "area" to "Lumbridge", "cache" to "osrs") { _, kind -> target[kind]!! }
        assertTrue(r, r.contains("moved to the cache in osrs"))
        @Suppress("UNCHECKED_CAST") val sp = CacheDefs.parseJson(m["game/data/server/spawns.json"]!!) as List<Map<String, Any?>>
        assertEquals(3029, (sp.first { it["name"] == "Goblin" }["id"] as Number).toInt())   // closest level (2) to the 2009 stats
        assertEquals(3105, (sp.first { it["name"] == "Hans" }["id"] as Number).toInt())
        assertTrue(m["game/data/server/shops.json"]!!.contains("\"id\":1931"))   // "Empty pot" is "Pot" there
        assertTrue(m["game/data/server/drop_tables.json"]!!.contains("\"id\":199"))   // "Grimy guam" is "Grimy guam leaf"
    }

    @Test fun oldCacheDefinitionsDecode() {
        // 530 NPC: animation set first (127), then models and name; Old School would stop at 127
        val b = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = b.write(v)
        fun u16(v: Int) { b.write(v shr 8); b.write(v and 255) }
        u8(127); u16(117); u8(1); u8(1); u16(40329); u8(2); b.write("Hans".toByteArray()); u8(0); u8(30); b.write("Talk-to".toByteArray()); u8(0); u8(95); u16(0); u8(0)
        val d = CacheDefs.parse("npcs", 0, b.toByteArray(), old = true)
        assertFalse(d.partial); assertEquals("Hans", d.name); assertEquals(listOf(40329), d.fields["models"])
        assertTrue(CacheDefs.parse("npcs", 0, b.toByteArray()).partial)
        // 530 item: worn model without an offset byte, then name
        val i = java.io.ByteArrayOutputStream()
        i.write(23); i.write(1); i.write(254); i.write(2); i.write("Axe".toByteArray()); i.write(0); i.write(0)
        val it = CacheDefs.parse("items", 1, i.toByteArray(), old = true)
        assertFalse(it.partial); assertEquals("Axe", it.name); assertEquals(listOf(510), it.fields["wornModels"])
    }

    @Test fun oneByteTerrainDecodes() {
        // every tile: underlay opcode (82 = underlay 1) then height 1 -> 0 ... one-byte form, with a trailing block the tiles do not use
        val b = java.io.ByteArrayOutputStream()
        repeat(4 * 64 * 64) { b.write(82); b.write(1); b.write(0) }
        b.write(1); b.write(2); b.write(3)
        val t = Terrain.decode(50, 50, b.toByteArray())
        assertEquals(1, t.underlay[t.at(0, 10, 10)])
    }

    @Test fun keysFromA2009scapeFile() {
        val root = java.nio.file.Files.createTempDirectory("keys").toFile()
        File(root, "cfg").mkdirs(); File(root, "cache").mkdirs()
        File(root, "cfg/xteas.json").writeText("""{"xteas":[{"regionId":"12850","keys":"1,2,3,4"},{"regionId":"1","keys":"bad"}]}""")
        val e = AssetEnv(resolve = { p -> if (p.isEmpty()) root else File(root, p) }, put = { p, b -> File(root, p).writeBytes(b); true },
            read = { p -> File(root, p).takeIf { it.isFile }?.readBytes() }, files = { emptyList() })
        val r = CacheTool.run(mapOf("action" to "keys", "dir" to "cache", "file" to "cfg/xteas.json"), e)
        assertTrue(r, r.startsWith("Saved 1 map keys"))
        val k = CacheDefs.parseJson(File(root, "cache/keys.json").readText()) as List<*>
        assertEquals("l50_50", (k[0] as Map<*, *>)["name"])
    }
}
