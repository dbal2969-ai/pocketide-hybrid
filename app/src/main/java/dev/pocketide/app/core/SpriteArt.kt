package dev.pocketide.app.core

/** Draw sprites from text: each character is a palette colour. Also packs PNG files into one atlas image with a Phaser-style JSON. */
object SpriteArt {
    fun parseColour(s: String): Int {
        val t = s.trim().removePrefix("#")
        return try {
            when (t.length) {
                6 -> (255 shl 24) or t.toInt(16)
                8 -> (t.substring(6).toInt(16) shl 24) or t.substring(0, 6).toInt(16)
                3 -> { val r = t.substring(0, 1).toInt(16) * 17; val g = t.substring(1, 2).toInt(16) * 17; val b = t.substring(2, 3).toInt(16) * 17; (255 shl 24) or (r shl 16) or (g shl 8) or b }
                else -> throw NumberFormatException()
            }
        } catch (e: NumberFormatException) { throw IllegalArgumentException("'$s' is not a colour (use #rrggbb)") }
    }

    /** '.' and ' ' are transparent unless the palette says otherwise. */
    fun fromText(rows: List<String>, palette: Map<String, String>, scale: Int): Png.Image {
        if (rows.isEmpty()) throw IllegalArgumentException("rows is empty")
        val sc = scale.coerceIn(1, 32); val w = rows.maxOf { it.length }; val h = rows.size
        if (w * sc > 2048 || h * sc > 2048) throw IllegalArgumentException("sprite would be larger than 2048 pixels")
        val pal = HashMap<Char, Int>(); for ((k, v) in palette) if (k.length == 1) pal[k[0]] = parseColour(v)
        val px = IntArray(w * sc * h * sc)
        for (y in 0 until h) for (x in 0 until w) {
            val ch = rows[y].getOrNull(x) ?: ' '
            val c = pal[ch] ?: if (ch == '.' || ch == ' ') 0 else throw IllegalArgumentException("'$ch' (row ${y + 1}, column ${x + 1}) is not in the palette")
            for (dy in 0 until sc) for (dx in 0 until sc) px[(y * sc + dy) * w * sc + x * sc + dx] = c
        }
        return Png.Image(w * sc, h * sc, px)
    }

    class Atlas(val image: Png.Image, val json: String)

    /** Shelf-pack images (name to image) into one sheet at most [maxWidth] wide, 1 pixel apart. */
    fun pack(images: Map<String, Png.Image>, imageName: String, maxWidth: Int = 2048): Atlas {
        if (images.isEmpty()) throw IllegalArgumentException("no PNG files to pack")
        val order = images.entries.sortedWith(compareByDescending<Map.Entry<String, Png.Image>> { it.value.height }.thenBy { it.key })
        var x = 0; var y = 0; var rowH = 0; var width = 0
        val pos = LinkedHashMap<String, IntArray>()
        for ((name, img) in order) {
            if (img.width > maxWidth) throw IllegalArgumentException("$name is wider than $maxWidth pixels")
            if (x + img.width > maxWidth) { x = 0; y += rowH + 1; rowH = 0 }
            pos[name] = intArrayOf(x, y); x += img.width + 1; rowH = maxOf(rowH, img.height); width = maxOf(width, x - 1)
        }
        val height = y + rowH
        if (height > 4096) throw IllegalArgumentException("atlas would be taller than 4096 pixels: pack fewer images")
        val px = IntArray(width * height)
        for ((name, img) in order) { val p = pos.getValue(name); for (j in 0 until img.height) System.arraycopy(img.argb, j * img.width, px, (p[1] + j) * width + p[0], img.width) }
        val frames = LinkedHashMap<String, Any?>()
        for ((name, img) in order) { val p = pos.getValue(name); frames[name] = linkedMapOf("frame" to linkedMapOf("x" to p[0], "y" to p[1], "w" to img.width, "h" to img.height)) }
        val json = JsonText.write(linkedMapOf("frames" to frames, "meta" to linkedMapOf("image" to imageName, "size" to linkedMapOf("w" to width, "h" to height))))
        return Atlas(Png.Image(width, height, px), json)
    }
}
