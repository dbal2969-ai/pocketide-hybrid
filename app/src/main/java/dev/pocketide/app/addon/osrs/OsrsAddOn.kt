package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*
import dev.pocketide.app.core.Tools.Def

/**
 * The RuneScape game-cache add-on (1.5.88): reading and writing Old School and 2009-era caches (main_file_cache.dat2 + idx),
 * building a playable game from their maps, models and animations (game_cache), the rules a cache does not hold from 2009scape's
 * server data (game_data), and running a 2009scape server project. It applies only to a project that holds a cache, server data
 * or a 2009scape server, and can be switched off in Settings. The generic game tools stay in the core for every game.
 */
object OsrsAddOn : AddOn {
    override val id = "osrs"
    override val title = "RuneScape game caches"
    override val summary = "Build games from an Old School or 2009 RuneScape cache (game_cache), read 2009scape server data (game_data), run a 2009scape server."

    private fun s(d: String) = Tools.s(d)
    private fun n(d: String) = Tools.n(d)
    private fun b(d: String) = Tools.b(d)

    private val CACHE_DEF = Def("game_cache", "Work with a game cache (a main_file_cache.dat2 file with main_file_cache.idx files, e.g. the Old School RuneScape cache from OpenRS2). " +
        "It is binary, never read or unzip it as text. The cache holds no NPC spawn data (no map object is an NPC); for where NPCs stand use action=enemies spawns=<spawn list> area=x1,z1,x2,z2. action=info lists what is inside. action=find looks up items, npcs or objects by part of a name and returns their ids and fields; with kind=npcs attackable=true it picks count different fighting NPCs at random (min_level, max_level, seed). action=place query=<town> gives the map squares, start tile and play area of a town (Falador, Lumbridge, Varrock, Draynor, Al Kharid, Edgeville, Barbarian Village, Port Sarim, Rimmington, Taverley); scaffold also accepts a town name in ids. " +
        "IMPORTANT: sprites (kind sprites) are interface pictures only (buttons, icons, chat bars) numbered separately from items, npcs and objects: sprite 1276 is NOT object 1276. Pictures of trees, NPCs, items and buildings do not exist in the cache; export their kind=models and draw them flat with game_assets model_render. " +
        "action=export writes readable files into the project: kind floors (floor colours as floors.json, water and other textured floors with their average colour), items/npcs/objects (one JSON file), sprites (PNG files named by sprite id; ids like \"0-200\" or \"all\", at most 3000 per call), maps (per 64x64 square: collision grids, and for all four floors the real tile heights, flags (blocked, bridge, under a roof), floor colours and the objects with their floor; ids like \"50,50\"; objects such as trees are encrypted and not included until the keys are loaded). " +
        "kind=ground draws each map square's ground as a lit picture (grass and dirt blended, paths, water, shaded by the real hills; scale = pixels per tile, default 4): use it as the ground of a 2D game instead of flat tiles. " +
        "kind=icons draws inventory pictures of items (ids like \"1351,1511,995\") from each item's own model at the angle the game uses (size, default 64): use them for inventory, bank and shop slots. " +
        "kind=player builds the player character from the cache's body parts (ids: male, female, or kit numbers; worn = item ids of gear to wear, e.g. a white helm and platebody, which replace the body parts they cover) as models/player.json, 128 units per tile, to draw with model_render or show in 3D. " +
        "Map exports also hold the objects placed on each square and a walking-collision grid, which need the square keys: action=keys with id (the OpenRS2 cache number in the zip's file name, e.g. 2038) downloads them first. " +
        "action=enemies writes out/enemies.json (default game/data) with count different fighting NPCs picked at random (min_level, max_level, seed, query) and a picture of each in out/npcs: use it for the enemies of a game; hitpoints and highest hit are estimated from the combat level, not in the cache. action=check reads a generated game folder (dir) and lists every script, page or data file it uses that is missing, every invalid JSON file, and every object with no picture, with how to fix each; run it before telling the person the game is ready. action=place_objects draws every building and object with a model on a town (ids: the town name, e.g. Falador, or map squares) as a picture (objects/<id>.png) and writes where each one stands, whether it is under a roof and its turn (places/m<x>_<z>.json), into out (default game/data). The game kit draws them on the map; needs the keys (action=keys) for objects to be readable. kind=models exports 3D models as JSON (ids: model numbers, or item:4151 / npc:3 / object:1276 to get a definition's model with its colours; items.json, npcs.json and objects.json list the model numbers). action=scaffold kind=viewer writes a three.js page that shows them. " +
        "action=scaffold writes a runnable starter game into out, exporting everything around ids (a town or map squares) from the cache in dir: map, walls, objects, ground pictures, a 3D world (kind=scene), the player and enemies as animated 3D models, their animations, the game's interface pictures; start every RuneScape-style game with it. " +
        "3D and animation exports: kind=scene bakes map squares (ids: a town or squares) for the kit's view3d.js; kind=anims ids=<sequence ids> writes animations for anim.js (stand 808, walk 819, run 824, punch 422, block 424, death 836); kind=npcs3d ids=<npc ids> writes animated NPC models with their stand, walk and (from rsmod names when its seq.sym is in the project) attack, block and death animations; kind=held ids=<item ids> writes items as held in the hand. " +
        "action=restore dir=<game folder> files=main.js,index.html (or all) puts the game kit's own files back exactly as scaffold wrote them, keeping the start tile, without touching the data: use it when a change broke the game. " +
        "action=pack builds a new cache from files in the same layout export writes (items.json, npcs.json, objects.json, sprites/<id>.png or <id>_<frame>.png, maps/m<x>_<z>.json), so you can edit or create items, sprites and maps from scratch. " +
        "dir is the folder holding main_file_cache.dat2 (for pack: the folder with the source files); out is the folder to write to. Animations and music are not decoded. For a numbered sprite, look at it with look_at_images instead of guessing.",
        mapOf("action" to s("info, find, place, place_objects, enemies, check, export, pack, keys, scaffold or restore"), "files" to s("restore: kit files to put back, comma separated, or all"), "worn" to s("export player: item ids to wear, e.g. \"6623,6617\""), "attackable" to s("find npcs: true to pick fighting NPCs"), "min_level" to n("find attackable: lowest combat level"), "max_level" to n("find attackable: highest combat level"), "count" to n("find attackable: how many different NPCs, default 20"), "seed" to n("find attackable: random seed"), "dir" to s("Folder with main_file_cache.dat2 (pack: folder with the source files)"), "kind" to s("export: items, npcs, objects, sprites, maps, models, floors, ground, icons, player, scene, anims, npcs3d or held; scaffold: walker (default) or viewer"), "scale" to n("export ground: pixels per tile, 1-8, default 4"), "size" to n("export icons: picture size in pixels, default 64"),
            "query" to s("find: part of a name"), "id" to s("keys: OpenRS2 cache number"), "ids" to s("export: ids such as \"0-200\", \"all\", item ids for icons, male/female for player, or map squares \"50,50;51,50\" for maps and ground"), "out" to s("export/pack: folder to write to, default cache-export or cache-packed")), listOf("action", "dir"))

    private val DATA_DEF = Def("game_data", "Look up the game rules a cache does not hold, from 2009scape's server data in the project (Server/data/configs and the content source): " +
        "where every NPC and ground item stands, NPC stats (attack, strength, defence, hitpoints, ranged, magic, attack speed, respawn time, aggressive), drop tables with weights, shops and their stock, " +
        "item weights, prices, equipment slots, bonuses and requirements, and which server source file handles a skill, place, quest or NPC. Use it instead of guessing numbers. " +
        "action=info lists what is there. action=npc query=<name> or id=<id> gives stats, spawns, drops and shops of NPCs. action=spawns area=<town, map squares or x1,z1,x2,z2> lists what stands there. " +
        "action=drops id=<npc id or name> gives the drop table with chances. action=shop query=<shop or keeper name>. action=item query=<name> or id=<id>. " +
        "action=source query=<skill, place, quest or NPC> lists the server's content files about it: read them with read_file for exact formulas, timings and dialogue (AGPL: use the facts, write your own code). " +
        "action=tables query=<word> lists the server's data tables (every enum in its source read as rows with named columns: recipes, levels, experience, tools, quest requirements; 276 in 2009scape), " +
        "action=table query=<name> out=game/data shows one and writes it as JSON to out/server/tables/<name>.json for the game to load: use these instead of copying numbers by hand. " +
        "action=export area=<town> out=game/data writes the town's spawns (with real stats and drops), shops, ground items, shared drop tables, item info and music into out/server; " +
        "then game_cache action=enemies spawns=<out>/server/spawns.json puts those NPCs in the game kit with pictures, at their real spawn points, with real stats and drops. " +
        "Ids are the 2009 cache's (build 530): they match a 2009scape cache directly. For an Old School cache pass cache=<its folder> to export, which moves ids by name.",
        mapOf("action" to s("info, npc, spawns, drops, shop, item, source, tables, table or export"), "query" to s("a name or a word"), "id" to s("an id"), "area" to s("spawns/export: a town, map squares like 50,50 or 49,49;51,51, or x1,z1,x2,z2 tiles"),
            "plane" to n("spawns: floor, default 0"), "out" to s("export: folder, default game/data"), "cache" to s("export: a cache folder whose ids to use (for an Old School cache)"),
            "dir" to s("folder to look in for the server data; default: the whole project")), listOf("action"))

    private fun cache(p: AddOnProject): String? = p.folderWith("main_file_cache.dat2", null)
    private fun serverData(p: AddOnProject): String? = p.folderWith("npc_spawns.json", "npc_configs.json")

    override fun applies(project: AddOnProject): Boolean = cache(project) != null || serverData(project) != null

    override fun tools(project: AddOnProject): List<Def> =
        (if (cache(project) != null) listOf(CACHE_DEF) else emptyList()) + (if (serverData(project) != null) listOf(DATA_DEF) else emptyList())

    override val onDemand: Set<String> get() = setOf("game_cache", "game_data")

    override fun changes(name: String, args: Map<String, Any?>): Boolean {
        val action = (args["action"] as? String)?.trim()?.lowercase()
        return (name == "game_cache" && action in setOf("export", "pack", "scaffold", "restore", "place_objects", "enemies", "keys")) ||
            (name == "game_data" && action == "export")
    }

    private fun serverEnv(env: AssetEnv) = ServerData.Env(files = { env.files("") }, read = { p -> env.read(p)?.toString(Charsets.UTF_8) }, put = { p, bytes -> env.put(p, bytes) })

    override fun run(name: String, args: Map<String, Any?>, env: AssetEnv): String = when (name) {
        "game_cache" -> CacheTool.run(args, env)
        "game_data" -> ServerData.run(args, serverEnv(env)) { dir, kind -> CacheTool.names(dir, kind, env) }
        else -> "$name is not part of this add-on."
    }

    override fun hasGame(files: Collection<String>): Boolean = GameRecipe.hasGame(files)

    /** The game notes: full for a new game, short when the project already holds one (so "continue" and "fix it" keep them). */
    override fun note(request: String, project: AddOnProject): String {
        val cache = cache(project)
        val have = cache != null && GameRecipe.hasGame(project.files())
        return when {
            !GameRecipe.wanted(request, cache != null, have) -> if (cache != null && GAME_WORDS.containsMatchIn(request)) ART else ""
            have -> GameRecipe.CONTINUE_NOTE
            else -> GameRecipe.NOTE
        }.let { if (it.isEmpty() || it == ART) it else it + (if (serverData(project) != null) GameRecipe.SERVER_NOTE else "") }
    }

    private val GAME_WORDS = Regex("\\b(game|sprites?|art|graphics|textures?|characters?|enem(?:y|ies)|trees?|buildings?|items?|icons?)\\b", RegexOption.IGNORE_CASE)

    /** For any game in a project that holds a cache (was in the Game developer profile before 1.5.88). */
    const val ART = "\n\nTHIS PROJECT HOLDS A GAME CACHE (main_file_cache.dat2): take the art from it rather than drawing it: game_cache kind=ground for the ground, " +
        "kind=icons for item pictures, kind=player for the character, and kind=models with game_assets model_render for trees, buildings and creatures."


    /** A game request naming a known town, in a project with a cache and no game yet: the app builds game/ from the cache first. */
    override fun prepare(request: String, project: AddOnProject): AddOnPrep? {
        val cache = cache(project) ?: return null
        if (GameRecipe.hasGame(project.files()) || !GameRecipe.wanted(request, true)) return null
        val squares = GameRecipe.preflightSquares(request) ?: return null
        val server = serverData(project) != null
        return AddOnPrep("Building the game folder from the cache first (map, buildings, pictures, game files). This takes a few seconds.") { env ->
            var text = try { CacheTool.run(mapOf("action" to "scaffold", "dir" to cache, "ids" to squares, "out" to "game"), env) }
                catch (e: Throwable) { "scaffold failed: " + (e.message ?: e.javaClass.simpleName) }
            // with 2009scape's server data in the project, the enemies stand where they really do, with real stats and drops
            if (env.read("game/main.js") != null && server) try {
                val sq = squares.split(',').mapNotNull { it.trim().toIntOrNull() }
                val area = if (sq.size == 2) "${sq[0] - 1},${sq[1] - 1};${sq[0] + 1},${sq[1] + 1}" else squares
                val ex = ServerData.run(mapOf("action" to "export", "area" to area, "out" to "game/data", "cache" to cache), serverEnv(env)) { d, k -> CacheTool.names(d, k, env) }
                val en = CacheTool.run(mapOf("action" to "enemies", "dir" to cache, "spawns" to "game/data/server/spawns.json", "out" to "game/data"), env)
                text += " Server data: " + ex.substringBefore(" Ids") + " " + en.substringBefore(";")
            } catch (e: Throwable) { }
            if (env.read("game/main.js") != null) GameRecipe.builtNote(text) else ""
        }
    }

    override fun runTargets(files: Set<String>, read: (String) -> String?, hasDir: (String) -> Boolean): List<RunTarget>? =
        if (ScapeServer.isProject(read)) ScapeServer.targets(files, hasDir) else null

    override fun isStep(targetId: String): Boolean = targetId.startsWith(ScapeServer.STEP)

    override fun beforeRun(targetId: String, read: (String) -> String?, write: (String, String) -> Unit): List<String> =
        if (isStep(targetId)) ScapeServer.refreshScripts(read, write) else emptyList()

    override val templates: List<Template> get() = listOf(ScapeServer.TEMPLATE)
}
