package tech.openera.stickerbridge.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.test.performScrollTo
import tech.openera.stickerbridge.data.AppRelease
import tech.openera.stickerbridge.data.AppSettings
import tech.openera.stickerbridge.data.LinkStart
import tech.openera.stickerbridge.data.LocalSticker
import tech.openera.stickerbridge.data.PackBuilder
import tech.openera.stickerbridge.data.Session
import tech.openera.stickerbridge.data.SyncStatus
import tech.openera.stickerbridge.data.ThemeMode
import tech.openera.stickerbridge.ui.theme.AppTheme
import tech.openera.stickerbridge.update.UpdateState
import tech.openera.stickerbridge.whatsapp.WaApp
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w393dp-h851dp-xxhdpi") // a typical phone
class ScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun themed(dark: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) =
        rule.setContent { AppTheme(if (dark) ThemeMode.Dark else ThemeMode.Light, false, content) }

    @Test fun welcomeLeadsToConnect() {
        var started = false
        themed { WelcomeScreen { started = true } }
        rule.onNodeWithText("Your TikTok stickers, in WhatsApp.").assertIsDisplayed()
        rule.onNodeWithText("Get started").performClick()
        assertTrue(started)
    }

    @Test fun connectShowsCodeThenWaitingThenConnected() {
        var link: LinkUi by mutableStateOf<LinkUi>(LinkUi.Code(LinkStart("SB-4821", "p", System.currentTimeMillis() + 600_000), waiting = false))
        var continued = false
        themed(dark = true) { ConnectScreen(link, {}, {}, {}, { continued = true }) }
        rule.onNodeWithText("SB-4821").assertIsDisplayed()
        rule.onNodeWithText("Copy").performClick()
        rule.onNodeWithText("Copied").assertIsDisplayed()

        link = LinkUi.Code(LinkStart("SB-4821", "p", System.currentTimeMillis() + 600_000), waiting = true)
        rule.onNodeWithText("Waiting for your message…").assertIsDisplayed()

        link = LinkUi.Linked("mia.draws")
        rule.waitForIdle()
        rule.onNodeWithText("Continue").performClick()
        assertTrue(continued)
    }

    @Test fun connectButtonDisabledWhileLoading() {
        themed { ConnectScreen(LinkUi.Loading, {}, {}, {}, {}) }
        rule.onNodeWithText("Open TikTok").assertIsNotEnabled()
    }

    @Test fun expiredCodeOffersANewOne() {
        var restarted = 0
        themed { ConnectScreen(LinkUi.Expired, {}, { restarted++ }, {}, {}) }
        rule.onNodeWithText("Get a new code").performClick()
        assertTrue(restarted >= 1)
    }

    @Test fun emptyStickersExplainWhatToDo() {
        themed {
            StickersTab(emptyList(), emptyList(), File("."), SyncStatus.Idle, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
        }
        rule.onNodeWithText("No stickers yet").assertIsDisplayed()
        rule.onNodeWithText("Send stickers to @stickerbridge").assertIsDisplayed()
        rule.onNodeWithContentDescription("Check for new stickers").assertIsDisplayed()
    }

    @Test fun packsShowUnlockProgressAndAddButtons() {
        val two = (1..2).map { LocalSticker("a$it", "a$it.webp", addedAt = it.toLong()) }
        val three = (1..3).map { LocalSticker("b$it", "b$it.webp", animated = true, addedAt = it.toLong()) }
        val packs = PackBuilder.build(two + three, "Faves")
        var added: Pair<String, WaApp>? = null
        themed {
            PacksTab(packs, File("."), listOf(WaApp.Consumer, WaApp.Business), mapOf("animated-1" to mapOf(WaApp.Consumer to true, WaApp.Business to false)), {}, onAdd = { p, app ->
                added = p.id to app
            })
        }
        rule.onNodeWithText("1 more to unlock").assertIsDisplayed()
        rule.onNodeWithText("Added to WhatsApp").assertExists()
        rule.onNodeWithText("Long-press a sticker in WhatsApp to add it to favourites").assertExists()
        rule.onNodeWithText("Add to WhatsApp Business").performClick()
        assertEquals("animated-1" to WaApp.Business, added)
    }

    @Test fun settingsTogglesAndDialogs() {
        var s by mutableStateOf(AppSettings())
        themed {
            SettingsTab(s, Session(token = "t", username = "mia"), {}, { f -> s = f(s) }, {}, {}, {}, {})
        }
        rule.onNodeWithText("@mia").assertIsDisplayed()
        rule.onNodeWithText("Wi-Fi only").performScrollTo().performClick()
        assertTrue(s.wifiOnly)
        rule.onNodeWithText("Dark").performScrollTo().performClick()
        assertEquals(ThemeMode.Dark, s.theme)
    }

    @Test fun updateBannerStates() {
        val r = AppRelease(2, "1.1.0", "v1/app/apk/2", "x", notes = "Faster sync")
        var state: UpdateState by mutableStateOf<UpdateState>(UpdateState.Available(r))
        var installed = false
        themed { UpdateBanner(state, { installed = true }, {}, {}) }
        rule.onNodeWithText("Update available · 1.1.0").assertIsDisplayed()
        rule.onNodeWithText("Faster sync").assertIsDisplayed()
        rule.onNodeWithText("Update").performClick()
        assertTrue(installed)
        state = UpdateState.NeedsPermission(r)
        rule.onNodeWithText("Open settings").assertIsDisplayed()
        state = UpdateState.Downloading(r, 0.4f)
        rule.onNodeWithText("Downloading 1.1.0…").assertIsDisplayed()
    }

    private val openSticker = androidx.compose.ui.test.SemanticsMatcher("opens a sticker") {
        it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.OnClick) { null }?.label == "Open sticker"
    }

    @Test fun longPressSelectsStickersIntoANewPack() {
        val stickers = (1..4).map { LocalSticker("s$it", "s$it.webp", addedAt = it.toLong(), isNew = false) }
        var sent = false
        themed {
            StickersTab(stickers, emptyList(), File("."), SyncStatus.Idle, {}, {}, {}, { sent = true }, { _, _ -> }, { _, _ -> }, {})
        }
        val tiles = rule.onAllNodes(openSticker)
        tiles[0].performTouchInput { longClick() }
        tiles[1].performClick()
        tiles[2].performClick()
        rule.onNodeWithText("3 selected").assertIsDisplayed()
        rule.onNodeWithText("New pack").assertIsDisplayed() // the dialog itself is checked in PackDialogTest

        // Leaving selection, a normal tap opens the sticker, which can be sent on its own.
        rule.onNodeWithContentDescription("Cancel selection").performClick()
        rule.onNodeWithText("Stickers").assertIsDisplayed()
        rule.onAllNodes(openSticker)[0].performClick()
        rule.onNodeWithText("Send to WhatsApp").performClick()
        assertTrue(sent)
    }

    @Test fun settingsShowSignedDonationsAndSourceCode() {
        themed {
            SettingsTab(
                AppSettings(), Session(token = "t", username = "mia"), {}, {}, {}, {}, {}, {},
                donation = tech.openera.stickerbridge.data.DonationInfo("https://paypal.me/cyriqfx", "+237672612437"),
            )
        }
        rule.onNodeWithText("PayPal").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("paypal.me/cyriqfx").assertIsDisplayed()
        rule.onNodeWithText("+237672612437").performScrollTo().performClick()
        rule.onNodeWithText("Copied").assertIsDisplayed()
        rule.onNodeWithText("Source code on GitHub").performScrollTo().assertIsDisplayed()
    }

    @Test fun intervalLabels() {
        assertEquals("1 minute", intervalLabel(1))
        assertEquals("15 minutes", intervalLabel(15))
        assertEquals("1 hour", intervalLabel(60))
        assertEquals("12 hours", intervalLabel(720))
    }
}
