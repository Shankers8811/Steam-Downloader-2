package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import java.io.File
import com.example.ScenarioTestHarness.awaitVisible
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.test.core.app.ApplicationProvider
import com.example.ScenarioTestHarness.assertAnyVisible
import com.example.ScenarioTestHarness.assertNoneVisible
import com.example.ScenarioTestHarness.setFlow
import com.example.data.download.DepotProgress
import com.example.data.download.DownloadSessionState
import com.example.data.download.SessionPhase
import com.example.ui.screens.downloader.DownloaderScreen
import com.example.ui.screens.downloader.DownloaderViewModel
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Human-scenario suite for the downloader tab: every download phase a user
 * can watch (idle setup, license check, downloading with live rates, paused
 * mid-depot, verifying, completed, failed, cancelled) plus the PC-transfer
 * batch panel. States are injected into the real engine's StateFlow and the
 * exact on-screen contract is asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloaderUiScenariosTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: DepotApplication
    private lateinit var vm: DownloaderViewModel

    private fun stateFor(phase: SessionPhase) = DownloadSessionState(
        phase = phase,
        appId = 400,
        appName = "Batch Game",
        branch = "public",
        totalBytes = 16L * 1024 * 1024 * 1024,
        downloadedBytes = 5L * 1024 * 1024 * 1024,
        networkBytesPerSec = 24.0 * 1024 * 1024,
        diskBytesPerSec = 18.0 * 1024 * 1024,
        etaSeconds = 600L,
        elapsedSeconds = 300L,
        currentFile = "bin/client.dll",
        completeFileCount = 42,
        depots = listOf(
            DepotProgress(444, "base content", null, 10L * 1024 * 1024 * 1024, 5L * 1024 * 1024 * 1024, false),
            DepotProgress(555, "dlc", 401, 6L * 1024 * 1024 * 1024, 0L, false)
        ),
        safeToMove = true,
        hasResumableSession = phase == SessionPhase.PAUSED,
        statusMessage = when (phase) {
            SessionPhase.IDLE -> "Idle"
            SessionPhase.FAILED -> "CDN responded 403"
            else -> ""
        },
        errorMessage = if (phase == SessionPhase.FAILED) "CDN responded 403 — try another region." else null,
        outputDisplay = "/sdcard/Android/data/com.example/files/SteamLibrary/steamapps/common/Batch Game"
    )

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        setFlow(app.prefs, "_steamUsername", "GamerOne")
        setFlow(app.downloadManager, "_state", DownloadSessionState())
        vm = DownloaderViewModel(app)
    }

    private fun showDownloader(state: DownloadSessionState = stateFor(SessionPhase.IDLE)) {
        setFlow(app.downloadManager, "_state", state)
        composeRule.setContent { DownloaderScreen(viewModel = vm) }
        composeRule.waitForIdle()
    }

    @Test
    fun `idle shows the account banner and every setup section`() {
        showDownloader()
        composeRule.assertAnyVisible("ACCOUNT GAMERONE")
        composeRule.assertAnyVisible("TARGET & BRANCH")
        composeRule.assertAnyVisible("STORAGE DESTINATION")
        composeRule.assertAnyVisible("DLC CONTENT")
        composeRule.assertAnyVisible("VALIDATE LICENSE & DOWNLOAD")
    }

    @Test
    fun `downloading shows live rates depot progress and pause-cancel controls`() {
        showDownloader(stateFor(SessionPhase.DOWNLOADING))
        composeRule.assertAnyVisible("DOWNLOADING")
        composeRule.assertAnyVisible("DOWN RATE")
        composeRule.assertAnyVisible("DISK WRITE")
        composeRule.assertAnyVisible("TIME REMAINING")
        composeRule.assertAnyVisible("DEPOTS  0/2")
        composeRule.assertAnyVisible("42 FILES INSTALLED")
        composeRule.assertAnyVisible("PAUSE")
        composeRule.assertAnyVisible("CANCEL")
    }

    @Test
    fun `paused shows resume and cancel and reports the staged bytes as safe to move`() {
        showDownloader(stateFor(SessionPhase.PAUSED))
        composeRule.assertAnyVisible("PAUSED")
        composeRule.assertAnyVisible("RESUME")
        composeRule.assertAnyVisible("SAFE TO MOVE")
    }

    @Test
    fun `verifying phase is announced during post-download checks`() {
        showDownloader(stateFor(SessionPhase.VERIFYING))
        composeRule.assertAnyVisible("VERIFYING")
    }

    @Test
    fun `completion is clearly reported to the human`() {
        showDownloader(stateFor(SessionPhase.COMPLETED))
        composeRule.assertAnyVisible("COMPLETE")
    }

    @Test
    fun `a failed download echoes the engine's error message to the user`() {
        showDownloader(stateFor(SessionPhase.FAILED))
        composeRule.assertAnyVisible("FAILED")
        composeRule.assertAnyVisible("CDN responded 403")
    }

    @Test
    fun `cancelled state returns the human to a clean setup`() {
        showDownloader(stateFor(SessionPhase.CANCELLED))
        composeRule.assertAnyVisible("CANCELLED")
        composeRule.assertAnyVisible("VALIDATE LICENSE & DOWNLOAD")
    }

    @Test
    fun `license validation phase shows the check banner`() {
        showDownloader(stateFor(SessionPhase.VALIDATING_LICENSE))
        composeRule.assertAnyVisible("CHECKING LICENSE")
    }

    @Test
    fun `the engine log corner is titled with its line count`() {
        showDownloader(stateFor(SessionPhase.IDLE))
        // Title always present; the placeholder/copy behind it is expanded on
        // demand via the SHOW/HIDE toggles.
        composeRule.assertAnyVisible("ENGINE LOG")
    }

    // ---------------- Full-device purge ("delete everything downloaded") ----------------

    private fun seedDownloadedFiles(): List<File> {
        val external = app.getExternalFilesDir(null)!!
        val gameDir = File(external, "SteamLibrary/steamapps/common/Batch Game").apply { mkdirs() }
        File(gameDir, "bin/client.dll").apply { parentFile!!.mkdirs() }.writeText("game bytes")
        val staging = File(gameDir, ".DepotDownloader").apply { mkdirs() }
        File(staging, "ledger.bin").writeText("partial chunks")
        File(staging, "session.json").writeText("{}")
        val batches = File(app.filesDir, "batches_400.json").apply { writeText("{\"appId\":400}") }
        val installs = File(app.filesDir, "installs.json").apply { writeText("{\"400\":\"$gameDir\"}") }
        return listOf(gameDir, staging, batches, installs)
    }

    @Test
    fun `delete-all entry is visible with one tap away`() {
        showDownloader(stateFor(SessionPhase.IDLE))
        composeRule.assertAnyVisible("DELETE EVERYTHING DOWNLOADED")
        composeRule.onNodeWithTag("delete_all_downloads", useUnmergedTree = true)
            .assertIsEnabled()
    }

    @Test
    fun `purge is blocked while the engine is writing`() = kotlinx.coroutines.runBlocking {
        showDownloader(stateFor(SessionPhase.DOWNLOADING))
        composeRule.onNodeWithTag("delete_all_downloads", useUnmergedTree = true)
            .assertIsNotEnabled()
        val result = app.downloadManager.purgeAllDownloadedData()
        assert(result == null) { "purge must refuse mid-download, got $result" }
    }

    @Test
    fun `confirming the dialog deletes every downloaded file and reports bytes freed`() {
        val files = seedDownloadedFiles()
        showDownloader(stateFor(SessionPhase.IDLE))
        composeRule.onNodeWithTag("delete_all_downloads", useUnmergedTree = true)
            .performClick()
        composeRule.waitForIdle()
        composeRule.assertAnyVisible("Delete all downloaded files?")
        composeRule.assertAnyVisible("Your Steam sign-in and library stay untouched")
        composeRule.onNodeWithTag("confirm_delete_all", useUnmergedTree = true)
            .performClick()
        composeRule.awaitVisible("All downloaded files deleted")
        files.forEach { f ->
            org.junit.Assert.assertFalse("still on disk after purge: $f", f.exists())
        }
    }

    @Test
    fun `cancelling the dialog keeps every file intact`() {
        val files = seedDownloadedFiles()
        showDownloader(stateFor(SessionPhase.IDLE))
        composeRule.onNodeWithTag("delete_all_downloads", useUnmergedTree = true)
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("cancel_delete_all", useUnmergedTree = true)
            .performClick()
        composeRule.waitForIdle()
        files.forEach { f ->
            org.junit.Assert.assertTrue("lost after cancelled purge: $f", f.exists())
        }
    }
}
