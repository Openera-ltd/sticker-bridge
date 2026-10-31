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
    var username: String? = null

    override suspend fun startLink(installId: String) = LinkStart("SB-1", "p", Long.MAX_VALUE)
    override suspend fun linkStatus(code: String, pollToken: String) = LinkStatus("pending")
    override suspend fun stickers(token: String, cursor: String?): StickerPage {
        calls++
        failWith?.let { throw it }
        val from = cursor?.toInt() ?: 0
        val page = stickers.drop(from).take(2)
        return StickerPage(page, (from + page.size).toString(), username)
    }
    override suspend fun download(token: String?, path: String) = files[path] ?: error("missing $path")
    override suspend fun downloadTo(path: String, dest: File, onProgress: (Long, Long) -> Unit) = Unit
    override suspend fun unlink(token: String) = Unit
    override suspend fun deleteData(token: String) = Unit
    override suspend fun config() = RemoteConfig()
    override suspend fun latestApp(): AppRelease? = null
    override suspend fun donation(): SignedDonation? = null

    fun add(id: String, bytes: ByteArray, animated: Boolean = false, emojis: List<String> = emptyList()) {
        stickers += RemoteSticker(id, "v1/stickers/$id/file", animated, 0, emojis)
        files["v1/stickers/$id/file"] = bytes
    }

    fun addReplacement(id: String, bytes: ByteArray, replaces: String, animated: Boolean = false) {
        stickers += RemoteSticker(id, "v1/stickers/$id/file", animated, 0, emptyList(), replaces)
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
            assertTrue(it.isNew)
        }
        assertTrue(store.packs().isEmpty()) // no packs until the user makes one
        assertEquals("5", settings.currentSession().cursor)
    }

    @Test fun secondSyncOnlyFetchesWhatsNew() = runBlocking {
        repeat(3) { api.add("s$it", testPng()) }
        repo.sync()
        assertEquals(0, repo.sync().added)
        api.add("s3", testPng(Color.GREEN))
        assertEquals(1, repo.sync().added)
        assertEquals(4, store.state.value.stickers.size)
    }

    @Test fun badFilesAreSkippedNotFatal() = runBlocking {
        api.add("ok", testPng())
        api.add("junk", "not an image".toByteArray())
        api.add("anim-wrong-size", StickerProcessorTest.animatedWebpHeader(100, 100), animated = true)
        api.add("anim-ok", StickerProcessorTest.animatedWebpHeader(512, 512), animated = true)
        val r = repo.sync()
        assertEquals(2, r.added)
        assertEquals(2, r.skipped)
        assertEquals(listOf(false, true), store.state.value.stickers.sortedBy { it.animated }.map { it.animated })
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

    @Test fun userPacksSplitByKindAndRefreshWhenChanged() = runBlocking {
        repeat(4) { api.add("s$it", testPng()) }
        repeat(3) { api.add("a$it", StickerProcessorTest.animatedWebpHeader(512, 512), animated = true) }
        repo.sync()

        // One pick of still + animated = two packs (WhatsApp never mixes them), both named "Sticker Bridge · ...".
        val ids = repo.createPack("Faves", listOf("s0", "s1", "s2", "a0", "a1", "a2"))
        assertEquals(2, ids.size)
        val packs = store.packs()
        assertEquals(listOf("Sticker Bridge · Faves", "Sticker Bridge · Faves Animated"), packs.map { it.name })
        assertEquals(listOf(false, true), packs.map { it.animated })
        assertTrue(packs.all { it.ready && store.file(it.trayFile).exists() })
        var v = packs[0].version

        // Adding takes only the matching kind.
        assertEquals(1, repo.addToPack(ids[0], listOf("s3", "a0")))
        assertEquals(4, store.packs()[0].stickers.size)
        assertEquals(++v, store.packs()[0].version)

        // Removing a sticker from the app removes it from its packs too.
        repo.remove("s1")
        assertFalse(store.file("s1.webp").exists())
        assertEquals(listOf("s0", "s2", "s3"), store.packs()[0].stickers.map { it.id })
        assertEquals(++v, store.packs()[0].version)

        repo.renamePack(ids[0], "Best")
        assertEquals("Sticker Bridge · Best", store.packs()[0].name)
        repo.removeFromPack(ids[0], "s0")
        assertFalse(store.packs()[0].ready)
        repo.deletePack(ids[1])
        assertEquals(1, store.packs().size)
        assertEquals(6, store.state.value.stickers.size) // deleting a pack keeps its stickers

        val before = store.file("s2.webp").readBytes()
        settings.update { it.copy(outline = true) }
        repo.reprocessAll()
        assertFalse(before.contentEquals(store.file("s2.webp").readBytes()))

        repo.markAllSeen()
        assertTrue(store.state.value.stickers.none { it.isNew })
    }

    @Test fun bigSelectionsSpillIntoMorePacks() = runBlocking {
        repeat(32) { api.add("s$it", testPng()) }
        while (repo.sync().added > 0) Unit // one sync reads at most 10 pages
        val ids = repo.createPack("Lots", store.state.value.stickers.map { it.id })
        assertEquals(2, ids.size)
        assertEquals(listOf(30, 2), store.packs().map { it.stickers.size })
        assertEquals("Sticker Bridge · Lots 2", store.packs()[1].name)
    }

    @Test fun remadeStickerReplacesTheOldOneEverywhere() = runBlocking {
        repeat(3) { api.add("s$it", testPng()) }
        repo.sync()
        val pack = repo.createPack("P", listOf("s0", "s1", "s2")).single()
        api.addReplacement("s1v2", testPng(Color.GREEN), replaces = "s1")
        assertEquals(1, repo.sync().added)
        assertEquals(listOf("s0", "s1v2", "s2"), store.state.value.stickers.map { it.id }.sorted())
        assertEquals(listOf("s0", "s1v2", "s2"), store.packs().single { it.id == pack }.stickers.map { it.id })
        assertFalse(store.file("s1.webp").exists())
    }

    @Test fun oldAutomaticPacksBecomeUserPacksWithTheSameIds() = runBlocking {
        repeat(4) { api.add("s$it", testPng()) }
        repo.sync()
        store.update(false) { it.copy(packsMigrated = false) } // as if from an older version
        repo.migratePacks()
        val p = store.packs().single()
        assertEquals("static-1", p.id) // same id = the pack already in WhatsApp keeps working
        assertEquals("Sticker Bridge · My TikTok Stickers", p.name)
        repo.migratePacks()
        assertEquals(1, store.packs().size) // only once
    }

    @Test fun usernameFoundLaterReplacesTheIdShownAtConnect() = runBlocking {
        settings.saveLink("tok", Account("7579924027591345174", "u_7579924027591345174"))
        api.username = "togata_ps5"
        repo.sync()
        assertEquals("togata_ps5", settings.currentSession().username)
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
    }
}
