package dev.pocketide.app.core

/**
 * Screenshot or sketch to code (1.5.112): when the person attaches a picture and asks for a page like it, the picture becomes the
 * target. look_at_preview then compares a screenshot of the page with it, cell by cell on a coarse grid, and reports a similarity
 * score and the areas that differ most, with the target's and the page's colours (v0 and Lovable build from screenshots; this gives
 * models that cannot see images the same feedback in words). Pure logic: the pixels come from PreviewPane.
 */
object DesignMatch {
    const val COLS = 9
    const val ROWS = 16

    private val ASK = Regex("""(?i)\b(?:make|build|create|recreate|copy|clone|match|turn|code|design|implement|do)\b[^.?!\n]{0,60}\b(?:this|it|that|these|attached|the (?:design|mock-?up|screenshot|sketch|drawing|picture|image|photo|layout|wireframe|figma))\b|\blike (?:this|the (?:picture|screenshot|image|photo|sketch|design|mock-?up))\b|\bfrom (?:this|the|my) (?:screenshot|sketch|design|mock-?up|image|picture|photo|drawing|wireframe)\b|\blook(?:s)? like (?:this|the (?:picture|image|screenshot))\b""")

    /** A request with a picture that asks for something built like it. */
    fun wanted(text: String, hasImage: Boolean): Boolean = hasImage && ASK.containsMatchIn(text) &&
        !Regex("""(?i)^\s*(?:what|why|how|is|are|does|do|can you (?:see|tell|explain))\b""").containsMatchIn(text)

    const val NOTE = "\n\nThe attached picture is the design to build. Make the page match it: layout, sizes, colours, text and spacing. " +
        "Then call look_at_preview: its report compares the page with the picture (a similarity score and the areas that differ most). " +
        "Fix the biggest differences and look again, up to three rounds, then say what still differs and why."

    /** Average colour (0xRRGGBB) of each cell of a [cols]×[rows] grid over a w×h ARGB image. */
    fun grid(px: IntArray, w: Int, h: Int, cols: Int = COLS, rows: Int = ROWS): IntArray {
        val out = IntArray(cols * rows)
        for (cy in 0 until rows) for (cx in 0 until cols) {
            val x0 = cx * w / cols; val x1 = maxOf(x0 + 1, (cx + 1) * w / cols); val y0 = cy * h / rows; val y1 = maxOf(y0 + 1, (cy + 1) * h / rows)
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            var y = y0
            while (y < y1 && y < h) { var x = x0; while (x < x1 && x < w) { val p = px[y * w + x]; r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; n++; x++ }; y++ }
            out[cy * cols + cx] = if (n == 0L) 0 else (((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt())
        }
        return out
    }

    private fun dist(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 255) - ((b shr 16) and 255); val dg = ((a shr 8) and 255) - ((b shr 8) and 255); val db = (a and 255) - (b and 255)
        return Math.sqrt((dr * dr + dg * dg + db * db).toDouble()) / 441.7
    }
    private fun light(c: Int) = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000

    class Area(val name: String, val target: Int, val page: Int, val diff: Double)
    class Result(val score: Int, val areas: List<Area>)

    /** Similarity 0..100 of two grids, and the (up to) three 3×3-block areas that differ most. */
    fun compare(target: IntArray, page: IntArray, cols: Int = COLS, rows: Int = ROWS): Result {
        val d = target.indices.map { dist(target[it], page[it]) }
        val score = (100 * (1 - d.average())).toInt().coerceIn(0, 100)
        val areas = ArrayList<Area>()
        val v = listOf("top", "middle", "bottom"); val hz = listOf("left", "centre", "right")
        for (ay in 0 until 3) for (ax in 0 until 3) {
            val cells = (0 until rows).filter { it * 3 / rows == ay }.flatMap { y -> (0 until cols).filter { it * 3 / cols == ax }.map { x -> y * cols + x } }
            if (cells.isEmpty()) continue
            fun avg(g: IntArray): Int { var r = 0; var gg = 0; var b = 0; for (i in cells) { r += (g[i] shr 16) and 255; gg += (g[i] shr 8) and 255; b += g[i] and 255 }; val n = cells.size; return ((r / n) shl 16) or ((gg / n) shl 8) or (b / n) }
            areas.add(Area(v[ay] + " " + hz[ax], avg(target), avg(page), cells.map { d[it] }.average()))
        }
        return Result(score, areas.filter { it.diff >= 0.08 }.sortedByDescending { it.diff }.take(3))
    }

    fun hex(c: Int) = "#%06x".format(c and 0xffffff)

    /** The words added to the page report. */
    fun report(r: Result): String {
        val sb = StringBuilder("Compared with the design picture: ${r.score}% alike")
        sb.append(when { r.score >= 92 -> " (very close)."; r.score >= 80 -> " (close; check the areas below)."; else -> " (far from it)." })
        if (r.areas.isEmpty()) return sb.append(" No area stands out.").toString()
        sb.append(" Areas that differ most:")
        for (a in r.areas) {
            val lt = light(a.target); val lp = light(a.page)
            val how = when { lp - lt > 40 -> "the page is lighter"; lt - lp > 40 -> "the page is darker"; else -> "a different colour" }
            sb.append("\n- ${a.name}: the design is about ${hex(a.target)}, the page about ${hex(a.page)} ($how)")
        }
        return sb.append("\n(A coarse comparison of colour by area: it shows where to look, not what is there.)").toString()
    }
}
