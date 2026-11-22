package dev.pocketide.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.ImageView
import java.io.ByteArrayOutputStream

// v0.4 W2: images attached to chat messages, sent to the model as JPEG data URLs.
object ChatImages {
    const val MAX_SIDE = 1280
    const val QUALITY = 80
    const val MAX_ATTACHED = 4

    /** Reads a picked image, downscaled to MAX_SIDE. Null when it can't be decoded. */
    fun fromUri(ctx: Context, uri: Uri): String? = try {
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIDE || bounds.outHeight / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().also { it.inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        bmp?.let { fromBitmap(it) }
    } catch (e: Exception) { null }

    fun fromBitmap(src: Bitmap): String {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, maxOf(1, (src.width * scale).toInt()), maxOf(1, (src.height * scale).toInt()), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun thumb(ctx: Context, dataUrl: String, sizeDp: Int = 64): ImageView {
        val iv = ImageView(ctx)
        iv.contentDescription = "Attached image"
        val bytes = Base64.decode(dataUrl.substringAfter("base64,"), Base64.DEFAULT)
        val opts = BitmapFactory.Options().also { it.inSampleSize = 4 }
        iv.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts))
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        val px = Ui.dp(ctx, sizeDp)
        iv.layoutParams = android.widget.LinearLayout.LayoutParams(px, px).also { it.rightMargin = Ui.dp(ctx, 6) }
        return iv
    }
}
