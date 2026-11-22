package dev.pocketide.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.pocketide.app.core.MediaOps
import java.io.ByteArrayOutputStream
import java.io.File

/** Carries out the picture and PDF actions with Android's own graphics and PDF classes. */
object MediaEdit {
    class Result(val message: String, val written: List<String>)
    class Failed(message: String) : Exception(message)

    private fun decode(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw Failed("That picture could not be read.")

    private fun encode(bm: Bitmap, format: String, quality: Int): ByteArray {
        val f = when (format) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        val out = ByteArrayOutputStream()
        if (!bm.compress(f, quality, out)) throw Failed("The picture could not be saved as $format.")
        return out.toByteArray()
    }

    private fun flatten(bm: Bitmap): Bitmap {
        if (!bm.hasAlpha()) return bm
        val copy = Bitmap.createBitmap(bm.width, bm.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(copy)
        c.drawColor(0xFFFFFFFF.toInt())
        c.drawBitmap(bm, 0f, 0f, null)
        return copy
    }

    /** Runs one action. [args] values are plain strings from the form. Returns what happened and which files were written. */
    fun apply(store: ProjectStore, path: String, actionId: String, args: Map<String, String>): Result {
        val kind = MediaOps.kindOf(path) ?: throw Failed("That kind of file has no picture actions.")
        val action = MediaOps.action(path, actionId) ?: throw Failed("Unknown action '$actionId'.")
        fun g(k: String) = args[k].orEmpty()
        val bytes = store.readBytes(path) ?: throw Failed("$path could not be read.")
        return try {
            if (kind == MediaOps.Kind.IMAGE) image(store, path, action.id, bytes, ::g) else pdf(store, path, action.id, bytes, ::g)
        } catch (e: IllegalArgumentException) {
            throw Failed(e.message ?: "Those values do not work.")
        } catch (e: OutOfMemoryError) {
            throw Failed("That file is too large to work on here.")
        }
    }

    private fun image(store: ProjectStore, path: String, id: String, bytes: ByteArray, g: (String) -> String): Result {
        val src = decode(bytes)
        val ext = path.substringAfterLast('.', "png").lowercase().let { if (it == "jpeg") "jpg" else it }
        val keepFormat = if (ext == "png" || ext == "jpg" || ext == "webp") ext else "png"
        fun save(bm: Bitmap, suffix: String, format: String = keepFormat, quality: Int = 92): Result {
            val out = MediaOps.outputPath(path, suffix, format) { store.exists(it) }
            val data = encode(if (format == "jpg") flatten(bm) else bm, format, quality)
            if (!store.writeBytes(out, data)) throw Failed("Could not write $out.")
            return Result("${bm.width}×${bm.height}, ${data.size / 1024} KB → $out", listOf(out))
        }
        return when (id) {
            "resize" -> {
                val (w, h) = MediaOps.targetSize(g("width"), g("height"), src.width, src.height)
                save(Bitmap.createScaledBitmap(src, w, h, true), "${w}x${h}")
            }
            "crop" -> {
                val r = MediaOps.cropRect(g("x"), g("y"), g("width"), g("height"), src.width, src.height)
                save(Bitmap.createBitmap(src, r[0], r[1], r[2], r[3]), "crop")
            }
            "rotate" -> {
                val d = MediaOps.degrees(g("degrees"))
                val m = Matrix().also { it.postRotate(d.toFloat()) }
                save(Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true), "rot$d")
            }
            "flip" -> {
                val horizontal = g("direction").trim().lowercase().startsWith("h")
                val m = Matrix().also { if (horizontal) it.postScale(-1f, 1f) else it.postScale(1f, -1f) }
                save(Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true), if (horizontal) "flipped-h" else "flipped-v")
            }
            "convert" -> {
                val f = MediaOps.format(g("format"))
                save(src, f, f, MediaOps.quality(g("quality"), if (f == "png") 100 else 90))
            }
            "thumbnail" -> {
                val long = maxOf(src.width, src.height).coerceAtLeast(1)
                val k = minOf(1.0, 320.0 / long)
                save(Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true), "thumb")
            }
            else -> throw Failed("Unknown picture action '$id'.")
        }
    }

    private fun <T> withPdf(store: ProjectStore, path: String, block: (PdfRenderer) -> T): T {
        val f = store.resolve(path) ?: throw Failed("$path is not in the project.")
        if (!f.isFile) throw Failed("$path could not be read.")
        var fd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        try {
            fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            r = try { PdfRenderer(fd) } catch (e: Exception) { throw Failed("This PDF could not be opened (it may be damaged or password-protected).") }
            return block(r)
        } finally {
            try { r?.close() } catch (e: Exception) { }
            try { fd?.close() } catch (e: Exception) { }
        }
    }

    private fun renderPage(r: PdfRenderer, index: Int, scale: Int): Bitmap {
        val page = r.openPage(index)
        try {
            val w = (page.width * scale).coerceIn(1, 5000)
            val h = (page.height * scale).coerceIn(1, 5000)
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bm).drawColor(0xFFFFFFFF.toInt())
            page.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bm
        } finally { page.close() }
    }

    private fun pdf(store: ProjectStore, path: String, id: String, bytes: ByteArray, g: (String) -> String): Result = when (id) {
        "info" -> withPdf(store, path) { r ->
            val sizes = (0 until r.pageCount).take(50).map { i -> val p = r.openPage(i); val s = "${p.width}×${p.height}pt"; p.close(); s }
            Result("${r.pageCount} page${if (r.pageCount == 1) "" else "s"}, ${bytes.size / 1024} KB. Sizes: " +
                sizes.groupingBy { it }.eachCount().entries.joinToString(", ") { "${it.key} ×${it.value}" }, emptyList())
        }
        "to_images" -> withPdf(store, path) { r ->
            val pages = MediaOps.pageList(g("pages"), r.pageCount)
            if (pages.size > 50) throw Failed("That is ${pages.size} pages; do at most 50 at a time.")
            val scale = MediaOps.scale(g("scale"))
            val written = ArrayList<String>()
            for (i in pages) {
                val bm = renderPage(r, i, scale)
                val out = MediaOps.outputPath(path, "page${i + 1}", "png") { store.exists(it) }
                if (!store.writeBytes(out, encode(bm, "png", 100))) throw Failed("Could not write $out.")
                bm.recycle()
                written.add(out)
            }
            Result("Saved ${written.size} page${if (written.size == 1) "" else "s"} as PNG.", written)
        }
        "extract_pages", "rotate" -> withPdf(store, path) { r ->
            val rotate = if (id == "rotate") MediaOps.degrees(g("degrees")) else 0
            val chosen = MediaOps.pageList(g("pages"), r.pageCount)
            val keep = if (id == "extract_pages") chosen else (0 until r.pageCount).toList()
            if (keep.size > 100) throw Failed("That is ${keep.size} pages; do at most 100 at a time.")
            val doc = PdfDocument()
            try {
                for (i in keep) {
                    val bm = renderPage(r, i, 2)
                    val turn = if (id == "rotate" && i in chosen) rotate else 0
                    val shown = if (turn == 90 || turn == 270) Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().also { it.postRotate(turn.toFloat()) }, true)
                    else if (turn == 180) Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().also { it.postRotate(180f) }, true)
                    else bm
                    val pw = shown.width / 2
                    val ph = shown.height / 2
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(pw.coerceAtLeast(1), ph.coerceAtLeast(1), i + 1).create())
                    page.canvas.drawBitmap(shown, null, Rect(0, 0, pw.coerceAtLeast(1), ph.coerceAtLeast(1)), null)
                    doc.finishPage(page)
                    if (shown !== bm) shown.recycle()
                    bm.recycle()
                }
                val suffix = if (id == "extract_pages") "pages" else "rotated"
                val out = MediaOps.outputPath(path, suffix, "pdf") { store.exists(it) }
                val tmp = File.createTempFile("pocketide", ".pdf", store.root)
                try {
                    tmp.outputStream().use { doc.writeTo(it) }
                    if (!store.writeBytes(out, tmp.readBytes())) throw Failed("Could not write $out.")
                } finally { tmp.delete() }
                Result("${keep.size} page${if (keep.size == 1) "" else "s"} → $out. ${MediaOps.PDF_NOTE}", listOf(out))
            } finally { doc.close() }
        }
        else -> throw Failed("Unknown PDF action '$id'.")
    }
}
