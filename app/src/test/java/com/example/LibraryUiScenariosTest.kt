package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.ScenarioTestHarness.assertAnyVisible
import com.example.ScenarioTestHarness.assertNoneVisible
import com.example.ScenarioTestHarness.awaitVisible
import com.example.ScenarioTestHarness.anyNodeVisible
import com.example.ScenarioTestHarness.setFlow
import com.example.data.db.SteamGameEntity
import com.example.ui.screens.library.LibraryScreen
import com.example.ui.screens.library.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
 *
 * Two test-environment realities are engineered around here:
 *  - the Room database file survives between tests in one class, so every
 *    seed starts from a hard `clearAllTables()` + read-back confirmation;
 *  - Lazy* lists virtualise: with the default tiny test viewport only the
 *    first row ever composes — the screen is rendered inside a 2000dp-tall
 *    box so the whole shelf composes into the semantics tree.
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

    /** Clear-then-seed with a read-back confirmation so no row can leak
     *  from a previous test and composition starts from known data. The
     *  JUnit thread is the app's main thread under Robolectric, so all
     *  direct Room calls are moved onto Dispatchers.IO. */
    private suspend fun seed(games: List<SteamGameEntity>) = withContext(Dispatchers.IO) {
        app.database.clearAllTables()
        var rows = app.database.steamGameDao().getAllGames().first()
        var guard = 0
        while (rows.isNotEmpty() && guard++ < 50) { delay(30); rows = app.database.steamGameDao().getAllGames().first() }
        if (games.isNotEmpty()) {
            app.database.steamGameDao().insertGames(games)
            guard = 0
            var ok = app.database.steamGameDao().getAllGames().first().size == games.size
            while (!ok && guard++ < 50) { delay(30); ok = app.database.steamGameDao().getAllGames().first().size == games.size }
        }
    }

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        seed(emptyList())
        setFlow(app.authManager, "_session", null)
        vm = LibraryViewModel(app)
    }

    private fun showLibrary() {
        composeRule.setContent {
            // Tall virtual viewport: LazyColumn composes every shelf row
            // into the semantics tree (default test windows only show ~1).
            Box(modifier = Modifier.width(1200.dp).height(2000.dp)) {
                LibraryScreen(viewModel = vm, onNavigateToDownloaderWithAppId = { _, _ -> })
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `empty library invites the human to sync`() {
        showLibrary()
        composeRule.awaitVisible("Library is empty")
    }

    @Test
    fun `populated shelf renders games with the store tabs`() = runBlocking {
        seed(listOf(cs2, gmod))
        showLibrary()
        // Grid virtualization is real even in a tall test box: the hero card
        // (most played = Garry's Mod) composes first, remaining capsules only
        // after scrolling forward — exactly what a human does.
        composeRule.awaitVisible("Garry's Mod")
        val grid = composeRule.onAllNodes(hasScrollAction(), useUnmergedTree = true)
            .onFirst()
        grid.performScrollToNode(hasText("Counter-Strike 2"))
        composeRule.waitForIdle()
        composeRule.awaitVisible("Counter-Strike 2")
        composeRule.assertAnyVisible("ALL GAMES")
        composeRule.assertAnyVisible("PLAYED")
        composeRule.assertAnyVisible("NEVER PLAYED")
    }

    @Test
    fun `typing in the search box narrows the shelf`() = runBlocking {
        seed(listOf(cs2, gmod))
        showLibrary()
        vm.onSearchQueryChanged("counter")
        composeRule.awaitVisible("Counter-Strike 2")
        composeRule.assertNoneVisible("Garry's Mod")
    }

    @Test
    fun `a search with no match tells the human plainly`() = runBlocking {
        seed(listOf(cs2, gmod))
        showLibrary()
        vm.onSearchQueryChanged("zzzz nothing matches")
        composeRule.awaitVisible("No games match")
    }

    @Test
    fun `played tab shows only played games`() = runBlocking {
        seed(listOf(cs2, gmod))
        showLibrary()
        composeRule.onNode(hasText("PLAYED", substring = false), useUnmergedTree = true)
            .performClick()
        composeRule.awaitVisible("Garry's Mod")
        composeRule.assertNoneVisible("Counter-Strike 2")
    }

    @Test
    fun `selecting a game opens the download-ready details sheet`() = runBlocking {
        seed(listOf(cs2))
        showLibrary()
        vm.openGameDetails(cs2)
        // The sheet opens instantly; DLC probing may still be running on
        // JVM, so accept any of the interim possibilities the sheet shows.
        composeRule.awaitVisible("Counter-Strike 2")
        val interim = listOf(
            "DOWNLOAD BASE GAME", "Checking DLC", "Scanning your licenses",
            "No DLC", "Not on this account", "OWNED"
        )
        val shown = interim.firstOrNull { composeRule.anyNodeVisible(it) }
        org.junit.Assert.assertTrue(
            "Details sheet rendered none of the expected states: $interim",
            shown != null
        )
        vm.closeGameDetails()
    }

    @Test
    fun `an expired-session error is told to the human as a snackbar`() {
        showLibrary()
        setFlow(vm, "_errorMessage", "Your Steam session expired — please sign in again.")
        composeRule.awaitVisible("Your Steam session expired")
    }
}
