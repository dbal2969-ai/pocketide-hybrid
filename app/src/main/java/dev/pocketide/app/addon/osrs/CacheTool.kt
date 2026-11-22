package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

import java.io.File

/**
 * The game_cache tool: look inside a game cache (main_file_cache.dat2 + idx files), pull out names, sprites and map collision
 * as ordinary files (JSON, PNG), and pack such files into a new cache. All results are short text for the assistant.
 */
object CacheTool {
    const val MAX_SPRITES = 3000
    const val MAX_MAPS = 200
    private val KINDS = "items, npcs, objects, sprites, maps"

    /** [resolve] turns a project path into a file; [put] writes a project file; [list] lists files under a project folder. */

    fun run(a: Map<String, Any?>, env: AssetEnv): String {
        fun g(k: String) = (a[k] as? String)?.trim().orEmpty().ifEmpty { (a[k] as? Number)?.toString().orEmpty() }
        val action = g("action").lowercase()
        val dir = g("dir").trim('/')
        return try {
            when (action) {
                "info" -> info(dir, env)
                "find" -> find(dir, g("kind").lowercase(), g("query"), env, mapOf("attackable" to g("attackable"), "min_level" to g("min_level"), "max_level" to g("max_level"), "count" to g("count"), "seed" to g("seed")))
                "place" -> place(g("query").ifEmpty { g("ids") })
                "check" -> GameCheck.check(env.files(dir).map { it.removePrefix("$dir/").removePrefix("/") }, { f -> env.read(if (dir.isEmpty()) f else "$dir/$f")?.toString(Charsets.UTF_8) }, { f -> env.read(if (dir.isEmpty()) f else "$dir/$f")?.size?.toLong() ?: 0L })
                "enemies" -> if (g("spawns").isNotEmpty()) enemiesFromSpawns(dir, g("spawns").trim('/'), g("out").trim('/').ifEmpty { "game/data" }, env, area(g("area"))) else enemies(dir, g("out").trim('/').ifEmpty { "game/data" }, env, mapOf("attackable" to "true", "min_level" to g("min_level"), "max_level" to g("max_level"), "count" to g("count"), "seed" to g("seed"), "query" to g("query")))
                "place_objects" -> placeObjects(dir, g("ids").ifEmpty { g("query") }, g("out").trim('/').ifEmpty { "game/data" }, env, mapOf("size" to g("size")))
                "export" -> export(dir, g("kind").lowercase(), g("ids"), g("out").trim('/').ifEmpty { "cache-export" }, env, mapOf("scale" to g("scale"), "size" to g("size"), "worn" to g("worn"), "female" to g("female")))
                "pack" -> pack(dir, g("out").trim('/').ifEmpty { "cache-packed" }, env)
                "keys" -> keys(dir, g("id"), env, g("file"))
                "restore" -> restore(dir.ifEmpty { "game" }, g("files"), env)
                "scaffold" -> scaffold(dir, g("kind").lowercase(), g("ids"), g("out").trim('/').ifEmpty { "game" }, env, mapOf("worn" to g("worn"), "female" to g("female")))
                else -> "action must be one of: info, find, place, place_objects, enemies, check, export, pack, keys, scaffold, restore."
            }
        } catch (e: OutOfMemoryError) { "The cache is too big for this phone to read that way. Export a smaller range with ids."
        } catch (e: Exception) { "game_cache $action failed: ${e.message ?: e.javaClass.simpleName}" }
    }

    private fun open(dir: String, env: AssetEnv): Js5 {
        val d = env.resolve(dir) ?: throw IllegalArgumentException("'$dir' is not a folder in the project")
        if (!Js5.hasCache(d)) throw IllegalArgumentException("no main_file_cache.dat2 in '${dir.ifEmpty { "the project" }}'. Unzip the cache first and pass the folder that holds main_file_cache.dat2.")
        return Js5(d)
    }

    /** Pixels per model unit for pictures of people and creatures: 96 pixels span 2 tiles of 128 units, so heights compare like in the game. */
    const val PIC_UNIT = 0.375

    /** Items the game kit needs pictures and names for: coins, every axe, and the six kinds of logs. */
    const val KIT_ITEMS = "995,1351,1349,1353,1361,1355,1357,1359,6739,1511,1521,1519,1517,1515,1513"

    val KIT_FILES = listOf("index.html", "main.js", "world.js", "pathfinder.js", "render.js", "view3d.js", "anim.js", "rules.js", "inv.js", "skills.js", "gather.js", "bank.js", "sfx.js", "save.js", "ui.js", "content.js", "README.md")

    /** Interface pictures of an Old School cache the kit uses: tab icons, skill icons, orbs, hit splats, side panel, compass. */
    const val KIT_SPRITES = "168,169,197-221,776,895,898,900-910,1026,1030,1031,1059-1072,1358-1361"

    /** The player's animations the kit plays: stand, walk, run, punch, kick, block, death, and chopping with each axe. */
    const val KIT_ANIMS = "808,819,824,422,423,424,836,879,877,875,873,871,869,867,2846"

    /**
     * A runnable starter game in [out]: the game kit (map walker with pathfinding) plus, when [dir] holds a cache, the data it needs
     * (floor colours, object names and the 3x3 map squares around the first square in [ids], default 50,50 = Lumbridge).
     */
    fun scaffold(dir: String, kind: String, ids: String, out: String, env: AssetEnv, opts: Map<String, String> = emptyMap()): String {
        if (kind == "viewer") return viewer(dir, ids, out, env)
        val kit = KIT_FILES.associateWith { env.asset(it) }
        if (kit.values.any { it == null }) return "The game kit is not available in this build."
        val place = Places.find(ids)
        val sq = place?.let { it.sx to it.sz } ?: (squares(ids)?.firstOrNull()) ?: (50 to 50)
        val cacheDir = try { env.resolve(dir)?.takeIf { Js5.hasCache(it) } } catch (e: Exception) { null }
        for ((name, bytes) in kit) {
            var b = bytes!!
            if (name == "main.js") b = b.toString(Charsets.UTF_8).replace("const START = { x: 3222, z: 3218 };", "const START = { x: ${place?.tileX ?: (sq.first * 64 + 32)}, z: ${place?.tileZ ?: (sq.second * 64 + 32)} };").toByteArray()
            if (!env.put("$out/$name", b)) return "Could not write $out/$name."
        }
        val notes = StringBuilder("Wrote the game kit to $out (open $out/index.html in the preview).")
        // the 3D library the kit's 3D view uses
        env.asset("../engine/three.module.min.js")?.let { env.put("$out/engine/three.module.min.js", it) }
        env.asset("../engine/THREE-LICENSE.txt")?.let { env.put("$out/engine/THREE-LICENSE.txt", it) }
        if (cacheDir != null) {
            // the game's content (people, quests, dialogue, actions, recipes): content.js reads this one file; empty until something fills it
            if (env.read("$out/data/content.json") == null) env.put("$out/data/content.json", "{}".toByteArray())
            val around = (-1..1).flatMap { dx -> (-1..1).map { dz -> "${sq.first + dx},${sq.second + dz}" } }.joinToString(";")
            notes.append("\n").append(export(dir, "floors", "", "$out/data", env))
            notes.append("\n").append(export(dir, "objects", "", "$out/data", env))
            notes.append("\n").append(export(dir, "maps", around, "$out/data", env))
            notes.append("\n").append(export(dir, "ground", around, "$out/data", env))  // shaded ground under the same squares; render.js uses it when it is there
            notes.append("\n").append(placeObjects(dir, around, "$out/data", env, emptyMap()))  // buildings and objects as pictures with their tiles
            notes.append("\n").append(export(dir, "items", KIT_ITEMS, "$out/data", env, mapOf("merge" to "true")))   // coins, axes and logs: the inventory shows their names from items.json
            notes.append("\n").append(export(dir, "icons", KIT_ITEMS, "$out/data", env))   // and their pictures from data/icons
            val female = opts["female"].orEmpty().lowercase() in setOf("true", "yes", "1")
            notes.append("\n").append(export(dir, "player", if (female) "female" else "male", "$out/data", env, mapOf("worn" to opts["worn"].orEmpty(), "female" to opts["female"].orEmpty())))   // the player's own picture, data/player.png, wearing the gear in worn=
            notes.append("\n").append(enemies(dir, "$out/data", env, mapOf("attackable" to "true", "min_level" to "3", "max_level" to "60", "count" to "20", "seed" to "1")))  // starter enemies for rules.js
            notes.append("\n").append(export(dir, "scene", around, "$out/data", env))   // the 3D world
            notes.append("\n").append(export(dir, "anims", KIT_ANIMS, "$out/data", env))   // the player's animations
            notes.append("\n").append(export(dir, "held", "1351,1349,1353,1361,1355,1357,1359,6739", "$out/data", env))   // axes in the hand while chopping
            if (!open(dir, env).use { CacheDefs.oldLayout(it) }) notes.append("\n").append(export(dir, "sprites", KIT_SPRITES, "$out/data", env))   // the interface pictures (Old School numbering)
            notes.append("\nStart tile ${place?.tileX ?: (sq.first * 64 + 32)}, ${place?.tileZ ?: (sq.second * 64 + 32)} (edit START in $out/main.js). Tap to walk; walls and objects block.")
        } else notes.append(" No cache found in '$dir', so no map data was exported: run game_cache export with kind=maps and out=$out/data.")
        return notes.toString()
    }

    /**
     * Puts the game kit's own files back in [out] (all of them, or the comma-separated [files]), keeping the start tile already in
     * main.js. The generated data is not touched. Used when a change broke the game and the person wants the working version back.
     */
    fun restore(out: String, files: String, env: AssetEnv): String {
        val names = files.split(',').map { it.trim().substringAfterLast('/') }.filter { it.isNotEmpty() }
        val want = if (names.isEmpty() || names.any { it.equals("all", true) }) KIT_FILES else names.filter { it in KIT_FILES }
        val unknown = names.filter { it !in KIT_FILES && !it.equals("all", true) }
        if (want.isEmpty()) return "None of those are game kit files. The kit files are: ${KIT_FILES.joinToString(", ")}."
        val start = env.read("$out/main.js")?.toString(Charsets.UTF_8)?.let { Regex("""const START = \{ x: (\d+), z: (\d+) \};""").find(it) }
        val done = ArrayList<String>()
        for (name in want) {
            var b = env.asset(name) ?: return "The game kit is not available in this build."
            if (name == "main.js" && start != null) b = b.toString(Charsets.UTF_8).replace("const START = { x: 3222, z: 3218 };", start.value).toByteArray()
            if (!env.put("$out/$name", b)) return "Could not write $out/$name."
            done.add(name)
        }
        return "Put back ${done.size} game kit file(s) in $out: ${done.joinToString(", ")}." + (if (start != null && "main.js" in done) " The start tile was kept." else "") +
            (if (unknown.isNotEmpty()) " Not kit files, left alone: ${unknown.joinToString(", ")}." else "") +
            " Files you added yourself are untouched; run action=check dir=$out next."
    }

    /** Map squares of a town (its 3 x 3) or of a squares list. */
    private fun townSquares(ids: String): List<Pair<Int, Int>>? {
        val town = Places.find(ids)
        return if (town != null) (-1..1).flatMap { dx -> (-1..1).map { dz -> (town.sx + dx) to (town.sz + dz) } } else squares(ids)
    }

    /** kind=scene: every square baked for the 3D view (ground, objects, upper floors), see [Scene3D]. */
    private fun scene(js: Js5, dir: String, ids: String, out: String, env: AssetEnv): String {
        val squares = townSquares(ids) ?: return "scene needs a town (e.g. Lumbridge) or map squares such as \"50,50;51,50\"."
        if (squares.size > 16) return "At most 16 map squares per call (a town is 9)."
        val t = js.table(Terrain.INDEX) ?: return "This cache has no maps."
        val keyMap = loadKeys(dir, env)
        val defs = defsOf(js, "objects"); val fc = FloorColours(js)
        val cache = HashMap<String, Scene3D.Square?>()
        fun sq(x: Int, z: Int): Scene3D.Square? = cache.getOrPut("$x,$z") {
            t.groupByName(Terrain.name(x, z))?.let { g -> js.group(Terrain.INDEX, g) }?.let { d -> try { val ter = Terrain.decode(x, z, d); Scene3D.Square(x, z, ter, ter.heights()) } catch (e: Exception) { null } }
        }
        val models = HashMap<Int, Models.Model?>()
        fun model(id: Int) = models.getOrPut(id) { try { js.group(Models.INDEX, id)?.let { Models.decode(id, it) } } catch (e: Exception) { null } }
        var written = 0; var size = 0L; val problems = ArrayList<String>(); val done = ArrayList<String>()
        for ((x, z) in squares) {
            val s = sq(x, z) ?: run { problems.add("$x,$z: not in this cache"); null } ?: continue
            val lg = t.groupByName(Locs.name(x, z))
            val locs = lg?.let { js.group(Terrain.INDEX, it, keyMap[Locs.name(x, z)]) }?.let { try { Locs.decode(it) } catch (e: Exception) { null } }
            if (locs == null) problems.add("$x,$z: objects are encrypted or missing (run action=keys), ground only")
            val bytes = Scene3D.bake(s, { a, b -> sq(a, b) }, { fc.under[it] }, { fc.over[it] }, locs.orEmpty(), defs, ::model, fc.old) { fc.sceneTexture(it) }
            if (env.put("$out/scene/s${x}_$z.bin.gz", bytes)) { written++; done.add("$x,$z"); size += bytes.size }
        }
        env.put("$out/scene/index.json", CacheDefs.json(mapOf("squares" to done)).toByteArray())
        return "Wrote $written baked 3D squares to $out/scene (s<x>_<z>.bin.gz, ${size / 1000} kB in all): ground with real heights and blended colours, every object as its real model, each upper floor apart (sections 2-4 are floors 1-3) so the view draws the floors up to the player's and hides the ones above indoors. " +
            "The game kit's 3D view (view3d.js) draws them." + (if (problems.isNotEmpty()) " Problems: ${problems.take(5).joinToString("; ")}." else "")
    }

    /** Stand, walk and run animation ids of a 2009-era animation set (config group 32), or null. */
    private fun animSet(js: Js5, id: Int): Triple<Int, Int, Int>? = try {
        val d = js.files(CacheDefs.CONFIG_INDEX, 32)?.get(id) ?: null
        if (d == null) null else { val r = Js5.Reader(d); var stand = -1; var walk = -1; var run = -1
            loop@ while (r.left() > 0) when (val op = r.u8()) { 0 -> break@loop; 1 -> { stand = r.u16(); walk = r.u16() }; in 2..5, 7, 8, 9 -> r.u16(); 6 -> run = r.u16(); else -> break@loop }
            Triple(stand, walk, run) }
    } catch (e: Exception) { null }

    /** kind=anims: sequences with their frames and skeletons as JSON for anim.js ($out/anim/<id>.json). */
    private fun anims(js: Js5, ids: String, out: String, env: AssetEnv): String {
        val list = parseIds(ids) ?: return "anims needs ids: sequence numbers, e.g. 808,819,824 (stand, walk, run)."
        if (list.size > 200) return "At most 200 animations per call."
        val old = CacheDefs.oldLayout(js)
        val skel = HashMap<Int, Anim.Skeleton?>(); val sets = HashMap<Int, Map<Int, ByteArray>?>()
        var ok = 0; val missing = ArrayList<Int>()
        for (id in list) { val l = Anim.load(js, id, old, skel, sets); if (l == null) { missing.add(id); continue }; if (env.put("$out/anim/$id.json", Anim.toJson(l).toByteArray())) ok++ }
        return "Wrote $ok animations to $out/anim (<id>.json: frame lengths in 20 ms ticks, the moves of each frame and the skeletons they act on; the kit's anim.js plays them on a model with vertex groups)." +
            (if (missing.isNotEmpty()) " Not in this cache: ${missing.take(10).joinToString(", ")}." else "")
    }

    /**
     * kind=held: an item as the player holds or wears it (its worn models, with vertex groups so it moves with the player's
     * animations), as $out/models/held_<id>.json: an axe while chopping, a sword in a fight.
     */
    private fun held(js: Js5, ids: String, out: String, env: AssetEnv): String {
        val list = parseIds(ids) ?: return "held needs item ids."
        val defs = defsOf(js, "items"); val fc = FloorColours(js)
        var ok = 0; val problems = ArrayList<String>()
        for (id in list.take(60)) {
            val d = defs[id] ?: continue
            try {
                val worn = (d.fields["wornModels"] as? List<*>)?.map { (it as Number).toInt() }?.filter { it in 0..65534 } ?: throw IllegalArgumentException("not worn")
                if (worn.isEmpty()) throw IllegalArgumentException("not worn")
                val parts = worn.map { mid -> Models.decode(mid, js.group(Models.INDEX, mid) ?: throw IllegalArgumentException("model $mid is missing")) }
                val swaps = HashMap<Int, Int>()
                (d.fields["recolor"] as? List<*>)?.forEach { p -> val l = p as? List<*>; if (l != null && l.size == 2) swaps[(l[0] as Number).toInt()] = (l[1] as Number).toInt() }
                if (env.put("$out/models/held_$id.json", Models.toJson(Models.merge(id, parts), swaps) { fc.texture(it) }.toByteArray())) ok++
            } catch (e: Exception) { problems.add("$id ${d.name}: ${e.message}") }
        }
        return "Wrote $ok held items to $out/models (held_<id>.json: the item as the player holds it; play the player's animation on it too so it moves with the hand)." +
            (if (problems.isNotEmpty()) " Not drawn: ${problems.take(4).joinToString("; ")}." else "")
    }

    /** Sequence names by id from an rsmod symbols file in the project (seq.sym: "id<TAB>name" lines), or null. */
    private fun seqNames(env: AssetEnv): Map<Int, String>? = try {
        val f = env.files("").firstOrNull { it.endsWith("/symbols/seq.sym") || it == "seq.sym" }
        f?.let { p -> env.read(p)?.toString(Charsets.UTF_8)?.lines()?.mapNotNull { l -> val t = l.split('\t'); t.getOrNull(0)?.trim()?.toIntOrNull()?.let { it to t.getOrElse(1) { "" }.trim() } }?.toMap() }
    } catch (e: Exception) { null }

    /**
     * Attack, block and death animations of an NPC, found by name next to its stand and walk animations ("cow_ready" and
     * "cow_walk" lead to "cow_attack", "cow_block", "cow_death"); people use the player's punch, block and death.
     */
    fun combatAnims(stand: Int, walk: Int, names: Map<Int, String>?): Map<String, Int> {
        val human = mapOf("attack" to 422, "defend" to 424, "death" to 836)
        if (names == null) return if (stand == 808 || walk == 819) human else emptyMap()
        val byName = names.entries.associate { it.value to it.key }
        val prefixes = listOfNotNull(names[stand], names[walk]).map { it.replace(Regex("_(ready|walk(_f)?|stand|idle)$"), "") }.distinct()
        if (prefixes.any { it == "human" }) return human
        fun find(vararg ends: String): Int? { for (p in prefixes) for (e in ends) byName["${p}_$e"]?.let { return it }; return null }
        return listOfNotNull(find("attack", "attack_unarmed", "attack_armed", "unarmedpunch", "attack1")?.let { "attack" to it },
            find("block", "defend", "unarmedblock", "block1")?.let { "defend" to it }, find("death", "die", "death1")?.let { "death" to it }).toMap()
    }

    /** kind=npcs3d: NPC models with their vertex groups and colours, plus their stand and walk animations, for the 3D view. */
    private fun npcs3d(js: Js5, ids: String, out: String, env: AssetEnv, opts: Map<String, String>): String {
        val names = seqNames(env)
        val list = parseIds(ids) ?: return "npcs3d needs NPC ids."
        val defs = defsOf(js, "npcs"); val fc = FloorColours(js); val old = CacheDefs.oldLayout(js)
        val animIds = java.util.TreeSet<Int>(); var ok = 0; val problems = ArrayList<String>()
        for (id in list.take(120)) {
            val d = defs[id] ?: continue
            try {
                val parts = (d.fields["models"] as? List<*>).orEmpty().map { m -> val mid = (m as Number).toInt(); Models.decode(mid, js.group(Models.INDEX, mid) ?: throw IllegalArgumentException("model $mid is missing")) }
                if (parts.isEmpty()) throw IllegalArgumentException("no models")
                val swaps = HashMap<Int, Int>()
                (d.fields["recolor"] as? List<*>)?.forEach { p -> val l = p as? List<*>; if (l != null && l.size == 2) swaps[(l[0] as Number).toInt()] = (l[1] as Number).toInt() }
                val json = Models.toJson(Models.merge(id, parts), swaps) { fc.texture(it) }
                var stand = (d.fields["standAnim"] as? Number)?.toInt() ?: -1; var walk = (d.fields["walkAnim"] as? Number)?.toInt() ?: -1
                (d.fields["animationSet"] as? Number)?.toInt()?.let { set -> animSet(js, set)?.let { (s, w, _) -> if (stand < 0) stand = s; if (walk < 0) walk = w } }
                val extra = ",\"name\":" + CacheDefs.json(d.name) + ",\"size\":" + ((d.fields["size"] as? Number)?.toInt() ?: 1) +
                    ",\"scaleXZ\":" + ((d.fields["widthScale"] as? Number)?.toInt() ?: 128) + ",\"scaleY\":" + ((d.fields["heightScale"] as? Number)?.toInt() ?: 128) +
                    ",\"standAnim\":$stand,\"walkAnim\":$walk" + combatAnims(stand, walk, names).let { m -> m.values.forEach { animIds.add(it) }; if (m.isEmpty()) "" else ",\"anims\":" + CacheDefs.json(m) } + "}"
                if (env.put("$out/npcs3d/$id.json", (json.removeSuffix("}") + extra).toByteArray())) ok++
                if (stand >= 0 && stand != 65535) animIds.add(stand); if (walk >= 0 && walk != 65535) animIds.add(walk)
            } catch (e: Exception) { problems.add("$id: ${e.message}") }
        }
        opts["anims"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.let { animIds.addAll(it) }
        val a = if (animIds.isNotEmpty()) " " + anims(js, animIds.joinToString(","), out, env) else ""
        return "Wrote $ok NPC models to $out/npcs3d (<id>.json with vertex groups, standAnim and walkAnim)." + a + (if (problems.isNotEmpty()) " Problems: ${problems.take(4).joinToString("; ")}." else "")
    }

    val VIEWER_FILES = listOf("viewer.html", "model.js")

    /** A page that shows exported 3D models spinning (three.js), with the models given in [ids] exported next to it. */
    private fun viewer(dir: String, ids: String, out: String, env: AssetEnv): String {
        val three = env.asset("../engine/three.module.min.js") ?: return "The 3D library is not available in this build."
        for (f in VIEWER_FILES) { val b = env.asset(f) ?: return "The game kit is not available in this build."; if (!env.put("$out/$f", b)) return "Could not write $out/$f." }
        if (!env.put("$out/engine/three.module.min.js", three)) return "Could not write the 3D library."
        env.asset("../engine/THREE-LICENSE.txt")?.let { env.put("$out/engine/THREE-LICENSE.txt", it) }
        val notes = StringBuilder("Wrote the model viewer to $out (open $out/viewer.html in the preview; ?m=item_4151 picks a model, tap to cycle).")
        val cacheDir = try { env.resolve(dir)?.takeIf { Js5.hasCache(it) } } catch (e: Exception) { null }
        if (cacheDir != null && ids.isNotBlank()) notes.append("\n").append(export(dir, "models", ids, "$out/data", env))
        else notes.append(" Export models with kind=models into $out/data/models, then list their names in the page's NAMES array.")
        if (cacheDir != null && ids.isNotBlank()) {
            val names = ids.split(',', ';', ' ').filter { it.isNotBlank() }.joinToString(",") { "\"" + it.replace(':', '_') + "\"" }
            val html = env.asset("viewer.html")!!.toString(Charsets.UTF_8).replace("const NAMES = [];", "const NAMES = [$names];")
            env.put("$out/viewer.html", html.toByteArray())
        }
        return notes.toString()
    }

    /** Download the XTEA keys for an OpenRS2 cache id into <dir>/keys.json (needed to read objects on maps). */
    fun keys(dir: String, id: String, env: AssetEnv, file: String = ""): String {
        if (file.isNotBlank()) return keysFromFile(dir, file.trim('/'), env)
        val n = id.toIntOrNull() ?: return "keys needs id: the OpenRS2 cache number (the 2038 in the file name ...openrs2_2038.zip)."
        val d = env.resolve(dir) ?: throw IllegalArgumentException("'$dir' is not a folder in the project")
        var result: ByteArray? = null; var err: String? = null
        val t = Thread {
            try {
                val c = java.net.URL("https://archive.openrs2.org/caches/runescape/$n/keys.json").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 60000
                if (c.responseCode != 200) { err = "OpenRS2 answered ${c.responseCode} for cache $n"; return@Thread }
                result = c.inputStream.use { it.readNBytes(30_000_000) }
            } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
        }
        t.start(); t.join(90000)
        val bytes = result ?: return "Could not download the keys: ${err ?: "timed out"}. Download https://archive.openrs2.org/caches/runescape/$n/keys.json yourself and put it next to main_file_cache.dat2."
        val parsed = try { CacheDefs.parseJson(bytes.toString(Charsets.UTF_8)) as? List<*> } catch (e: Exception) { null } ?: return "The download was not a keys file."
        val rel = if (dir.isEmpty()) "keys.json" else "$dir/keys.json"
        if (!env.put(rel, bytes)) return "Could not write $rel."
        return "Saved ${parsed.size} keys to $rel. Map objects (trees, walls, buildings) can now be exported."
    }

    /**
     * Map keys from a file already in the project: 2009scape's xteas.json ({"xteas":[{"regionId":"12850","keys":"a,b,c,d"}]}) or an
     * OpenRS2 keys.json, saved as <dir>/keys.json in the OpenRS2 form the exports read.
     */
    fun keysFromFile(dir: String, file: String, env: AssetEnv): String {
        val txt = env.read(file)?.toString(Charsets.UTF_8) ?: return "No file named $file in the project."
        val parsed = try { CacheDefs.parseJson(txt) } catch (e: Exception) { return "$file is not valid JSON." }
        val rows = ArrayList<Map<String, Any?>>()
        fun add(region: Int, key: List<Int>) { if (key.size == 4) rows.add(linkedMapOf("archive" to 5, "name" to "l${region shr 8}_${region and 255}", "mapsquare" to region, "key" to key)) }
        when (parsed) {
            is Map<*, *> -> for (e in (parsed["xteas"] as? List<*>).orEmpty()) {
                val m = e as? Map<*, *> ?: continue
                val region = m["regionId"].toString().toIntOrNull() ?: continue
                val key = when (val k = m["keys"]) { is String -> k.split(',').mapNotNull { it.trim().toIntOrNull() }; is List<*> -> k.mapNotNull { (it as? Number)?.toInt() }; else -> emptyList() }
                add(region, key)
            }
            is List<*> -> for (e in parsed) { val m = e as? Map<*, *> ?: continue; val r = (m["mapsquare"] as? Number)?.toInt() ?: continue; add(r, (m["key"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }.orEmpty()) }
        }
        if (rows.isEmpty()) return "$file holds no map keys (expected 2009scape's xteas.json or an OpenRS2 keys.json)."
        val rel = if (dir.isEmpty()) "keys.json" else "$dir/keys.json"
        if (!env.put(rel, MiniJson.write(rows).toByteArray())) return "Could not write $rel."
        return "Saved ${rows.size} map keys from $file to $rel. Map objects (trees, walls, buildings) can now be exported."
    }

    /** Square name ("l50_50") to its key, from keys.json beside the cache or in the project root; empty when there is none. */
    private fun loadKeys(dir: String, env: AssetEnv): Map<String, IntArray> {
        for (p in listOf(if (dir.isEmpty()) "keys.json" else "$dir/keys.json", "keys.json")) {
            val txt = env.read(p)?.toString(Charsets.UTF_8) ?: continue
            try {
                val out = HashMap<String, IntArray>()
                for (e in CacheDefs.parseJson(txt) as List<*>) {
                    val m = e as? Map<*, *> ?: continue
                    val name = m["name"] as? String ?: continue
                    val k = (m["key"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }?.toIntArray() ?: continue
                    if (k.size == 4) out[name] = k
                }
                return out
            } catch (e: Exception) { }
        }
        return emptyMap()
    }

    private val INDEX_NAMES = mapOf(0 to "animations", 1 to "skeletons", 2 to "config (items, npcs, objects, ...)", 3 to "interfaces", 4 to "sound effects", 5 to "maps",
        6 to "models", 7 to "models", 8 to "sprites", 9 to "textures", 10 to "binary", 11 to "music jingles", 12 to "scripts", 13 to "fonts", 14 to "music", 15 to "models", 16 to "music samples", 17 to "map areas", 18 to "textures", 19 to "textures", 20 to "textures")

    fun info(dir: String, env: AssetEnv): String = open(dir, env).use { js ->
        val sb = StringBuilder("Game cache in '${dir.ifEmpty { "the project" }}':\n")
        for (i in js.indexes()) {
            val t = js.table(i)
            sb.append("  index $i" + (INDEX_NAMES[i]?.let { " ($it)" } ?: "") + ": " + (t?.let { "${it.groups.size} groups, ${it.files.sumOf { f -> f.size }} files, revision ${it.revision}" } ?: "no table") + "\n")
        }
        val items = CacheDefs.files(js, "items")?.size; val npcs = CacheDefs.files(js, "npcs")?.size; val objs = CacheDefs.files(js, "objects")?.size
        if (CacheDefs.oldLayout(js)) sb.append("This is a 2009-era cache (build 530 layout, as used by 2009scape): items, npcs and objects are read from indexes 19, 18 and 16. Map keys: action=keys file=<2009scape Server/data/configs/xteas.json>.\n")
        sb.append("Readable: items ${items ?: 0}, npcs ${npcs ?: 0}, objects ${objs ?: 0}, sprites ${js.table(Sprites.INDEX)?.groups?.size ?: 0}, map squares ${js.table(Terrain.INDEX)?.nameHash?.size ?: 0}.\n")
        sb.append("Use find (kind=$KINDS-names) to look up an id by name, export to write JSON/PNG files, pack to build a cache from files. Models, music and animations are not decoded.")
        sb.toString()
    }

    private fun defsOf(js: Js5, kind: String): Map<Int, CacheDefs.Def> {
        val g = CacheDefs.kindGroup(kind) ?: throw IllegalArgumentException("kind must be items, npcs or objects here")
        val files = CacheDefs.files(js, kind) ?: throw IllegalArgumentException("this cache has no $kind definitions")
        val old = CacheDefs.oldLayout(js)
        return files.toSortedMap().mapValues { CacheDefs.parse(kind, it.key, it.value, old) }
    }

    fun find(dir: String, kind: String, query: String, env: AssetEnv, opts: Map<String, String> = emptyMap()): String {
        val attackable = opts["attackable"].orEmpty().lowercase() in setOf("true", "yes", "1")
        if (query.isBlank() && !attackable) return "find needs query (part of a name)."
        open(dir, env).use { js ->
            val defs = defsOf(js, kind)
            val q = query.lowercase()
            if (attackable) {
                if (kind != "npcs" && kind != "npc") return "attackable only works with kind=npcs."
                return attackableSample(defs.values, q, opts)
            }
            val hits = defs.values.filter { it.name.isNotEmpty() && it.name != "null" && it.name.lowercase().contains(q) }
            if (hits.isEmpty()) return "No $kind with '$query' in the name (${defs.size} checked)."
            val sb = StringBuilder("${hits.size} match" + (if (hits.size == 1) "" else "es") + (if (hits.size > 30) ", first 30" else "") + ":\n")
            for (d in hits.take(30)) sb.append(CacheDefs.json(CacheDefs.toMap(d))).append('\n')
            return sb.toString().trimEnd()
        }
    }

    /** True for an NPC a player can fight: it has a body, a name, a combat level and an Attack action. */
    fun isAttackable(d: CacheDefs.Def): Boolean {
        val name = d.name
        if (name.isEmpty() || name == "null") return false
        if (((d.fields["combatLevel"] as? Number)?.toInt() ?: 0) <= 0) return false
        if ((d.fields["models"] as? List<*>).isNullOrEmpty()) return false
        return (d.fields["actions"] as? List<*>)?.any { it is String && it.equals("Attack", true) } == true
    }

    /** A random, repeatable pick of different fighting NPCs (one per name) within a combat-level range; null when none match. */
    fun attackablePick(defs: Collection<CacheDefs.Def>, query: String, opts: Map<String, String>): Pair<List<CacheDefs.Def>, Int>? {
        val lo = opts["min_level"]?.toIntOrNull() ?: 1; val hi = opts["max_level"]?.toIntOrNull() ?: 1000
        val count = (opts["count"]?.toIntOrNull() ?: 20).coerceIn(1, 60)
        val seed = opts["seed"]?.toLongOrNull() ?: 1L
        val pool = defs.filter { isAttackable(it) && ((it.fields["combatLevel"] as Number).toInt() in lo..hi) && (query.isEmpty() || it.name.lowercase().contains(query)) }
        // one NPC per name (lowest id), then a shuffle that depends only on the seed
        val byName = pool.sortedBy { it.id }.distinctBy { it.name.lowercase() }
        if (byName.isEmpty()) return null
        return byName.shuffled(java.util.Random(seed)).take(count) to byName.size
    }

    fun attackableSample(defs: Collection<CacheDefs.Def>, query: String, opts: Map<String, String>): String {
        val lo = opts["min_level"]?.toIntOrNull() ?: 1; val hi = opts["max_level"]?.toIntOrNull() ?: 1000
        val seed = opts["seed"]?.toLongOrNull() ?: 1L
        val (pick, total) = attackablePick(defs, query, opts) ?: return "No fighting NPCs with combat level $lo-$hi (${defs.size} checked)."
        val sb = StringBuilder("${pick.size} different fighting NPCs picked at random from $total (combat level $lo-$hi, seed $seed; the same seed gives the same list, a new seed a new list):\n")
        for (d in pick.sortedBy { (it.fields["combatLevel"] as Number).toInt() }) sb.append(CacheDefs.json(CacheDefs.toMap(d))).append('\n')
        sb.append("Export their models with kind=models ids=\"npc:<id>\" and draw each with game_assets model_render, or use action=enemies to write them all, with pictures, for a game. Where NPCs stand is not in the cache, and no map object is an NPC (type 9 is a diagonal wall). For real positions use game_data (2009scape) or an Old School spawn list such as NPCList_OSRS.json with action=enemies spawns=<file> area=x1,z1,x2,z2; scatter NPCs at random only when the person asks for it.")
        return sb.toString().trimEnd()
    }

    /**
     * Writes a ready-to-use enemy list for a game: out/enemies.json (id, name, combat level, size, and estimated hitpoints and
     * highest hit) and out/npcs/<id>.png, one picture per enemy drawn from its own model. Hitpoints and hits are NOT in the
     * cache, so they are estimated from the combat level and marked so.
     */
    /** Every id of [kind] (npcs or items) with its name and combat level, for moving ids between caches by name. */
    fun names(dir: String, kind: String, env: AssetEnv): Map<Int, Pair<String, Int>> = open(dir, env).use { js ->
        defsOf(js, kind).filter { it.value.name.isNotEmpty() && it.value.name != "null" }.mapValues { it.value.name to ((it.value.fields["combatLevel"] as? Number)?.toInt() ?: 0) }
    }

    private fun npcPicture(js: Js5, fc: FloorColours, d: CacheDefs.Def): ByteArray {
        val swaps = HashMap<Int, Int>()
        (d.fields["recolor"] as? List<*>)?.forEach { p -> val l = p as? List<*>; if (l != null && l.size == 2) swaps[(l[0] as Number).toInt()] = (l[1] as Number).toInt() }
        val meshes = (d.fields["models"] as? List<*> ?: throw IllegalArgumentException("no models")).map { m ->
            val mid = (m as Number).toInt()
            Mesh.fromJson(Models.toJson(Models.decode(mid, js.group(Models.INDEX, mid) ?: throw IllegalArgumentException("model $mid is not in the cache")), swaps) { tex -> fc.texture(tex) })
        }
        val tiles = ((d.fields["size"] as? Number)?.toInt() ?: 1).coerceIn(1, 3)
        return Png.encode(ModelRender.render(meshes, 96 * tiles, 30.0, 15.0, 0.0, PIC_UNIT))
    }

    /**
     * Enemies from a server spawns file (game_data export): every NPC there that fights, with its real stats, spawn points and drops,
     * in the kit's enemies.json form, plus its picture and the names and pictures of everything it drops.
     */
    /** "x1,z1,x2,z2" (tiles) as an area, or null. */
    fun area(s: String): IntArray? = s.split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 4 }?.let { v ->
        intArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3])) }

    /**
     * The rows of a spawns file as NPC rows with a spawns list. A game_data export already has that shape; a flat spawn list
     * (one row per spawn: id, name, x, y, p, as in NPCList_OSRS.json) is grouped by id, its y becoming z. With [area] only the
     * spawns inside it are kept, and NPCs left with none are dropped.
     */
    fun spawnRows(list: List<Map<*, *>>, area: IntArray?): List<Map<*, *>> {
        fun inside(x: Int, z: Int) = area == null || (x >= area[0] && z >= area[1] && x <= area[2] && z <= area[3])
        val flat = list.isNotEmpty() && list.all { it["spawns"] == null && it["x"] is Number && (it["y"] is Number || it["z"] is Number) }
        val rows: List<Map<*, *>> = if (!flat) list else {
            val byId = LinkedHashMap<Int, MutableMap<String, Any?>>()
            for (r in list) {
                val id = (r["id"] as? Number)?.toInt() ?: continue
                val x = (r["x"] as Number).toInt(); val z = ((r["y"] ?: r["z"]) as Number).toInt(); val p = (r["p"] as? Number ?: r["plane"] as? Number)?.toInt() ?: 0
                val row = byId.getOrPut(id) { linkedMapOf("id" to id, "name" to r["name"], "spawns" to ArrayList<List<Int>>(),
                    "anims" to linkedMapOf("stand" to r["standingAnimation"], "walk" to r["walkingAnimation"])) }
                @Suppress("UNCHECKED_CAST") (row["spawns"] as MutableList<List<Int>>).add(listOf(x, z, p, 1))
            }
            byId.values.toList()
        }
        if (area == null) return rows
        return rows.mapNotNull { r ->
            val sp = (r["spawns"] as? List<*>).orEmpty().filter { s -> val l = s as? List<*>; l != null && l.size >= 2 && inside((l[0] as Number).toInt(), (l[1] as Number).toInt()) }
            if (sp.isEmpty()) null else LinkedHashMap<Any?, Any?>(r).also { it["spawns"] = sp }
        }
    }

    fun enemiesFromSpawns(dir: String, spawnsPath: String, out: String, env: AssetEnv, area: IntArray? = null): String {
        val txt = env.read(spawnsPath)?.toString(Charsets.UTF_8) ?: return "No file named $spawnsPath. Write it first with game_data action=export, or add an Old School spawn list (NPCList_OSRS.json)."
        val list = (CacheDefs.parseJson(txt) as? List<*>)?.mapNotNull { it as? Map<*, *> }?.let { spawnRows(it, area) } ?: return "$spawnsPath is not a list of NPCs."
        open(dir, env).use { js ->
            val defs = defsOf(js, "npcs"); val fc = FloorColours(js)
            // a spawn list made from an older cache can give a number that now belongs to another NPC (6257 was a gypsy, now Tempoross)
            val renumbered = ArrayList<String>()
            val matched = list.filter { n ->
                val d = (n["id"] as? Number)?.toInt()?.let { defs[it] } ?: return@filter true
                val listed = (n["name"] as? String)?.takeIf { it.isNotBlank() && it != "null" } ?: return@filter true
                if (d.name.isBlank() || d.name == "null") return@filter true   // nameless (changes with quest progress): handled below
                sameNpc(listed, d.name).also { if (!it) renumbered.add("$listed (now ${d.name}, ${d.id})") }
            }
            val rows = ArrayList<Map<String, Any?>>(); val problems = ArrayList<String>(); var pictures = 0
            val dropIds = java.util.TreeSet<Int>()
            for (n in matched) {
                val id = (n["id"] as? Number)?.toInt() ?: continue
                val d = defs[id] ?: continue
                if (!isAttackable(d)) continue   // only what the game lets you attack (its "Attack" option)
                fun i(k: String, def: Int) = (n[k] as? Number)?.toInt() ?: def
                val level = (d.fields["combatLevel"] as? Number)?.toInt()?.takeIf { it > 0 } ?: 1
                val str = i("strength", 1); val strBonus = ((n["bonuses"] as? List<*>)?.getOrNull(11) as? Number)?.toInt() ?: 0
                var pic = false
                try { if (env.put("$out/npcs/$id.png", npcPicture(js, fc, d))) { pictures++; pic = true } } catch (e: Exception) { problems.add("$id ${d.name}: ${e.message}") }
                (n["drops"] as? Map<*, *>)?.values?.forEach { l -> (l as? List<*>)?.forEach { x -> ((x as? Map<*, *>)?.get("id") as? Number)?.toInt()?.let { if ((x as Map<*, *>)["table"] == null && it > 0) dropIds.add(it) } } }
                rows.add(linkedMapOf("id" to id, "name" to d.name, "combatLevel" to level, "size" to ((d.fields["size"] as? Number)?.toInt() ?: 1),
                    "hitpoints" to i("hitpoints", Math.max(3, Math.round(level * 0.8).toInt() + 3)), "estimated" to (n["hitpoints"] == null), "maxHit" to Math.max(1, Math.floor(0.5 + (str + 8) * (strBonus + 64) / 640.0).toInt()),
                    "attack" to i("attack", 1), "strength" to str, "defence" to i("defence", 1), "attackSpeed" to i("attackSpeed", 4),
                    "aggressive" to (n["aggressive"] == true), "respawnTicks" to i("respawnTicks", 50), "examine" to n["examine"],
                    "spawns" to n["spawns"], "drops" to n["drops"], "anims" to n["anims"], "picture" to pic))
            }
            // everyone else who stands there (shopkeepers, quest givers, townsfolk) goes into data/content.json as people, with the
            // ground items next to spawns.json; content.js lets the player talk to them and take the items
            val people = ArrayList<Map<String, Any?>>(); val personIds = java.util.TreeSet<Int>()
            for (n in matched) {
                val id = (n["id"] as? Number)?.toInt() ?: continue
                val d = defs[id] ?: continue
                if (isAttackable(d) || d.name.isEmpty() || d.name == "null" || (d.fields["models"] as? List<*>).isNullOrEmpty()) continue
                for (s in (n["spawns"] as? List<*>).orEmpty()) {
                    val t = (s as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: continue
                    if (t.size < 3) continue
                    people.add(linkedMapOf("id" to id, "name" to d.name, "x" to t[0], "z" to t[1], "plane" to t[2], "walk" to (t.getOrElse(3) { 0 } == 1),
                        "actions" to (d.fields["actions"] as? List<*>)?.filterIsInstance<String>(), "examine" to n["examine"]))
                    personIds.add(id)
                }
            }
            var personPics = 0
            for (id in personIds) try { if (env.put("$out/npcs/$id.png", npcPicture(js, fc, defs[id]!!))) personPics++ } catch (e: Exception) { }
            if (personIds.isNotEmpty()) try { npcs3d(js, personIds.joinToString(","), out, env, emptyMap()) } catch (e: Exception) { }
            val ground = (env.read(spawnsPath.substringBeforeLast('/', "") + "/ground_items.json")?.toString(Charsets.UTF_8)?.let { CacheDefs.parseJson(it) } as? List<*>).orEmpty()
            val groundIds = ground.mapNotNull { ((it as? Map<*, *>)?.get("id") as? Number)?.toInt() }.toSet()
            mergeContent(out, env, linkedMapOf("people" to people, "groundItems" to ground))
            if (groundIds.isNotEmpty()) dropIds.addAll(groundIds)
            if (rows.isEmpty()) return "No NPC in $spawnsPath fights (or none is in this cache)."
            if (!env.put("$out/enemies.json", CacheDefs.json(rows).toByteArray())) return "Could not write $out/enemies.json."
            val extraAnims = rows.flatMap { r -> (r["anims"] as? Map<*, *>)?.values?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList() }.distinct().joinToString(",")
            try { npcs3d(js, rows.joinToString(",") { it["id"].toString() }, out, env, mapOf("anims" to extraAnims)) } catch (e: Exception) { }
            val ids = (KIT_ITEMS.split(',').map { it.toInt() } + dropIds).distinct().joinToString(",")
            export(dir, "items", ids, out, env, mapOf("merge" to "true")); export(dir, "icons", ids, out, env)
            return "Wrote ${rows.size} enemies with their real stats, spawn points and drops to $out/enemies.json and $pictures pictures to $out/npcs; " +
                "${people.size} people (${personIds.size} kinds, $personPics pictures, 3D models) and ${ground.size} ground items to $out/content.json; " +
                "names and pictures of ${dropIds.size} dropped items added to $out/items.json and $out/icons. The game kit places them where they stand in the real game." +
                (if (problems.isNotEmpty()) " No picture for: ${problems.take(4).joinToString("; ")}." else "") +
                (if (renumbered.isNotEmpty()) " Left out ${renumbered.size} whose number belongs to another NPC in this cache: ${renumbered.take(6).joinToString("; ")}." else "")
        }
    }

    /** Whether a spawn list's NPC name and the cache's name are the same NPC, allowing renames such as Traiborn → Wizard Traiborn. */
    fun sameNpc(listed: String, cache: String): Boolean {
        fun compact(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
        fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }.toSet()
        return compact(listed) == compact(cache) || words(listed).intersect(words(cache)).isNotEmpty()
    }


    /** Sets sections of [out]/content.json (the game's content for content.js), keeping the others. */
    fun mergeContent(out: String, env: AssetEnv, sections: Map<String, Any?>): Boolean {
        val now = (env.read("$out/content.json")?.toString(Charsets.UTF_8)?.let { try { CacheDefs.parseJson(it) } catch (e: Exception) { null } } as? Map<*, *>)
            ?.entries?.associateTo(LinkedHashMap<String, Any?>()) { it.key.toString() to it.value } ?: LinkedHashMap()
        now.putAll(sections)
        return env.put("$out/content.json", CacheDefs.json(now).toByteArray())
    }

    fun enemies(dir: String, out: String, env: AssetEnv, opts: Map<String, String>): String {
        open(dir, env).use { js ->
            val defs = defsOf(js, "npcs")
            val (pick, total) = attackablePick(defs.values, opts["query"].orEmpty().lowercase(), opts) ?: return "No fighting NPCs match (${defs.size} checked)."
            val fc = FloorColours(js)
            val rows = ArrayList<Map<String, Any?>>(); val problems = ArrayList<String>(); var pictures = 0
            for (d in pick.sortedBy { (it.fields["combatLevel"] as Number).toInt() }) {
                val level = (d.fields["combatLevel"] as Number).toInt()
                var pic = false
                try {
                    val swaps = HashMap<Int, Int>()
                    (d.fields["recolor"] as? List<*>)?.forEach { p -> val l = p as? List<*>; if (l != null && l.size == 2) swaps[(l[0] as Number).toInt()] = (l[1] as Number).toInt() }
                    val meshes = (d.fields["models"] as List<*>).map { m ->
                        val mid = (m as Number).toInt()
                        Mesh.fromJson(Models.toJson(Models.decode(mid, js.group(Models.INDEX, mid) ?: throw IllegalArgumentException("model $mid is not in the cache")), swaps) { tex -> fc.texture(tex) })
                    }
                    val tiles = ((d.fields["size"] as? Number)?.toInt() ?: 1).coerceIn(1, 3)   // the picture is 2 tiles wide per tile of size, drawn at the game's real scale
                    if (env.put("$out/npcs/${d.id}.png", Png.encode(ModelRender.render(meshes, 96 * tiles, 30.0, 15.0, 0.0, PIC_UNIT)))) { pictures++; pic = true }
                } catch (e: Exception) { problems.add("${d.id} ${d.name}: ${e.message}") }
                rows.add(linkedMapOf("id" to d.id, "name" to d.name, "combatLevel" to level, "size" to ((d.fields["size"] as? Number)?.toInt() ?: 1),
                    "hitpoints" to Math.max(3, Math.round(level * 0.8).toInt() + 3), "maxHit" to Math.max(1, level / 8 + 1), "estimated" to true, "picture" to pic))
            }
            if (!env.put("$out/enemies.json", CacheDefs.json(rows).toByteArray())) return "Could not write $out/enemies.json."
            val m3 = try { npcs3d(js, rows.joinToString(",") { it["id"].toString() }, out, env, emptyMap()) } catch (e: Exception) { "" }
            return (if (m3.startsWith("Wrote")) m3.substringBefore(" (") + " (3D, animated). " else "") + "Wrote ${rows.size} different fighting enemies (picked at random from $total, seed ${opts["seed"] ?: "1"}) to $out/enemies.json and $pictures pictures to $out/npcs. " +
                "Hitpoints and the highest hit are estimated from the combat level (the cache does not hold them), and the file says so. Where they stand is for the game to decide." +
                (if (problems.isNotEmpty()) " No picture for: ${problems.take(4).joinToString("; ")}." else "")
        }
    }

    fun place(name: String): String {
        val p = Places.find(name)
        if (p != null) return Places.describe(p)
        return "No place called '$name'. Known places: ${Places.ALL.joinToString(", ") { it.name }}. For any other spot pass map squares (tile / 64) to scaffold or export."
    }

    /** "0-10,15,30-32" or "all" to a sorted id list; null for all. */
    fun parseIds(s: String): List<Int>? {
        val t = s.trim().lowercase()
        if (t.isEmpty() || t == "all") return null
        val out = java.util.TreeSet<Int>()
        for (part in t.split(',', ' ', ';').filter { it.isNotEmpty() }) {
            val r = part.split('-')
            val lo = r[0].toIntOrNull() ?: throw IllegalArgumentException("'$part' is not a number or range")
            val hi = if (r.size > 1) r[1].toIntOrNull() ?: throw IllegalArgumentException("'$part' is not a range") else lo
            if (hi < lo || hi - lo > 1_000_000) throw IllegalArgumentException("'$part' is not a valid range")
            for (i in lo..hi) out.add(i)
        }
        return out.toList()
    }

    /** Floor colours as 0xRRGGBB by floor id; a floor that is a texture (water) gets the average colour of its picture. */
    private class FloorColours(val js: Js5) {
        private val texFiles: Map<Int, ByteArray> by lazy { try { js.files(9, 0) ?: emptyMap() } catch (e: Exception) { emptyMap() } }
        private val texAvg = HashMap<Int, Int?>()
        // 2009-era caches build textures from recipes; their average colours sit in a list in index 26 (16-bit HSL, one per texture)
        private val oldAverages: IntArray? by lazy {
            try {
                if (!CacheDefs.oldLayout(js)) return@lazy null
                val r = Js5.Reader(js.files(26, 0)?.get(0) ?: js.group(26, 0) ?: return@lazy null)
                val n = r.u16(); val defined = BooleanArray(n) { r.u8() == 1 }
                val k = defined.count { it }
                val repeating = BooleanArray(n); for (i in 0 until n) if (defined[i]) repeating[i] = r.u8() == 1
                r.skip(k * 7)   // opaque, low detail, flipped, scroll speed, brightness, material, animation: one byte each
                // the game paints a floor with its texture's colour only for repeating textures; others keep the floor's own colour
                val all = IntArray(n) { if (defined[it]) Models.rgb(r.u16()) else -1 }
                oldAll = all
                IntArray(n) { if (all[it] >= 0 && repeating[it]) all[it] else -1 }
            } catch (e: Exception) { null }
        }
        private var oldAll: IntArray? = null
        /** For 3D scenes: a texture's average colour in either cache layout (a 2009 face uses it only when its own colour is black). */
        fun sceneTexture(id: Int): Int? = if (oldAverages != null) oldAll?.getOrNull(id)?.takeIf { it >= 0 } else texture(id)
        val old: Boolean get() = oldAverages != null
        // Model faces: in 2009-era caches a textured face keeps its own colour (the texture only adds shine), so no swap there.
        fun texture(id: Int): Int? = if (oldAverages != null) null else texAvg.getOrPut(id) {
            try {
                val r = Js5.Reader(texFiles[id] ?: return@getOrPut null)
                r.u16(); r.u8(); val n = r.u8(); if (n < 1) return@getOrPut null
                val sprite = r.u16()
                val f = Sprites.decode(js.group(Sprites.INDEX, sprite) ?: return@getOrPut null).frames[0]
                var rr = 0L; var gg = 0L; var bb = 0L; var cnt = 0
                for (p in f.argb) if (p ushr 24 != 0) { rr += (p shr 16) and 255; gg += (p shr 8) and 255; bb += p and 255; cnt++ }
                if (cnt == 0) null else ((rr / cnt).toInt() shl 16) or ((gg / cnt).toInt() shl 8) or (bb / cnt).toInt()
            } catch (e: Exception) { null }
        }
        private fun table(group: Int): Map<Int, Int> {
            val files = js.files(CacheDefs.CONFIG_INDEX, group) ?: return emptyMap()
            val m = HashMap<Int, Int>()
            val avg = oldAverages
            if (avg != null) {
                // 2009-era floors: a textured overlay is drawn with its texture (wood, stone, water), so its average colour wins
                for ((id, b) in files) {
                    val (colour, tex) = CacheDefs.floor530(b)
                    val c = if (group == CacheDefs.OVERLAYS && tex >= 0) avg.getOrNull(tex)?.takeIf { it >= 0 } ?: colour else colour
                    if (c >= 0 && c != 0xFF00FF) m[id] = c
                }
                return m
            }
            for ((id, b) in files) {
                var c = CacheDefs.floorColour(group, b)
                if (group == CacheDefs.OVERLAYS && c == 0xFF00FF) c = -1   // the game's "no colour" marker: use the texture's colour, or let the ground underneath show
                if (c < 0 && group == CacheDefs.OVERLAYS) c = CacheDefs.floorTexture(b).takeIf { it >= 0 }?.let { texture(it) } ?: -1
                if (c >= 0) m[id] = c
            }
            return m
        }
        val under: Map<Int, Int> by lazy { table(CacheDefs.UNDERLAYS) }
        val over: Map<Int, Int> by lazy { table(CacheDefs.OVERLAYS) }
    }

    fun export(dir: String, kind: String, ids: String, out: String, env: AssetEnv, opts: Map<String, String> = emptyMap()): String {
        open(dir, env).use { js ->
            when (kind) {
                "items", "npcs", "objects", "item", "npc", "object", "locs" -> {
                    val k = when (kind) { "item" -> "items"; "npc" -> "npcs"; "object", "locs" -> "objects"; else -> kind }
                    val sel = parseIds(ids)?.toSet()
                    val defs = defsOf(js, k).values.filter { sel == null || it.id in sel }
                    val path = "$out/$k.json"
                    // merge=true keeps what the file already lists (a game's full items.json) and adds or updates these ids
                    val kept = if (opts["merge"] != "true") emptyList() else {
                        val ids = defs.map { it.id }.toSet()
                        (env.read(path)?.toString(Charsets.UTF_8)?.let { try { CacheDefs.parseJson(it) } catch (e: Exception) { null } } as? List<*>).orEmpty()
                            .filter { e -> ((e as? Map<*, *>)?.get("id") as? Number)?.toInt().let { it == null || it !in ids } }
                    }
                    val all: List<Any?> = kept + defs.map { CacheDefs.toMap(it) }
                    val sb = StringBuilder("[\n")
                    all.forEachIndexed { i, d -> if (i > 0) sb.append(",\n"); CacheDefs.writeJson(sb, d) }
                    sb.append("\n]\n")
                    if (!env.put(path, sb.toString().toByteArray())) return "Could not write $path."
                    val named = defs.count { it.name.isNotEmpty() && it.name != "null" }
                    return "Wrote $path: ${defs.size} $k ($named with names)" + (if (kept.isNotEmpty()) ", keeping ${kept.size} it already had" else "") + ". Search it with search_code or read parts of it; names and options only, no models."
                }
                "floors", "floor" -> {
                    val fc = FloorColours(js)
                    fun hex(m: Map<Int, Int>) = m.toSortedMap().entries.associate { (id, c) -> id.toString() to String.format("#%06x", c) }
                    val u = hex(fc.under); val o = hex(fc.over)
                    val path = "$out/floors.json"
                    if (!env.put(path, CacheDefs.json(mapOf("underlays" to u, "overlays" to o)).toByteArray())) return "Could not write $path."
                    return "Wrote $path: ${u.size} underlay and ${o.size} overlay colours (id to #rrggbb; water and other textured floors use the average colour of their texture). Map files list underlay and overlay ids per tile."
                }
                "scene" -> return scene(js, dir, ids, out, env)
                "anims", "anim", "animations" -> return anims(js, ids, out, env)
                "npcs3d", "npc3d" -> return npcs3d(js, ids, out, env, opts)
                "held" -> return held(js, ids, out, env)
                "ground" -> {
                    val t = js.table(Terrain.INDEX) ?: return "This cache has no maps."
                    val squares = squares(ids) ?: return "ground needs ids as map squares, e.g. \"50,50\" or \"50,50;51,50\" (x,z). Lumbridge is 50,50."
                    if (squares.size > 40) return "At most 40 ground pictures per call."
                    val scale = opts["scale"]?.toIntOrNull()?.coerceIn(1, 8) ?: 4
                    val fc = FloorColours(js)
                    val src = Ground.Source({ sx, sz -> t.groupByName(Terrain.name(sx, sz))?.let { g -> js.group(Terrain.INDEX, g)?.let { d -> try { Terrain.decode(sx, sz, d) } catch (e: Exception) { null } } } }, { fc.under[it - 1] }, { fc.over[it - 1] })   // map tiles store the definition id + 1 (0 = none)
                    var done = 0; val missing = ArrayList<String>()
                    for ((x, z) in squares) {
                        try {
                            val img = Ground.render(x, z, scale, src)
                            if (env.put("$out/ground/g${x}_$z.png", Png.encode(img))) done++
                        } catch (e: IllegalArgumentException) { missing.add("$x,$z") }
                    }
                    return "Wrote $done ground pictures to $out/ground (g<x>_<z>.png, ${64 * scale} pixels square, ${scale} pixels per tile, north at the top). " +
                        "They are lit by the real hills and blend the floor colours. Draw one under a map square and put objects and characters on top; line squares up by their x,z. Look at them with look_at_images." +
                        (if (missing.isNotEmpty()) " Not in this cache: ${missing.joinToString("; ")}." else "")
                }
                "icons", "icon" -> {
                    val sel = parseIds(ids) ?: return "icons needs item ids, e.g. \"1351,1511,995\" or a range like \"1275-1290\" (at most 300)."
                    if (sel.size > 300) return "At most 300 icons per call."
                    val size = opts["size"]?.toIntOrNull()?.coerceIn(16, 256) ?: 64
                    val defs = defsOf(js, "items"); val fc = FloorColours(js)
                    var done = 0; val problems = ArrayList<String>()
                    for (id in sel) {
                        try {
                            val d = defs[id] ?: throw IllegalArgumentException("does not exist")
                            if (d.fields["noteTemplate"] != null || d.fields["placeholderTemplate"] != null) throw IllegalArgumentException("is a bank note or placeholder: draw the item it stands for")
                            val mid = (d.fields["model"] as? Number)?.toInt() ?: throw IllegalArgumentException("has no 3D model")
                            val swaps = HashMap<Int, Int>()
                            (d.fields["recolor"] as? List<*>)?.forEach { p -> val l = p as? List<*>; if (l != null && l.size == 2) swaps[(l[0] as Number).toInt()] = (l[1] as Number).toInt() }
                            val model = Models.decode(mid, js.group(Models.INDEX, mid) ?: throw IllegalArgumentException("model $mid is not in the cache"))
                            val mesh = Mesh.fromJson(Models.toJson(model, swaps) { fc.texture(it) })
                            fun ang(k: String) = ((d.fields[k] as? Number)?.toDouble() ?: 0.0) * 360.0 / 2048.0
                            // the game turns models with y pointing down; ours points up, so tilt and roll run the other way
                            val img = ModelRender.render(listOf(mesh), size, ang("yan2d"), -ang("xan2d"), -ang("zan2d"))
                            if (env.put("$out/icons/$id.png", Png.encode(img))) done++
                        } catch (e: Exception) { problems.add("$id: ${e.message}") }
                    }
                    return "Wrote $done icons to $out/icons (<item id>.png, ${size}x$size, each drawn from the item's own model at the angle and tilt the game uses for its inventory picture)." +
                        (if (problems.isNotEmpty()) " Problems: ${problems.take(6).joinToString("; ")}${if (problems.size > 6) " and ${problems.size - 6} more" else ""}." else "") +
                        " Look at them with look_at_images; item definitions list the ids."
                }
                "player", "character" -> {
                    val kits = IdKit.choose(ids) ?: return "player needs kit numbers (e.g. \"0,10,18,26,33,36,42\") or male or female. Kits are numbered in config index 2, group 3."
                    val files = js.files(CacheDefs.CONFIG_INDEX, IdKit.GROUP) ?: return "This cache has no identity kits."
                    val fc = FloorColours(js)
                    val female = ids.trim().lowercase().let { it == "female" || it == "woman" } || opts["female"].orEmpty().lowercase() in setOf("true", "yes", "1")
                    val wornIds = opts["worn"].orEmpty().let { if (it.isBlank()) emptyList() else parseIds(it) ?: emptyList() }
                    val worn = ArrayList<CacheDefs.Def>(); val hiddenParts = HashSet<Int>(); val bad = ArrayList<String>()
                    if (wornIds.isNotEmpty()) {
                        val items = defsOf(js, "items")
                        for (w in wornIds) {
                            val d = items[w]
                            val models = d?.fields?.get(if (female) "wornModelsFemale" else "wornModels") as? List<*> ?: (d?.fields?.get("wornModels") as? List<*>)
                            if (d == null || models.isNullOrEmpty()) { bad.add("$w (${d?.name ?: "no such item"}: nothing to wear)"); continue }
                            worn.add(d)
                            // what the worn item covers: body parts of the kit (0 hair, 1 jaw, 2 torso, 3 arms, 4 hands, 5 legs, 6 feet)
                            val slot = (d.fields["wearSlot"] as? Number)?.toInt()
                            when (slot) { 0 -> hiddenParts.add(0); 4 -> hiddenParts.add(2); 7 -> hiddenParts.add(5); 9 -> hiddenParts.add(4); 10 -> hiddenParts.add(6) }
                            for (k in listOf("hidesSlot", "hidesSlot2")) when ((d.fields[k] as? Number)?.toInt()) { 6 -> hiddenParts.add(3); 8 -> hiddenParts.add(0); 11 -> hiddenParts.add(1); 0 -> {} }
                        }
                    }
                    fun recoloured(m: Models.Model, swaps: Map<Int, Int>): Models.Model =
                        if (swaps.isEmpty()) m else Models.Model(m.id, m.x, m.y, m.z, m.a, m.b, m.c, IntArray(m.colour.size) { swaps[m.colour[it]] ?: m.colour[it] }, m.alpha, m.texture, m.vertexGroup)
                    val parts = ArrayList<Models.Model>(); val used = ArrayList<String>()
                    for (k in kits) {
                        val kit = IdKit.parse(k, files[k] ?: return "kit $k does not exist (this cache has ${files.size} kits)")
                        if (kit.part in hiddenParts) continue
                        for (m in kit.models) parts.add(recoloured(Models.decode(m, js.group(Models.INDEX, m) ?: return "model $m of kit $k is not in the cache"), kit.recolor))
                        used.add("$k (part ${kit.part})")
                    }
                    for (d in worn) {
                        val swaps = HashMap<Int, Int>()
                        (d.fields["recolor"] as? List<*>)?.forEach { p -> val l = p as? List<*>; if (l != null && l.size == 2) swaps[(l[0] as Number).toInt()] = (l[1] as Number).toInt() }
                        val models = (d.fields[if (female) "wornModelsFemale" else "wornModels"] as? List<*> ?: d.fields["wornModels"] as List<*>).map { (it as Number).toInt() }
                        for (m in models) parts.add(recoloured(Models.decode(m, js.group(Models.INDEX, m) ?: return "model $m of item ${d.id} is not in the cache"), swaps))
                        used.add("item ${d.id} ${d.name}")
                    }
                    if (parts.isEmpty()) return "Those kits have no models."
                    val merged = Models.merge(parts[0].id, parts)
                    val path = "$out/models/player.json"
                    if (!env.put(path, Models.toJson(merged) { fc.texture(it) }.toByteArray())) return "Could not write $path."
                    // the flat picture the game kit draws for the player, at the same scale and angle as the enemy pictures
                    val pic = try {
                        val mesh = Mesh.fromJson(Models.toJson(merged) { fc.texture(it) })
                        if (env.put("$out/player.png", Png.encode(ModelRender.render(listOf(mesh), 96, 30.0, 15.0, 0.0, PIC_UNIT)))) " Also wrote $out/player.png, the player's picture (the game kit draws it)." else " Could not write $out/player.png."
                    } catch (e: Exception) { " No picture of the player: ${e.message}." }
                    return "Wrote $path: a character made of ${parts.size} models: ${used.joinToString(", ")}. Units are the game's (128 per tile, a player is about 190 tall), y points up; scale by 1/128 for tile units.$pic " +
                        "To draw it another way use game_assets model_render (trim, size 128-256) or show it in 3D; its colours are the kit's and the worn items' own." +
                        (if (bad.isNotEmpty()) " Not worn: ${bad.joinToString("; ")}." else "") + (if (hiddenParts.isNotEmpty()) " Body parts covered by the gear were left out." else "")
                }
                "models", "model" -> {
                    val tokens = ids.split(',', ';', ' ').filter { it.isNotBlank() }
                    if (tokens.isEmpty()) return "models needs ids: model numbers (5412), or item:4151, npc:3, object:1276 to get the model of a definition with its colours."
                    if (tokens.size > 200) return "At most 200 models per call."
                    var done = 0; val problems = ArrayList<String>()
                    val cache = HashMap<String, Map<Int, CacheDefs.Def>>()
                    val texFiles: Map<Int, ByteArray> = try { js.files(9, 0) ?: emptyMap() } catch (e: Exception) { emptyMap() }
                    val texAvg = HashMap<Int, Int?>()
                    fun texColour(id: Int): Int? = texAvg.getOrPut(id) {
                        try {
                            val r = Js5.Reader(texFiles[id] ?: return@getOrPut null)
                            r.u16(); r.u8(); val n = r.u8(); if (n < 1) return@getOrPut null
                            val sprite = r.u16()
                            val f = Sprites.decode(js.group(Sprites.INDEX, sprite) ?: return@getOrPut null).frames[0]
                            var rr = 0L; var gg = 0L; var bb = 0L; var cnt = 0
                            for (p in f.argb) if (p ushr 24 != 0) { rr += (p shr 16) and 255; gg += (p shr 8) and 255; bb += p and 255; cnt++ }
                            if (cnt == 0) null else ((rr / cnt).toInt() shl 16) or ((gg / cnt).toInt() shl 8) or (bb / cnt).toInt()
                        } catch (e: Exception) { null }
                    }
                    for (tok in tokens) {
                        try {
                            val kind2 = tok.substringBefore(':', "")
                            val num = tok.substringAfter(':').toIntOrNull() ?: throw IllegalArgumentException("'$tok' is not a model number")
                            val modelIds: List<Int>; val swaps = HashMap<Int, Int>()
                            if (kind2.isEmpty()) modelIds = listOf(num)
                            else {
                                val k = when (kind2) { "item" -> "items"; "npc" -> "npcs"; "object", "obj" -> "objects"; else -> throw IllegalArgumentException("'$tok': use item:, npc: or object:") }
                                val def = cache.getOrPut(k) { defsOf(js, k) }[num] ?: throw IllegalArgumentException("$tok does not exist")
                                @Suppress("UNCHECKED_CAST") val ms = (def.fields["models"] as? List<Number>)?.map { it.toInt() } ?: (def.fields["model"] as? Number)?.let { listOf(it.toInt()) } ?: emptyList()
                                if (ms.isEmpty()) throw IllegalArgumentException("$tok has no 3D model")
                                modelIds = ms
                                (def.fields["recolor"] as? List<*>)?.forEach { p -> val l = p as? List<*>; if (l != null && l.size == 2) swaps[(l[0] as Number).toInt()] = (l[1] as Number).toInt() }
                            }
                            val parts = modelIds.map { mid -> Models.decode(mid, js.group(Models.INDEX, mid) ?: throw IllegalArgumentException("model $mid is not in the cache")) }
                            val merged = Models.merge(modelIds[0], parts)
                            val file = tok.replace(':', '_') + ".json"
                            if (env.put("$out/models/$file", Models.toJson(merged, swaps) { texColour(it) }.toByteArray())) done++
                        } catch (e: Exception) { problems.add("$tok: ${e.message}") }
                    }
                    return "Wrote $done models to $out/models (JSON: vertices, faces, colors; y points up)." + (if (problems.isNotEmpty()) " Problems: ${problems.take(5).joinToString("; ")}." else "") +
                        " View them with action=scaffold kind=viewer."
                }
                "sprites", "sprite" -> {
                    val t = js.table(Sprites.INDEX) ?: return "This cache has no sprites."
                    val want = parseIds(ids)?.let { l -> val s = l.toSet(); t.groups.filter { it in s } } ?: t.groups.toList()
                    if (want.isEmpty()) return "No sprites with those ids."
                    val take = want.take(MAX_SPRITES)
                    var frames = 0; var bad = 0
                    for (g in take) {
                        val d = js.group(Sprites.INDEX, g)
                        val sheet = try { d?.let { Sprites.decode(it) } } catch (e: Exception) { null }
                        if (sheet == null) { bad++; continue }
                        for ((n, f) in sheet.frames.withIndex()) {
                            val name = if (sheet.frames.size == 1) "$g.png" else "${g}_$n.png"
                            if (env.put("$out/sprites/$name", Sprites.toPng(f))) frames++
                        }
                    }
                    return "Wrote $frames PNG files to $out/sprites (sprite ids ${take.first()}-${take.last()}, ${take.size} sprites${if (bad > 0) ", $bad unreadable" else ""})." +
                        (if (want.size > take.size) " ${want.size - take.size} more not exported: call again with ids from ${want[take.size]}." else "") +
                        " Names are the sprite ids; use look_at_images on the folder to see them."
                }
                "maps", "map" -> {
                    val t = js.table(Terrain.INDEX) ?: return "This cache has no maps."
                    val squares = squares(ids) ?: return "maps needs ids as map squares, e.g. \"50,50\" or \"50,50;51,50\" (x,z). Lumbridge is 50,50."
                    if (squares.size > MAX_MAPS) return "At most $MAX_MAPS map squares per call."
                    var done = 0; val missing = ArrayList<String>(); val noObjects = ArrayList<String>()
                    val keyMap = loadKeys(dir, env)
                    val objDefs: Map<Int, CacheDefs.Def> = try { defsOf(js, "objects") } catch (e: Exception) { emptyMap() }
                    for ((x, z) in squares) {
                        val gid = t.groupByName(Terrain.name(x, z))
                        val d = gid?.let { js.group(Terrain.INDEX, it) }
                        if (d == null) { missing.add("$x,$z"); continue }
                        val ter = Terrain.decode(x, z, d)
                        val m = LinkedHashMap<String, Any?>(); m["x"] = x; m["z"] = z
                        m["legend"] = "planes: # blocked, = bridge, . open; 64 rows per plane, top row is the north edge, columns run west to east; each square is 64x64 tiles. " +
                            "collision: one character per tile (base 32): add N=1 E=2 S=4 W=8 for a wall on that edge, 16 for a tile that cannot be entered. objects: [id, x, z, plane, type, rotation]; type 0-3 walls, 10-11 solid objects, 22 floor decoration. " +
                            "heights, flags, underlays, overlays, overlayShapes: one list per floor (0-3) of 4096 numbers, index = x * 64 + z; heights are in client units, up positive, 128 units = one tile, and a floor above with an empty list is exactly 240 units above the floor below; flags add 1 blocked, 2 bridge (the tile is drawn one floor down), 4 under a roof (hide the roof when the player stands there); a floor with no data is an empty list"
                        m["planes"] = (0 until 4).map { ter.grid(it) }
                        m["underlay"] = (0 until 4096).map { ter.underlay[it] }       // ground 0 only, index = x * 64 + z, ids in floors.json
                        m["overlay"] = (0 until 4096).map { ter.overlay[it] }
                        m["height"] = (0 until 4096).map { ter.height[it] }
                        // every floor: real heights (up positive, 128 units = one tile) and the floor colours; a floor with nothing to say is an empty list
                        val hs = ter.heights()
                        fun perPlane(a: IntArray) = (0 until 4).map { p -> if ((0 until 4096).all { a[p * 4096 + it] == 0 }) emptyList() else (0 until 4096).map { a[p * 4096 + it] } }
                        m["heights"] = (0 until 4).map { p -> if (p > 0 && (0 until 4096).all { hs[p * 4096 + it] == hs[(p - 1) * 4096 + it] + 240 }) emptyList() else (0 until 4096).map { hs[p * 4096 + it] } }
                        m["flags"] = perPlane(ter.settings)
                        m["underlays"] = perPlane(ter.underlay)
                        m["overlays"] = perPlane(ter.overlay)
                        m["overlayShapes"] = perPlane(ter.overlayShape)
                        val key = keyMap[Locs.name(x, z)]
                        val lg = t.groupByName(Locs.name(x, z))
                        val ld = lg?.let { js.group(Terrain.INDEX, it, key) }
                        if (ld != null) {
                            try {
                                val locs = Locs.decode(ld)
                                m["objects"] = locs.map { listOf(it.id, it.x, it.z, it.plane, it.type, it.rot) }
                                val fl = Collision.build(ter, locs, { id -> objDefs[id]?.fields?.get("solid") != false }, { id -> ((objDefs[id]?.fields?.get("width") as? Int) ?: 1) to ((objDefs[id]?.fields?.get("length") as? Int) ?: 1) })
                                m["collision"] = (0 until 4).map { Collision.rows(fl[it]) }
                            } catch (e: Exception) { noObjects.add("$x,$z") }
                        } else if (lg != null) noObjects.add("$x,$z")
                        if (env.put("$out/maps/m${x}_$z.json", CacheDefs.json(m).toByteArray())) done++
                    }
                    return "Wrote $done map squares to $out/maps." + (if (missing.isNotEmpty()) " Not in this cache: ${missing.joinToString("; ")}." else "") +
                        (if (noObjects.isNotEmpty()) " No objects for ${noObjects.take(8).joinToString("; ")}${if (noObjects.size > 8) " and ${noObjects.size - 8} more" else ""}: run action=keys with the OpenRS2 cache id first (the number in the zip's file name), then export again." else " Each file has the terrain grid, the objects and a collision grid ready for pathfinding.")
                }
                else -> return "kind must be one of: $KINDS, models, floors, ground, icons, player."
            }
        }
    }

    const val MAX_PLACE_SQUARES = 9
    const val MAX_PLACE_MODELS = 2000

    /**
     * Pictures of every object that has a model on the given squares (a town is its 3 by 3 squares), and where each one stands:
     * objects/<id>.png and places/m<x>_<z>.json (one row per object: id, x, z, plane, width, length, under a roof 0/1, turn 0-3 quarter turns).
     * The game kit draws these instead of coloured blocks. Objects with no model are counted, not drawn.
     */
    fun placeObjects(dir: String, ids: String, out: String, env: AssetEnv, opts: Map<String, String>): String {
        val town = Places.find(ids)
        val squares: List<Pair<Int, Int>> = if (town != null) (-1..1).flatMap { dx -> (-1..1).map { dz -> (town.sx + dx) to (town.sz + dz) } }
            else (squares(ids) ?: return "place_objects needs a town (e.g. Falador, Lumbridge) or map squares such as \"46,52;47,52\".")
        if (squares.size > MAX_PLACE_SQUARES) return "At most $MAX_PLACE_SQUARES map squares per call (a town is 9)."
        val size = (opts["size"]?.toIntOrNull() ?: 96).coerceIn(32, 256)
        open(dir, env).use { js ->
            val t = js.table(Terrain.INDEX) ?: return "This cache has no maps."
            val keyMap = loadKeys(dir, env)
            val objDefs = defsOf(js, "objects")
            val fc = FloorColours(js)
            val wanted = LinkedHashSet<Int>(); val problems = ArrayList<String>()
            var rows = 0; var noModel = 0; var skippedModels = 0; var done = 0
            val squareRows = ArrayList<Triple<Int, Int, List<Pair<Int, String>>>>()
            for ((x, z) in squares) {
                val lg = t.groupByName(Locs.name(x, z))
                if (lg == null) { problems.add("$x,$z: no objects in the cache"); continue }
                val ld = js.group(Terrain.INDEX, lg, keyMap[Locs.name(x, z)])
                if (ld == null) { problems.add("$x,$z: its objects are encrypted; run action=keys with the cache number first"); continue }
                val tg = t.groupByName(Terrain.name(x, z))?.let { js.group(Terrain.INDEX, it) }
                if (tg == null) { problems.add("$x,$z: no ground data"); continue }
                val ter = Terrain.decode(x, z, tg)
                val locs = try { Locs.decode(ld) } catch (e: Exception) { problems.add("$x,$z: objects could not be read (${e.message})"); continue }
                val rowsHere = ArrayList<Pair<Int, String>>()
                for (l in locs) {
                    if (l.x !in 0..63 || l.z !in 0..63 || l.plane !in 0..3) continue
                    val d = objDefs[l.id] ?: continue
                    val models = (d.fields["models"] as? List<*>)?.map { (it as Number).toInt() }
                    if (models.isNullOrEmpty()) { noModel++; continue }
                    if (wanted.size >= MAX_PLACE_MODELS && l.id !in wanted) { skippedModels++; continue }
                    wanted.add(l.id)
                    val w = ((d.fields["width"] as? Number)?.toInt() ?: 1).coerceIn(1, 8)
                    val len = ((d.fields["length"] as? Number)?.toInt() ?: 1).coerceIn(1, 8)
                    val roof = if (ter.settings[l.plane * 4096 + l.x * 64 + l.z] and 4 != 0) 1 else 0
                    rowsHere.add(l.id to "[${l.id},${l.x},${l.z},${l.plane},$w,$len,$roof,${l.rot and 3}]")
                }
                squareRows.add(Triple(x, z, rowsHere))
            }
            // one picture per object id: its models joined, in its own colours, at a fixed three-quarter view
            val failedIds = ArrayList<String>()
            for (id in wanted) {
                try {
                    val d = objDefs[id] ?: throw IllegalArgumentException("does not exist")
                    val swaps = HashMap<Int, Int>()
                    (d.fields["recolor"] as? List<*>)?.forEach { p -> val l = p as? List<*>; if (l != null && l.size == 2) swaps[(l[0] as Number).toInt()] = (l[1] as Number).toInt() }
                    val meshes = ((d.fields["models"] as? List<*>) ?: emptyList<Any>()).map { m ->
                        val mid = (m as Number).toInt()
                        val model = Models.decode(mid, js.group(Models.INDEX, mid) ?: throw IllegalArgumentException("model $mid is not in the cache"))
                        Mesh.fromJson(Models.toJson(model, swaps) { tex -> fc.texture(tex) })
                    }
                    if (meshes.isEmpty()) throw IllegalArgumentException("has no model")
                    val img = ModelRender.render(meshes, size, 45.0, 30.0)
                    if (env.put("$out/objects/$id.png", Png.encode(img))) done++
                } catch (e: Exception) { failedIds.add("$id: ${e.message}") }
            }
            // only objects with a picture get a position: the kit then never asks for a file that is not there
            val drawn = wanted.filter { env.read("$out/objects/$it.png") != null }.toSet()
            val rowFiles = LinkedHashMap<String, String>()
            for ((x, z, list) in squareRows) {
                val keep = list.filter { it.first in drawn }
                rows += keep.size
                rowFiles["$out/places/m${x}_$z.json"] = "{\"x\":$x,\"z\":$z,\"objects\":[" + keep.joinToString(",") { it.second } + "]}"
            }
            for ((path, body) in rowFiles) env.put(path, body.toByteArray())
            val sb = StringBuilder("Wrote ${done} object pictures to $out/objects and $rows object positions to $out/places (${rowFiles.size} squares).")
            if (noModel > 0) sb.append(" $noModel objects have no model and are left as plain tiles.")
            if (skippedModels > 0) sb.append(" $skippedModels more objects were left out: at most $MAX_PLACE_MODELS different models per call.")
            if (failedIds.isNotEmpty()) sb.append(" Could not draw: ${failedIds.take(5).joinToString("; ")}${if (failedIds.size > 5) " and ${failedIds.size - 5} more" else ""}.")
            if (problems.isNotEmpty()) sb.append(" Problems: ${problems.take(6).joinToString("; ")}.")
            sb.append(" The game kit draws these automatically; roofs (under-roof objects) are hidden while the player stands under one.")
            return sb.toString()
        }
    }

    private fun squares(s: String): List<Pair<Int, Int>>? {
        if (s.isBlank()) return null
        val out = ArrayList<Pair<Int, Int>>()
        for (p in s.split(';', '/', '|')) {
            val ab = p.trim().replace(" ", "").split(',')
            if (ab.size != 2) return null
            val x = ab[0].toIntOrNull() ?: return null; val z = ab[1].toIntOrNull() ?: return null
            out.add(x to z)
        }
        return out
    }

    /** Build a cache in [out] from items.json / npcs.json / objects.json, sprites/<id>[_n].png and maps/m<x>_<z>.json found in [src]. */
    fun pack(src: String, out: String, env: AssetEnv): String {
        val prefix = if (src.isEmpty()) "" else "$src/"
        val all = env.files(src).map { it }
        fun under(rel: String) = all.filter { it.startsWith(prefix + rel) }
        val outDir = env.resolve(out) ?: throw IllegalArgumentException("'$out' is not a folder in the project")
        if (outDir.exists() && !outDir.isDirectory) throw IllegalArgumentException("'$out' is a file")
        val notes = ArrayList<String>()
        val config = java.util.TreeMap<Int, Js5Writer.Group>()
        for ((kind, group) in listOf("items" to CacheDefs.ITEMS, "npcs" to CacheDefs.NPCS, "objects" to CacheDefs.OBJECTS)) {
            val path = under("$kind.json").firstOrNull { it == prefix + "$kind.json" } ?: continue
            val txt = env.read(path)?.toString(Charsets.UTF_8) ?: continue
            val list = CacheDefs.parseJson(txt) as? List<*> ?: throw IllegalArgumentException("$path must be a JSON array of objects")
            val files = java.util.TreeMap<Int, ByteArray>()
            for (e in list) {
                @Suppress("UNCHECKED_CAST") val m = e as? Map<String, Any?> ?: throw IllegalArgumentException("$path has an entry that is not an object")
                val id = (m["id"] as? Number)?.toInt() ?: throw IllegalArgumentException("$path has an entry without a numeric id")
                if (id < 0 || id > 200000) throw IllegalArgumentException("$path has id $id, which is out of range")
                files[id] = CacheDefs.encode(kind, m)
            }
            config[group] = Js5Writer.Group(files); notes.add("${files.size} $kind")
        }
        val sprites = java.util.TreeMap<Int, java.util.TreeMap<Int, Png.Image>>()
        val spriteRe = Regex("""(\d+)(?:_(\d+))?\.png""", RegexOption.IGNORE_CASE)
        for (p in under("sprites/")) {
            val m = spriteRe.matchEntire(p.substringAfterLast('/')) ?: continue
            val id = m.groupValues[1].toInt(); val n = m.groupValues[2].ifEmpty { "0" }.toInt()
            val img = Png.decode(env.read(p) ?: continue)
            sprites.getOrPut(id) { java.util.TreeMap() }[n] = img
        }
        val spriteGroups = java.util.TreeMap<Int, Js5Writer.Group>()
        for ((id, frames) in sprites) {
            val list = frames.values.map { Sprites.Frame(it.width, it.height, 0, 0, it.argb) }
            spriteGroups[id] = Js5Writer.Group(mapOf(0 to Sprites.encode(Sprites.Sheet(list.maxOf { it.width }, list.maxOf { it.height }, list))))
        }
        if (spriteGroups.isNotEmpty()) notes.add("${spriteGroups.size} sprites")
        val maps = java.util.TreeMap<Int, Js5Writer.Group>()
        var squares = 0
        val mapRe = Regex("""m(\d+)_(\d+)\.json""")
        for (p in under("maps/")) {
            val m = mapRe.matchEntire(p.substringAfterLast('/')) ?: continue
            val x = m.groupValues[1].toInt(); val z = m.groupValues[2].toInt()
            val obj = CacheDefs.parseJson(env.read(p)?.toString(Charsets.UTF_8) ?: continue) as? Map<*, *> ?: throw IllegalArgumentException("$p must be a JSON object")
            val planes = (obj["planes"] as? List<*>)?.map { pl -> (pl as? List<*>)?.map { it.toString() } ?: throw IllegalArgumentException("$p: each plane must be a list of 64 strings") }
                ?: throw IllegalArgumentException("$p needs a planes list")
            val t = Terrain.fromGrids(x, z, planes); squares++
            for ((name, arr) in listOf("underlay" to t.underlay, "overlay" to t.overlay, "height" to t.height)) {
                val l = (obj[name] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: continue
                if (l.size == 4096) for (i in 0 until 4096) arr[i] = l[i]
            }
            for (i in 0 until 4096) if (t.height[i] != 0) t.hasHeight[i] = true
            maps[maps.size + 1] = Js5Writer.Group(mapOf(0 to t.encode()), Js5.nameHash(Terrain.name(x, z)))
            (obj["objects"] as? List<*>)?.let { list ->
                val locs = list.map { o -> val a = (o as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: throw IllegalArgumentException("$p: each object must be [id, x, z, plane, type, rotation]")
                    if (a.size != 6 || a[1] !in 0..63 || a[2] !in 0..63 || a[3] !in 0..3 || a[4] !in 0..22 || a[5] !in 0..3 || a[0] < 0) throw IllegalArgumentException("$p: bad object $a")
                    Locs.Loc(a[0], a[1], a[2], a[3], a[4], a[5]) }
                maps[maps.size + 1] = Js5Writer.Group(mapOf(0 to Locs.encode(locs)), Js5.nameHash(Locs.name(x, z)))
            }
        }
        if (maps.isNotEmpty()) notes.add("$squares map squares")
        if (notes.isEmpty()) return "Nothing to pack in '${src.ifEmpty { "the project" }}'. Expected items.json, npcs.json, objects.json, sprites/<id>.png or maps/m<x>_<z>.json (what export writes)."
        // build in a temp folder, then hand the three kinds of files to the project
        val tmp = File(outDir.parentFile ?: outDir, ".pack-tmp-" + System.nanoTime())
        try {
            Js5Writer(tmp).use { w ->
                if (config.isNotEmpty()) w.addIndex(CacheDefs.CONFIG_INDEX, config)
                if (spriteGroups.isNotEmpty()) w.addIndex(Sprites.INDEX, spriteGroups)
                if (maps.isNotEmpty()) w.addIndex(Terrain.INDEX, maps)
            }
            val wrote = ArrayList<String>()
            for (f in tmp.listFiles().orEmpty().sortedBy { it.name }) { if (!env.put("$out/${f.name}", f.readBytes())) return "Could not write $out/${f.name}."; wrote.add(f.name) }
            // read it back to prove the file is sound
            val back = Js5(outDir)
            val check = back.use { b -> listOf(CacheDefs.CONFIG_INDEX, Sprites.INDEX, Terrain.INDEX).filter { b.table(it) != null }.joinToString(", ") { "index $it ${b.table(it)!!.groups.size} groups" } }
            return "Packed ${notes.joinToString(", ")} into $out (${wrote.size} files, read back OK: $check). Read it with game_cache on '$out'."
        } finally { tmp.deleteRecursively() }
    }
}
