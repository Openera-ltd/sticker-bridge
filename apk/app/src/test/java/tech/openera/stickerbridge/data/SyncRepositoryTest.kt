package tech.openera.stickerbridge.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/** A server we control: a list of stickers, paged 2 at a time. */
class FakeApi(var stickers: MutableList<RemoteSticker> = mutableListOf()) : StickerApi {
    val files = mutableMapOf<String, ByteArray>()
    var failWith: Exception? = null
    var calls = 0

    override suspend fun startLink(installId: String) = LinkStart("SB-1", "p", Long.MAX_VALUE)
    override suspend fun linkStatus(code: String, pollToken: String) = LinkStatus("pending")
    override suspend fun stickers(token: String, cursor: String?): StickerPage {
        calls++
        failWith?.let { throw it }
        val from = cursor?.toInt() ?: 0
        val page = stickers.drop(from).take(2)
        return StickerPage(page, (from + page.size).toString())
    }
    override suspend fun download(token: String?, path: String) = files[path] ?: error("missing $path")
    override suspend fun downloadTo(path: String, dest: File, onProgress: (Long, Long) -> Unit) = Unit
    override suspend fun unlink(token: String) = Unit
    override suspend fun deleteData(token: String) = Unit
    override suspend fun config() = RemoteConfig()
    override suspend fun latestApp(): AppRelease? = null

    fun add(id: String, bytes: ByteArray, animated: Boolean = false, emojis: List<String> = emptyList()) {
        stickers += RemoteSticker(id, "v1/stickers/$id/file", animated, 0, emojis)
        files["v1/stickers/$id/file"] = bytes
    }
}

fun testPng(color: Int = Color.RED): ByteArray {
    val b = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

@RunWith(RobolectricTestRunner::class)
class SyncRepositoryTest {
    private lateinit var ctx: Context
    private lateinit var settings: SettingsRepo
    private lateinit var store: StickerStore
    private lateinit var api: FakeApi
    private lateinit var repo: SyncRepository
    private var now = 1_000L

    @Before fun setUp() = runBlocking {
        ctx = ApplicationProvider.getApplicationContext()
        settings = SettingsRepo(ctx)
        settings.clearSession()
        settings.update { AppSettings() }
        store = StickerStore(File(ctx.cacheDir, "st-${System.nanoTime()}"))
        api = FakeApi()
        repo = SyncRepository(api, settings, store) { now++ }
        settings.saveLink("tok", Account("1", "mia"))
    }

    @Test fun pullsConvertsAndPacksNewStickers() = runBlocking {
        repeat(5) { api.add("s$it", testPng()) }
        val r = repo.sync()
        assertNull(r.error)
        assertEquals(5, r.added)
        assertEquals(4, api.calls) // pages of 2, 2, 1, then an empty page confirms the end
        val st = store.state.value
        assertEquals(5, st.stickers.size)
        st.stickers.forEach {
            assertTrue(store.file(it.fileName).length() in 1..StickerProcessor.MAX_STATIC.toLong())
            assertEquals(listOf("😀"), it.emojis) // default emoji
            assertTrue(it.isNew)
        }
        val pack = store.packs("My TikTok Stickers").single()
        assertTrue(pack.ready)
        assertTrue(store.file(pack.trayFile).exists())
        assertEquals("5", settings.currentSession().cursor)
    }

    @Test fun secondSyncOnlyFetchesWhatsNew() = runBlocking {
        repeat(3) { api.add("s$it", testPng()) }
        repo.sync()
        val v1 = store.packs("My TikTok Stickers").single().version
        assertEquals(0, repo.sync().added)
        assertEquals(v1, store.packs("My TikTok Stickers").single().version) // nothing changed, no refresh
        api.add("s3", testPng(Color.GREEN), emojis = listOf("🔥"))
        assertEquals(1, repo.sync().added)
        val pack = store.packs("My TikTok Stickers").single()
        assertEquals(4, pack.stickers.size)
        assertEquals(v1 + 1, pack.version) // WhatsApp will refresh the pack
        assertEquals(listOf("🔥"), pack.stickers.last().emojis)
    }

    @Test fun badFilesAreSkippedNotFatal() = runBlocking {
        api.add("ok", testPng())
        api.add("junk", "not an image".toByteArray())
        api.add("anim-wrong-size", StickerProcessorTest.animatedWebpHeader(100, 100), animated = true)
        api.add("anim-ok", StickerProcessorTest.animatedWebpHeader(512, 512), animated = true)
        val r = repo.sync()
        assertEquals(2, r.added)
        assertEquals(2, r.skipped)
        val packs = store.packs("P")
        assertEquals(listOf(false, true), packs.map { it.animated })
    }

    @Test fun animatedStickersWhatsAppWouldRejectBecomeStillOnes() = runBlocking {
        // TikTok's DM stickers arrive as animated images in sizes WhatsApp won't take.
        api.add("tiktok-anim", testPng(Color.BLUE), animated = true)
        assertEquals(1, repo.sync().added)
        val s = store.state.value.stickers.single()
        assertFalse(s.animated)
        val bytes = store.file(s.fileName).readBytes()
        assertEquals("WEBP", String(bytes, 8, 4))
        assertTrue(bytes.size <= StickerProcessor.MAX_STATIC)
    }

    @Test fun expiredLoginSignsOut() = runBlocking {
        api.failWith = ApiException(401, "nope")
        val r = repo.sync()
        assertTrue(r.signedOut)
        assertFalse(settings.currentSession().connected)
    }

    @Test fun networkErrorKeepsLogin() = runBlocking {
        api.failWith = java.io.IOException("offline")
        val r = repo.sync()
        assertNotNull(r.error)
        assertFalse(r.signedOut)
        assertTrue(settings.currentSession().connected)
    }

    @Test fun removeEmojisRenameAndOutlineAllRefreshPacks() = runBlocking {
        repeat(4) { api.add("s$it", testPng()) }
        repo.sync()
        var v = store.packs("My TikTok Stickers").single().version

        repo.setEmojis("s0", listOf("😂", "😂", "🔥", "✨", "💯"))
        assertEquals(listOf("😂", "🔥", "✨"), store.state.value.stickers.first { it.id == "s0" }.emojis)
        assertEquals(++v, store.packs("My TikTok Stickers").single().version)

        repo.remove("s1")
        assertFalse(store.file("s1.webp").exists())
        assertEquals(3, store.packs("My TikTok Stickers").single().stickers.size)
        assertEquals(++v, store.packs("My TikTok Stickers").single().version)

        settings.update { it.copy(packName = "Faves") }
        repo.renamePacks()
        assertEquals("Faves", store.packs("Faves").single().name)

        val before = store.file("s2.webp").readBytes()
        settings.update { it.copy(outline = true) }
        repo.reprocessAll()
        assertFalse(before.contentEquals(store.file("s2.webp").readBytes()))

        repo.markAllSeen()
        assertTrue(store.state.value.stickers.none { it.isNew })
    }

    @Test fun storeSurvivesRestart() = runBlocking {
        repeat(3) { api.add("s$it", testPng()) }
        repo.sync()
        val again = StickerStore(store.dir)
        assertEquals(store.state.value, again.state.value)
    }

    @Test fun deleteAllClearsPhoneAndSession() = runBlocking {
        repeat(3) { api.add("s$it", testPng()) }
        repo.sync()
        repo.signOut(deleteServerData = true)
        assertTrue(store.state.value.stickers.isEmpty())
        assertFalse(settings.currentSession().connected)
    }

    @Test fun demoServerWorksEndToEnd() = runBlocking {
        var t = 1_000_000L
        val demo = SyncRepository(DemoApi { t }, settings, store) { now++ }
        assertEquals(DemoApi.FIRST_BATCH, demo.sync().added)
        assertEquals(0, demo.sync().added) // too soon for another
        t += DemoApi.NEW_EVERY_MS
        assertEquals(1, demo.sync().added)
        assertTrue(store.packs("P").first().ready)
    }
}
