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

    /** Install dir of the live engine — lets control methods flip the marker. */
    @Volatile
    private var activeInstallDir: File? = null

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
        // Persist the choice: an explicit pause MUST survive an app exit /
        // device power-off and stay paused afterwards (Steam semantics).
        lastRequest?.let { req -> activeInstallDir?.let { dir -> writeSessionMarker(dir, req, pausedExplicitly = true) } }
        runCatching { activeDownloader?.close() }
        engineJob?.cancel()
    }

    /** Cancels the session. Staging stays on disk so it can be resumed later. */
    fun cancel() {
        if (engineJob?.isActive != true) {
            // Cold-state cancel: no live engine (e.g. a session restored from
            // disk into PAUSED). Flip the UI to CANCELLED; staging stays.
            if (_state.value.phase == SessionPhase.PAUSED) {
                controlSignal = ControlSignal.NONE
                lastRequest?.let { req ->
                    (installDirForAppId(req.appId) ?: resolveInstallDirFor(req))
                        .let { dir -> writeSessionMarker(dir, req, pausedExplicitly = true) }
                }
                update {
                    it.copy(
                        phase = SessionPhase.CANCELLED,
                        statusMessage = "Cancelled. Partial content stays in staging and can be resumed."
                    )
                }
            }
            return
        }
        if (controlSignal == ControlSignal.NONE) {
            controlSignal = ControlSignal.CANCEL
        }
        // Persist the stop so an exit/reboot treats this as an explicit stop,
        // not as a crash to auto-continue.
        lastRequest?.let { req -> activeInstallDir?.let { dir -> writeSessionMarker(dir, req, pausedExplicitly = true) } }
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
        // The marker lived inside the staging dir, so it is gone too.
        if (engineJob?.isActive != true) {
            // Nothing left to resume: cold session returns to IDLE cleanly.
            if (restoredRequest?.appId == appId) restoredRequest = null
            if (lastRequest?.appId == appId) lastRequest = null
            _state.value = DownloadSessionState()
        } else {
            update { it.copy(hasResumableSession = false) }
        }
        log(LogLevel.INFO, "Staging for app $appId cleared (${formatBytes(stagedBytes)}).")
    }

    // ------------------------------------------------------------------
    // Persistent sessions — Steam-style pause / power-off resume
    // ------------------------------------------------------------------

    /**
     * Snapshot of one download session, stored at
     * "<installDir>/.DepotDownloader/session.json". It is what makes pause,
     * app exit and device power-off harmless: the full request plus the
     * explicit-pause flag are recoverable from disk any time.
     */
    private data class SessionRecord(
        val appId: Int,
        val appName: String,
        val branch: String,
        val dlcModeName: String,
        val depotIds: String,
        val dlcDepotId: String,
        val pausedExplicitly: Boolean,
        val updatedAtMs: Long
    ) {
        fun toRequest(): DownloadRequest = DownloadRequest(
            appId = appId,
            appName = appName,
            branch = branch,
            dlcMode = runCatching { DlcMode.valueOf(dlcModeName) }
                .getOrDefault(DlcMode.BASE_AND_DLC),
            depotIds = depotIds,
            dlcDepotId = dlcDepotId
        )
    }

    /** Session restored from disk, ready for [resume] (null when none). */
    @Volatile
    var restoredRequest: DownloadRequest? = null
        private set

    /** True when the restored session was explicitly paused by the user. */
    @Volatile
    var restoredPausedExplicitly: Boolean = true
        private set

    /**
     * Scans every library root for session markers and restores the most
     * recent one into a PAUSED state — the Downloader tab then shows RESUME /
     * CANCEL exactly like Steam after a restart. A game folder that was
     * MOVED to another root (e.g. the SD card) while paused is recognised by
     * its marker and adopted at the new location. Returns the restored
     * request, or null when there is nothing to restore / engine is busy.
     */
    fun restorePersistedSession(): DownloadRequest? {
        if (engineJob?.isActive == true || _state.value.isEngineActive) return null
        val (record, dir) = findPersistedSession() ?: return null
        val request = record.toRequest()
        if (request.appId == 0) return null
        restoredRequest = request
        restoredPausedExplicitly = record.pausedExplicitly
        lastRequest = request
        rememberInstall(request.appId, dir.absolutePath)
        _state.value = DownloadSessionState(
            phase = SessionPhase.PAUSED,
            appId = request.appId,
            appName = request.appName,
            branch = request.branch.ifBlank { "public" },
            outputDisplay = dir.absolutePath,
            hasResumableSession = true,
            statusMessage = if (record.pausedExplicitly) {
                "Paused session restored — every chunk-checkpoint survived; resume any time."
            } else {
                "Resumable session restored — it can continue from its checkpoint."
            },
            logLines = emptyList()
        )
        log(
            LogLevel.INFO,
            "Restored resumable session for \"${request.appName}\" (app ${request.appId}) at ${dir.absolutePath} (explicit pause: ${record.pausedExplicitly})."
        )
        return request
    }

    /** All on-disk library roots: internal + every external app dir (SD card). */
    private fun libraryRoots(): List<File> = buildList {
        add(context.getExternalFilesDir(null) ?: context.filesDir)
        context.getExternalFilesDirs(null)?.forEach { if (it != null) add(it) }
        add(context.filesDir)
    }.map { it.absolutePath }.distinct().map(::File)

    private fun steamappsCommonOf(root: File): File =
        File(File(File(root, "SteamLibrary"), "steamapps"), "common")

    /**
     * Relocation-aware install-dir resolution: if the game's folder (with
     * non-empty staging) is found under ANY library root, that location wins
     * — this is how a moved folder resumes instead of re-downloading.
     */
    private fun resolveInstallDirFor(request: DownloadRequest): File {
        for (root in libraryRoots()) {
            val dir = File(steamappsCommonOf(root), sanitizeFolderName(request.appName))
            val staging = File(dir, STAGING_DIR_NAME)
            if (staging.isDirectory && staging.listFiles()?.isNotEmpty() == true) return dir
        }
        return installDirFor(request.appName)
    }

    private fun sessionMarkerOf(installDir: File): File =
        File(File(installDir, STAGING_DIR_NAME), SESSION_FILE)

    private fun writeSessionMarker(installDir: File, request: DownloadRequest, pausedExplicitly: Boolean) {
        val staging = File(installDir, STAGING_DIR_NAME)
        runCatching { staging.mkdirs() }
        val json = org.json.JSONObject()
            .put("appId", request.appId)
            .put("appName", request.appName)
            .put("branch", request.branch)
            .put("dlcMode", request.dlcMode.name)
            .put("depotIds", request.depotIds)
            .put("dlcDepotId", request.dlcDepotId)
            .put("pausedExplicitly", pausedExplicitly)
            .put("installDir", installDir.absolutePath)
            .put("updatedAtMs", System.currentTimeMillis())
        val out = File(staging, SESSION_FILE)
        val tmp = File(staging, "$SESSION_FILE.tmp")
        runCatching {
            tmp.writeText(json.toString())
            if (!tmp.renameTo(out)) {
                runCatching { tmp.copyTo(out, overwrite = true) }
                tmp.delete()
            }
        }
    }

    private fun deleteSessionMarker(installDir: File) {
        runCatching { sessionMarkerOf(installDir).delete() }
    }

    private fun readSessionMarker(marker: File): SessionRecord? = runCatching {
        val j = org.json.JSONObject(marker.readText())
        SessionRecord(
            appId = j.optInt("appId", 0),
            appName = j.optString("appName", ""),
            branch = j.optString("branch", "public"),
            dlcModeName = j.optString("dlcMode", DlcMode.BASE_AND_DLC.name),
            depotIds = j.optString("depotIds", ""),
            dlcDepotId = j.optString("dlcDepotId", ""),
            pausedExplicitly = j.optBoolean("pausedExplicitly", false),
            updatedAtMs = j.optLong("updatedAtMs", marker.lastModified())
        )
    }.getOrNull()

    /** Newest resumable session found on ANY library root (marker or legacy). */
    private fun findPersistedSession(): Pair<SessionRecord, File>? {
        val installs = installsMap()
        val candidates = mutableListOf<Pair<SessionRecord, File>>()
        for (root in libraryRoots()) {
            val games = steamappsCommonOf(root).listFiles() ?: continue
            for (dir in games) {
                if (!dir.isDirectory) continue
                val staging = File(dir, STAGING_DIR_NAME)
                if (!staging.isDirectory || staging.listFiles()?.isNotEmpty() != true) continue
                val record = readSessionMarker(sessionMarkerOf(dir))
                    ?: legacyRecordFor(dir, installs)
                if (record != null) candidates += record to dir
            }
        }
        return candidates.maxByOrNull { it.first.updatedAtMs }
    }

    /**
     * Recovery for staging written by older builds (no session.json): rebuild
     * the request from the installs map. Never auto-continues — provenance
     * unknown, so explicit RESUME is required.
     */
    private fun legacyRecordFor(dir: File, installs: Map<Int, String>): SessionRecord? {
        val staging = File(dir, STAGING_DIR_NAME)
        if (staging.listFiles()?.isNotEmpty() != true) return null
        val appId = installs.entries.firstOrNull { it.value == dir.absolutePath }?.key
            ?: return null
        val mtime = staging.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L
        return SessionRecord(
            appId = appId,
            appName = dir.name,
            branch = "public",
            dlcModeName = DlcMode.BASE_AND_DLC.name,
            depotIds = "",
            dlcDepotId = "",
            pausedExplicitly = true,
            updatedAtMs = mtime
        )
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
            // Snapshot the depot plan for the PC-transfer batch planner.
            persistPlanSnapshot(request, fullPlan)

            var selected = selectDepots(fullPlan, request.dlcMode, licensedDlcIds, request.depotIds)

            // PC-transfer batches: content already moved to the PC is skipped
            // automatically — the remaining depot set is exactly what's left.
            val moved = batchRecordOf(request.appId)?.optStringSet("movedDepotIds").orEmpty()
            if (moved.isNotEmpty()) {
                val before = selected.size
                selected = selected.filterNot { it.depotId in moved }
                if (before != selected.size) {
                    log(LogLevel.INFO, "PC-transfer mode: ${before - selected.size} depot(s) already moved to your PC — downloading only the balance.")
                }
            }
            if (selected.isEmpty()) {
                failEngine(
                    if (moved.isNotEmpty())
                        "Nothing left to download — every remaining depot was already moved to your PC. Merge the batches there and let Steam verify the install."
                    else if (request.dlcMode == DlcMode.DLC_ONLY)
                        "No licensed DLC depots found for this app."
                    else
                        "No downloadable depots found for app ${request.appId} on branch '${request.branch}' (Windows x64)."
                )
                return
            }

            val totalCompressed = selected.sumOf { it.downloadBytes }
            val totalUncompressed = selected.sumOf { it.sizeBytes }
            counters.totalCompressed = totalCompressed

            val installDir = resolveInstallDirFor(request)
            activeInstallDir = installDir
            rememberInstall(request.appId, installDir.absolutePath)
            // Steam-style durability: record this session to disk. A marker
            // with pausedExplicitly=false means "engine was running" — the app
            // auto-continues it after exit/power-off, exactly like Steam.
            writeSessionMarker(installDir, request, pausedExplicitly = false)

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
                    writeSessionMarker(installDir, request, pausedExplicitly = true)
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
                    writeSessionMarker(installDir, request, pausedExplicitly = true)
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
                    // Done for good — no session to restore next launch.
                    deleteSessionMarker(installDir)
                    activeInstallDir = null
                    // PC-transfer pipeline: remember this batch + regenerate
                    // the Steam appmanifest + README so discovery just works.
                    recordCompletedBatch(request, selected, installDir)
                    writePcTransferFiles(installDir, request)
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
            activeInstallDir = null
        }
    }

    // ------------------------------------------------------------------
    // Depot plan filtering
    // ------------------------------------------------------------------

    private fun selectDepots(
        plan: List<DepotPlanEntry>,
        mode: DlcMode,
        licensedDlcIds: Set<Int>,
        depotIdsRaw: String = ""
    ): List<DepotPlanEntry> {
        val base = when (mode) {
            DlcMode.BASE_ONLY -> plan.filter { it.dlcAppId == null }
            // Owned-DLC depots only (unlicensed ones are never selected — same as Steam).
            DlcMode.BASE_AND_DLC -> plan.filter { it.dlcAppId == null || it.dlcAppId in licensedDlcIds }
            DlcMode.DLC_ONLY -> plan.filter { it.dlcAppId != null && it.dlcAppId in licensedDlcIds }
        }
        val ids = depotIdsRaw
            .split(',', ' ', ';')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toIntOrNull() }
            .toSet()
        if (ids.isEmpty()) return base
        val unknown = ids - plan.map { it.depotId }.toSet()
        if (unknown.isNotEmpty()) {
            log(LogLevel.WARN, "Depot filter: id(s) ${unknown.joinToString(", ")} not in this app's depot plan — ignored.")
        }
        return base.filter { it.depotId in ids }
    }

    // ------------------------------------------------------------------
    // PC-transfer batches (storage-limited phones)
    // ------------------------------------------------------------------

    /** UI-facing summary of the PC-transfer state for one app. */
    data class BatchSummary(
        val appId: Int,
        val appName: String,
        val planDepotCount: Int,
        val planBytes: Long,
        val movedDepotCount: Int,
        val movedBytes: Long,
        val movedBatches: Int,
        val remainingDepotCount: Int,
        val remainingBytes: Long,
        val freeBytes: Long,
        val lastBatchDepots: List<Int>,
        val canMarkMoved: Boolean
    )

    /** Free/total bytes of the download volume (for the planner UI). */
    fun storageInfo(): Pair<Long, Long> {
        val root = installRoot
        runCatching { root.mkdirs() }
        val stat = android.os.StatFs(root.absolutePath)
        return stat.availableBytes to stat.totalBytes
    }

    private fun batchFileOf(appId: Int): File = File(context.filesDir, "batches_$appId.json")

    private fun batchRecordOf(appId: Int): org.json.JSONObject? = runCatching {
        val file = batchFileOf(appId)
        if (file.exists()) org.json.JSONObject(file.readText()) else null
    }.getOrNull()

    private fun saveBatchRecord(appId: Int, json: org.json.JSONObject) {
        json.put("updatedAtMs", System.currentTimeMillis())
        val tmp = File(context.filesDir, "batches_$appId.tmp")
        runCatching {
            tmp.writeText(json.toString())
            val out = batchFileOf(appId)
            if (!tmp.renameTo(out)) {
                runCatching { tmp.copyTo(out, overwrite = true) }
                tmp.delete()
            }
        }
    }

    private fun org.json.JSONObject.optStringSet(key: String): Set<Int> {
        val arr = optJSONArray(key) ?: return emptySet()
        val out = mutableSetOf<Int>()
        for (i in 0 until arr.length()) out += arr.optInt(i)
        return out
    }

    /** Persist the depot plan (before filters) for the batch planner card. */
    private fun persistPlanSnapshot(request: DownloadRequest, plan: List<DepotPlanEntry>) {
        val rec = batchRecordOf(request.appId) ?: org.json.JSONObject()
            .put("appId", request.appId)
            .put("appName", request.appName)
        rec.put("appName", request.appName)
        val arr = org.json.JSONArray()
        plan.forEach { entry ->
            arr.put(
                org.json.JSONObject()
                    .put("depotId", entry.depotId)
                    .put("name", entry.name)
                    .put("dlcAppId", entry.dlcAppId ?: 0)
                    .put("sizeBytes", entry.sizeBytes)
                    .put("downloadBytes", entry.downloadBytes)
            )
        }
        rec.put("plan", arr)
        if (!rec.has("movedDepotIds")) rec.put("movedDepotIds", org.json.JSONArray())
        if (!rec.has("movedBytes")) rec.put("movedBytes", 0L)
        if (!rec.has("movedBatches")) rec.put("movedBatches", 0)
        saveBatchRecord(request.appId, rec)
    }

    /** Remember the depots of the batch that just completed (pre-moved). */
    private fun recordCompletedBatch(request: DownloadRequest, selected: List<DepotPlanEntry>, installDir: File) {
        val rec = batchRecordOf(request.appId) ?: org.json.JSONObject()
            .put("appId", request.appId)
            .put("appName", request.appName)
        rec.put("appName", request.appName)
        val arr = org.json.JSONArray()
        selected.forEach { arr.put(it.depotId) }
        rec.put("lastBatchDepots", arr)
        rec.put("lastBatchBytes", selected.sumOf { it.sizeBytes })
        rec.put("installDir", installDir.absolutePath)
        if (!rec.has("movedDepotIds")) rec.put("movedDepotIds", org.json.JSONArray())
        saveBatchRecord(request.appId, rec)
    }

    /**
     * Writes Steam's own discovery files next to the game:
     *  - "steamapps/appmanifest_<appId>.acf"  (StateFlags 4 = installed; Steam
     *    re-validates content against the current manifests on discovery)
     *  - "steamapps/common/<Game>/TRANSFER_README_PC.txt" (copy-both files steps)
     * With these, Windows Steam shows "existing files found" and finishes
     * validation instead of downloading everything again.
     */
    private fun writePcTransferFiles(installDir: File, request: DownloadRequest) {
        runCatching {
            val rec = batchRecordOf(request.appId)
            val movedIds = rec?.optStringSet("movedDepotIds").orEmpty()
            val movedBytes = rec?.optLong("movedBytes", 0L) ?: 0L
            val localBytes = installDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            val steamappsDir = installDir.parentFile?.parentFile ?: return@runCatching
            val acf = File(steamappsDir, "appmanifest_${request.appId}.acf")
            val unix = System.currentTimeMillis() / 1000L
            val safeName = request.appName.replace(""", "'")
            acf.writeText(
                buildString {
                    append(""AppState"
{
")
                    append("	"appid"		"${request.appId}"
")
                    append("	"Universe"		"1"
")
                    append("	"name"		"$safeName"
")
                    append("	"StateFlags"		"4"
")
                    append("	"installdir"		"${installDir.name}"
")
                    append("	"LastUpdated"		"$unix"
")
                    append("	"UpdateResult"		"0"
")
                    append("	"SizeOnDisk"		"${movedBytes + localBytes}"
")
                    append("	"BuildID"		"0"
")
                    append("	"LastOwner"		"0"
")
                    append("	"BytesToDownload"		"0"
")
                    append("	"BytesDownloaded"		"0"
")
                    append("	"AutoUpdateBehavior"		"0"
")
                    append("	"AllowOtherDownloadsWhileRunning"		"0"
")
                    append("	"ScheduledAutoUpdate"		"0"
")
                    append("	"UserConfig"
	{
		"language"		"english"
	}
")
                    append("}
")
                }
            )
            val remainingHint = if (movedIds.isNotEmpty()) {
                "
This is a LATER batch — the first batch(es) (${movedIds.size} depot(s), ${formatBytes(movedBytes)}) are already on your PC. Copy this batch INTO the same folder in step 2 (files do not overlap; choose "Skip" if Windows asks about replacing) and replace appmanifest_${request.appId}.acf with this newest copy.
"
            } else ""
            File(installDir, "TRANSFER_README_PC.txt").writeText(
                "PC TRANSFER — ${request.appName} (app ${request.appId})
" +
                    "===================================================

" +
                    "1. On your PC open your Steam library folder, e.g.
" +
                    "   C:\Program Files (x86)\Steam\steamapps\

" +
                    "2. Copy this folder ("${installDir.name}") into:
" +
                    "   steamapps\common\  so the final path is
" +
                    "   steamapps\common\${installDir.name}\

" +
                    "3. Copy the file "appmanifest_${request.appId}.acf" — it sits one level
" +
                    "   ABOVE this folder (in steamapps\) — into the PC's steamapps\ folder,
" +
                    "   right next to the "common" folder.
" + remainingHint + "
" +
                    "4. Fully close Steam (system tray icon -> Exit), then start it again.
" +
                    "   Steam finds the existing files, runs a validation scan
" +
                    "   ("Discovering existing files") and then the game is ready to PLAY.
" +
                    "   If a batch is still missing, Steam downloads just that part.
"
            )
            log(LogLevel.OK, "PC transfer files written: appmanifest_${request.appId}.acf + TRANSFER_README_PC.txt")
        }
    }

    /** Batch summary for the UI card (null when this app never started). */
    fun getBatchSummary(appId: Int): BatchSummary? {
        val rec = batchRecordOf(appId) ?: return null
        val planArr = rec.optJSONArray("plan")
        var planCount = 0
        var planBytes = 0L
        val moved = rec.optStringSet("movedDepotIds")
        var remainingCount = 0
        var remainingBytes = 0L
        if (planArr != null) {
            for (i in 0 until planArr.length()) {
                val p = planArr.optJSONObject(i) ?: continue
                planCount++
                planBytes += p.optLong("sizeBytes", 0L)
                if (p.optInt("depotId") !in moved) {
                    remainingCount++
                    remainingBytes += p.optLong("sizeBytes", 0L)
                }
            }
        }
        val lastArr = rec.optJSONArray("lastBatchDepots")
        val lastBatch = mutableListOf<Int>()
        if (lastArr != null) for (i in 0 until lastArr.length()) lastBatch += lastArr.optInt(i)
        val (free, _) = storageInfo()
        val st = _state.value
        return BatchSummary(
            appId = appId,
            appName = rec.optString("appName", "App $appId"),
            planDepotCount = planCount,
            planBytes = planBytes,
            movedDepotCount = moved.size,
            movedBytes = rec.optLong("movedBytes", 0L),
            movedBatches = rec.optInt("movedBatches", 0),
            remainingDepotCount = remainingCount,
            remainingBytes = remainingBytes,
            freeBytes = free,
            lastBatchDepots = lastBatch,
            canMarkMoved = st.phase == SessionPhase.COMPLETED && st.appId == appId &&
                lastBatch.isNotEmpty()
        )
    }

    /**
     * Greedy "next balance" batch: smallest-first depot selection under
     * [targetBytes]. null when nothing remains.
     */
    fun suggestNextBatch(appId: Int, targetBytes: Long): Triple<List<Int>, Long, Int>? {
        val rec = batchRecordOf(appId) ?: return null
        val planArr = rec.optJSONArray("plan") ?: return null
        val moved = rec.optStringSet("movedDepotIds")
        val pool = mutableListOf<Triple<Int, Long, Long>>() // id, sizeBytes, downloadBytes
        for (i in 0 until planArr.length()) {
            val p = planArr.optJSONObject(i) ?: continue
            val id = p.optInt("depotId")
            if (id !in moved) pool += Triple(id, p.optLong("sizeBytes", 0L), p.optLong("downloadBytes", 0L))
        }
        if (pool.isEmpty()) return null
        pool.sortBy { it.second }
        val chosen = mutableListOf<Int>()
        var bytes = 0L
        for ((id, size, _) in pool) {
            if (bytes + size <= targetBytes || chosen.isEmpty()) {
                chosen += id
                bytes += size
            }
        }
        return Triple(chosen, bytes, pool.size)
    }

    /**
     * After the user copied the just-completed batch to the PC: record its
     * depots as moved and delete the local copy so the phone has room for
     * the next batch. Only valid right after COMPLETED.
     */
    fun markBatchMovedAndPurge(): Boolean {
        val st = _state.value
        if (engineJob?.isActive == true || st.phase != SessionPhase.COMPLETED) return false
        val appId = st.appId
        val rec = batchRecordOf(appId) ?: return false
        val lastArr = rec.optJSONArray("lastBatchDepots") ?: return false
        val dir = installDirForAppId(appId) ?: return false

        val movedNow = rec.optStringSet("movedDepotIds").toMutableSet()
        for (i in 0 until lastArr.length()) movedNow += lastArr.optInt(i)
        val arr = org.json.JSONArray()
        movedNow.sorted().forEach { arr.put(it) }
        rec.put("movedDepotIds", arr)
        rec.put("movedBatches", rec.optInt("movedBatches", 0) + 1)
        val dirBytes = runCatching {
            dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
        rec.put("movedBytes", rec.optLong("movedBytes", 0L) + maxOf(dirBytes, rec.optLong("lastBatchBytes", 0L)))
        saveBatchRecord(appId, rec)

        // Free the phone: game content + staging + restores go with the batch.
        runCatching { dir.deleteRecursively() }
        if (restoredRequest?.appId == appId) restoredRequest = null
        if (lastRequest?.appId == appId) lastRequest = null
        _state.value = DownloadSessionState()
        log(LogLevel.OK, "Batch recorded as moved to PC (${movedNow.size} depot(s) total) — local copy deleted, space freed.")
        return true
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
        val installDir = resolveInstallDirFor(request)
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
        private const val SESSION_FILE = "session.json"
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
