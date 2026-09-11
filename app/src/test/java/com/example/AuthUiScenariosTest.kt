package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.ScenarioTestHarness.assertAnyVisible
import com.example.ScenarioTestHarness.assertNoneVisible
import com.example.ScenarioTestHarness.setFlow
import com.example.data.auth.AuthState
import com.example.data.auth.SteamGuardType
import com.example.ui.screens.auth.AuthViewModel
import com.example.ui.screens.auth.LoginScreen
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Human-scenario suite for the sign-in flow. Every state a user can land in
 * while authenticating — fresh boot, network round-trip, Steam Guard (2FA)
 * of each supported kind, human error messages — is injected into the real
 * AuthManager and the on-screen contract is asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthUiScenariosTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: DepotApplication
    private lateinit var vm: AuthViewModel

    private fun guardState(
        guardType: Int,
        showCodeField: Boolean = false
    ) = AuthState.AwaitingGuard(
        accountName = "tester",
        steamId = "76561198000000000",
        clientId = "client",
        requestId = "request",
        pollIntervalSec = 5L,
        guardType = guardType,
        promptMessage = SteamGuardType.defaultPrompt(guardType),
        availableTypes = listOf(guardType),
        rememberMe = true,
        showCodeField = showCodeField
    )

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        vm = AuthViewModel(app)
        setFlow(app.authManager, "_authState", AuthState.LoggedOut)
        setFlow(app.authManager, "_guardError", null)
        setFlow(app.prefs, "_steamUsername", "")
    }

    private fun showLogin() {
        composeRule.setContent { LoginScreen(viewModel = vm) }
        composeRule.waitForIdle()
    }

    @Test
    fun `fresh boot shows the complete sign-in form`() {
        showLogin()
        composeRule.assertAnyVisible("ACCOUNT SIGN-IN")
        composeRule.assertAnyVisible("Steam account name")
        composeRule.assertAnyVisible("Password")
        composeRule.assertAnyVisible("SIGN IN WITH STEAM")
        composeRule.assertAnyVisible("Remember me")
        composeRule.assertAnyVisible("Not affiliated with Valve Corporation")
    }

    @Test
    fun `busy network round-trip shows its progress message`() {
        setFlow(app.authManager, "_authState", AuthState.Busy("Reading Steam's login list…"))
        showLogin()
        composeRule.assertAnyVisible("Reading Steam")
        // The form must NOT flash the guard challenge during a plain round-trip.
        composeRule.assertNoneVisible("XXXXX")
    }

    @Test
    fun `email code guard phase shows the code field and email prompt`() {
        setFlow(app.authManager, "_authState", guardState(SteamGuardType.EMAIL_CODE))
        showLogin()
        composeRule.assertAnyVisible("emailed to your email address")
        composeRule.assertAnyVisible("XXXXX")
        composeRule.assertAnyVisible("VERIFY & SIGN IN")
    }

    @Test
    fun `mobile code guard phase prompts for the mobile app code`() {
        setFlow(app.authManager, "_authState", guardState(SteamGuardType.DEVICE_CODE))
        showLogin()
        composeRule.assertAnyVisible("Steam Guard page of your Steam Mobile App")
        composeRule.assertAnyVisible("VERIFY & SIGN IN")
    }

    @Test
    fun `device confirmation hides code entry until the user asks for it`() {
        setFlow(app.authManager, "_authState", guardState(SteamGuardType.DEVICE_CONFIRMATION))
        showLogin()
        composeRule.assertAnyVisible("approve this sign-in request")
        composeRule.assertNoneVisible("XXXXX")
        composeRule.assertAnyVisible("Enter a code from the Steam Mobile App instead")
    }

    @Test
    fun `wrong-password error is surfaced as the human-readable message`() {
        val message = "Invalid password. Check your credentials and try again."
        setFlow(app.authManager, "_authState", AuthState.Error(message, accountName = "tester"))
        showLogin()
        composeRule.assertAnyVisible(message)
        // The form must stay available for an immediate retry.
        composeRule.assertAnyVisible("SIGN IN WITH STEAM")
    }

    @Test
    fun `rate-limit error is surfaced to the user`() {
        val message = "Steam is rate-limiting sign-in attempts. Wait a few minutes, then try again."
        setFlow(app.authManager, "_authState", AuthState.Error(message))
        showLogin()
        composeRule.assertAnyVisible("rate-limiting")
    }

    @Test
    fun `failed guard code surfaces the guard error inline`() {
        setFlow(app.authManager, "_authState", guardState(SteamGuardType.EMAIL_CODE))
        setFlow(app.authManager, "_guardError", "That code didn't match — check it and try again.")
        showLogin()
        composeRule.assertAnyVisible("That code didn't match")
    }

    @Test
    fun `sign-in button stays disabled until both credentials are typed`() {
        showLogin()
        composeRule.onNodeWithTag("sign_in_button", useUnmergedTree = true)
            .assertIsNotEnabled()
        val fields = composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assert(fields.size >= 2) { "expected >=2 editable username/password fields, got ${fields.size}" }
        composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)[0]
            .performTextInput("tester")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sign_in_button", useUnmergedTree = true)
            .assertIsNotEnabled()
        composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)[1]
            .performTextInput("hunter2")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sign_in_button", useUnmergedTree = true)
            .assertIsEnabled()
    }

    @Test
    fun `cancelling a guard challenge drops back to the login form`() {
        setFlow(app.authManager, "_authState", guardState(SteamGuardType.EMAIL_CODE))
        showLogin()
        composeRule.assertAnyVisible("XXXXX")
        // The manager's public cancel path — the same one the CLOSE button hits.
        vm.cancelSignIn()
        assert(app.authManager.authState.value is AuthState.LoggedOut)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sign_in_button", useUnmergedTree = true).assertIsDisplayed()
        composeRule.assertNoneVisible("XXXXX")
    }

    @Test
    fun `remembered account name is prefilled into the username field`() {
        setFlow(app.prefs, "_steamUsername", "OldAccountName")
        val freshVm = AuthViewModel(app)
        composeRule.setContent { LoginScreen(viewModel = freshVm) }
        composeRule.waitForIdle()
        composeRule.assertAnyVisible("OldAccountName")
    }
}
