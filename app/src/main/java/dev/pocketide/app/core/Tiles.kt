package dev.pocketide.app.core

/**
 * Tile maps for the game kit. One JSON format ("pocketide map"): width, height, tileSize, layers {name: [tile ids, row by row from the
 * top]}, collision (rows of # and .), objects [{name,type,x,y,w,h,properties}], tilesets. Made from ASCII art, or imported from Tiled JSON.
 */
object Tiles {
    private val SOLID_LAYER = Regex("(?i)collision|solid|wall|block|obstacle")
    private val SOLID_PROP = Regex("(?i)solid|collides|blocked|collision")

    private fun json(m: Map<String, Any?>) = JsonText.write(m)

    /** rows: map text. legend: char -> {"tile": id, "solid": bool, "layer": "name"}. Characters not in the legend are empty ground. */
    fun fromText(rows: List<String>, legend: Map<String, Any?>, tileSize: Int, objectChars: Map<String, String>): String {
        if (rows.isEmpty()) throw IllegalArgumentException("rows is empty")
        val w = rows.maxOf { it.length }; val h = rows.size
        if (w > 1024 || h > 1024) throw IllegalArgumentException("a map can be at most 1024 x 1024 tiles")
        val ground = IntArray(w * h); val top = IntArray(w * h); val collision = ArrayList<String>(); val objects = ArrayList<Map<String, Any?>>()
        for ((y, row) in rows.withIndex()) {
            val sb = StringBuilder()
            for (x in 0 until w) {
                val ch = (row.getOrNull(x) ?: ' ').toString()
                val spec = legend[ch] as? Map<*, *>
                val tile = (spec?.get("tile") as? Number)?.toInt() ?: 0
                if ((spec?.get("layer") as? String)?.lowercase() == "top") top[y * w + x] = tile else ground[y * w + x] = tile
                sb.append(if (spec?.get("solid") == true) '#' else '.')
                objectChars[ch]?.let { objects.add(linkedMapOf("name" to it, "type" to it, "x" to x, "y" to y, "w" to 1, "h" to 1)) }
            }
            collision.add(sb.toString())
        }
        val layers = LinkedHashMap<String, Any?>(); layers["ground"] = ground.toList(); if (top.any { it != 0 }) layers["top"] = top.toList()
        return json(linkedMapOf("width" to w, "height" to h, "tileSize" to tileSize, "layers" to layers, "collision" to collision, "objects" to objects))
    }

    /** Tiled JSON (.tmj / .json with CSV or plain array data) to the pocketide map format. */
    fun fromTiled(text: String): String {
        val m = JsonText.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("not a Tiled JSON map")
        val w = (m["width"] as? Number)?.toInt() ?: throw IllegalArgumentException("map has no width")
        val h = (m["height"] as? Number)?.toInt() ?: throw IllegalArgumentException("map has no height")
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096) throw IllegalArgumentException("map size is not usable")
        val ts = (m["tilewidth"] as? Number)?.toInt() ?: 16
        if (m["infinite"] == true) throw IllegalArgumentException("infinite maps are not supported: in Tiled turn off Infinite in the map properties, then export again")
        val solidGids = HashSet<Int>()
        val tilesets = ArrayList<Map<String, Any?>>()
        for (t in (m["tilesets"] as? List<*>) ?: emptyList<Any>()) {
            val s = t as? Map<*, *> ?: continue
            val first = (s["firstgid"] as? Number)?.toInt() ?: 1
            tilesets.add(linkedMapOf("firstgid" to first, "name" to (s["name"] as? String ?: ""), "image" to (s["image"] as? String ?: ""), "tilewidth" to ((s["tilewidth"] as? Number)?.toInt() ?: ts),
                "columns" to ((s["columns"] as? Number)?.toInt() ?: 0), "tilecount" to ((s["tilecount"] as? Number)?.toInt() ?: 0)))
            for (tile in (s["tiles"] as? List<*>) ?: emptyList<Any>()) {
                val tm = tile as? Map<*, *> ?: continue
                val id = (tm["id"] as? Number)?.toInt() ?: continue
                for (p in (tm["properties"] as? List<*>) ?: emptyList<Any>()) {
                    val pm = p as? Map<*, *> ?: continue
                    if (SOLID_PROP.matches(pm["name"] as? String ?: "") && pm["value"] == true) solidGids.add(first + id)
                }
            }
        }
        val layers = LinkedHashMap<String, Any?>(); val blocked = BooleanArray(w * h); val objects = ArrayList<Map<String, Any?>>()
        for (l in (m["layers"] as? List<*>) ?: emptyList<Any>()) {
            val lm = l as? Map<*, *> ?: continue
            val name = lm["name"] as? String ?: "layer"
            when (lm["type"]) {
                "tilelayer" -> {
                    val data = lm["data"] as? List<*> ?: throw IllegalArgumentException("layer '$name' stores its tiles as text; in Tiled set Tile Layer Format to CSV and export again")
                    if (data.size != w * h) throw IllegalArgumentException("layer '$name' has ${data.size} tiles, expected ${w * h}")
                    val ids = data.map { ((it as? Number)?.toLong() ?: 0L).toInt() and 0x1FFFFFFF }
                    layers[name] = ids
                    val solidLayer = SOLID_LAYER.containsMatchIn(name)
                    for (i in ids.indices) if (ids[i] != 0 && (solidLayer || ids[i] in solidGids)) blocked[i] = true
                }
                "objectgroup" -> for (o in (lm["objects"] as? List<*>) ?: emptyList<Any>()) {
                    val om = o as? Map<*, *> ?: continue
                    val props = LinkedHashMap<String, Any?>()
                    for (p in (om["properties"] as? List<*>) ?: emptyList<Any>()) { val pm = p as? Map<*, *> ?: continue; (pm["name"] as? String)?.let { props[it] = pm["value"] } }
                    fun tiles(k: String, d: Double) = ((om[k] as? Number)?.toDouble() ?: d) / ts
                    objects.add(linkedMapOf("name" to (om["name"] as? String ?: ""), "type" to ((om["type"] ?: om["class"]) as? String ?: ""),
                        "x" to tiles("x", 0.0), "y" to tiles("y", 0.0), "w" to tiles("width", ts.toDouble()), "h" to tiles("height", ts.toDouble()), "properties" to props))
                }
            }
        }
        val collision = (0 until h).map { y -> String(CharArray(w) { x -> if (blocked[y * w + x]) '#' else '.' }) }
        return json(linkedMapOf("width" to w, "height" to h, "tileSize" to ts, "layers" to layers, "collision" to collision, "objects" to objects, "tilesets" to tilesets))
    }
}
