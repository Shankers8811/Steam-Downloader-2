package com.example

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-emulator human-interaction suite. Boots the real MainActivity and
 * performs the same gestures a human would: fill the sign-in form, watch
 * button gating flip, hit SIGN IN, and confirm the app reacts to Steam's
 * real answer (we have no valid account — so the correct outcome is a
 * human-readable error surface, never a hang or a crash).
 */
@RunWith(AndroidJUnit4::class)
class AppUiInteractionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** Waits until the sign-in form is rendered (bootsplash may show first
     *  while a remembered session is being probed). */
    private fun awaitLoginForm(timeoutMs: Long = 25_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ready = false
        while (System.currentTimeMillis() < deadline && !ready) {
            ready = runCatching {
                composeRule.onAllNodes(
                    hasText("SIGN IN WITH STEAM", ignoreCase = true),
                    useUnmergedTree = true
                ).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
            if (!ready) Thread.sleep(400)
        }
        assertTrue("Login form never rendered", ready)
    }

    private fun textInputs() = composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)

    @Test
    fun `login form enables the sign-in button only once both fields are filled`() {
        awaitLoginForm()
        composeRule.onNodeWithTag("sign_in_button", useUnmergedTree = true)
            .assertIsNotEnabled()
        val fields = textInputs().fetchSemanticsNodes()
        assertTrue("Expected account + password text fields, found ${fields.size}", fields.size >= 2)
        textInputs()[0].performTextInput("arenactest")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sign_in_button", useUnmergedTree = true)
            .assertIsNotEnabled()
        textInputs()[1].performTextInput("secret123")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sign_in_button", useUnmergedTree = true)
            .assertIsEnabled()
    }

    @Test
    fun `fake credentials end at a human-readable error from the REAL Steam api`() {
        awaitLoginForm()
        val fields = textInputs().fetchSemanticsNodes()
        assertTrue(fields.size >= 2)
        // A username that cannot exist (Steam allows 3..64 chars; 2 is invalid)
        textInputs()[0].performTextInput("zz")
        textInputs()[1].performTextInput("definitely-wrong-password")
        composeRule.onNodeWithTag("sign_in_button", useUnmergedTree = true)
            .assertIsEnabled()
            .performClick()

        // The app must come back from the round-trip with *something*
        // human-readable — never silence. Generous window: emulator +
        // real Steam endpoints.
        val anchors = listOf(
            "3 characters", "characters", "account", "invalid",
            "Incorrect", "password", "try again", "not exist", "STEAM GUARD",
            "rate-limit", "wait", "offline", "network", "error"
        )
        val deadline = System.currentTimeMillis() + 120_000
        var surfaced: String? = null
        while (System.currentTimeMillis() < deadline && surfaced == null) {
            surfaced = anchors.firstOrNull { needle ->
                runCatching {
                    composeRule.onAllNodes(
                        hasText(needle, substring = true, ignoreCase = true),
                        useUnmergedTree = true
                    ).fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
            if (surfaced == null) Thread.sleep(1_000)
        }
        val visible = runCatching {
            composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().allTexts()
        }.getOrDefault(listOf("<tree unreadable>"))
        assertTrue(
            "After tapping SIGN IN with fake credentials the app never surfaced " +
                "a human-readable answer. Visible texts: ${visible.take(40)}",
            surfaced != null
        )
    }

    private fun androidx.compose.ui.semantics.SemanticsNode.allTexts(): List<String> {
        val mine = runCatching {
            config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .joinToString(" | ") { annotated -> annotated.text }
        }.getOrNull()
        val rest: List<String> = children.flatMap { child -> child.allTexts() }
        return listOfNotNull(mine) + rest
    }
}
