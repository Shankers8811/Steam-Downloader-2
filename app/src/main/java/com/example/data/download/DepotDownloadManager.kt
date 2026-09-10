package com.example.data.download

import android.content.Context
import com.example.data.api.SteamApiService
import com.example.data.license.LicenseValidator
import com.example.data.model.DlcMode
import com.example.data.model.LogLevel
import com.example.data.model.LogLine
import com.example.data.steam.DepotPlanEntry
import com.example.data.steam.SteamRuntime
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.AppItem
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * The download engine — now running natively on Steam's real infrastructure,
 * exactly the way GameNative does it (see docs/GAMENATIVE_NOTES.md):
 *
 *   VALIDATING_LICENSE → ALLOCATING → DOWNLOADING → VERIFYING → COMPLETED
 *                                          ↘ PAUSED (resume any time)
 *
 * Content comes from Valve's CDN through [DepotDownloader] using the app's
 * CM session: depot manifests + depot keys are fetched over the CM protocol,
 * every ~1 MiB chunk is decompressed (gzip/VZip/zstd/LZMA) and checksum-
 * verified before it is written, and the per-depot staging ledger inside
 * "<installDir>/.DepotDownloader" makes pause / process death / refreshes
 * resumable with full re-verification on resume — never a corrupt byte.
 */
class DepotDownloadManager(
    private val context: Context,
    private val runtime: SteamRuntime,
    private val api: SteamApiService
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(DownloadSessionState())
    val state: StateFlow<DownloadSessionState> = _state.asStateFlow()

    private enum class ControlSignal { NONE, PAUSE, CANCEL }

    @Volatile
    private var controlSignal = ControlSignal.NONE

    private var engineJob: Job? = null

    /**
     * Live engine instance of the current session. Kept so that an emergency
     * CANCEL can close the downloader's OWN internal coroutine scope — that
     * kills in-flight CDN reads immediately instead of politely waiting for
     * the current chunk to finish (possibly hanging forever on a stalled
     * connection). This is what makes cancel truly instant.
     */
    @Volatile
    private var activeDownloader: DepotDownloader? = null

    @Volatile
    private var lastRequest: DownloadRequest? = null

    /** Live counters fed by IDownloadListener events (job thread). */
    private val counters = DownloadCounters()

    /** Hot-path throttle for per-chunk depot-row recomposition. */
    @Volatile
    private var lastDepotRowUpdateMs = 0L

    // ------------------------------------------------------------------
    // Public control API
    // ------------------------------------------------------------------

    /** The real, on-disk root where games are installed. */
    val installRoot: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "SteamLibrary")

    val installRootDisplay: String
        get() = installRoot.absolutePath

    /**
     * Resolved install directory for an app, following the Steam convention:
     * `SteamLibrary/steamapps/common/<Title>`.
     */
    fun installDirFor(appName: String): File =
        File(File(installRoot, "steamapps/common"), sanitizeFolderName(appName))

    /** True when resumable on-disk staging exists for this app id. */
    fun hasStagingSession(appId: Int): Boolean {
        val dir = installDirForAppId(appId) ?: return false
        return File(dir, STAGING_DIR_NAME).listFiles()?.isNotEmpty() == true
    }

    /**
     * Starts downloading. If staging from a previous run exists for the same
     * app, it is picked up automatically and content resumes from recorded
     * chunk boundaries (with re-verification).
     */
    fun start(request: DownloadRequest, tokenProvider: suspend () -> String?) {
        if (engineJob?.isActive == true) {
            log(LogLevel.WARN, "A download session is already active.")
            return
        }
        controlSignal = ControlSignal.NONE
        lastRequest = request
        engineJob = scope.launch { runEngine(request, tokenProvider, isResume = false) }
    }

    /** Resumes a session that was paused in this process. */
    fun resume(tokenProvider: suspend () -> String?) {
        if (engineJob?.isActive == true) return
        val request = lastRequest ?: return
        controlSignal = ControlSignal.NONE
        engineJob = scope.launch { runEngine(request, tokenProvider, isResume = true) }
    }

    /** Pauses promptly; staging keeps the safe position for a later resume. */
    fun pause() {
        if (engineJob?.isActive != true) return
        if (controlSignal == ControlSignal.NONE) {
            controlSignal = ControlSignal.PAUSE
            log(LogLevel.INFO, "Pause requested — stopping the engine; progress so far is kept…")
        }
        // Close the downloader's internal scope so pause is immediate even if
        // a CDN read is stalled, then cancel the engine job.
        runCatching { activeDownloader?.close() }
        engineJob?.cancel()
    }

    /** Cancels the session. Staging stays on disk so it can be resumed later. */
    fun cancel() {
        if (controlSignal == ControlSignal.NONE) {
            controlSignal = ControlSignal.CANCEL
        }
        // Instant abort: close the downloader's internal scope (kills socket
        // reads), then cancel our engine job. Staging/ledger stay on disk so
        // a later resume is still possible — "emergency stop", not "delete".
        runCatching { activeDownloader?.close() }
        engineJob?.cancel()
    }

    /** Removes the engine's staging ledger + partial files for an app. */
    fun clearStaging(appId: Int) {
        val dir = installDirForAppId(appId) ?: return
        val staged = File(dir, STAGING_DIR_NAME)
        val stagedBytes = staged.walkBottomUp().filter { it.isFile }.map { it.length() }.sum()
        staged.deleteRecursively()
        val map = installsMap().toMutableMap()
        map.remove(appId)
        saveInstallsMap(map)
        update { it.copy(hasResumableSession = false) }
        log(LogLevel.INFO, "Staging for app $appId cleared (${formatBytes(stagedBytes)}).")
    }

    // ------------------------------------------------------------------
    // Engine
    // ------------------------------------------------------------------

    private suspend fun runEngine(
        request: DownloadRequest,
        tokenProvider: suspend () -> String?,
        isResume: Boolean
    ) {
        counters.reset()
        resetStateFor(request, isResume)

        try {
            // ---------------- CM session assert ----------------
            update { it.copy(statusMessage = "Checking Steam connection…") }
            if (!runtime.waitLoggedOn(10_000L)) {
                failEngine("Not signed in to Steam — sign in first, then start the download.")
                return
            }

            // ---------------- VALIDATING_LICENSE ----------------
            update {
                it.copy(
                    phase = SessionPhase.VALIDATING_LICENSE,
                    statusMessage = "Validating licenses before downloading (like the Steam client)…"
                )
            }
            val webToken = tokenProvider()
            val ownershipChecker: suspend (Int) -> Boolean? = ownership@{ appId ->
                val token = webToken ?: return@ownership null
                try {
                    api.checkAppOwnership(token, appId).response?.appOwnership?.ownsApp
                } catch (e: Exception) {
                    null
                }
            }
            // Audit fix (f): only a definitive "false" from Steam blocks the
            // download. A network failure here degrades to the CM-side
            // enforcement (the native downloader refuses unowned content).
            val baseOwnership = ownershipChecker(request.appId)
            if (baseOwnership == false) {
                failEngine(
                    "License missing: \"${request.appName}\" is not on this Steam account. " +
                        "Steam only lets you download content you own."
                )
                return
            }
            if (baseOwnership == null) {
                log(LogLevel.WARN, "Web license check unavailable — continuing; the CM download engine enforces ownership itself.")
            }

            val validator = LicenseValidator(httpClient = defaultHttpClient(), ownershipChecker = ownershipChecker)
            val report = validator.validate(request.appId, request.appName, request.dlcMode) { line -> log(line.level, line.text) }
            update { it.copy(licenseReport = report) }

            // ---------------- Depot plan (PICS) ----------------
            update { it.copy(statusMessage = "Resolving depot content plan from Steam PICS…") }
            val hasLicenses = runtime.waitLicenses(20_000L)
            if (!hasLicenses) {
                failEngine("Steam did not send the account license list — try again in a moment.")
                return
            }

            val fullPlan = runtime.fetchDepotPlan(request.appId, request.branch.ifBlank { "public" }, language = null)
            if (fullPlan == null) {
                failEngine("Steam PICS did not return content info for app ${request.appId} (app may be delisted).")
                return
            }

            val licensedDlcIds = report.licensedDlc.map { it.appId }.toSet()
            if (report.baseUnverified && request.dlcMode != DlcMode.BASE_ONLY) {
                log(LogLevel.WARN, "DLC licenses could not be pre-checked (web unreachable) — only base-game depots are selected this run; owned DLC can still be fetched in a later run once the web check succeeds.")
            }
            val selected = selectDepots(fullPlan, request.dlcMode, licensedDlcIds)
            if (selected.isEmpty()) {
                failEngine(
                    if (request.dlcMode == DlcMode.DLC_ONLY)
                        "No licensed DLC depots found for this app."
                    else
                        "No downloadable depots found for app ${request.appId} on branch '${request.branch}' (Windows x64)."
                )
                return
            }

            val totalCompressed = selected.sumOf { it.downloadBytes }
            val totalUncompressed = selected.sumOf { it.sizeBytes }
            counters.totalCompressed = totalCompressed

            val installDir = installDirFor(request.appName)
            rememberInstall(request.appId, installDir.absolutePath)

            val stagedDir = File(installDir, STAGING_DIR_NAME)
            val resumeAvailable = stagedDir.listFiles()?.isNotEmpty() == true

            update {
                it.copy(
                    phase = SessionPhase.ALLOCATING,
                    totalBytes = totalUncompressed,
                    depots = selected.map { entry ->
                        DepotProgress(
                            depotId = entry.depotId,
                            name = entry.name,
                            dlcAppId = entry.dlcAppId,
                            totalCompressedBytes = entry.downloadBytes,
                            downloadedCompressedBytes = 0L,
                            completed = false
                        )
                    },
                    outputDisplay = installDir.absolutePath,
                    hasResumableSession = resumeAvailable,
                    statusMessage = "Preparing ${selected.size} depot(s) — ${formatBytes(totalCompressed)} to download (${formatBytes(totalUncompressed)} installed)…"
                )
            }
            log(LogLevel.OK, "Depot plan: ${selected.size} depot(s), ${formatBytes(totalCompressed)} compressed / ${formatBytes(totalUncompressed)} installed.")
            log(LogLevel.INFO, "Install directory: ${installDir.absolutePath}")
            if (resumeAvailable) {
                log(LogLevel.OK, "Found existing chunk staging — resuming with full re-verification of existing content.")
            }
            installDir.mkdirs()
            if (!installDir.isDirectory) {
                failEngine("Could not create the install directory — check storage permissions.")
                return
            }

            // ---------------- DOWNLOADING ----------------
            update { it.copy(phase = SessionPhase.DOWNLOADING, statusMessage = "Downloading content from Steam CDN…") }

            val statLoop = startStatisticsLoop(totalCompressed)

            val downloader = DepotDownloader(
                steamClient = runtime.client,
                licenses = runtime.licenses.value,
                debug = false,
                useLanCache = false,
                maxDownloads = 8,
                maxFileWrites = 1,
                androidEmulation = true, // fetch Windows depots (a phone mimics a PC install)
                parentJob = engineJob
                // NOTE: named args on purpose — the 1.8.0 ctor has an extra
                // positional slot (maxDecompress) that would silently shift any
                // positional call.
            )
            activeDownloader = downloader

            val failedErrors = mutableListOf<String>()
            downloader.addListener(object : IDownloadListener {
                override fun onDownloadStarted(item: DownloadItem) {
                    log(LogLevel.INFO, "Engine started for app ${request.appId}.")
                }

                override fun onStatusUpdate(message: String) {
                    log(LogLevel.INFO, message)
                }

                override fun onFileCompleted(depotId: Int, fileName: String, depotPercentComplete: Float) {
                    counters.completeFileCount++
                    update {
                        it.copy(
                            currentFile = fileName,
                            completeFileCount = counters.completeFileCount,
                            recentFiles = (listOf(fileName) + it.recentFiles).take(MAX_RECENT_FILES)
                        )
                    }
                }

                override fun onChunkCompleted(
                    depotId: Int,
                    depotPercentComplete: Float,
                    compressedBytes: Long,
                    uncompressedBytes: Long
                ) {
                    counters.recordChunk(depotId, System.currentTimeMillis(), compressedBytes, uncompressedBytes)

                    // Hot-path guard: full depot-row recomposition at most ~6x/s,
                    // counters/state keep their own docs via the stats loop.
                    val nowMs = System.currentTimeMillis()
                    if (depotPercentComplete < 1f && nowMs - lastDepotRowUpdateMs < 160L) return
                    lastDepotRowUpdateMs = nowMs

                    val entry = selected.firstOrNull { it.depotId == depotId } ?: return
                    update { st ->
                        st.copy(
                            downloadedBytes = counters.compressedTotal,
                            depots = st.depots.map { row ->
                                if (row.depotId == depotId) {
                                    row.copy(
                                        downloadedCompressedBytes = if (entry.downloadBytes > 0)
                                            compressedBytes.coerceAtMost(entry.downloadBytes)
                                        else compressedBytes
                                    )
                                } else row
                            }
                        )
                    }
                }

                override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
                    update { st ->
                        st.copy(
                            depots = st.depots.map { row ->
                                if (row.depotId == depotId) row.copy(
                                    completed = true,
                                    downloadedCompressedBytes = row.totalCompressedBytes.coerceAtLeast(row.downloadedCompressedBytes)
                                ) else row
                            }
                        )
                    }
                    log(LogLevel.OK, "Depot $depotId complete — ${formatBytes(compressedBytes)} transferred (${formatBytes(uncompressedBytes)} uncompressed).")
                }

                override fun onDownloadCompleted(item: DownloadItem) {
                    log(LogLevel.OK, "All depots processed.")
                }

                override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                    failedErrors.add(error.message ?: error.javaClass.simpleName)
                    log(LogLevel.ERROR, "Download failed: ${error.message ?: error.javaClass.simpleName}")
                }
            })

            val item = AppItem(
                appId = request.appId,
                installDirectory = installDir.absolutePath,
                branch = request.branch.ifBlank { "public" },
                os = "windows",
                osArch = "64",
                depot = selected.map { it.depotId },
                verify = true // always re-verify existing content (Steam's "verify integrity")
            )

            downloader.add(item)
            downloader.finishAdding()

            withContext(Dispatchers.IO) {
                downloader.getCompletion().await()
            }
            downloader.close()
            statLoop.cancel()

            // ---------------- Outcome ----------------
            when {
                controlSignal == ControlSignal.PAUSE -> {
                    update {
                        it.copy(
                            phase = SessionPhase.PAUSED,
                            networkBytesPerSec = 0.0,
                            diskBytesPerSec = 0.0,
                            hasResumableSession = true,
                            statusMessage = "Paused at a chunk boundary — resume any time; completed files are whole."
                        )
                    }
                    log(LogLevel.INFO, "Paused. Staging keeps ${formatBytes(counters.compressedTotal)} of verified content; resume re-verifies instead of restarting.")
                }
                controlSignal == ControlSignal.CANCEL -> {
                    update {
                        it.copy(
                            phase = SessionPhase.CANCELLED,
                            networkBytesPerSec = 0.0,
                            diskBytesPerSec = 0.0,
                            hasResumableSession = File(installDir, STAGING_DIR_NAME).exists(),
                            statusMessage = "Cancelled. Partial content stays in staging and can be resumed."
                        )
                    }
                    log(LogLevel.WARN, "Download cancelled by user.")
                }
                failedErrors.isNotEmpty() -> {
                    failEngine("Steam reported a download error: ${failedErrors.first()}")
                }
                else -> {
                    update {
                        it.copy(
                            phase = SessionPhase.VERIFYING,
                            statusMessage = "Verifying installed content integrity…"
                        )
                    }
                    log(LogLevel.INFO, "All chunks were verified while downloading (per-chunk checksums).")
                    update {
                        it.copy(
                            phase = SessionPhase.COMPLETED,
                            downloadedBytes = counters.totalCompressed,
                            networkBytesPerSec = 0.0,
                            diskBytesPerSec = 0.0,
                            depots = it.depots.map { row -> row.copy(
                                completed = true,
                                downloadedCompressedBytes = row.totalCompressedBytes
                            ) },
                            safeToMove = true,
                            hasResumableSession = false,
                            statusMessage = "Completed — everything downloaded & verified, safe to move."
                        )
                    }
                    log(LogLevel.OK, "\"${request.appName}\" fully installed to ${installDir.absolutePath} — files are verified and safe to move to your PC.")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Pause/cancel already handled via controlSignal — but a raw cancel
            // (e.g. process teardown) lands here; surface it as a paused state.
            if (_state.value.isEngineActive) {
                update {
                    it.copy(
                        phase = SessionPhase.PAUSED,
                        networkBytesPerSec = 0.0,
                        diskBytesPerSec = 0.0,
                        hasResumableSession = true,
                        statusMessage = "Stopped — staging preserved; resume any time."
                    )
                }
            }
        } catch (e: Exception) {
            if (controlSignal == ControlSignal.PAUSE) {
                update {
                    it.copy(
                        phase = SessionPhase.PAUSED,
                        networkBytesPerSec = 0.0,
                        diskBytesPerSec = 0.0,
                        hasResumableSession = true,
                        statusMessage = "Paused — staging preserved; resume any time."
                    )
                }
            } else if (controlSignal == ControlSignal.CANCEL) {
                update { it.copy(phase = SessionPhase.CANCELLED, statusMessage = "Cancelled.") }
            } else {
                failEngine("${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
            }
        } finally {
            activeDownloader = null
        }
    }

    // ------------------------------------------------------------------
    // Depot plan filtering
    // ------------------------------------------------------------------

    private fun selectDepots(
        plan: List<DepotPlanEntry>,
        mode: DlcMode,
        licensedDlcIds: Set<Int>
    ): List<DepotPlanEntry> = when (mode) {
        DlcMode.BASE_ONLY -> plan.filter { it.dlcAppId == null }
        // Owned-DLC depots only (unlicensed ones are never selected — same as Steam).
        DlcMode.BASE_AND_DLC -> plan.filter { it.dlcAppId == null || it.dlcAppId in licensedDlcIds }
        DlcMode.DLC_ONLY -> plan.filter { it.dlcAppId != null && it.dlcAppId in licensedDlcIds }
    }

    // ------------------------------------------------------------------
    // Statistics (Steam-style)
    // ------------------------------------------------------------------

    /** Samples cumulative compressed/uncompressed counters into smooth rates. */
    private fun startStatisticsLoop(totalCompressedBytes: Long): Job = scope.launch {
        val startedAt = System.currentTimeMillis()
        var peak = 0.0
        while (isActive) {
            delay(STAT_TICK_MS)
            val snapshot = counters.snapshot()
            if (snapshot.networkBps > peak) peak = snapshot.networkBps
            val elapsed = (System.currentTimeMillis() - startedAt) / 1000L
            val etaSeconds = if (totalCompressedBytes > 0 && snapshot.networkBps > 1.0) {
                ((totalCompressedBytes - counters.compressedTotal) / snapshot.networkBps).toLong().coerceAtLeast(0L)
            } else -1L
            update {
                it.copy(
                    networkBytesPerSec = snapshot.networkBps,
                    diskBytesPerSec = snapshot.diskBps,
                    networkPeakBytesPerSec = maxOf(it.networkPeakBytesPerSec, peak),
                    etaSeconds = etaSeconds,
                    elapsedSeconds = elapsed,
                    downloadedBytes = counters.compressedTotal
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Install directory mapping (persists across process death)
    // ------------------------------------------------------------------

    private fun installsMap(): MutableMap<Int, String> {
        val file = File(context.filesDir, INSTALLS_FILE)
        if (!file.exists()) return mutableMapOf()
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val map = mutableMapOf<Int, String>()
            json.keys().forEach { key ->
                map[key.toInt()] = json.getString(key)
            }
            map
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun saveInstallsMap(map: Map<Int, String>) {
        val file = File(context.filesDir, INSTALLS_FILE)
        val tmp = File(context.filesDir, "$INSTALLS_FILE.tmp")
        try {
            val json = JSONObject()
            map.forEach { (k, v) -> json.put(k.toString(), v) }
            tmp.writeText(json.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    private fun installDirForAppId(appId: Int): File? =
        installsMap()[appId]?.let { File(it) }

    private fun rememberInstall(appId: Int, path: String) {
        val map = installsMap()
        map[appId] = path
        saveInstallsMap(map)
    }

    // ------------------------------------------------------------------
    // Helpers / state plumbing
    // ------------------------------------------------------------------

    private fun defaultHttpClient(): okhttp3.OkHttpClient =
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

    private fun resetStateFor(request: DownloadRequest, isResume: Boolean) {
        val installDir = installDirFor(request.appName)
        _state.value = DownloadSessionState(
            phase = SessionPhase.VALIDATING_LICENSE,
            appId = request.appId,
            appName = request.appName,
            branch = request.branch.ifBlank { "public" },
            outputDisplay = installDir.absolutePath,
            hasResumableSession = File(installDir, STAGING_DIR_NAME).listFiles()?.isNotEmpty() == true,
            statusMessage = if (isResume) "Resuming session…" else "Preparing download…",
            logLines = emptyList()
        )
        log(LogLevel.INFO, if (isResume) "Resuming \"${request.appName}\"…" else "Starting \"${request.appName}\" (app ${request.appId})…")
    }

    private fun failEngine(message: String) {
        update {
            it.copy(
                phase = SessionPhase.FAILED,
                networkBytesPerSec = 0.0,
                diskBytesPerSec = 0.0,
                errorMessage = message,
                statusMessage = "Failed: $message"
            )
        }
        log(LogLevel.ERROR, message)
    }

    private fun sanitizeFolderName(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("[\\\\/:;*?\"<>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifBlank { "app" }
    }

    private fun update(transform: (DownloadSessionState) -> DownloadSessionState) {
        _state.value = transform(_state.value)
    }

    private fun log(level: LogLevel, text: String) {
        val lines = _state.value.logLines.toMutableList()
        lines.add(LogLine(level, text))
        if (lines.size > MAX_LOG_LINES) lines.removeAt(0)
        _state.value = _state.value.copy(logLines = lines)
    }

    // ------------------------------------------------------------------
    // Counters fed by engine events
    // ------------------------------------------------------------------

    private class DownloadCounters {
        private val lock = Any()

        @Volatile
        var compressedTotal: Long = 0L

        @Volatile
        var completeFileCount: Int = 0

        @Volatile
        var totalCompressed: Long = 0L

        private var uncompressedTotal: Long = 0L
        private var lastSampleMs: Long = 0L
        private var lastCompressed: Long = 0L
        private var lastUncompressed: Long = 0L
        private var emaNetwork: Double = Double.NaN
        private var emaDisk: Double = Double.NaN

        fun reset() {
            synchronized(lock) {
                compressedTotal = 0L
                uncompressedTotal = 0L
                completeFileCount = 0
                totalCompressed = 0L
                lastSampleMs = 0L
                lastCompressed = 0L
                lastUncompressed = 0L
                emaNetwork = Double.NaN
                emaDisk = Double.NaN
                perDepotCompressed.clear() // CRITICAL: stale per-depot values from a
                perDepotUncompressed.clear() // previous run would corrupt resume math.
            }
        }

        /**
         * compressedBytes / uncompressedBytes are PER-depot cumulative values
         * (for the current engine run); multiple depots download in parallel,
         * so the download-wide sum is maintained by diffing each depot's last
         * seen value. Per-depot maps reset per run — critical for resume.
         */
        private val perDepotCompressed = mutableMapOf<Int, Long>()
        private val perDepotUncompressed = mutableMapOf<Int, Long>()

        fun recordChunk(depotId: Int, nowMs: Long, compressed: Long, uncompressed: Long) {
            synchronized(lock) {
                val prevC = perDepotCompressed[depotId] ?: 0L
                val prevU = perDepotUncompressed[depotId] ?: 0L
                perDepotCompressed[depotId] = compressed
                perDepotUncompressed[depotId] = uncompressed
                compressedTotal += (compressed - prevC)
                uncompressedTotal += (uncompressed - prevU)

                if (lastSampleMs == 0L) {
                    lastSampleMs = nowMs
                    lastCompressed = compressedTotal
                    lastUncompressed = uncompressedTotal
                    return
                }
                val dt = nowMs - lastSampleMs
                if (dt < 150L) return

                val instNetwork = (compressedTotal - lastCompressed).toDouble() * 1000.0 / dt
                val instDisk = (uncompressedTotal - lastUncompressed).toDouble() * 1000.0 / dt

                emaNetwork = if (emaNetwork.isNaN()) instNetwork else emaNetwork + EMA_ALPHA * (instNetwork - emaNetwork)
                emaDisk = if (emaDisk.isNaN()) instDisk else emaDisk + EMA_ALPHA * (instDisk - emaDisk)

                lastSampleMs = nowMs
                lastCompressed = compressedTotal
                lastUncompressed = uncompressedTotal
            }
        }

        data class Snapshot(val networkBps: Double, val diskBps: Double)

        fun snapshot(): Snapshot = synchronized(lock) {
            // Rate decays to zero quickly when chunks stop coming (idleness).
            val idleMs = System.currentTimeMillis() - lastSampleMs
            val decay = if (lastSampleMs == 0L) 0.0 else if (idleMs > 3000L) 0.0 else {
                (1.0 - idleMs / 3000.0).coerceIn(0.0, 1.0)
            }
            Snapshot(
                networkBps = (if (emaNetwork.isNaN()) 0.0 else emaNetwork) * decay,
                diskBps = (if (emaDisk.isNaN()) 0.0 else emaDisk) * decay
            )
        }
    }

    companion object {
        private const val STAGING_DIR_NAME = ".DepotDownloader"
        private const val INSTALLS_FILE = "installs.json"
        private const val MAX_LOG_LINES = 250
        private const val MAX_RECENT_FILES = 8
        private const val STAT_TICK_MS = 500L
        private const val EMA_ALPHA = 0.35

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1024.0 && unit < units.size - 1) {
                value /= 1024.0
                unit++
            }
            return String.format(Locale.US, if (unit >= 3) "%.2f %s" else "%.1f %s", value, units[unit])
        }
    }
}
