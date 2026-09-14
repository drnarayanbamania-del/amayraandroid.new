package com.amayra.maya.playtest

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.amayra.maya.MayaApplication
import com.amayra.maya.core.AssistantState
import com.amayra.maya.core.StateBus
import com.amayra.maya.ui.home.MyraHomeScreen
import com.amayra.maya.ui.theme.MayaTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins the live partial-speech strip: a fabricated Listening(partial) state —
 * exactly what VoiceController.onPartialResults publishes — must render the
 * spoken words above the talk input, and the strip must recompose as the
 * partial evolves (guards against the stale-read bug class).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MayaApplication::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListeningPartialStripTest {

    @get:org.junit.Rule
    val compose = createComposeRule()

    private fun setContent() {
        val app = ApplicationProvider.getApplicationContext<MayaApplication>()
        compose.setContent { MayaTheme { MyraHomeScreen(app) } }
    }

    @Test
    fun `partial speech renders above input and updates`() {
        setContent()

        // Reset any state leaked from other tests in this process, then idle: strip absent.
        compose.runOnIdle { StateBus.setState(AssistantState.Idle) }
        compose.waitForIdle()
        compose.onNodeWithText("🎙 Listening… speak now", substring = true).assertDoesNotExist()

        // Recognizer emits a first partial (as VoiceController.onPartialResults does).
        compose.runOnIdle { StateBus.setState(AssistantState.Listening("hello")) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("“hello”", substring = true).or(hasText("\"hello\"", substring = true)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("hello", substring = true).assertIsDisplayed()

        // A longer partial arrives — strip must recompose to the new words.
        compose.runOnIdle { StateBus.setState(AssistantState.Listening("hello amayra kaisi ho")) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("hello amayra kaisi ho", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        // Session ends → strip disappears.
        compose.runOnIdle { StateBus.setState(AssistantState.Idle) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("hello amayra kaisi ho", substring = true)).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `empty listening state shows the prompt line`() {
        setContent()
        compose.runOnIdle { StateBus.setState(AssistantState.Listening("")) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Listening… speak now", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
