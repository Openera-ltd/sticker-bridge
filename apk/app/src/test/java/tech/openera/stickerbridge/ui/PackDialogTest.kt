package tech.openera.stickerbridge.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

// Default screen config on purpose: with a custom screen size, Robolectric never settles once a dialog text field has focus.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PackDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun namedPacksStartWithStickerBridge() {
        var made: String? = null
        rule.setContent { NewPackDialog(3, { made = it }, {}) }
        rule.onNodeWithText("Sticker Bridge · 3").assertIsDisplayed()
        rule.onNodeWithText("Create").assertIsNotEnabled()
        rule.onNodeWithText("Pack name").performTextInput("Faves")
        rule.onNodeWithText("Sticker Bridge · Faves · 3").assertIsDisplayed()
        rule.onNodeWithText("Create").performClick()
        assertEquals("Faves", made)
    }
}
