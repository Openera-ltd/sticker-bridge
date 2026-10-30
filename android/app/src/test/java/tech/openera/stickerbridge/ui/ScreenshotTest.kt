package tech.openera.stickerbridge.ui

import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.openera.stickerbridge.data.AppRelease
import tech.openera.stickerbridge.data.AppSettings
import tech.openera.stickerbridge.data.DemoApi
import tech.openera.stickerbridge.data.LinkStart
import tech.openera.stickerbridge.data.LocalSticker
import tech.openera.stickerbridge.data.PackBuilder
import tech.openera.stickerbridge.data.Session
import tech.openera.stickerbridge.data.StickerProcessor
import tech.openera.stickerbridge.data.SyncStatus
import tech.openera.stickerbridge.data.ThemeMode
import tech.openera.stickerbridge.ui.theme.AppTheme
import tech.openera.stickerbridge.update.UpdateState
import tech.openera.stickerbridge.whatsapp.WaApp
import java.io.File

/** Draws every main screen to build/screenshots/ so the design can be checked without a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w393dp-h851dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File("build/screenshots").apply { mkdirs() }
    private val dir = File("build/screenshot-stickers").apply { mkdirs() }

    private val stickers: List<LocalSticker> by lazy {
        val api = DemoApi()
        (0 until 8).map { i ->
            val bytes = runBlocking { api.download(null, "demo/$i") }
            File(dir, "d$i.webp").writeBytes(StickerProcessor.toStaticSticker(bytes, outline = true))
            LocalSticker("d$i", "d$i.webp", emojis = listOf("😂"), addedAt = i.toLong(), isNew = i >= 6)
        }
    }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        rule.setContent {
            AppTheme(if (dark) ThemeMode.Dark else ThemeMode.Light, false) {
                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) { content() }
            }
        }
        // Let the image loader finish: it works off the main thread, then posts back to it.
        repeat(20) {
            rule.waitForIdle()
            Thread.sleep(100)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val release = AppRelease(2, "1.1.0", "v1/app/apk/2", "x", notes = "Animated stickers and faster sync.")

    @Test fun welcome() = shot("01-welcome", false) { WelcomeScreen {} }

    @Test fun connect() = shot("02-connect", false) {
        ConnectScreen(LinkUi.Code(LinkStart("SB-4821", "p", System.currentTimeMillis() + 590_000), waiting = true), {}, {}, {}, {})
    }

    @Test fun connected() = shot("03-connected", true) { ConnectScreen(LinkUi.Linked("mia.draws"), {}, {}, {}, {}) }

    @Test fun stickersLight() = shot("04-stickers", false) {
        HomeFrame("stickers", {}, SnackbarHostState()) {
            StickersTab(stickers, PackBuilder.build(stickers, "My TikTok Stickers"), dir, SyncStatus.Idle, System.currentTimeMillis() - 120_000, {}, {}, {}, { _, _ -> }, {})
        }
    }

    @Test fun stickersDarkWithUpdate() = shot("05-stickers-dark-update", true) {
        HomeFrame("stickers", {}, SnackbarHostState()) {
            StickersTab(stickers, emptyList(), dir, SyncStatus.Idle, System.currentTimeMillis() - 60_000, { UpdateBanner(UpdateState.Available(release), {}, {}, {}) }, {}, {}, { _, _ -> }, {})
        }
    }

    @Test fun packs() = shot("06-packs", false) {
        val anim = (1..2).map { LocalSticker("a$it", "d${it + 2}.webp", animated = true, addedAt = 100L + it) }
        HomeFrame("packs", {}, SnackbarHostState()) {
            PacksTab(
                PackBuilder.build(stickers + anim, "My TikTok Stickers"), dir,
                listOf(WaApp.Consumer, WaApp.Business),
                mapOf("static-1" to mapOf(WaApp.Consumer to true, WaApp.Business to false)), {},
            ) { _, _ -> }
        }
    }

    @Test fun packsDark() = shot("07-packs-dark", true) {
        HomeFrame("packs", {}, SnackbarHostState()) {
            PacksTab(PackBuilder.build(stickers, "My TikTok Stickers"), dir, listOf(WaApp.Consumer), emptyMap(), {}) { _, _ -> }
        }
    }

    @Test fun settings() = shot("08-settings", false) {
        HomeFrame("settings", {}, SnackbarHostState()) {
            SettingsTab(AppSettings(), Session(token = "t", username = "mia.draws", lastSyncAt = System.currentTimeMillis() - 300_000), {}, {}, {}, {}, {}, {})
        }
    }

    @Test fun emptyDark() = shot("09-empty-dark", true) {
        HomeFrame("stickers", {}, SnackbarHostState()) {
            StickersTab(emptyList(), emptyList(), dir, SyncStatus.Idle, 0, {}, {}, {}, { _, _ -> }, {})
        }
    }
}
