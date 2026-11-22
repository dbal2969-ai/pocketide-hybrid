package dev.pocketide.app.core

/**
 * A page of pictures with their file names under them, for look_at_images. Sprites and art often come with numbers as names
 * (1442_222.png), so nothing but looking at them tells the assistant which one is a player or a tree. The sheet is shown
 * through the same hidden page check as look_at_preview, so a model that can see images gets one screenshot per page.
 */
object ContactSheet {
    const val PER_PAGE = 48
    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp")

    fun isImage(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in IMAGE

    fun pages(count: Int, per: Int = PER_PAGE): Int = maxOf(1, (count + per - 1) / per)

    /** Files of one page (1-based), in name order with numbers compared as numbers (2_0 before 10_0). */
    fun slice(files: List<String>, page: Int, per: Int = PER_PAGE): List<String> {
        val sorted = files.sortedWith(naturalOrder())
        val p = page.coerceIn(1, pages(sorted.size, per))
        return sorted.drop((p - 1) * per).take(per)
    }

    private fun naturalOrder(): Comparator<String> = Comparator { a, b ->
        val x = Regex("""\d+|\D+""").findAll(a).map { it.value }.toList()
        val y = Regex("""\d+|\D+""").findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) p.toBigInteger().compareTo(q.toBigInteger()).takeIf { it != 0 } ?: p.length.compareTo(q.length) else p.compareTo(q)
            if (c != 0) return@Comparator c
        }
        x.size.compareTo(y.size)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun url(path: String) = "/" + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    fun html(dir: String, files: List<String>, page: Int, per: Int = PER_PAGE): String {
        val total = files.size
        val p = page.coerceIn(1, pages(total, per))
        val shown = slice(files, p, per)
        val from = (p - 1) * per + 1
        val sb = StringBuilder("<!doctype html><meta name=viewport content=\"width=device-width,initial-scale=1\"><style>" +
            "body{margin:0;background:#222;color:#ddd;font:10px sans-serif}h1{font-size:11px;margin:4px 6px}" +
            ".g{display:grid;grid-template-columns:repeat(5,1fr);gap:3px;padding:3px}" +
            "figure{margin:0;text-align:center;background:#333;padding:2px}" +
            "img{width:100%;height:56px;object-fit:contain;image-rendering:pixelated;background:#555}" +
            "figcaption{font-size:8px;word-break:break-all}</style>")
        sb.append("<h1>").append(esc(dir.ifEmpty { "(project)" })).append(": images ").append(from).append("-").append(from + shown.size - 1)
            .append(" of ").append(total).append(" (page ").append(p).append(" of ").append(pages(total, per)).append(")</h1><div class=g>")
        for (f in shown) sb.append("<figure><img src=\"").append(esc(url(f))).append("\"><figcaption>")
            .append(esc(f.substringAfterLast('/').substringBeforeLast('.'))).append("</figcaption></figure>")
        sb.append("</div>")
        return sb.toString()
    }
}
