package com.example

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-emulator smoke test: boots the real APK and proves the app's own deep
 * class probe resolves every critical dependency on-device (SpongyCastle
 * included), then drives the auth screen to confirm the UI actually renders.
 */
@RunWith(AndroidJUnit4::class)
class EmulatedBootSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun deepClassProbeFindsAllCriticalClassesOnDevice() {
        val report = CrashLog.deepClassCheck()
        assertTrue(
            "SpongyCastle must resolve on-device (SHA-1 depends on it):\n$report",
            report.contains("OK    org.spongycastle.jce.provider.BouncyCastleProvider")
        )
        assertTrue(
            "Bouncycastle entry must also resolve:\n$report",
            report.contains("OK    org.bouncycastle.jce.provider.BouncyCastleProvider")
        )
        assertFalse(
            "No classpath failure is acceptable:\n$report",
            report.contains("FAIL  ")
        )
    }

    @Test
    fun authScreenRendersForSignedOutUser() {
        // Fresh install: no remembered session, so the auth screen must
        // appear — but only after the startup session-restore tick. Wait
        // up to 25 s on the cold emulator instead of asserting against the
        // very first composed frame. Using the unmerged tree so a styled
        // button label (a child Text inside the button) is matchable.
        val anchors = listOf("SIGN IN WITH STEAM", "ACCOUNT SIGN-IN", "Steam account name")
        val deadlineMs = System.currentTimeMillis() + 25_000
        var found: String? = null
        while (System.currentTimeMillis() < deadlineMs && found == null) {
            found = anchors.firstOrNull { anchor ->
                runCatching {
                    composeRule.onAllNodesWithText(anchor, substring = true,
                        ignoreCase = true, useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
            if (found == null) Thread.sleep(500)
        }
        // If nothing matched, dump every on-screen text into the failure so
        // the CI log (and the PR verdict comment) tells us what IS rendered.
        val visible = runCatching {
            composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().allTexts()
        }.getOrDefault(listOf("(<no semantics tree readable>)"))
        assertTrue(
            "Auth screen did not render: no sign-in anchor found within 25s. " +
                "Visible texts: ${visible.take(30)}",
            found != null
        )
        composeRule.onNodeWithText(found!!, substring = true,
            ignoreCase = true, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun SemanticsNode.allTexts(): List<String> {
        // config.getOrNull doesn't exist; the subscript operator throws on a
        // missing key, so guard it instead.
        val mine: String? = runCatching {
            config[SemanticsProperties.Text]
                .joinToString(" | ") { annotated -> annotated.text }
        }.getOrNull()
        val rest: List<String> = children.flatMap { child -> child.allTexts() }
        return listOfNotNull(mine) + rest
    }
}
