package com.example

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.ScenarioTestHarness.assertAnyVisible
import com.example.ScenarioTestHarness.assertNoneVisible
import com.example.ScenarioTestHarness.anyNodeVisible
import com.example.ScenarioTestHarness.setFlow
import com.example.data.db.SteamGameEntity
import com.example.ui.screens.library.LibraryScreen
import com.example.ui.screens.library.LibraryViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Human-scenario suite for the library tab: empty state, a populated
 * shelf, store-style tabs, search narrowing/error paths and the game
 * details sheet a user opens before downloading. Data is seeded through
 * the real Room database so what renders is exactly what a human sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryUiScenariosTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: DepotApplication
    private lateinit var vm: LibraryViewModel

    private val cs2 = SteamGameEntity(
        appId = 730, name = "Counter-Strike 2",
        playtimeForever = 0, imgIconUrl = "", imgHeaderUrl = ""
    )
    private val gmod = SteamGameEntity(
        appId = 4000, name = "Garry's Mod",
        playtimeForever = 1005, imgIconUrl = "", imgHeaderUrl = ""
    )

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        app.database.steamGameDao().insertGames(emptyList())
        setFlow(app.authManager, "_session", null)
        vm = LibraryViewModel(app)
    }

    private fun showLibrary() {
        composeRule.setContent { LibraryScreen(viewModel = vm, onNavigateToDownloaderWithAppId = { _, _ -> }) }
        composeRule.waitForIdle()
    }

    @Test
    fun `empty library invites the human to sync`() {
        showLibrary()
        composeRule.assertAnyVisible("Library is empty")
    }

    @Test
    fun `populated shelf renders games with the store tabs`() = runBlocking {
        app.database.steamGameDao().insertGames(listOf(cs2, gmod))
        showLibrary()
        composeRule.assertAnyVisible("Counter-Strike 2")
        composeRule.assertAnyVisible("Garry's Mod")
        composeRule.assertAnyVisible("ALL GAMES")
        composeRule.assertAnyVisible("PLAYED")
        composeRule.assertAnyVisible("NEVER PLAYED")
    }

    @Test
    fun `typing in the search box narrows the shelf`() = runBlocking {
        app.database.steamGameDao().insertGames(listOf(cs2, gmod))
        showLibrary()
        vm.onSearchQueryChanged("counter")
        composeRule.waitForIdle()
        composeRule.assertAnyVisible("Counter-Strike 2")
        composeRule.assertNoneVisible("Garry's Mod")
    }

    @Test
    fun `a search with no match tells the human plainly`() = runBlocking {
        app.database.steamGameDao().insertGames(listOf(cs2, gmod))
        showLibrary()
        vm.onSearchQueryChanged("zzzz nothing matches")
        composeRule.waitForIdle()
        composeRule.assertAnyVisible("No games match")
    }

    @Test
    fun `played tab shows only played games`() = runBlocking {
        app.database.steamGameDao().insertGames(listOf(cs2, gmod))
        showLibrary()
        composeRule.onNode(hasText("PLAYED", substring = false), useUnmergedTree = true)
            .performClick()
        composeRule.waitForIdle()
        composeRule.assertAnyVisible("Garry's Mod")
        composeRule.assertNoneVisible("Counter-Strike 2")
    }

    @Test
    fun `selecting a game opens the download-ready details sheet`() = runBlocking {
        app.database.steamGameDao().insertGames(listOf(cs2))
        showLibrary()
        vm.openGameDetails(cs2)
        composeRule.waitForIdle()
        composeRule.assertAnyVisible("DOWNLOAD BASE GAME")
        composeRule.assertAnyVisible("OWNED")
        vm.closeGameDetails()
    }

    @Test
    fun `an expired-session error is told to the human as a snackbar`() {
        showLibrary()
        setFlow(vm, "_errorMessage", "Your Steam session expired — please sign in again.")
        composeRule.waitForIdle()
        composeRule.assertAnyVisible("Your Steam session expired")
    }
}
