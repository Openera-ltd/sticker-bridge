package tech.openera.stickerbridge.whatsapp

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import tech.openera.stickerbridge.Graph
import tech.openera.stickerbridge.data.Account
import tech.openera.stickerbridge.data.AppSettings
import tech.openera.stickerbridge.data.FakeApi
import tech.openera.stickerbridge.data.SyncRepository
import tech.openera.stickerbridge.data.testPng
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
class StickerContentProviderTest {
    private lateinit var ctx: Context
    private lateinit var provider: StickerContentProvider
    private lateinit var authority: String

    @Before fun setUp() = runBlocking {
        Graph.resetForTests()
        ctx = ApplicationProvider.getApplicationContext()
        val g = Graph.get(ctx)
        g.store.clear()
        g.settings.update { AppSettings() }
        g.settings.saveLink("t", Account("1", "mia"))
        val api = FakeApi()
        repeat(4) { api.add("s$it", testPng(), emojis = listOf("😂", "🔥")) }
        api.add("lonely", tech.openera.stickerbridge.data.StickerProcessorTest.animatedWebpHeader(512, 512), animated = true)
        SyncRepository(api, g.settings, g.store).sync()
        authority = WhatsApp.authority(ctx)
        provider = Robolectric.setupContentProvider(StickerContentProvider::class.java, authority)
    }

    @After fun tearDown() = Graph.resetForTests()

    private fun uri(path: String) = Uri.parse("content://$authority/$path")

    @Test fun listsOnlyPacksWhatsAppWillAccept() {
        provider.query(uri("metadata"), null, null, null, null)!!.use { c ->
            // the animated pack has 1 sticker, so it is hidden
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals("static-1", c.getString(c.getColumnIndexOrThrow("sticker_pack_identifier")))
            assertEquals(AppSettings.DEFAULT_PACK_NAME, c.getString(c.getColumnIndexOrThrow("sticker_pack_name")))
            assertEquals("tray_static-1.png", c.getString(c.getColumnIndexOrThrow("sticker_pack_icon")))
            assertEquals("Sticker Bridge", c.getString(c.getColumnIndexOrThrow("sticker_pack_publisher")))
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("animated_sticker_pack")))
            assertTrue(c.getString(c.getColumnIndexOrThrow("image_data_version")).toInt() >= 1)
            assertEquals(StickerContentProvider.PACK_COLUMNS.toList(), c.columnNames.toList())
        }
    }

    @Test fun listsStickersWithEmojis() {
        provider.query(uri("stickers/static-1"), null, null, null, null)!!.use { c ->
            assertEquals(4, c.count)
            c.moveToFirst()
            assertEquals("😂,🔥", c.getString(c.getColumnIndexOrThrow("sticker_emoji")))
            assertTrue(c.getString(c.getColumnIndexOrThrow("sticker_file_name")).endsWith(".webp"))
        }
    }

    @Test fun servesStickerAndTrayFiles() {
        provider.openAssetFile(uri("stickers_asset/static-1/s0.webp"), "r").use { fd ->
            val bytes = fd.createInputStream().readBytes()
            assertEquals("WEBP", String(bytes, 8, 4))
        }
        provider.openAssetFile(uri("stickers_asset/static-1/tray_static-1.png"), "r").use { fd ->
            assertEquals(0x89.toByte(), fd.createInputStream().readBytes()[0])
        }
        assertEquals("image/webp", provider.getType(uri("stickers_asset/static-1/s0.webp")))
        assertEquals("image/png", provider.getType(uri("stickers_asset/static-1/tray_static-1.png")))
    }

    @Test(expected = FileNotFoundException::class)
    fun refusesFilesOutsideThePack() {
        provider.openAssetFile(uri("stickers_asset/static-1/index.json"), "r")
    }

    @Test(expected = FileNotFoundException::class)
    fun refusesHiddenPacks() {
        provider.openAssetFile(uri("stickers_asset/animated-1/lonely.webp"), "r")
    }

    @Test(expected = FileNotFoundException::class)
    fun refusesWriteMode() {
        provider.openAssetFile(uri("stickers_asset/static-1/s0.webp"), "rw")
    }

    @Test fun addIntentMatchesWhatsAppsContract() {
        val i = WhatsApp.addIntent(ctx, WaApp.Consumer, "static-1", "Faves")
        assertEquals("com.whatsapp.intent.action.ENABLE_STICKER_PACK", i.action)
        assertEquals("static-1", i.getStringExtra("sticker_pack_id"))
        assertEquals(authority, i.getStringExtra("sticker_pack_authority"))
        assertEquals("Faves", i.getStringExtra("sticker_pack_name"))
        assertEquals("com.whatsapp", i.`package`)
    }
}
