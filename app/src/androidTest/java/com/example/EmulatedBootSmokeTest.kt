package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
        composeRule.waitForIdle()
        composeRule.onNode(hasText("SIGN IN WITH STEAM", ignoreCase = true))
            .assertIsDisplayed()
        composeRule.onNode(hasText("ACCOUNT SIGN-IN", ignoreCase = true))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Steam account name", substring = true)
            .assertIsDisplayed()
    }
}
