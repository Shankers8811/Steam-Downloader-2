package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.ScenarioTestHarness.setFlow
import com.example.data.auth.AuthState
import com.example.data.auth.SteamSession
import com.example.data.db.SteamGameEntity
import com.example.ui.screens.auth.AuthViewModel
import com.example.ui.screens.auth.LoginScreen
import com.example.ui.screens.library.LibraryScreen
import com.example.ui.screens.library.LibraryViewModel
import com.example.ui.theme.DepotDownloaderTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Records the README screenshots from the REAL screens (Pixel 8 viewport,
 * native graphics) — no mock UI. Run with `-Proborazzi.test.record=true`
 * (CI does this, uploads the `ui-screenshots` artifact, and mirrors the PNGs
 * to the git-readable `screenshots` branch).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class AppScreenshotsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val shelf = listOf(
        SteamGameEntity(appId = 730, name = "Counter-Strike 2", playtimeForever = 1284, imgIconUrl = "", imgHeaderUrl = ""),
        SteamGameEntity(appId = 4000, name = "Garry's Mod", playtimeForever = 1005, imgIconUrl = "", imgHeaderUrl = ""),
        SteamGameEntity(appId = 413150, name = "Stardew Valley", playtimeForever = 342, imgIconUrl = "", imgHeaderUrl = ""),
        SteamGameEntity(appId = 105600, name = "Terraria", playtimeForever = 211, imgIconUrl = "", imgHeaderUrl = ""),
        SteamGameEntity(appId = 620, name = "Portal 2", playtimeForever = 96, imgIconUrl = "", imgHeaderUrl = ""),
        SteamGameEntity(appId = 546560, name = "Half-Life: Alyx", playtimeForever = 0, imgIconUrl = "", imgHeaderUrl = "")
    )

    private suspend fun seed(games: List<SteamGameEntity>) = withContext(Dispatchers.IO) {
        val app = ApplicationProvider.getApplicationContext<DepotApplication>()
        app.database.clearAllTables()
        var rows = app.database.steamGameDao().getAllGames().first()
        var guard = 0
        while (rows.isNotEmpty() && guard++ < 50) {
            delay(30)
            rows = app.database.steamGameDao().getAllGames().first()
        }
        if (games.isNotEmpty()) {
            app.database.steamGameDao().insertGames(games)
            guard = 0
            var ok = app.database.steamGameDao().getAllGames().first().size == games.size
            while (!ok && guard++ < 50) {
                delay(30)
                ok = app.database.steamGameDao().getAllGames().first().size == games.size
            }
        }
    }

    @Test
    fun login_screen() {
        val app = ApplicationProvider.getApplicationContext<DepotApplication>()
        val authViewModel = AuthViewModel(app)
        // Deterministic signed-out state, same injection the auth scenario
        // suite uses — never snapshot the bootsplash by accident.
        setFlow(app.authManager, "_authState", AuthState.LoggedOut)
        composeRule.setContent {
            DepotDownloaderTheme {
                LoginScreen(viewModel = authViewModel)
            }
        }
        composeRule.waitForIdle()
        // Type into the real fields so the primary button is enabled —
        // exactly what a sign-in attempt looks like.
        val editable = composeRule.onAllNodes(hasSetTextAction())
        editable[0].performTextInput("gabe_newell")
        editable[1].performTextInput("correcthorsebatterystaple")
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/login.png")
    }

    @Test
    fun library_shelf() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DepotApplication>()
        // Signed-in header ("N games you own — <account>") exactly like a
        // real session, without any login. The VM's one-shot auto-sync then
        // fires against (unreachable) Steam and eventually stores 0 games —
        // so we let it fully settle FIRST (compose + waitForIdle pumps it to
        // completion), and only THEN seed the shelf. Nothing re-syncs after
        // that (autoSyncAttempted is one-shot).
        setFlow(app.authManager, "_session", SteamSession(
            steamId = "76561197960435530",
            accountName = "gabe_newell",
            refreshToken = "screenshot-refresh-token",
            accessToken = "screenshot-access-token"
        ))
        val vm = LibraryViewModel(app)
        composeRule.setContent {
            DepotDownloaderTheme {
                Box(Modifier.fillMaxSize()) {
                    LibraryScreen(viewModel = vm, onNavigateToDownloaderWithAppId = { _, _ -> })
                }
            }
        }
        composeRule.waitForIdle()
        seed(shelf)
        // Self-heal: if the auto-sync's store landed after our seed (slow
        // runner), the shelf would be empty again — re-verify and re-seed.
        withContext(Dispatchers.IO) {
            val rows = app.database.steamGameDao().getAllGames().first().size
            if (rows != shelf.size) seed(shelf)
        }
        composeRule.waitForIdle()
        // Clear the sync's leftover snackbar/spinner noise before the shot.
        setFlow(vm, "_statusMessage", null)
        setFlow(vm, "_errorMessage", null)
        setFlow(vm, "_isLoading", false)
        composeRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/library.png")
    }
}
