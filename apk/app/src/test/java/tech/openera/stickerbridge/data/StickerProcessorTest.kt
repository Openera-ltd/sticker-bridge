package tech.openera.stickerbridge.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class StickerProcessorTest {

    private fun png(w: Int, h: Int, noisy: Boolean = false): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawCircle(w / 2f, h / 2f, minOf(w, h) / 3f, Paint().apply { color = Color.MAGENTA })
        if (noisy) {
            val r = Random(1)
            for (y in 0 until h step 2) for (x in 0 until w step 2) b.setPixel(x, y, Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256)))
        }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test fun detectsFormats() {
        assertEquals(StickerProcessor.Kind.StaticImage, StickerProcessor.detect(png(10, 10)))
        assertEquals(StickerProcessor.Kind.Gif, StickerProcessor.detect("GIF89a.....".toByteArray()))
        assertEquals(StickerProcessor.Kind.StaticImage, StickerProcessor.detect(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals(StickerProcessor.Kind.AnimatedWebp, StickerProcessor.detect(animatedWebpHeader(512, 512)))
        assertEquals(StickerProcessor.Kind.Unknown, StickerProcessor.detect("hello world!".toByteArray()))
    }

    @Test fun stillStickerIs512SquareWebpUnder100KB() {
        for (input in listOf(png(1200, 400), png(300, 900), png(64, 64), png(1024, 1024, noisy = true))) {
            val out = StickerProcessor.toStaticSticker(input, outline = true)
            assertTrue("size ${out.size}", out.size <= StickerProcessor.MAX_STATIC)
            assertEquals("RIFF", String(out, 0, 4))
            assertEquals("WEBP", String(out, 8, 4))
            val bmp = BitmapFactory.decodeByteArray(out, 0, out.size)
            assertEquals(512, bmp.width)
            assertEquals(512, bmp.height)
        }
    }

    @Test fun outlineAddsWhiteBorderAndKeepsCornersTransparent() {
        val src = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val with = StickerProcessor.compose(src, outline = true)
        val without = StickerProcessor.compose(src, outline = false)
        assertEquals(0, Color.alpha(with.getPixel(0, 0)))
        // Just outside the image edge: white with outline, transparent without.
        val edge = 12 + 2
        assertEquals(Color.WHITE, with.getPixel(256, edge - 6) or 0xFF000000.toInt())
        assertEquals(0, Color.alpha(without.getPixel(256, 1)))
        assertEquals(Color.BLUE, with.getPixel(256, 256))
    }

    @Test fun trayIs96PngUnder50KB() {
        val sticker = StickerProcessor.toStaticSticker(png(600, 600), outline = false)
        val tray = StickerProcessor.tray(sticker)
        assertTrue(tray.size <= StickerProcessor.MAX_TRAY)
        val bmp = BitmapFactory.decodeByteArray(tray, 0, tray.size)
        assertEquals(96, bmp.width)
        assertEquals(96, bmp.height)
        assertEquals(0x89.toByte(), tray[0])
    }

    @Test fun animatedMustBe512AndUnder500KB() {
        assertTrue(StickerProcessor.isValidAnimated(animatedWebpHeader(512, 512)))
        assertFalse(StickerProcessor.isValidAnimated(animatedWebpHeader(480, 512)))
        assertFalse(StickerProcessor.isValidAnimated(animatedWebpHeader(512, 512, size = 600 * 1024)))
        assertFalse(StickerProcessor.isValidAnimated(png(512, 512)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonImages() {
        StickerProcessor.toStaticSticker("not an image".toByteArray(), true)
    }

    companion object {
        /** A minimal RIFF/WEBP/VP8X header with the animation flag set. */
        fun animatedWebpHeader(w: Int, h: Int, size: Int = 64): ByteArray {
            val b = ByteArray(size)
            "RIFF".toByteArray().copyInto(b, 0)
            "WEBP".toByteArray().copyInto(b, 8)
            "VP8X".toByteArray().copyInto(b, 12)
            b[20] = 0x02
            fun put24(o: Int, v: Int) { b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte(); b[o + 2] = ((v shr 16) and 0xFF).toByte() }
            put24(24, w - 1); put24(27, h - 1)
            return b
        }
    }
}
