package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.SteamApiService
import com.example.data.download.DepotDownloadManager
import com.example.data.download.SessionPhase
import com.example.data.steam.SteamRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Emulated-device behaviour tests for the download engine's durable-session
 * and PC-transfer-batch machinery — the exact flows a user would do by hand:
 * pause a download, power off, move folders, resume the balance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadEnginePersistenceTest {

    private lateinit var ctx: Context
    private lateinit var manager: DepotDownloadManager

    private fun newManager(): DepotDownloadManager {
        // The manager is cheap to construct (scope + state only); runtime/api
        // are only touched when a download actually starts.
        return DepotDownloadManager(
            context = ctx,
            runtime = SteamRuntime(ctx),
            api = SteamApiService.create()
        )
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // Clean slate: each test lays out its own filesystem scenario.
        val external = ctx.getExternalFilesDir(null)!!
        File(external, "SteamLibrary").deleteRecursively()
        File(ctx.filesDir, "batches_400.json").delete()
        File(ctx.filesDir, "batches_401.json").delete()
        File(ctx.filesDir, "installs.json").delete()
        manager = newManager()
    }

    private fun gameDir(appName: String): File =
        File(ctx.getExternalFilesDir(null), "SteamLibrary/steamapps/common/$appName")

    private fun stagingDirOf(dir: File): File = File(dir, ".DepotDownloader")

    private fun writeSessionMarker(installDir: File, appId: Int, name: String, pausedExplicitly: Boolean) {
        val staging = stagingDirOf(installDir).apply { mkdirs() }
        // Non-empty staging = a resumable ledger exists.
        File(staging, "ledger.bin").writeText("chunk ledger")
        val marker = JSONObject()
            .put("appId", appId)
            .put("appName", name)
            .put("branch", "public")
            .put("dlcMode", "BASE_AND_DLC")
            .put("depotIds", "")
            .put("dlcDepotId", "")
            .put("pausedExplicitly", pausedExplicitly)
            .put("installDir", installDir.absolutePath)
            .put("updatedAtMs", System.currentTimeMillis())
        File(staging, "session.json").writeText(marker.toString())
    }

    private fun writeBatchRecord(
        appId: Int,
        plan: List<Pair<Int, Long>>,
        moved: List<Int>
    ): File {
        val planArr = JSONArray()
        plan.forEach { (id, size) ->
            planArr.put(
                JSONObject()
                    .put("depotId", id)
                    .put("name", "depot$id")
                    .put("dlcAppId", 0)
                    .put("sizeBytes", size)
                    .put("downloadBytes", size / 2)
            )
        }
        val movedArr = JSONArray()
        moved.forEach { movedArr.put(it) }
        val rec = JSONObject()
            .put("appId", appId)
            .put("appName", "Batch Game")
            .put("movedDepotIds", movedArr)
            .put("movedBytes", 0L)
            .put("movedBatches", moved.size)
            .put("plan", planArr)
        val file = File(ctx.filesDir, "batches_$appId.json")
        file.writeText(rec.toString())
        return file
    }

    // ---------------- Session restore ----------------

    @Test
    fun `pause marker restores a PAUSED session`() {
        val dir = gameDir("Batch Game")
        writeSessionMarker(dir, appId = 400, name = "Batch Game", pausedExplicitly = true)

        val restored = manager.restorePersistedSession()

        assertNotNull(restored)
        assertEquals(400, restored!!.appId)
        assertEquals("Batch Game", restored.appName)
        assertEquals("public", restored.branch)
        val state = manager.state.value
        assertEquals(SessionPhase.PAUSED, state.phase)
        assertEquals(400, state.appId)
        assertTrue(state.hasResumableSession)
        assertTrue(manager.restoredPausedExplicitly)
        assertEquals(restored, manager.restoredRequest)
    }

    @Test
    fun `legacy staging without session json is still recovered`() {
        // Simulates staging written by older builds: no session.json, but the
        // installs map remembers the app id <-> folder binding.
        val dir = gameDir("Legacy Game")
        val staging = stagingDirOf(dir).apply { mkdirs() }
        File(staging, "chunk.st").writeText("staged chunks")
        val installs = JSONObject().put("401", dir.absolutePath)
        File(ctx.filesDir, "installs.json").writeText(installs.toString())

        val restored = manager.restorePersistedSession()

        assertNotNull(restored)
        assertEquals(401, restored!!.appId)
        assertEquals("Legacy Game", restored.appName)
        assertEquals(SessionPhase.PAUSED, manager.state.value.phase)
        // Legacy sessions must never auto-continue (provenance unknown).
        assertTrue(manager.restoredPausedExplicitly)
    }

    @Test
    fun `empty storage restores nothing`() {
        assertNull(manager.restorePersistedSession())
        assertNull(manager.restoredRequest)
    }

    @Test
    fun `moved folder is adopted at its new root`() {
        // The marker lives inside the game folder, so moving the folder to
        // another library root keeps it discoverable: the resolver should
        // find it where it actually is, not where it was first created.
        val altRoot = File(ctx.filesDir, "sd_root").apply {
            File(this, "SteamLibrary/steamapps/common/Batch Game").mkdirs()
        }
        val dir = File(altRoot, "SteamLibrary/steamapps/common/Batch Game")
        writeSessionMarker(dir, appId = 400, name = "Batch Game", pausedExplicitly = true)

        // No marker under the primary root at all — only the relocated one.
        val restored = manager.restorePersistedSession()

        assertNotNull(restored)
        assertEquals(dir.absolutePath, manager.state.value.outputDisplay)
    }

    @Test
    fun `cold cancel on a restored session flips state to CANCELLED`() {
        val dir = gameDir("Batch Game")
        writeSessionMarker(dir, appId = 400, name = "Batch Game", pausedExplicitly = true)
        manager.restorePersistedSession()

        manager.cancel()

        assertEquals(SessionPhase.CANCELLED, manager.state.value.phase)
    }

    // ---------------- PC-transfer batches ----------------

    @Test
    fun `batch suggestion packs greedily and skips moved depots`() {
        val gb = 1024L * 1024 * 1024
        writeBatchRecord(
            appId = 400,
            plan = listOf(111 to 10 * gb, 222 to 5 * gb, 333 to 1 * gb),
            moved = listOf(111)
        )

        val hit = manager.suggestNextBatch(400, 6 * gb)
        assertNotNull(hit)
        val (ids, bytes, remaining) = hit!!
        assertEquals(setOf(222, 333), ids.toSet())
        assertEquals(6 * gb, bytes)
        assertEquals(2, remaining)

        // Zero budget still yields the smallest remaining depot.
        val tiny = manager.suggestNextBatch(400, 0)
        assertNotNull(tiny)
        assertEquals(listOf(333), tiny!!.first)

        // Once everything is moved, there is nothing left to plan.
        writeBatchRecord(appId = 400, plan = listOf(111 to 10 * gb), moved = listOf(111))
        assertNull(manager.suggestNextBatch(400, gb))
        assertNull(manager.suggestNextBatch(400, gb * 100))
    }

    @Test
    fun `batch summary math reflects plan and moved state`() {
        val gb = 1024L * 1024 * 1024
        writeBatchRecord(
            appId = 400,
            plan = listOf(111 to 10 * gb, 222 to 5 * gb, 333 to 1 * gb),
            moved = listOf(111, 222)
        )

        val summary = manager.getBatchSummary(400)
        assertNotNull(summary)
        summary!!
        assertEquals(400, summary.appId)
        assertEquals(3, summary.planDepotCount)
        assertEquals(16 * gb, summary.planBytes)
        assertEquals(2, summary.movedDepotCount)
        assertEquals(2, summary.movedBatches)
        assertEquals(1, summary.remainingDepotCount)
        assertEquals(1 * gb, summary.remainingBytes)
        // IDLE state must never allow "mark moved".
        assertFalse(summary.canMarkMoved)
    }

    @Test
    fun `batch summary is null for an unknown app`() {
        assertNull(manager.getBatchSummary(999))
    }

    @Test
    fun `mark moved refuses when nothing is paused or completed`() {
        // IDLE engine + no rows -> hard guard against accidental data loss.
        assertFalse(manager.markBatchMovedAndPurge())
    }

    // ---------------- Storage probe ----------------

    @Test
    fun `storage info reports sane free and total values`() {
        val (free, total) = manager.storageInfo()
        assertTrue(total > 0)
        assertTrue(free > 0)
        assertTrue(free <= total)
    }
}
