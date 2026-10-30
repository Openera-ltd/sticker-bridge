package tech.openera.stickerbridge.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns whatever the bot saved into a file WhatsApp accepts:
 * 512 x 512 WebP, transparent, under 100 KB (still) or 500 KB (animated).
 */
object StickerProcessor {
    const val SIZE = 512
    const val MAX_STATIC = 100 * 1024
    const val MAX_ANIMATED = 500 * 1024
    const val TRAY = 96
    const val MAX_TRAY = 50 * 1024
    private const val OUTLINE = 8

    enum class Kind { StaticImage, AnimatedWebp, Gif, Unknown }

    fun detect(b: ByteArray): Kind = when {
        b.size >= 16 && b.ascii(0, 4) == "RIFF" && b.ascii(8, 4) == "WEBP" ->
            if (b.size >= 21 && b.ascii(12, 4) == "VP8X" && (b[20].toInt() and 0x02) != 0) Kind.AnimatedWebp
            else Kind.StaticImage
        b.size >= 6 && b.ascii(0, 4) == "GIF8" -> Kind.Gif
        b.size >= 8 && b[0] == 0x89.toByte() && b.ascii(1, 3) == "PNG" -> Kind.StaticImage
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> Kind.StaticImage
        else -> Kind.Unknown
    }

    /** Width and height from an animated WebP's VP8X header, or null. */
    fun webpCanvasSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 30 || b.ascii(12, 4) != "VP8X") return null
        fun u24(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16)
        return (u24(24) + 1) to (u24(27) + 1)
    }

    /** Animated stickers are converted on the server; here we only check they meet WhatsApp's rules. */
    fun isValidAnimated(b: ByteArray): Boolean =
        detect(b) == Kind.AnimatedWebp && b.size <= MAX_ANIMATED && webpCanvasSize(b) == (SIZE to SIZE)

    /** Converts a still image (PNG, JPEG, WebP, or a GIF's first frame) to a WhatsApp sticker. */
    fun toStaticSticker(input: ByteArray, outline: Boolean): ByteArray {
        val src = BitmapFactory.decodeByteArray(input, 0, input.size)
            ?: throw IllegalArgumentException("Not an image")
        try {
            return encodeWebp(compose(src, outline), MAX_STATIC)
        } finally {
            src.recycle()
        }
    }

    fun compose(src: Bitmap, outline: Boolean): Bitmap {
        val pad = if (outline) OUTLINE + 4 else 4
        val box = SIZE - pad * 2
        val scale = minOf(box.toFloat() / src.width, box.toFloat() / src.height)
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val x = (SIZE - w) / 2f
        val y = (SIZE - h) / 2f
        if (outline) {
            // White silhouette drawn around the image = WhatsApp's recommended 8 px outline.
            val white = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            }
            for (r in intArrayOf(OUTLINE / 2, OUTLINE)) {
                for (deg in 0 until 360 step 15) {
                    val a = Math.toRadians(deg.toDouble())
                    c.drawBitmap(scaled, x + (cos(a) * r).toFloat(), y + (sin(a) * r).toFloat(), white)
                }
            }
        }
        c.drawBitmap(scaled, x, y, Paint(Paint.FILTER_BITMAP_FLAG))
        if (scaled !== src) scaled.recycle()
        return out
    }

    @Suppress("DEPRECATION")
    fun encodeWebp(bmp: Bitmap, maxBytes: Int): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        var last = ByteArray(0)
        for (q in intArrayOf(92, 85, 75, 65, 55, 45, 35, 25)) {
            val bos = ByteArrayOutputStream()
            bmp.compress(format, q, bos)
            last = bos.toByteArray()
            if (last.size <= maxBytes) return last
        }
        throw IllegalStateException("Could not get sticker under ${maxBytes / 1024} KB (got ${last.size / 1024} KB)")
    }

    /** 96 x 96 PNG icon for a pack, made from its first sticker. */
    fun tray(stickerFile: ByteArray): ByteArray {
        val src = BitmapFactory.decodeByteArray(stickerFile, 0, stickerFile.size)
            ?: throw IllegalArgumentException("Not an image")
        val small = Bitmap.createScaledBitmap(src, TRAY, TRAY, true)
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.PNG, 100, bos)
        if (small !== src) small.recycle()
        src.recycle()
        val png = bos.toByteArray()
        check(png.size <= MAX_TRAY) { "Tray icon too large" }
        return png
    }

    private fun ByteArray.ascii(from: Int, len: Int) = String(this, from, len, Charsets.US_ASCII)
}
