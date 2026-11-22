package dev.pocketide.app.core

import java.io.File

/**
 * The game_assets tool: make and convert the files any game needs, without a desktop. Sprites from text, atlases, sound effects and
 * melodies, 3D models from shapes or from glTF/OBJ files, tile levels from ASCII art or Tiled, and runnable starter games.
 */
object GameAssets {
    val TEMPLATES = listOf("rpg3d", "topdown", "platformer", "walk3d", "blank") + Design.APP_KITS.toList()
    val CORE_FILES = listOf("loop", "input", "grid", "tilemap", "camera", "save", "sound", "tween", "fsm", "ui", "entities", "model")
    /** Extra core modules of the open-world kit (rpg3d): noise, creatures made in code, the world generator and the skill tree. */
    val RPG_CORE = listOf("noise", "creatures", "worldgen", "skilltree")

    /** The starter kits with what each holds, for action=kits. */
    val KITS = linkedMapOf(
        "rpg3d" to "open-world 3D role-playing game made from design.json: hills, water and roads, villages of houses with several floors (stairs; roofs hide inside), dungeons with rooms and bosses, creatures made in code (people, bandits, trolls, imps, vampires, skeletons, wolves, bears, dragons, slimes, bats) that roam, fight or flee, sword and fireball combat, levels, a branching skill tree, quests and dialogue (data/content.json), loot, chests, saving, phone controls; 7 play tests in tests/play.txt",
        "topdown" to "top-down tile game: joystick or tap-to-walk with pathfinding, a villager to talk to, coins, saving",
        "platformer" to "2D platformer: run, jump, stomp enemies, coins, goal, lives, best score",
        "walk3d" to "a small third-person 3D walker (three.js): joystick, crates, gems",
        "blank" to "an empty game with a loop, input and a canvas",
        "crud" to "app: a tracker for any kind of record (tasks, habits, budget, inventory, contacts) from app.json fields: list and drag-and-drop board, search, filters, sorting, stats, add/edit/delete with checks, CSV/JSON export and import, saved on the device; 5 play tests",
        "dashboard" to "app: a dashboard from app.json and a CSV/JSON data file: KPI tiles, line/bar/donut charts (no library), filters, a sortable table, CSV export; 3 play tests",
        "landing" to "app: a website (business, product, portfolio) from app.json: navigation with a phone menu, hero, features, steps, gallery, pricing, FAQ, a checked contact form, footer; 3 play tests",
        "form" to "app: quizzes, surveys and multi-step forms from app.json: steps, many field types, checks, progress, quiz scoring with explanations, results, responses kept and exported as CSV; 3 play tests",
        "store" to "app: a shop from app.json: products, categories, search, cart with stock limits and shipping, checkout form, order history (no payment provider); 4 play tests",
        "notes" to "app: notes and journals from app.json: write, edit, pin, tag, search, safe Markdown with tickable check lists, Markdown export; 4 play tests; sign-in and online storage with a cloud block",
        "booking" to "app: a booking calendar from app.json: pick a day and service, see free times, book with a name and contact, cancel from upcoming bookings; 4 play tests; sign-in and online storage with a cloud block",
        "recipe" to "app: a recipe box from app.json: ingredients and steps, search and tags, a servings scaler that rescales the amounts (fractions too), tick-off ingredients, JSON import and export; 5 play tests; online with a cloud block",
        "assistant" to "app: an AI assistant chat from app.json: the conversation on the device, the model reached through an endpoint on your own server (no API key in the app), demo mode without one; 3 play tests",
        "chat" to "app: chat rooms from app.json: rooms, your name, delete your messages; shared between signed-in people when app.json has a cloud block (it checks every 4 seconds), on the device otherwise; 3 play tests")
    /** The shared files of the app kits (copied to out/appkit). */
    val APP_FILES = listOf("ui.css", "dom.js", "store.js", "charts.js", "cloud.js")

    private fun str(a: Map<String, Any?>, k: String) = (a[k] as? String)?.trim().orEmpty().ifEmpty { (a[k] as? Number)?.toString().orEmpty() }
    private fun int(a: Map<String, Any?>, k: String, d: Int) = (a[k] as? Number)?.toInt() ?: str(a, k).toIntOrNull() ?: d
    private fun dbl(a: Map<String, Any?>, k: String, d: Double) = (a[k] as? Number)?.toDouble() ?: str(a, k).toDoubleOrNull() ?: d

    /** A JSON value given either as a real object/array or as text holding JSON. */
    private fun jsonArg(a: Map<String, Any?>, k: String): Any? {
        val v = a[k] ?: return null
        return if (v is String) { if (v.isBlank()) null else try { JsonText.parse(v) } catch (e: Exception) { throw IllegalArgumentException("$k is not valid JSON: ${e.message}") } } else v
    }
    private fun lines(a: Map<String, Any?>, k: String): List<String> = when (val v = a[k]) {
        is List<*> -> v.map { it.toString() }
        is String -> v.trimEnd('\n').split('\n').map { it.trimEnd('\r') }
        else -> emptyList()
    }
    private fun cap(s: String) = if (s.length > 1500) s.take(1500) + "..." else s

    /**
     * Building blocks from the cache game kit that work in any game: name to (what it does, files it needs besides itself).
     * The engine path is the 3D library, copied to out/engine.
     */
    val BLOCKS = linkedMapOf(
        "anim.js" to ("plays skeletal animations (walk, attack, any move) on a model JSON with vertex groups; applyFrame works without three.js" to listOf("engine/three.module.min.js")),
        "view3d.js" to ("3D world: baked map squares, characters as animated models, a camera that orbits the player (drag, pinch, wheel), tap to tile, screen positions for labels" to listOf("anim.js", "engine/three.module.min.js")),
        "pathfinder.js" to ("tile pathfinding round walls and blocked tiles (A*, diagonal rules of tile games)" to listOf("world.js")),
        "world.js" to ("tile world: loads map squares with walls and blocked tiles, answers walkable and canStep" to emptyList()),
        "inv.js" to ("a 28-slot inventory with stacks, tap, long press menu (use, drop, examine) and drag to swap" to emptyList()),
        "bank.js" to ("a bank: deposit, withdraw 1/5/10/X/all, keeps order, saves" to emptyList()),
        "skills.js" to ("skills with the classic experience table (levels 1-99) and level-up events" to emptyList()),
        "sfx.js" to ("sounds made in code (hit, miss, coin, level up, click) and a quiet looping tune; no audio files" to emptyList()),
        "save.js" to ("versioned saving in local storage with a memory fallback" to emptyList()),
        "ui.js" to ("a side panel of tabs (combat, stats, inventory, options) with volume, mute, save and reset" to listOf("skills.js")),
        "rules.js" to ("melee combat: hit chance, max hit, styles, experience, enemies that chase, drops and respawns" to listOf("pathfinder.js", "world.js")),
        "gather.js" to ("a gathering skill pattern (trees and axes): success chance per level and tool, respawning nodes" to emptyList()),
        "content.js" to ("game content as data: people, dialogue with choices, quests with stages and a journal, scripted actions, recipes with levels and tools" to listOf("skills.js")),
        "core/creatures.js" to ("low-poly creatures made in code with walk, attack, hurt and death animations: humanoids, beasts, dragons, slimes, flyers, and ready LOOKS (troll, imp, vampire, wolf...)" to listOf("engine/three.module.min.js")),
        "core/worldgen.js" to ("an open world from a design: hills, water, roads, villages of multi-floor houses, dungeons, trees and rocks, collision and free spots" to listOf("core/noise.js", "engine/three.module.min.js")),
        "core/skilltree.js" to ("a branching skill tree (perks with prerequisites and effects) with its own panel" to emptyList()),
        "core/noise.js" to ("seeded random numbers and smooth noise for terrain and placement" to emptyList()))

    /** action=blocks: lists the kit's building blocks, or copies the chosen ones (and what they need) into out for any game. */
    private fun blocks(which: String, out: String, env: AssetEnv): String {
        if (which.isBlank()) return "Building blocks from the game kit, usable in any game (copy with action=blocks files=<names> out=<folder>):\n" +
            BLOCKS.entries.joinToString("\n") { (k, v) -> "- $k: ${v.first}" + (if (v.second.isNotEmpty()) " (needs ${v.second.joinToString(", ")})" else "") } +
            "\nModels and animations for them come from game_cache (export kind=models, npcs3d, held, player, anims, scene) or game_assets model_build / model_import."
        val want = LinkedHashSet<String>()
        fun add(n: String) { if (!want.add(n)) return; BLOCKS[n]?.second?.forEach { add(it) } }
        // a name may be given with or without its folder (creatures.js or core/creatures.js)
        val byName = { n: String -> BLOCKS.keys.firstOrNull { it == n } ?: BLOCKS.keys.firstOrNull { it.substringAfterLast('/') == n.substringAfterLast('/') } ?: n.substringAfterLast('/') }
        val names = if (which.trim().equals("all", true)) BLOCKS.keys.toList() else which.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(byName)
        val unknown = names.filter { it !in BLOCKS }
        names.filter { it in BLOCKS }.forEach { add(it) }
        if (want.isEmpty()) return "No such block: ${unknown.joinToString(", ")}. Blocks: ${BLOCKS.keys.joinToString(", ")}."
        val dir = out.ifEmpty { "game" }
        val done = ArrayList<String>()
        for (f in want) {
            val b = env.asset(if (f.startsWith("engine/")) "../$f" else f) ?: return "The block $f is not available in this build."
            if (!env.put("$dir/$f", b)) return "Could not write $dir/$f."
            done.add(f)
        }
        return "Copied ${done.size} file(s) to $dir: ${done.joinToString(", ")}. Each file says at the top how to use it; import them as ES modules (type=\"module\")." +
            (if (unknown.isNotEmpty()) " Unknown, skipped: ${unknown.joinToString(", ")}." else "")
    }

    fun run(a: Map<String, Any?>, env: AssetEnv): String {
        val action = str(a, "action").lowercase()
        val out = str(a, "out").trim('/')
        return try {
            when (action) {
                "sprite" -> sprite(a, out.ifEmpty { "assets/sprite.png" }, env)
                "atlas" -> atlas(str(a, "dir").trim('/'), out.ifEmpty { "assets/atlas" }, env)
                "sound" -> sound(a, out, env)
                "model_build" -> modelBuild(a, out.ifEmpty { "models/model.json" }, env)
                "model_import" -> modelImport(str(a, "path").trim('/'), out, env)
                "model_render" -> modelRender(a, out, env)
                "model_export" -> modelExport(str(a, "path").trim('/'), out, env)
                "level" -> level(a, out.ifEmpty { "data/map.json" }, env)
                "tiled" -> tiled(str(a, "path").trim('/'), out.ifEmpty { "data/map.json" }, env)
                "scaffold" -> scaffold(str(a, "kind").lowercase().ifEmpty { "blank" }, out.ifEmpty { "game" }, env)
                "blocks" -> blocks(str(a, "files").ifEmpty { str(a, "kind") }, out, env)
                "kits" -> "Starter kits (game_assets action=scaffold kind=<name> out=<folder>); start from the closest one instead of writing an engine:\n" +
                    KITS.entries.joinToString("\n") { "- ${it.key}: ${it.value}" } + "\nBuilding blocks for any game: action=blocks."
                else -> "action must be one of: sprite, atlas, sound, model_build, model_import, model_export, model_render, level, tiled, scaffold, kits, blocks."
            }
        } catch (e: IllegalArgumentException) { "game_assets $action: ${e.message}"
        } catch (e: Exception) { "game_assets $action failed: ${e.message ?: e.javaClass.simpleName}"
        } catch (e: OutOfMemoryError) { "game_assets $action: the file is too big for this phone to handle." }
    }

    private fun write(env: AssetEnv, path: String, bytes: ByteArray) { if (!env.put(path, bytes)) throw IllegalArgumentException("could not write $path") }

    private fun sprite(a: Map<String, Any?>, out: String, env: AssetEnv): String {
        val rows = lines(a, "rows")
        @Suppress("UNCHECKED_CAST") val pal = (jsonArg(a, "palette") as? Map<String, Any?>)?.mapValues { it.value.toString() } ?: throw IllegalArgumentException("palette is needed: an object like {\"a\": \"#ff0000\", \"b\": \"#222\"} (a letter for each colour; . is empty)")
        val img = SpriteArt.fromText(rows, pal, int(a, "scale", 1))
        val path = if (out.endsWith(".png")) out else "$out.png"
        write(env, path, Png.encode(img))
        return "Wrote $path (${img.width}x${img.height}). Check it with look_at_images on its folder."
    }

    private fun atlas(dir: String, out: String, env: AssetEnv): String {
        val files = env.files(dir).filter { it.endsWith(".png", true) }
        if (files.isEmpty()) throw IllegalArgumentException("no PNG files in '${dir.ifEmpty { "the project" }}'")
        val images = LinkedHashMap<String, Png.Image>()
        for (f in files.sorted()) images[f.substringAfterLast('/').removeSuffix(".png").removeSuffix(".PNG")] = Png.decode(env.read(f) ?: continue)
        val base = out.removeSuffix(".png").removeSuffix(".json")
        val atlas = SpriteArt.pack(images, base.substringAfterLast('/') + ".png")
        write(env, "$base.png", Png.encode(atlas.image)); write(env, "$base.json", atlas.json.toByteArray())
        return "Packed ${images.size} images into $base.png (${atlas.image.width}x${atlas.image.height}) with frame positions in $base.json (Phaser atlas format: this.load.atlas('name', '$base.png', '$base.json'))."
    }

    private fun sound(a: Map<String, Any?>, out: String, env: AssetEnv): String {
        val preset = str(a, "preset").lowercase(); val notes = str(a, "notes"); val wave = str(a, "wave").lowercase().ifEmpty { "square" }
        if (wave !in listOf("sine", "square", "saw", "triangle", "noise")) throw IllegalArgumentException("wave must be sine, square, saw, triangle or noise")
        val voices = when {
            notes.isNotEmpty() -> Sound.melody(notes, int(a, "tempo", 120), wave)
            preset.isNotEmpty() -> Sound.PRESETS[preset] ?: throw IllegalArgumentException("unknown preset '$preset' (${Sound.PRESETS.keys.joinToString(", ")})")
            else -> { val f = dbl(a, "freq", 440.0); listOf(Sound.Voice(wave, f, dbl(a, "freq_end", f), dbl(a, "duration", 0.3).coerceIn(0.01, 30.0), dbl(a, "volume", 0.6).coerceIn(0.0, 1.0), decay = dbl(a, "decay", 3.0))) }
        }
        val path = (out.ifEmpty { "assets/" + (preset.ifEmpty { if (notes.isNotEmpty()) "melody" else "sound" }) + ".wav" }).let { if (it.endsWith(".wav")) it else "$it.wav" }
        val bytes = Sound.render(voices)
        write(env, path, bytes)
        return "Wrote $path (${"%.2f".format((bytes.size - 44) / 2.0 / Sound.RATE)} s). Load it with the kit's Sound class: sound.load('name', '$path')."
    }

    private fun modelBuild(a: Map<String, Any?>, out: String, env: AssetEnv): String {
        val shapes = jsonArg(a, "shapes") as? List<*> ?: throw IllegalArgumentException("shapes is needed: a list like [{\"type\":\"box\",\"size\":[1,2,1],\"pos\":[0,0,0],\"color\":\"#cc3333\"}] (box, cylinder, cone, sphere, plane)")
        val mesh = Primitives.build(shapes)
        val path = if (out.endsWith(".json")) out else "$out.json"
        write(env, path, mesh.toJson().toByteArray())
        return "Wrote $path: ${mesh.vertexCount()} vertices, ${mesh.faceCount()} faces. Show it with the kit's loadModel('$path') (three.js)."
    }

    private fun modelImport(path: String, out: String, env: AssetEnv): String {
        if (path.isEmpty()) throw IllegalArgumentException("path is needed: a .glb, .gltf or .obj file in the project")
        val bytes = env.read(path) ?: throw IllegalArgumentException("'$path' not found")
        val dir = path.substringBeforeLast('/', "")
        fun sibling(n: String) = env.read(if (dir.isEmpty()) n else "$dir/$n")
        val lower = path.lowercase()
        val mesh = when {
            lower.endsWith(".obj") -> {
                val text = bytes.toString(Charsets.UTF_8)
                val mtlName = Regex("(?m)^mtllib\\s+(.+)$").find(text)?.groupValues?.get(1)?.trim()
                ModelImport.obj(text, mtlName?.let { sibling(it)?.toString(Charsets.UTF_8) })
            }
            lower.endsWith(".glb") || lower.endsWith(".gltf") -> ModelImport.gltf(bytes) { sibling(it) }
            else -> throw IllegalArgumentException("only .glb, .gltf and .obj files can be imported (FBX and Blender files: export to glTF first)")
        }
        val dest = (out.ifEmpty { "models/" + path.substringAfterLast('/').substringBeforeLast('.') }).let { if (it.endsWith(".json")) it else "$it.json" }
        write(env, dest, mesh.toJson().toByteArray())
        return "Imported $path to $dest: ${mesh.vertexCount()} vertices, ${mesh.faceCount()} faces, one colour per face (textures are not kept)."
    }

    /** A flat picture of one or more models: a tree, a character, an item icon. path is a model file, several separated by commas, or a folder of model files. */
    private fun modelRender(a: Map<String, Any?>, out: String, env: AssetEnv): String {
        val path = str(a, "path").trim('/')
        if (path.isEmpty()) throw IllegalArgumentException("path is needed: a model .json file, several separated by commas (parts of one character), or a folder of models")
        val files = path.split(',').map { it.trim().trim('/') }.filter { it.isNotEmpty() }.flatMap { p ->
            if (p.endsWith(".json")) listOf(p) else env.files(p).filter { it.endsWith(".json") }.sorted() }
        if (files.isEmpty()) throw IllegalArgumentException("no model files found in '$path'")
        if (files.size > 40) throw IllegalArgumentException("that is ${files.size} models; draw at most 40 at once")
        val meshes = files.map { Mesh.fromJson((env.read(it) ?: throw IllegalArgumentException("'$it' not found")).toString(Charsets.UTF_8)) }
        var img = ModelRender.render(meshes, int(a, "size", 64), dbl(a, "yaw", 30.0), dbl(a, "pitch", 30.0))
        if (a["trim"] == true || str(a, "trim").lowercase() == "true") img = ModelRender.trim(img)
        val dest = (out.ifEmpty { "sprites/" + files.first().substringAfterLast('/').removeSuffix(".json") }).let { if (it.endsWith(".png")) it else "$it.png" }
        write(env, dest, Png.encode(img))
        return "Drew ${files.size} model${if (files.size == 1) "" else "s"} as $dest (${img.width}x${img.height}, yaw ${dbl(a, "yaw", 30.0).toInt()}, pitch ${dbl(a, "pitch", 30.0).toInt()}). Check it with look_at_images. Other angles: yaw 0, 90, 180, 270 for four directions."
    }

    private fun modelExport(path: String, out: String, env: AssetEnv): String {
        val mesh = Mesh.fromJson((env.read(path) ?: throw IllegalArgumentException("'$path' not found")).toString(Charsets.UTF_8))
        val base = (out.ifEmpty { path.removeSuffix(".json") }).removeSuffix(".obj")
        val (obj, mtl) = ModelImport.toObj(mesh, base.substringAfterLast('/'))
        write(env, "$base.obj", obj.toByteArray()); write(env, "$base.mtl", mtl.toByteArray())
        return "Wrote $base.obj and $base.mtl (${mesh.faceCount()} faces) for Blender or other 3D tools."
    }

    private fun level(a: Map<String, Any?>, out: String, env: AssetEnv): String {
        val rows = lines(a, "rows")
        @Suppress("UNCHECKED_CAST") val legend = (jsonArg(a, "legend") as? Map<String, Any?>) ?: throw IllegalArgumentException("legend is needed: {\"#\": {\"tile\": 1, \"solid\": true}, \".\": {\"tile\": 2}} (a character per kind of tile)")
        @Suppress("UNCHECKED_CAST") val objs = (jsonArg(a, "objects") as? Map<String, Any?>)?.mapValues { it.value.toString() } ?: emptyMap()
        val json = Tiles.fromText(rows, legend, int(a, "tile_size", 16), objs)
        val path = if (out.endsWith(".json")) out else "$out.json"
        write(env, path, json.toByteArray())
        return "Wrote $path (${rows.maxOf { it.length }}x${rows.size} tiles). Load it with TileMap.load('$path')."
    }

    private fun tiled(path: String, out: String, env: AssetEnv): String {
        if (path.isEmpty()) throw IllegalArgumentException("path is needed: a Tiled map exported as JSON (.tmj or .json)")
        val json = Tiles.fromTiled((env.read(path) ?: throw IllegalArgumentException("'$path' not found")).toString(Charsets.UTF_8))
        val dest = if (out.endsWith(".json")) out else "$out.json"
        write(env, dest, json.toByteArray())
        return "Imported $path to $dest. Layers named collision/solid/wall, and tiles with a solid property, became blocked tiles. Load it with TileMap.load('$dest')."
    }

    /** A runnable starter game: the shared engine modules in core/, the template's main.js and a page. */
    /** The kit's own play tests into tests/play.txt (when the project has none yet): it sits at the project root, so with the game in a folder each test names its page. */
    private fun playTests(kind: String, out: String, env: AssetEnv) {
        if (env.read(PlayTests.FILE) != null) return
        val t = env.asset("templates/$kind/play.txt")?.toString(Charsets.UTF_8) ?: return
        val withPage = if (out.isEmpty() || out == ".") t else t.replace(Regex("(?m)^(# .+)$")) { m -> m.value + "\npage: $out/index.html" }
        write(env, PlayTests.FILE, withPage.toByteArray())
    }

    /** An app kit: the shared app files, the kit's page, app.js, app.json (kept when it exists), its data and its play tests. */
    private fun app(kind: String, out: String, env: AssetEnv, asset: (String) -> ByteArray): String {
        for (f in APP_FILES) write(env, "$out/appkit/$f", asset("appkit/$f"))
        write(env, "$out/index.html", env.asset("templates/$kind/index.html") ?: asset("templates/apppage.html"))
        write(env, "$out/app.js", asset("templates/$kind/app.js"))
        if (env.read("$out/app.json") == null) write(env, "$out/app.json", asset("templates/$kind/app.json"))
        if (env.read("$out/cloud-setup.sql") == null) write(env, "$out/cloud-setup.sql", asset("templates/cloud-setup.sql"))   // paste once in Supabase to turn on sign-in and online storage
        if (kind == "dashboard" && env.read("$out/data.csv") == null) write(env, "$out/data.csv", asset("templates/dashboard/data.csv"))
        playTests(kind, out, env)
        return "Wrote the $kind app kit to $out (open $out/index.html in the preview): ${KITS[kind]}. Change what it does in $out/app.json first (app.js reads it; its header lists every option); " +
            "the look is in $out/appkit/ui.css (--accent comes from app.json). Play tests are in ${PlayTests.FILE}: add one for each new feature and run look_at_preview test=all."
    }

    /** The open-world kit: its page, game, design and content, the core modules it uses, the content engine, inventory and skills, three.js and its play tests. */
    private fun rpg3d(out: String, env: AssetEnv, asset: (String) -> ByteArray): String {
        for (m in CORE_FILES + RPG_CORE) write(env, "$out/core/$m.js", asset("core/$m.js"))
        for (f in listOf("content.js", "inv.js", "skills.js")) write(env, "$out/$f", asset(f))
        write(env, "$out/index.html", asset("templates/rpg3d/index.html"))
        write(env, "$out/main.js", asset("templates/rpg3d/main.js"))
        if (env.read("$out/design.json") == null) write(env, "$out/design.json", asset("templates/rpg3d/design.json"))
        if (env.read("$out/data/content.json") == null) write(env, "$out/data/content.json", asset("templates/rpg3d/content.json"))
        playTests("rpg3d", out, env)
        write(env, "$out/engine/three.module.min.js", asset("../engine/three.module.min.js")); env.asset("../engine/THREE-LICENSE.txt")?.let { env.put("$out/engine/THREE-LICENSE.txt", it) }
        return "Wrote the open-world 3D RPG kit to $out (open $out/index.html in the preview). Change the world in $out/design.json (title, seed, world size and hills, villages with houses, floors and people, dungeons with rooms, creatures, boss and chests, creature stats, wildlife, items, the skill tree) " +
            "and quests and dialogue in $out/data/content.json (content.js format: quests with stages and journal, dialogues with options; kills count as vars kill:<creature>). " +
            "Creatures are made in code from core/creatures.js LOOKS (add a look or set size/color in design.json); the world is built by core/worldgen.js. Play tests for every feature are in ${PlayTests.FILE}: add one for each new feature and run look_at_preview test=all."
    }

    private fun scaffold(kind: String, out: String, env: AssetEnv): String {
        if (kind !in TEMPLATES) throw IllegalArgumentException("kind must be one of: ${TEMPLATES.joinToString(", ")}")
        fun asset(n: String) = env.asset(n) ?: throw IllegalArgumentException("the game kit file $n is not available in this build")
        if (kind == "rpg3d") return rpg3d(out, env) { asset(it) }
        if (kind in Design.APP_KITS) return app(kind, out, env) { asset(it) }
        for (m in CORE_FILES) write(env, "$out/core/$m.js", asset("core/$m.js"))
        if (kind == "topdown") { write(env, "$out/core/pathfinder.js", asset("pathfinder.js")); write(env, "$out/core/world.js", asset("world.js")) }
        val main = asset("templates/$kind/main.js")
        write(env, "$out/main.js", main)
        for (m in Regex("assets/(\\w+)\\.wav").findAll(main.toString(Charsets.UTF_8)).map { it.groupValues[1] }.toSet()) Sound.PRESETS[m]?.let { write(env, "$out/assets/$m.wav", Sound.render(it)) }
        write(env, "$out/index.html", asset(if (kind == "walk3d") "templates/page3d.html" else "templates/page2d.html"))
        playTests(kind, out, env)
        if (kind == "walk3d") write(env, "$out/data/models/player.json", Primitives.build(listOf(
            mapOf("type" to "cylinder", "size" to listOf(0.8, 1.2, 0.8), "pos" to listOf(0, 0, 0), "color" to "#3d6fd6"),
            mapOf("type" to "sphere", "size" to listOf(0.6, 0.6, 0.6), "pos" to listOf(0, 1.2, 0), "color" to "#f2c79a"),
            mapOf("type" to "box", "size" to listOf(0.18, 0.18, 0.3), "pos" to listOf(0, 1.42, 0.3), "color" to "#cc3333"))).toJson().toByteArray())
        if (kind == "walk3d") { write(env, "$out/engine/three.module.min.js", asset("../engine/three.module.min.js")); env.asset("../engine/THREE-LICENSE.txt")?.let { env.put("$out/engine/THREE-LICENSE.txt", it) } }
        val what = when (kind) {
            "topdown" -> "a top-down tile game: joystick or tap-to-walk with pathfinding, a villager to talk to, coins, saving"
            "platformer" -> "a platformer: run, jump, stomp enemies, coins, goal, lives, best score"
            "walk3d" -> "a third-person 3D walker (three.js): joystick, crates, gems"
            else -> "an empty game with a loop, input and a canvas"
        }
        return "Wrote $what to $out (open $out/index.html in the preview). Engine modules are in $out/core (loop, input, grid, tilemap, camera, save, sound, tween, fsm, ui, entities, model); edit main.js to change the game. " +
            "Add art with action=sprite/atlas, sound with action=sound, 3D with action=model_build/model_import, levels with action=level/tiled."
    }
}
