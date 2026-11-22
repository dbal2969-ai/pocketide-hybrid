package dev.pocketide.app.addon.osrs

import dev.pocketide.app.core.*

/**
 * Identity kits: the body parts a player is made of (head or hair, beard, torso, arms, hands, legs, feet), stored in the
 * cache's config index 2, group 3. A character is these parts' models joined together.
 */
object IdKit {
    const val GROUP = 3
    /** The kits a new male and a new female character start with (hair, beard, torso, arms, hands, legs, feet). */
    val MALE = listOf(0, 10, 18, 26, 33, 36, 42)
    val FEMALE = listOf(45, 56, 61, 67, 70, 79)

    class Kit(val id: Int, val part: Int, val models: List<Int>, val recolor: Map<Int, Int>, val selectable: Boolean)

    fun parse(id: Int, data: ByteArray): Kit {
        val r = Js5.Reader(data); var part = -1; var selectable = true
        val models = ArrayList<Int>(); val recolor = LinkedHashMap<Int, Int>()
        loop@ while (r.left() > 0) when (r.u8()) {
            0 -> break@loop
            1 -> part = r.u8()
            2 -> repeat(r.u8()) { models.add(r.u16()) }
            3 -> selectable = false
            40 -> repeat(r.u8()) { val from = r.u16(); recolor[from] = r.u16() }
            41 -> repeat(r.u8()) { r.u16(); r.u16() }
            in 60..69 -> r.u16()
            else -> break@loop
        }
        return Kit(id, part, models, recolor, selectable)
    }

    /** "male" / "female" / empty give the starting character; otherwise kit numbers such as "0,10,18,26,33,36,42". */
    fun choose(ids: String): List<Int>? = when (ids.trim().lowercase()) {
        "", "male", "man", "default" -> MALE
        "female", "woman" -> FEMALE
        else -> CacheTool.parseIds(ids)
    }
}
