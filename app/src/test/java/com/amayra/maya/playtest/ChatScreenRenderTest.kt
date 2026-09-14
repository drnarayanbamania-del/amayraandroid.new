package com.amayra.maya.playtest

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.amayra.maya.MayaApplication
import com.amayra.maya.ui.chat.ChatScreen
import com.amayra.maya.ui.theme.MayaTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * First-ever render of the docs-reconstructed ChatScreen (it was rebuilt from
 * architecture docs after an accidental overwrite and had never been composed).
 * Sets the real ChatScreen inside the real theme + app graph (no activity
 * launch — Robolectric's default-manifest resolution is unavailable here),
 * then drives the input -> send flow and asserts the user turn renders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MayaApplication::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatScreenRenderTest {

    @get:org.junit.Rule
    val compose = createComposeRule()

    @Test
    fun `chat screen renders and accepts a message`() {
        val app = ApplicationProvider.getApplicationContext<MayaApplication>()
        compose.setContent {
            MayaTheme {
                ChatScreen(app)
            }
        }

        // Always-present affordances.
        compose.onNodeWithContentDescription("Attach image").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()

        // The input-row trailing button is the mic when idle and the stop
        // button while the proactive greeting speaks — exactly one must exist.
        val mic = compose.onAllNodes(hasContentDescription("Voice input")).fetchSemanticsNodes()
        val stop = compose.onAllNodes(hasContentDescription("Stop speaking")).fetchSemanticsNodes()
        assertTrue("neither mic nor stop button found", mic.isNotEmpty() || stop.isNotEmpty())

        // Type + send: must not crash; the user turn appears in the list.
        compose.onNodeWithText("Ask Maya…").performTextInput("hello Maya")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("hello Maya")).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
