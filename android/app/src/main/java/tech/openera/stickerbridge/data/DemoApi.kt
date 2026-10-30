package tech.openera.stickerbridge.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/**
 * A pretend server for the demo build: connecting succeeds after a few seconds,
 * and each sync "receives" one more sticker. Nothing leaves the phone.
 */
class DemoApi(private val clock: () -> Long = System::currentTimeMillis) : StickerApi {
    private var linkStartedAt = 0L
    private var lastDeliveryAt = 0L

    override suspend fun startLink(installId: String): LinkStart {
        delay(400)
        linkStartedAt = clock()
        return LinkStart("SB-" + Random.nextInt(1000, 9999), "demo", clock() + 10 * 60_000)
    }

    override suspend fun linkStatus(code: String, pollToken: String): LinkStatus =
        if (clock() - linkStartedAt > 3_500) LinkStatus("linked", "demo-token", Account("demo", "you.on.tiktok"))
        else LinkStatus("pending")

    override suspend fun stickers(token: String, cursor: String?): StickerPage {
        delay(500)
        val have = cursor?.toIntOrNull() ?: 0
        val due = clock() - lastDeliveryAt >= NEW_EVERY_MS
        val upTo = when {
            have == 0 -> FIRST_BATCH
            due -> minOf(have + 1, WORDS.size)
            else -> have
        }
        if (upTo > have) lastDeliveryAt = clock()
        val items = (have until upTo).map { i ->
            RemoteSticker(id = "demo$i", file = "demo/$i", createdAt = clock(), emojis = listOf(EMOJI[i % EMOJI.size]))
        }
        return StickerPage(items, upTo.toString())
    }

    override suspend fun download(token: String?, path: String): ByteArray =
        render(path.substringAfterLast('/').toIntOrNull() ?: 0)

    override suspend fun downloadTo(path: String, dest: File, onProgress: (Long, Long) -> Unit) =
        throw UnsupportedOperationException("No updates in demo mode")

    override suspend fun unlink(token: String) = Unit
    override suspend fun deleteData(token: String) = Unit
    override suspend fun config() = RemoteConfig()
    override suspend fun latestApp(): AppRelease? = null

    /** Draws a simple text sticker, like the ones people send on TikTok. */
    private fun render(i: Int): ByteArray {
        val word = WORDS[i % WORDS.size]
        val (bg, fg) = COLORS[i % COLORS.size]
        val bmp = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = if (word.length > 4) 120f else 170f
            textAlign = Paint.Align.CENTER
        }
        val w = text.measureText(word) + 90
        val h = text.textSize + 90
        c.save()
        c.rotate(if (i % 2 == 0) -6f else 5f, 300f, 300f)
        c.drawRoundRect(RectF(300 - w / 2, 300 - h / 2, 300 + w / 2, 300 + h / 2), 48f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        c.drawText(word, 300f, 300f + text.textSize * 0.35f, text)
        c.restore()
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    companion object {
        const val FIRST_BATCH = 6
        /** In the demo, a "new" sticker arrives at most this often. */
        const val NEW_EVERY_MS = 20_000L
        val WORDS = listOf("LOL", "omg", "slay", "bruh", "no way", "ok!", "fr", "yesss", "brb", "iconic", "lmao", "ate", "periodt", "same", "help", "cute", "wow", "nah", "bet", "vibes")
        private val EMOJI = listOf("😂", "😱", "💅", "😐", "🙅", "👌", "💯", "🙌", "🏃", "✨", "🤣", "😋")
        private val COLORS = listOf(
            Color.parseColor("#FFD43B") to Color.parseColor("#111418"),
            Color.parseColor("#FF8FB1") to Color.parseColor("#3B0A1C"),
            Color.parseColor("#111418") to Color.WHITE,
            Color.parseColor("#9BE7C4") to Color.parseColor("#0B3B27"),
            Color.parseColor("#B9C8FF") to Color.parseColor("#14215A"),
            Color.parseColor("#FFB37A") to Color.parseColor("#3D1A00"),
        )
    }
}
