package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

/**
 * Well-known places in the Old School RuneScape world, so "a game set in Falador" can be turned into the map squares to export.
 * Squares are 64x64 tiles: square = tile / 64. A town sits in the middle of the 3 by 3 squares the game kit exports around its square.
 * The play area is a rough box of tiles (x0, z0, x1, z1) round the town walls.
 */
object Places {
    class Place(val name: String, val aliases: List<String>, val sx: Int, val sz: Int, val tileX: Int, val tileZ: Int, val area: IntArray, val note: String)

    val ALL = listOf(
        Place("Falador", listOf("falador"), 46, 52, 2964, 3380, intArrayOf(2935, 3310, 3030, 3400), "walled city, white castle in the middle, parks and the White Knights' castle to the north-east"),
        Place("Lumbridge", listOf("lumbridge"), 50, 50, 3222, 3218, intArrayOf(3190, 3190, 3260, 3260), "castle, river Lum and the swamp to the south"),
        Place("Varrock", listOf("varrock"), 50, 53, 3213, 3428, intArrayOf(3170, 3380, 3290, 3500), "walled city with the palace, square and bank"),
        Place("Draynor Village", listOf("draynor"), 48, 50, 3093, 3244, intArrayOf(3070, 3225, 3135, 3290), "village with a bank, market and the willow trees"),
        Place("Al Kharid", listOf("al kharid", "alkharid", "al-kharid"), 51, 49, 3293, 3174, intArrayOf(3260, 3140, 3330, 3210), "desert town east of Lumbridge"),
        Place("Edgeville", listOf("edgeville"), 48, 54, 3093, 3493, intArrayOf(3070, 3460, 3135, 3520), "bank town north-west of Varrock"),
        Place("Barbarian Village", listOf("barbarian village"), 48, 53, 3082, 3420, intArrayOf(3060, 3395, 3110, 3450), "village with the longhall"),
        Place("Port Sarim", listOf("port sarim", "portsarim"), 47, 50, 3029, 3217, intArrayOf(2990, 3180, 3060, 3260), "harbour town on the south coast"),
        Place("Rimmington", listOf("rimmington"), 46, 50, 2957, 3214, intArrayOf(2940, 3190, 2985, 3250), "small village south-west of Falador"),
        Place("Taverley", listOf("taverley"), 45, 53, 2933, 3450, intArrayOf(2900, 3420, 2960, 3480), "druid village north of Falador")
    )

    /** The place named in [text] ("falador", "Al Kharid"...), or null. */
    fun find(text: String): Place? {
        val t = text.lowercase()
        return ALL.firstOrNull { p -> p.aliases.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(t) } }
    }

    /** "46,52" for a place name, otherwise the text unchanged. */
    fun squaresFor(text: String): String = find(text)?.let { "${it.sx},${it.sz}" } ?: text

    fun describe(p: Place): String =
        "${p.name}: map square ${p.sx},${p.sz} (tile ${p.tileX},${p.tileZ} is the middle of town; ${p.note}). The 3 by 3 squares ${p.sx - 1}-${p.sx + 1} by ${p.sz - 1}-${p.sz + 1} cover the whole town and its surroundings. " +
            "Keep the game inside tiles ${p.area[0]},${p.area[1]} to ${p.area[2]},${p.area[3]} (a rough box round the town; check it against the exported collision). " +
            "Start the player near ${p.tileX},${p.tileZ}."
}
