package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

/**
 * Built-in instructions for making a game from a game cache. Added to the assistant's prompt only when the project holds a
 * cache AND the request is about a game AND it names the cache, a known town, or RuneScape. Anything else never sees it.
 * The same steps work for any top-down game built on cache maps: swap the town, the gear and the rules, keep the cache steps.
 */
object GameRecipe {
    private val GAME = Regex("\\b(game|play(?:able|er)?|enem(?:y|ies)|mechanics?|combat|monsters?|npcs?|rpg|quest|dungeon|world)\\b", RegexOption.IGNORE_CASE)
    private val RS = Regex("\\b(runescape|osrs|old ?school|runelite|rsmod|openrs2|2009 ?scape|rs2|rs ?cache|cache|map squares?|from the cache)\\b", RegexOption.IGNORE_CASE)

    /** True when the project already holds a generated game (the kit's rules and pathfinder files sit side by side). */
    fun hasGame(files: Collection<String>): Boolean {
        val dirs = files.filter { it.endsWith("pathfinder.js") }.map { it.removeSuffix("pathfinder.js") }
        return dirs.any { d -> files.contains(d + "rules.js") && files.contains(d + "main.js") }
    }

    /**
     * True when the assistant should get the game notes: the request asks for a game built from the cache (a known town counts,
     * with game words), or the project already holds a game made from the cache, so "continue", "fix it" and "add X" keep the notes.
     */
    fun wanted(request: String, cachePresent: Boolean, gameHere: Boolean = false): Boolean {
        if (!cachePresent) return false
        if (gameHere) return true
        if (!GAME.containsMatchIn(request)) return false
        return RS.containsMatchIn(request) || Places.find(request) != null
    }

    /** The map squares the app builds before the model starts, or null when the request names no known town (the model then looks it up). */
    fun preflightSquares(request: String): String? = Places.find(request)?.let { "${it.sx},${it.sz}" }

    /** What to tell the model once the app has built the game folder itself. */
    fun builtNote(result: String): String = "\n\nTHE APP HAS ALREADY BUILT THE GAME FOLDER (game/) before you started: " + result.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(300) +
        (result.indexOf("Server data:").takeIf { it >= 0 }?.let { " " + result.substring(it).take(500) + " Enemies already stand at their real spawn points with real stats and drops: do not replace them with random ones." } ?: "") +
        " It already runs: a 3D world from the cache with animated player and enemies (and a flat map from Options), walls, objects, buildings, side panel with the game's own icons, chat, saving, sounds, combat and woodcutting. Do NOT scaffold again and do not rewrite game/*.js files from scratch. Start at STEP 3 below and only change what the request needs."

    /** Where NPCs stand: not in the cache. Said wherever a game is built or changed, so no one invents spawn points. */
    const val SPAWNS = "NPC POSITIONS: the cache holds no spawn data, and no map object is an NPC (an object of type 9 is a diagonal wall, not a spawn marker). " +
        "Take where NPCs stand from the 2009scape server data (game_data) or from an Old School spawn list such as NPCList_OSRS.json (github.com/mejrs/data_osrs; fields id, name, x, y, p, with Old School ids): " +
        "game_cache action=enemies spawns=<that file> area=x1,z1,x2,z2 out=game/data writes the fighting NPCs that stand in that area. Never place NPCs at random or by guessing unless the person asks for it. "

    const val CONTINUE_NOTE = "\n\nTHIS PROJECT HOLDS A GAME MADE FROM THE GAME CACHE (folder with main.js, rules.js, pathfinder.js). Work on it, do not start over. Read game/README.md first: it lists every file and where to change things. " +
        "Change only what the request needs; the rules live in rules.js, the player in main.js, pictures and data under game/data. Never delete the generated data pictures. A plan file (PLAN.md) may be out of date: trust the files, not the plan. " +
        "The person's latest message decides what to work on: work on PLAN.md items only when they ask you to continue the plan. " + SPAWNS +
        "After a change run game_cache action=check dir=game, fix what it lists, then look_at_preview and say what is in the game and what is not. Never claim a feature the files do not contain."

    /** Added when the project also holds 2009scape's server data (spawns, stats, drops, shops). */
    const val SERVER_NOTE = "\n\nTHE PROJECT HOLDS 2009SCAPE SERVER DATA: use the game_data tool for every number the cache does not hold (NPC stats, attack speeds, spawn points, drops, shops, item weights and bonuses) instead of guessing, and action=source to find the server file for a skill or quest. " +
        "For a town: game_data action=export area=<town> out=game/data (add cache=<cache folder> when the cache is Old School), then game_cache action=enemies spawns=game/data/server/spawns.json out=game/data: the enemies then stand where they do in the real game, with real stats and drops."

    const val NOTE = "\n\nBUILDING A GAME FROM THE PROJECT'S GAME CACHE. Steps in order; check each before the next. Do not ask the person for details you can look up in the cache. Work on your own after the first request: pick sensible defaults, say what you chose in one line, and keep going until the page runs.\n " + SPAWNS + "" +
        "STEP 1, the world. Normally the app has already built game/ (see the note above). If it has not: game_cache action=info dir=<cache folder>; if objects are not readable, action=keys id=<cache number from the zip's name>; action=place query=<town>; then action=scaffold dir=<cache folder> ids=<town squares> out=game. This writes a runnable game: map, walls, objects, buildings as pictures, side panel, chat, saving, sounds, combat and woodcutting. Do not draw buildings or the map by hand.\n" +
        "STEP 2, the player. For named gear: action=find kind=items query=<word> to get ids. Pick helm, body, legs, shield, weapon, boots and gloves, then action=export kind=player ids=male worn=<those ids> out=game/data (this also writes game/data/player.png, which the game draws).\n" +
        "STEP 3, the stats. Use the levels the person names, in the start levels of main.js. Combat level 100 is Attack 85, Strength 85, Defence 75, Hitpoints 77, Prayer 55. Say these are standard figures, not read from the cache.\n" +
        "STEP 4, the enemies. action=find kind=npcs attackable=true count=<n> min_level=<a> max_level=<b> seed=<number> gives different fighting NPCs at random. Export each model (kind=models ids=\"npc:<id>\") and draw it with game_assets model_render. Put them on walkable tiles in the play area, away from the start.\n" +
        "STEP 5, the rules. Combat, enemies and stats live in rules.js only; change that file, not the cache steps.\n" +
        "STEP 6, the check. action=check dir=game. Fix every problem it lists: a file that nothing loads must be imported or deleted. Then look_at_preview and say what is in the game and what is not. Never report a step done while the page shows an error.\n" +
        "3D AND ANIMATION: the scaffold already bakes the 3D world (game_cache export kind=scene) and exports animated models (kind=player, npcs3d, held) and animations (kind=anims). To add a character, export its model with kind=npcs3d and its animations, then add it with view3d.addActor; play animations with anim.js. Never write your own 3D engine or animation code: use view3d.js and anim.js.\n" +
        "SAY PLAINLY WHAT IS NOT IN THE GAME: music from the cache, scripted quests, upper floors you can walk on, equipment and the dimmed tabs of the side panel do not work yet; enemy spawn points come only from server data (game_data). Do not claim those work.\n" +
        "TO MAKE A SIMILAR GAME (another town, other gear, a different genre): keep steps 1, 2 and 6; change the town, the gear ids and the rules file. " +
        "FOR OTHER GAMES: the kit's files are building blocks (game_assets action=blocks lists and copies them): view3d.js, anim.js, pathfinder.js, inv.js, bank.js, skills.js, sfx.js, save.js, ui.js."
}
