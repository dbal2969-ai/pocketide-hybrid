package dev.pocketide.app.core

/** Which picture and PDF actions exist, and checking their arguments. The work itself needs Android, this part does not. */
object MediaOps {
    enum class Kind { IMAGE, PDF }

    fun kindOf(path: String): Kind? = when (path.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "webp", "bmp", "heic", "heif" -> Kind.IMAGE
        "pdf" -> Kind.PDF
        else -> null
    }

    fun canEdit(path: String): Boolean = kindOf(path) != null

    class Param(val name: String, val help: String, val required: Boolean = false)
    class Action(val id: String, val label: String, val help: String, val params: List<Param> = emptyList(), val lossy: Boolean = false)

    private val IMAGE = listOf(
        Action("resize", "Resize", "Scale the picture. Give a width, a height, or both.", listOf(Param("width", "pixels, or leave empty"), Param("height", "pixels, or leave empty"))),
        Action("crop", "Crop", "Cut out a rectangle.", listOf(Param("x", "left edge in pixels", true), Param("y", "top edge in pixels", true), Param("width", "pixels", true), Param("height", "pixels", true))),
        Action("rotate", "Rotate", "Turn the picture by 90, 180 or 270 degrees.", listOf(Param("degrees", "90, 180 or 270", true))),
        Action("flip", "Flip", "Mirror the picture.", listOf(Param("direction", "horizontal or vertical", true))),
        Action("convert", "Convert", "Save as another format.", listOf(Param("format", "png, jpg or webp", true), Param("quality", "1-100, for jpg and webp"))),
        Action("thumbnail", "Make a thumbnail", "A small copy, at most 320 pixels on its long side.")
    )

    private val PDF = listOf(
        Action("info", "Page count and sizes", "How many pages there are and how big they are."),
        Action("extract_pages", "Keep only some pages", "Make a new PDF with the pages you name.", listOf(Param("pages", "for example 1-3,5", true)), lossy = true),
        Action("rotate", "Rotate pages", "Turn pages by 90, 180 or 270 degrees.", listOf(Param("degrees", "90, 180 or 270", true), Param("pages", "for example 1-3, or empty for all")), lossy = true),
        Action("to_images", "Save pages as pictures", "Write each page as a PNG next to the PDF.", listOf(Param("pages", "for example 1-3, or empty for all"), Param("scale", "1 to 4, bigger is sharper")))
    )

    fun actions(path: String): List<Action> = when (kindOf(path)) {
        Kind.IMAGE -> IMAGE
        Kind.PDF -> PDF
        null -> emptyList()
    }

    fun action(path: String, id: String): Action? = actions(path).firstOrNull { it.id == id }

    /** Pages written as "1-3,5" turned into zero-based numbers. Empty means every page. */
    fun pageList(spec: String, pageCount: Int): List<Int> {
        val s = spec.trim()
        if (s.isEmpty()) return (0 until pageCount).toList()
        val out = LinkedHashSet<Int>()
        for (part in s.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = Regex("""^(\d+)\s*(?:-\s*(\d+))?$""").matchEntire(part) ?: throw IllegalArgumentException("'$part' is not a page or a range like 2-5.")
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt()
            if (a < 1 || b < a) throw IllegalArgumentException("'$part' is not a valid range; pages start at 1.")
            for (p in a..b) { if (p <= pageCount) out.add(p - 1) }
        }
        if (out.isEmpty()) throw IllegalArgumentException("None of those pages exist; the file has $pageCount.")
        return out.toList()
    }

    /** 90, 180 or 270. */
    fun degrees(v: String): Int {
        val n = v.trim().toIntOrNull() ?: throw IllegalArgumentException("Give 90, 180 or 270 degrees.")
        val d = ((n % 360) + 360) % 360
        if (d != 90 && d != 180 && d != 270) throw IllegalArgumentException("Only 90, 180 or 270 degrees.")
        return d
    }

    fun format(v: String): String = when (v.trim().lowercase().removePrefix(".")) {
        "png" -> "png"
        "jpg", "jpeg" -> "jpg"
        "webp" -> "webp"
        else -> throw IllegalArgumentException("Choose png, jpg or webp.")
    }

    fun quality(v: String, fallback: Int = 90): Int {
        if (v.isBlank()) return fallback
        val n = v.trim().toIntOrNull() ?: throw IllegalArgumentException("Quality is a number from 1 to 100.")
        if (n < 1 || n > 100) throw IllegalArgumentException("Quality is a number from 1 to 100.")
        return n
    }

    fun scale(v: String, fallback: Int = 2): Int {
        if (v.isBlank()) return fallback
        val n = v.trim().toIntOrNull() ?: throw IllegalArgumentException("Scale is a number from 1 to 4.")
        return n.coerceIn(1, 4)
    }

    /** A size to resize to, from width and height; either may be empty, but not both. */
    fun targetSize(width: String, height: String, origW: Int, origH: Int): Pair<Int, Int> {
        val w = width.trim().toIntOrNull()
        val h = height.trim().toIntOrNull()
        if (w == null && h == null) throw IllegalArgumentException("Give a width, a height, or both.")
        if ((w != null && w <= 0) || (h != null && h <= 0)) throw IllegalArgumentException("Sizes must be bigger than zero.")
        if (origW <= 0 || origH <= 0) throw IllegalArgumentException("That picture has no size.")
        val nw = w ?: ((h!! * origW.toDouble() / origH).toInt().coerceAtLeast(1))
        val nh = h ?: ((w!! * origH.toDouble() / origW).toInt().coerceAtLeast(1))
        if (nw.toLong() * nh > 50_000_000L) throw IllegalArgumentException("That is too large; keep it under about 50 megapixels.")
        return nw to nh
    }

    /** A crop rectangle that stays inside the picture. */
    fun cropRect(x: String, y: String, w: String, h: String, origW: Int, origH: Int): IntArray {
        fun n(v: String, name: String): Int = v.trim().toIntOrNull() ?: throw IllegalArgumentException("$name must be a number.")
        val left = n(x, "x"); val top = n(y, "y"); val width = n(w, "width"); val height = n(h, "height")
        if (left < 0 || top < 0) throw IllegalArgumentException("x and y cannot be negative.")
        if (width <= 0 || height <= 0) throw IllegalArgumentException("Width and height must be bigger than zero.")
        if (left >= origW || top >= origH) throw IllegalArgumentException("That starts outside the picture (it is ${origW}×${origH}).")
        val cw = minOf(width, origW - left)
        val ch = minOf(height, origH - top)
        return intArrayOf(left, top, cw, ch)
    }

    /** A new file name for a result, keeping it next to the original: photo.png + "-small" + png. */
    fun outputPath(path: String, suffix: String, ext: String = "", exists: (String) -> Boolean = { false }): String {
        val dot = path.lastIndexOf('.')
        val stem = if (dot > 0) path.substring(0, dot) else path
        val e = (ext.ifEmpty { if (dot > 0) path.substring(dot + 1) else "bin" }).lowercase()
        var candidate = "$stem-$suffix.$e"
        var i = 2
        while (exists(candidate)) { candidate = "$stem-$suffix-$i.$e"; i++ }
        return candidate
    }

    const val PDF_NOTE = "Pages are redrawn as pictures inside the new PDF, so text in it can no longer be selected or searched."
}
