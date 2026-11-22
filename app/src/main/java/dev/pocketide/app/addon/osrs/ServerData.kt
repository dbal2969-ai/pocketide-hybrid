package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

/**
 * Reads the game rules that a game cache does not hold, from a 2009scape server folder in the project (its Server/data/configs
 * JSON and XML files, and the content source under Server/src/main/content): where every NPC and ground item stands, NPC
 * combat stats, attack speeds and respawn times, drop tables, shops, item weights, prices and equipment bonuses, doors and
 * music areas. Only the published data files are read; no server code is copied. Ids are those of the 2009 cache (build 530):
 * they match a 2009scape cache directly, and [Mapper] moves them to another cache (Old School) by name.
 */
object ServerData {
    /** Ids of special "items" in drop tables that stand for a shared table or a clue scroll. */
    val SLOTS = mapOf(31 to "RDT", 1 to "clue_easy", 5733 to "clue_medium", 12070 to "clue_hard", 799 to "CELEDT", 14422 to "USDT", 14424 to "HDT", 14426 to "GDT", 14428 to "RSDT", 14430 to "ASDT")
    val TABLE_NAMES = mapOf("RDT" to "rare drop table", "CELEDT" to "minor gem/rune table", "USDT" to "uncommon seed table", "HDT" to "herb table", "GDT" to "gem table", "RSDT" to "rare seed table", "ASDT" to "allotment seed table")

    class Env(val files: () -> List<String>, val read: (String) -> String?, val put: (String, ByteArray) -> Boolean)

    class Spawn(val npc: Int, val x: Int, val z: Int, val plane: Int, val walk: Boolean, val dir: Int)
    class GroundItem(val item: Int, val amount: Int, val x: Int, val z: Int, val plane: Int, val respawn: Int)
    class Drop(val id: Int, val min: Int, val max: Int, val weight: Double)

    /** All the data of one 2009scape server folder, parsed on demand. */
    class Data(val configs: String, val content: String?, private val read: (String) -> String?, private val all: List<String>) {
        private fun json(name: String): Any? = read("$configs/$name")?.let { try { MiniJson.parse(it) } catch (e: Exception) { null } }
        private fun str(m: Map<*, *>, k: String) = m[k]?.toString().orEmpty()

        val npcs: Map<Int, Map<String, String>> by lazy { byId(json("npc_configs.json")) }
        val items: Map<Int, Map<String, String>> by lazy { byId(json("item_configs.json")) }
        private fun byId(v: Any?): Map<Int, Map<String, String>> {
            val out = HashMap<Int, Map<String, String>>()
            for (e in (v as? List<*>).orEmpty()) { val m = e as? Map<*, *> ?: continue; val id = str(m, "id").toIntOrNull() ?: continue
                out[id] = m.entries.filter { it.value != null }.associate { it.key.toString() to it.value.toString() } }
            return out
        }

        val spawns: List<Spawn> by lazy {
            val out = ArrayList<Spawn>()
            for (e in (json("npc_spawns.json") as? List<*>).orEmpty()) {
                val m = e as? Map<*, *> ?: continue; val id = str(m, "npc_id").toIntOrNull() ?: continue
                for (t in tuples(str(m, "loc_data"))) if (t.size >= 3) out.add(Spawn(id, t[0], t[1], t[2], t.getOrElse(3) { 0 } == 1, t.getOrElse(4) { 0 }))
            }
            out
        }
        val ground: List<GroundItem> by lazy {
            val out = ArrayList<GroundItem>()
            for (e in (json("ground_spawns.json") as? List<*>).orEmpty()) {
                val m = e as? Map<*, *> ?: continue; val id = str(m, "item_id").toIntOrNull() ?: continue
                for (t in tuples(str(m, "loc_data"))) if (t.size >= 4) out.add(GroundItem(id, t[0], t[1], t[2], t[3], t.getOrElse(4) { 0 }))
            }
            out
        }
        /** NPC id to its drop table: always (every kill), main (one weighted roll), charm, tertiary. */
        val drops: Map<Int, Map<String, List<Drop>>> by lazy {
            val out = HashMap<Int, Map<String, List<Drop>>>()
            for (e in (json("drop_tables.json") as? List<*>).orEmpty()) {
                val m = e as? Map<*, *> ?: continue
                val t = linkedMapOf<String, List<Drop>>()
                for ((key, name) in listOf("default" to "always", "main" to "main", "charm" to "charm", "tertiary" to "tertiary")) {
                    val l = (m[key] as? List<*>).orEmpty().mapNotNull { d -> (d as? Map<*, *>)?.let { Drop(str(it, "id").toIntOrNull() ?: return@let null, str(it, "minAmount").toIntOrNull() ?: 1, str(it, "maxAmount").toIntOrNull() ?: 1, str(it, "weight").toDoubleOrNull() ?: 0.0) } }
                    if (l.isNotEmpty()) t[name] = l
                }
                for (id in str(m, "ids").split(',').mapNotNull { it.trim().toIntOrNull() }) out[id] = t
            }
            out
        }
        /** Shared tables (rare drop table, gems, herbs, seeds) from shared_tables/<NAME>.xml. */
        val shared: Map<String, List<Drop>> by lazy {
            val out = LinkedHashMap<String, List<Drop>>()
            val row = Regex("""<item\s+id="(\d+)"\s+minAmt="(\d+)"\s+maxAmt="(\d+)"\s+weight="([\d.]+)"""")
            for (name in TABLE_NAMES.keys) {
                val x = read("$configs/shared_tables/$name.xml") ?: continue
                out[name] = row.findAll(x).map { Drop(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[4].toDouble()) }.toList()
            }
            out
        }
        class Shop(val id: Int, val title: String, val npcs: List<Int>, val currency: Int, val general: Boolean, val stock: List<IntArray>)
        val shops: List<Shop> by lazy {
            (json("shops.json") as? List<*>).orEmpty().mapNotNull { e ->
                val m = e as? Map<*, *> ?: return@mapNotNull null
                Shop(str(m, "id").toIntOrNull() ?: 0, str(m, "title"), str(m, "npcs").split(',').mapNotNull { it.trim().toIntOrNull() },
                    str(m, "currency").toIntOrNull() ?: 995, str(m, "general_store") == "true", tuples(str(m, "stock")).filter { it.size >= 2 }.map { it.toIntArray() })
            }
        }
        val doors: List<Map<String, String>> by lazy { (json("door_configs.json") as? List<*>).orEmpty().mapNotNull { e -> (e as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() } } }
        /** Map square (x * 256 + z region id) to the music track id that plays there. */
        val music: Map<Int, Int> by lazy { (json("music_regions.json") as? List<*>).orEmpty().mapNotNull { e -> (e as? Map<*, *>)?.let { (str(it, "region").toIntOrNull() ?: return@let null) to (str(it, "id").toIntOrNull() ?: return@let null) } }.toMap() }

        fun npcName(id: Int) = npcs[id]?.get("name") ?: "npc $id"
        fun itemName(id: Int) = SLOTS[id]?.let { TABLE_NAMES[it] ?: it.replace('_', ' ') } ?: items[id]?.get("name") ?: "item $id"
        fun contentFiles(): List<String> = if (content == null) emptyList() else all.filter { it.startsWith("$content/") && (it.endsWith(".kt") || it.endsWith(".java")) }

        /** Every enum in the content source as a data table (recipes, levels, experience, tools, requirements). Read once. */
        val tables: List<CodeTables.Table> by lazy {
            val out = ArrayList<CodeTables.Table>()
            for (f in contentFiles()) {
                val t = read(f) ?: continue
                if (!t.contains("enum ")) continue
                try { out.addAll(CodeTables.enums(t, f.removePrefix("$content/"))) } catch (e: Exception) { }
            }
            out
        }
    }

    /** "{a,b,c}-{d,e,f}-" to [[a,b,c],[d,e,f]]. */
    fun tuples(s: String): List<List<Int>> = Regex("""\{([^}]*)\}""").findAll(s).map { m -> m.groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull() } }.toList()

    /** Finds the configs folder (the one holding npc_spawns.json and npc_configs.json) and the content source, or explains what is missing. */
    fun locate(files: List<String>, dir: String, read: (String) -> String?): Data? {
        val want = if (dir.isEmpty()) "" else "$dir/"
        val spawns = files.filter { it.startsWith(want) && it.endsWith("/npc_spawns.json") || it == want + "npc_spawns.json" }
            .map { it.removeSuffix("npc_spawns.json").trimEnd('/') }
            .filter { d -> files.contains(if (d.isEmpty()) "npc_configs.json" else "$d/npc_configs.json") }
            .sortedWith(compareBy({ d -> if (files.any { it.startsWith(if (d.isEmpty()) "shared_tables/" else "$d/shared_tables/") }) 0 else 1 }, { it.length }))   // the full original before a partial copy
        val configs = spawns.firstOrNull() ?: return null
        val content = files.asSequence().mapNotNull { f -> f.indexOf("/src/main/content/").takeIf { it >= 0 }?.let { f.substring(0, it) + "/src/main/content" } }
            .sortedBy { it.length }.firstOrNull()
        return Data(configs, content, read, files)
    }

    /** x1,z1,x2,z2 of a town name, map squares ("50,50" or "49,49;51,51"), or four numbers. Null = everywhere. */
    fun area(s: String): IntArray? {
        val t = s.trim(); if (t.isEmpty() || t.equals("all", true)) return null
        Places.find(t)?.let { return it.area }
        val nums = Regex("-?\\d+").findAll(t).map { it.value.toInt() }.toList()
        if (nums.size == 4 && nums.all { it > 255 }) return intArrayOf(minOf(nums[0], nums[2]), minOf(nums[1], nums[3]), maxOf(nums[0], nums[2]), maxOf(nums[1], nums[3]))
        if (nums.size >= 2 && nums.size % 2 == 0 && nums.all { it in 0..255 }) {
            val xs = nums.filterIndexed { i, _ -> i % 2 == 0 }; val zs = nums.filterIndexed { i, _ -> i % 2 == 1 }
            return intArrayOf(xs.min() * 64, zs.min() * 64, xs.max() * 64 + 63, zs.max() * 64 + 63)
        }
        throw IllegalArgumentException("area must be a town (${Places.ALL.joinToString(", ") { it.name }}), map squares like 50,50 or 49,49;51,51, or x1,z1,x2,z2 tiles")
    }
    private fun inside(a: IntArray?, x: Int, z: Int) = a == null || (x in a[0]..a[2] && z in a[1]..a[3])

    /**
     * Moves 2009 ids to another cache's ids by name. [names] gives, for "npcs" or "items", every id of the target cache with its
     * name and combat level (0 for items). The same id is kept when its name matches; otherwise the first id with the same name
     * (for NPCs the one whose level is closest). Unmatched ids map to null.
     */
    class Mapper(private val data: Data, names: (String) -> Map<Int, Pair<String, Int>>) {
        private val npcT = names("npcs"); private val itemT = names("items")
        private val npcBy = npcT.entries.groupBy { it.value.first.lowercase() }
        private val itemBy = itemT.entries.groupBy { it.value.first.lowercase() }
        val missed = LinkedHashSet<String>()
        fun npc(id: Int): Int? {
            val name = data.npcs[id]?.get("name") ?: return null
            if (npcT[id]?.first.equals(name, true)) return id
            val c = npcBy[name.lowercase()] ?: run { missed.add(name); return null }
            val lvl = levelOf(data.npcs[id])
            return c.minByOrNull { Math.abs(it.value.second - lvl) * 100000L + it.key }?.key
        }
        // names that changed a little between the games: "Grimy guam" is "Grimy guam leaf", "Empty pot" is "Pot"
        private fun near(name: String): List<Map.Entry<Int, Pair<String, Int>>>? {
            val n = name.lowercase()
            if (n.startsWith("empty ")) itemBy[n.removePrefix("empty ")]?.let { return it }
            val longer = itemBy.keys.filter { it.startsWith("$n ") }
            return if (longer.size == 1) itemBy[longer[0]] else null
        }

        /** Target item id to the 2009 id it came from. */
        val origin = HashMap<Int, Int>()
        fun item(id: Int): Int? {
            if (SLOTS.containsKey(id)) return id
            val name = data.items[id]?.get("name") ?: return null
            val t = if (itemT[id]?.first.equals(name, true)) id else (itemBy[name.lowercase()] ?: near(name))?.minOf { it.key } ?: run { missed.add(name); return null }
            origin.putIfAbsent(t, id)
            return t
        }
    }

    /** Combat level from a 2009scape NPC entry (the cache's own level is used when there is one). */
    fun levelOf(m: Map<String, String>?): Int {
        if (m == null) return 0
        fun l(k: String) = m[k]?.toIntOrNull() ?: 1
        val base = 0.25 * (l("defence_level") + l("lifepoints") + 1)
        val melee = 0.325 * (l("attack_level") + l("strength_level"))
        val range = 0.325 * (l("range_level") * 1.5); val mage = 0.325 * (l("magic_level") * 1.5)
        return Math.floor(base + maxOf(melee, range, mage)).toInt()
    }

    // ---------------------------------------------------------------- the tool

    fun run(a: Map<String, Any?>, env: Env, names: ((String, String) -> Map<Int, Pair<String, Int>>)? = null): String {
        fun g(k: String) = (a[k] as? String)?.trim().orEmpty().ifEmpty { (a[k] as? Number)?.toString().orEmpty() }
        val action = g("action").lowercase()
        val files = env.files()
        val data = locate(files, g("dir").trim('/'), env.read)
            ?: return "No 2009scape server data in ${g("dir").ifEmpty { "the project" }}: the folder with npc_spawns.json and npc_configs.json (2009scape's Server/data/configs) was not found. " +
                "Import the 2009scape repository (gitlab.com/2009scape/2009scape) into the project first."
        return try {
            when (action) {
                "info", "" -> info(data)
                "npc" -> npc(data, g("query"), g("id"))
                "spawns" -> spawns(data, area(g("area")), g("plane").toIntOrNull() ?: 0, g("query"))
                "drops" -> drops(data, g("id").ifEmpty { g("query") })
                "shop", "shops" -> shops(data, g("query"))
                "item" -> item(data, g("query"), g("id"))
                "source" -> source(data, g("query").ifEmpty { g("topic") })
                "tables" -> tables(data, g("query"))
                "table" -> table(data, g("query").ifEmpty { g("id") }, g("out").trim('/'), env)
                "export" -> export(data, area(g("area")), g("out").trim('/').ifEmpty { "game/data" }, env, g("cache").trim('/'), names)
                else -> "action must be one of: info, npc, spawns, drops, shop, item, source, tables, table, export."
            }
        } catch (e: IllegalArgumentException) { "game_data $action: ${e.message}" }
    }

    private fun info(d: Data): String =
        "2009scape server data in ${d.configs}" + (d.content?.let { " (content source in $it, ${d.contentFiles().size} files)" } ?: "") + ":\n" +
            "- ${d.npcs.size} NPCs with stats (attack, strength, defence, hitpoints, ranged, magic, attack speed, respawn, aggressive, examine)\n" +
            "- ${d.spawns.size} NPC spawn points and ${d.ground.size} ground item spawns\n" +
            "- ${d.drops.size} NPCs with drop tables, ${d.shared.size} shared tables (${d.shared.keys.joinToString(", ")})\n" +
            "- ${d.shops.size} shops, ${d.items.size} items (weight, price, equipment slot and bonuses, requirements, attack speed)\n" +
            "- ${d.doors.size} doors, music for ${d.music.size} map areas\n" +
            "Ids are the 2009 cache's (build 530). Use npc, spawns, drops, shop, item and source to look things up, export to write a town's data for the game."

    private fun pickNpcs(d: Data, query: String, id: String): List<Int> {
        id.toIntOrNull()?.let { return if (d.npcs.containsKey(it)) listOf(it) else emptyList() }
        val q = query.lowercase().trim(); if (q.isEmpty()) throw IllegalArgumentException("npc needs query (part of a name) or id")
        return d.npcs.filter { it.value["name"]?.lowercase()?.contains(q) == true }.keys.sorted()
    }

    private fun npc(d: Data, query: String, id: String): String {
        val ids = pickNpcs(d, query, id)
        if (ids.isEmpty()) return "No NPC matches '${query.ifEmpty { id }}'."
        val sb = StringBuilder("${ids.size} match${if (ids.size == 1) "" else "es"}" + (if (ids.size > 12) ", first 12" else "") + ":\n")
        for (n in ids.take(12)) {
            val m = d.npcs[n]!!
            val sp = d.spawns.filter { it.npc == n }
            val keys = listOf("attack_level", "strength_level", "defence_level", "lifepoints", "range_level", "magic_level", "attack_speed", "respawn_delay", "aggressive", "agg_radius", "combat_style", "weakness", "slayer_exp", "examine")
            sb.append("$n ${m["name"]}: ").append(keys.filter { m[it] != null }.joinToString(", ") { "${it.removeSuffix("_level")}=${m[it]}" })
            sb.append("; ${sp.size} spawn${if (sp.size == 1) "" else "s"}").append(sp.take(4).joinToString("") { " (${it.x},${it.z},${it.plane})" })
            d.drops[n]?.let { t -> sb.append("; drops: ").append(t.entries.joinToString("; ") { (k, l) -> k + " " + l.take(6).joinToString(", ") { d.itemName(it.id) } + (if (l.size > 6) " +${l.size - 6}" else "") }) }
            d.shops.filter { n in it.npcs }.forEach { sb.append("; shop: ${it.title}") }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun spawns(d: Data, area: IntArray?, plane: Int, query: String): String {
        if (area == null) return "spawns needs area: a town name, map squares (50,50) or x1,z1,x2,z2 tiles."
        val q = query.lowercase()
        val here = d.spawns.filter { it.plane == plane && inside(area, it.x, it.z) && (q.isEmpty() || d.npcName(it.npc).lowercase().contains(q)) }
        val ground = d.ground.filter { it.plane == plane && inside(area, it.x, it.z) }
        if (here.isEmpty() && ground.isEmpty()) return "Nothing spawns there on floor $plane."
        val sb = StringBuilder("${here.size} NPC spawns in ${area.joinToString(",")} (floor $plane):\n")
        for ((id, l) in here.groupBy { it.npc }.entries.sortedBy { d.npcName(it.key) }.take(60))
            sb.append("$id ${d.npcName(id)} x${l.size}: ").append(l.take(6).joinToString(" ") { "${it.x},${it.z}" + if (it.walk) "" else "(stays)" }).append('\n')
        if (ground.isNotEmpty()) sb.append("${ground.size} ground items: ").append(ground.take(20).joinToString("; ") { "${d.itemName(it.item)} x${it.amount} at ${it.x},${it.z}" })
        return sb.toString().trimEnd()
    }

    private fun drops(d: Data, which: String): String {
        val id = which.toIntOrNull() ?: pickNpcs(d, which, "").firstOrNull { d.drops.containsKey(it) } ?: return "No NPC with drops matches '$which'."
        val t = d.drops[id] ?: return "${d.npcName(id)} ($id) has no drop table."
        val sb = StringBuilder("Drops of ${d.npcName(id)} ($id). 'always' drops every kill; 'main' is one weighted roll (chance = weight / total):\n")
        for ((k, l) in t) {
            val total = l.sumOf { it.weight }
            sb.append("$k:\n")
            for (x in l) sb.append("  ${x.id} ${d.itemName(x.id)} ${if (x.min == x.max) "${x.min}" else "${x.min}-${x.max}"}" + (if (k == "always") "" else " weight ${x.weight} (${String.format("%.1f", 100 * x.weight / total)}%)") + "\n")
        }
        val tables = t.values.flatten().mapNotNull { SLOTS[it.id] }.filter { it in d.shared }.distinct()
        for (n in tables) sb.append("$n (${TABLE_NAMES[n]}): ").append(d.shared[n]!!.joinToString(", ") { d.itemName(it.id) + " w" + it.weight }).append('\n')
        return sb.toString().trimEnd()
    }

    private fun shops(d: Data, query: String): String {
        val q = query.lowercase().trim()
        val l = d.shops.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.npcs.any { n -> d.npcName(n).lowercase().contains(q) } }
        if (l.isEmpty()) return "No shop matches '$query'."
        return l.take(10).joinToString("\n") { s ->
            "${s.id} ${s.title}" + (if (s.general) " (general store)" else "") + ", kept by ${s.npcs.joinToString { d.npcName(it) + " ($it)" }}, paid in ${d.itemName(s.currency)}: " +
                s.stock.joinToString(", ") { "${d.itemName(it[0])} x${it[1]}" + (d.items[it[0]]?.get("shop_price")?.let { p -> " ${p}gp" } ?: "") }
        } + if (l.size > 10) "\n… and ${l.size - 10} more." else ""
    }

    private fun item(d: Data, query: String, id: String): String {
        val ids = id.toIntOrNull()?.let { listOf(it) } ?: run {
            val q = query.lowercase().trim(); if (q.isEmpty()) throw IllegalArgumentException("item needs query (part of a name) or id")
            d.items.filter { it.value["name"]?.lowercase()?.contains(q) == true }.keys.sorted()
        }
        if (ids.isEmpty()) return "No item matches '${query.ifEmpty { id }}'."
        val keys = listOf("examine", "equipment_slot", "bonuses", "requirements", "attack_speed", "two_handed", "weight", "shop_price", "ge_buy_limit", "tradeable", "weapon_interface")
        return ids.take(12).joinToString("\n") { n -> val m = d.items[n] ?: return@joinToString "$n: not in item_configs"
            "$n ${m["name"]}: " + keys.filter { m[it] != null && m[it] != "null" }.joinToString(", ") { "$it=${m[it]}" } } +
            "\nbonuses order: stab, slash, crush, magic, ranged attack; stab, slash, crush, magic, ranged defence; summoning defence; strength (11), prayer (12), magic damage (13), ranged strength (14)." +
            "\nequipment_slot: 0 head, 1 cape, 2 neck, 3 weapon, 4 body, 5 shield, 7 legs, 9 hands, 10 feet, 12 ring, 13 ammo."
    }

    /** Content source files about a topic, best matches first: the server's own code for a skill, an area, a quest or an NPC. */
    private fun source(d: Data, topic: String): String {
        val c = d.content ?: return "The content source (Server/src/main/content) is not in the project."
        val words = topic.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }
        if (words.isEmpty()) return "source needs query: a skill, place, quest or NPC (e.g. woodcutting, lumbridge, cook's assistant)."
        val all = d.contentFiles()
        val scored = all.map { f -> val p = f.removePrefix("$c/").lowercase(); f to words.sumOf { w -> (if (p.substringAfterLast('/').contains(w)) 3 else 0) + (if (p.contains(w)) 1 else 0) } }
            .filter { it.second > 0 }.sortedWith(compareBy({ -it.second }, { it.first.length }))
        if (scored.isEmpty()) return "No content file mentions '$topic' in its path (${all.size} files). Try search_code with a word from it."
        return "Content source about '$topic' (read the files with read_file; the logic and numbers are there, AGPL-licensed: use the facts, write your own code):\n" +
            scored.take(25).joinToString("\n") { it.first } + if (scored.size > 25) "\n… and ${scored.size - 25} more." else ""
    }

    /** Data tables in the content source whose name, columns or file match every word of [query] (all of them without one). */
    private fun tables(d: Data, query: String): String {
        if (d.content == null) return "The content source (Server/src/main/content) is not in the project."
        val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val hits = d.tables.filter { t -> val hay = (t.name + " " + t.file + " " + t.columns.joinToString(" ")).lowercase(); words.all { hay.contains(it) } }
        if (hits.isEmpty()) return "No data table matches '$query' (${d.tables.size} tables in the source). Try one word: a skill (cooking, smithing), an item kind (logs, bars) or a column (level, experience)."
        return "${hits.size} data table${if (hits.size == 1) "" else "s"}" + (if (words.isEmpty()) "" else " matching '$query'") + (if (hits.size > 40) ", first 40" else "") +
            ". Each is an enum of the server read as rows; [item] etc. marks columns holding ids. Get one with action=table query=<name> (out=<folder> writes it as JSON):\n" +
            hits.take(40).joinToString("\n") { CodeTables.describe(it) }
    }

    /** One table by name: shown (first rows) and, with [out], written to [out]/server/tables/<name>.json for a game to load. */
    private fun table(d: Data, name: String, out: String, env: Env): String {
        if (name.isEmpty()) throw IllegalArgumentException("table needs query: the table's name (see action=tables)")
        val all = d.tables.filter { it.name.equals(name, true) }.ifEmpty { d.tables.filter { it.name.lowercase().contains(name.lowercase()) } }
        if (all.isEmpty()) return "No table named '$name'. List them with action=tables."
        if (all.size > 1 && all.none { it.name.equals(name, true) }) return "Several tables match '$name': ${all.take(15).joinToString(", ") { it.name + " (" + it.file + ")" }}. Pass the exact name."
        val t = all.first { it.name.equals(name, true) || all.size == 1 }
        val json = CodeTables.toJson(t)
        val wrote = if (out.isNotEmpty()) { val p = "$out/server/tables/${t.name}.json"; if (env.put(p, json.toByteArray())) "Wrote $p (${t.rows.size} rows). " else "Could not write $p. " } else ""
        val ids = if (t.kinds.isEmpty()) "" else " Id columns: ${t.kinds.entries.joinToString { "${it.key} = ${it.value}" }} (2009 ids)."
        return wrote + "${CodeTables.describe(t)}.$ids Rows" + (if (t.rows.size > 25) " (first 25 of ${t.rows.size})" else "") + ":\n" +
            t.rows.take(25).joinToString("\n") { CacheDefs.json(it) }
    }

    /**
     * Writes a town's server data for the game kit: spawns.json (every NPC that stands there, with its stats, attack speed,
     * respawn, aggressiveness and drops), shops.json, ground_items.json, shared drop tables and the items they name
     * (names, weights, prices, equipment slots and bonuses) into [out]/server. With [cache] the ids are moved to that cache by name.
     */
    private fun export(d: Data, area: IntArray?, out: String, env: Env, cache: String, names: ((String, String) -> Map<Int, Pair<String, Int>>)?): String {
        if (area == null) return "export needs area: a town name, map squares or x1,z1,x2,z2 tiles."
        val mapper = if (cache.isNotEmpty() && names != null) Mapper(d) { k -> names(cache, k) } else null
        val npcId: (Int) -> Int? = { mapper?.npc(it) ?: if (mapper == null) it else null }
        val itemId: (Int) -> Int? = { mapper?.item(it) ?: if (mapper == null) it else null }
        val itemsUsed = java.util.TreeSet<Int>()
        fun dropJson(l: List<Drop>) = l.mapNotNull { x -> val i = itemId(x.id) ?: return@mapNotNull null; if (!SLOTS.containsKey(x.id)) itemsUsed.add(i)
            linkedMapOf("id" to i, "name" to d.itemName(x.id), "min" to x.min, "max" to x.max, "weight" to x.weight).also { m -> SLOTS[x.id]?.let { m["table"] = it } } }
        val here = d.spawns.filter { inside(area, it.x, it.z) }
        val npcs = ArrayList<Map<String, Any?>>(); var skipped = 0
        for ((id, l) in here.groupBy { it.npc }) {
            val m = d.npcs[id] ?: continue
            val target = npcId(id) ?: run { skipped++; null } ?: continue
            fun num(k: String) = m[k]?.toIntOrNull()
            val row = linkedMapOf<String, Any?>("id" to target, "serverId" to id, "name" to (m["name"] ?: ""), "examine" to m["examine"],
                "attack" to (num("attack_level") ?: 1), "strength" to (num("strength_level") ?: 1), "defence" to (num("defence_level") ?: 1),
                "hitpoints" to num("lifepoints"), "ranged" to (num("range_level") ?: 1), "magic" to (num("magic_level") ?: 1),
                "attackSpeed" to (num("attack_speed") ?: 4), "respawnTicks" to (num("respawn_delay") ?: 50), "aggressive" to (m["aggressive"] == "true"),
                "aggroRadius" to num("agg_radius"), "combatStyle" to m["combat_style"], "bonuses" to m["bonuses"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() },
                "fights" to (num("attack_level") != null && num("lifepoints") != null),
                "spawns" to l.map { listOf(it.x, it.z, it.plane, if (it.walk) 1 else 0, it.dir) })
            // attack, block and death animations: 2009 ids, so only kept when the game uses the 2009 cache
            if (mapper == null) linkedMapOf("attack" to num("melee_animation"), "defend" to num("defence_animation"), "death" to num("death_animation")).filterValues { it != null && it > 0 }.takeIf { it.isNotEmpty() }?.let { row["anims"] = it }
            d.drops[id]?.let { t -> row["drops"] = t.mapValues { (_, v) -> dropJson(v) } }
            npcs.add(row)
        }
        val shops = d.shops.filter { s -> s.npcs.any { n -> here.any { it.npc == n } } }.map { s ->
            s.stock.forEach { st -> itemId(st[0])?.let { itemsUsed.add(it) } }
            linkedMapOf("id" to s.id, "title" to s.title, "npcs" to s.npcs.mapNotNull(npcId), "currency" to itemId(s.currency), "general" to s.general,
                "stock" to s.stock.mapNotNull { st -> itemId(st[0])?.let { i -> linkedMapOf("id" to i, "name" to d.itemName(st[0]), "amount" to st[1], "price" to d.items[st[0]]?.get("shop_price")?.toIntOrNull()) } })
        }
        val ground = d.ground.filter { inside(area, it.x, it.z) }.mapNotNull { gi -> itemId(gi.item)?.let { i -> itemsUsed.add(i); linkedMapOf("id" to i, "name" to d.itemName(gi.item), "amount" to gi.amount, "x" to gi.x, "z" to gi.z, "plane" to gi.plane, "respawnTicks" to gi.respawn) } }
        val usedTables = npcs.flatMap { n -> ((n["drops"] as? Map<*, *>)?.values ?: emptyList<Any?>()).flatMap { (it as? List<*>).orEmpty() } }.mapNotNull { (it as? Map<*, *>)?.get("table") as? String }.toSet()
        val tables = d.shared.filterKeys { it in usedTables }.mapValues { (_, v) -> dropJson(v) }
        val itemInfo = itemsUsed.mapNotNull { i -> val src = if (mapper == null) i else mapper.origin[i] ?: return@mapNotNull null; val m = d.items[src] ?: return@mapNotNull null
            linkedMapOf("id" to i, "name" to m["name"], "examine" to m["examine"], "weight" to m["weight"]?.toDoubleOrNull(), "price" to m["shop_price"]?.toIntOrNull(),
                "slot" to m["equipment_slot"]?.toIntOrNull(), "bonuses" to m["bonuses"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }, "requirements" to m["requirements"], "attackSpeed" to m["attack_speed"]?.toIntOrNull()) }
        val dir = "$out/server"
        fun put(name: String, v: Any?) { if (!env.put("$dir/$name", CacheDefs.json(v).toByteArray())) throw IllegalArgumentException("could not write $dir/$name") }
        put("spawns.json", npcs); put("shops.json", shops); put("ground_items.json", ground); put("drop_tables.json", tables); put("items.json", itemInfo)
        val regions = (area[0] / 64..area[2] / 64).flatMap { x -> (area[1] / 64..area[3] / 64).map { z -> x * 256 + z } }
        put("music.json", regions.mapNotNull { r -> d.music[r]?.let { linkedMapOf("square" to "${r shr 8},${r and 255}", "track" to it) } })
        val fighters = npcs.count { it["fights"] == true }
        return "Wrote 2009scape data for ${area.joinToString(",")} to $dir: spawns.json (${npcs.size} NPCs, ${here.size} spawn points, $fighters that fight, with real stats, attack speed, respawn time and drops), " +
            "shops.json (${shops.size}), ground_items.json (${ground.size}), drop_tables.json (${tables.size} shared tables), items.json (${itemInfo.size} items: weight, price, slot, bonuses), music.json." +
            (if (mapper != null) " Ids were moved to the cache in $cache by name" + (if (mapper.missed.isNotEmpty()) "; not in that cache: ${mapper.missed.take(10).joinToString(", ")}${if (mapper.missed.size > 10) " and ${mapper.missed.size - 10} more" else ""}" else "") + (if (skipped > 0) " ($skipped NPCs left out)" else "") + "." else " Ids are the 2009 cache's.") +
            " The game kit uses spawns.json for its enemies when it is there (run game_cache action=enemies with spawns=$dir/spawns.json for their pictures)."
    }
}
