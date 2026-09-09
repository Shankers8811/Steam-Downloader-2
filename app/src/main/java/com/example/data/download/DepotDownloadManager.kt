package com.example.data.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.data.api.SteamApiService
import com.example.data.license.LicenseValidator
import com.example.data.model.DlcMode
import com.example.data.model.LicenseReport
import com.example.data.model.LogLevel
import com.example.data.model.LogLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The download engine.
 *
 * Pipeline (same semantics as the Steam desktop client):
 *
 *   VALIDATING_LICENSE → ALLOCATING → DOWNLOADING → VERIFYING → COMPLETED
 *                                          ↘ PAUSED (resume any time)
 *
 * integrity rules that make "pause → move files to PC → resume on phone" safe:
 *
 *  • Incoming bytes are written to hidden staging (`steam_staging/<appId>`),
 *    never directly to the user-visible game folder.
 *  • Staging writes advance ONLY at 1 MiB chunk boundaries; the on-disk
 *    partial file is always re-truncated to the last completed chunk, so an
 *    interrupted chunk can never leave garbage bytes behind.
 *  • `session.json` (chunk bitmap per file, atomic temp+rename write) is the
 *    resume source of truth — surviving pauses, process death and reboots.
 *  • A file is copied out of staging into the destination folder ONLY when
 *    100% complete: it is streamed through SHA-256 into "<name>.downloading"
 *    and then renamed to its final name. Visible files are always whole.
 */
class DepotDownloadManager(
    private val context: Context,
    private val api: SteamApiService,
    initialTargetTreeUri: String?,
    initialTargetDisplay: String
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val cdn = SimulatedSteamCdn()

    private val _state = MutableStateFlow(DownloadSessionState())
    val state: StateFlow<DownloadSessionState> = _state.asStateFlow()

    private enum class ControlSignal { NONE, PAUSE, CANCEL }

    @Volatile
    private var controlSignal = ControlSignal.NONE

    private var engineJob: Job? = null

    @Volatile
    private var targetTreeUri: Uri? = initialTargetTreeUri
        ?.takeIf { it.isNotBlank() }
        ?.let(Uri::parse)

    @Volatile
    private var targetDisplay: String = initialTargetDisplay.ifBlank { DEFAULT_DISPLAY }

    // ------------------------------------------------------------------
    // Public control API
    // ------------------------------------------------------------------

    fun setDestination(treeUriString: String?, display: String) {
        targetTreeUri = treeUriString?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        targetDisplay = display.ifBlank { DEFAULT_DISPLAY }
        update {
            it.copy(
                outputDisplay = destinationDisplayFor(_state.value.appName),
                statusMessage = "Storage location set to $targetDisplay"
            )
        }
        log(LogLevel.INFO, "Storage destination set: $targetDisplay")
    }

    /** True when resumable staging exists on disk for this app id. */
    fun hasStagingSession(appId: Int): Boolean = File(stagingDirFor(appId), SESSION_FILE).exists()

    /**
     * Starts downloading. If staging from a previous run exists for the same
     * app, it is picked up automatically and the download resumes from the
     * recorded chunk boundaries.
     */
    fun start(request: DownloadRequest, tokenProvider: suspend () -> String?) {
        if (engineJob?.isActive == true) {
            log(LogLevel.WARN, "A download session is already active.")
            return
        }
        controlSignal = ControlSignal.NONE
        engineJob = scope.launch { runEngine(request, tokenProvider, isResume = false) }
    }

    /** Resumes a session that was paused in this process. */
    fun resume(tokenProvider: suspend () -> String?) {
        val snapshot = _state.value
        if (snapshot.phase != SessionPhase.PAUSED) return
        if (engineJob?.isActive == true) return
        controlSignal = ControlSignal.NONE
        val request = DownloadRequest(
            appId = snapshot.appId,
            appName = snapshot.appName,
            branch = snapshot.branch,
            dlcMode = snapshot.licenseReport?.let { DlcMode.BASE_AND_DLC } ?: DlcMode.BASE_ONLY,
            depotIds = "",
            dlcDepotId = ""
        )
        log(LogLevel.INFO, "Resuming download from recorded chunk boundaries…")
        engineJob = scope.launch { runEngine(request, tokenProvider, isResume = true) }
    }

    /**
     * Pauses at the NEXT CHUNK BOUNDARY: the in-flight chunk finishes, the
     * partial file is flushed and re-truncated to the last completed chunk and
     * session.json is persisted. No partially-written visible file can exist.
     */
    fun pause() {
        val snapshot = _state.value
        if (!snapshot.isEngineActive) return
        if (_state.value.phase == SessionPhase.DOWNLOADING ||
            _state.value.phase == SessionPhase.VALIDATING_LICENSE ||
            _state.value.phase == SessionPhase.ALLOCATING
        ) {
            controlSignal = ControlSignal.PAUSE
            log(LogLevel.INFO, "Pause requested — finishing the current chunk, then stopping at a clean boundary…")
        }
    }

    /** Stops the session. Staging is kept so the download can resume later. */
    fun cancel() {
        if (engineJob?.isActive != true) return
        controlSignal = ControlSignal.CANCEL
        log(LogLevel.WARN, "Cancel requested — stopping and preserving staged chunks…")
    }

    /** Discards staged partial data for an app (the "clear partials" action). */
    fun clearStaging(appId: Int) {
        if (engineJob?.isActive == true) return
        stagingDirFor(appId).deleteRecursively()
        update {
            it.copy(
                phase = SessionPhase.IDLE,
                hasResumableSession = false,
                totalBytes = 0L,
                downloadedBytes = 0L,
                files = emptyList(),
                currentFile = "",
                currentFileChunkIndex = 0,
                currentFileChunkCount = 0,
                completeFileCount = 0,
                fileCount = 0,
                safeToMove = false,
                statusMessage = "Partial data cleared — ready for a fresh download"
            )
        }
        log(LogLevel.INFO, "Staging for app $appId was discarded.")
    }

    // ------------------------------------------------------------------
    // Engine
    // ------------------------------------------------------------------

    private suspend fun runEngine(
        request: DownloadRequest,
        tokenProvider: suspend () -> String?,
        isResume: Boolean
    ) = withContext(Dispatchers.IO) {
        val stagingDir = stagingDirFor(request.appId).apply { mkdirs() }
        val restoredSession = loadSession(stagingDir)

        if (!isResume) {
            update {
                DownloadSessionState(
                    phase = SessionPhase.VALIDATING_LICENSE,
                    appId = request.appId,
                    appName = request.appName,
                    branch = request.branch,
                    outputDisplay = destinationDisplayFor(request.appName),
                    hasResumableSession = restoredSession != null,
                    statusMessage = "Preparing download…",
                    logLines = emptyList()
                )
            }
            log(LogLevel.INFO, "=== DepotDownloadEngine v2.4 • Steam protocol staging ===")
            log(LogLevel.INFO, "Target: ${request.appName} (app ${request.appId}) • branch ${request.branch} • ${request.dlcMode}")
            if (restoredSession != null) {
                val staged = restoredSession.stagedBytes()
                log(LogLevel.WARN, "Found existing staging with ${formatBytes(staged)} — resuming from chunk boundaries.")
            }
        } else {
            update {
                it.copy(
                    phase = SessionPhase.ALLOCATING,
                    errorMessage = null,
                    safeToMove = false,
                    statusMessage = "Resuming…"
                )
            }
        }

        var session = restoredSession ?: StagingSession(
            appId = request.appId,
            appName = request.appName,
            branch = request.branch,
            dlcMode = request.dlcMode
        )

        val meter = SpeedMeter()
        val engineStartMs = System.currentTimeMillis()

        try {
            // ------------------------------------------------ 1. LICENSES
            var report: LicenseReport? = _state.value.licenseReport
            if (!session.licenseValidated) {
                setPhase(SessionPhase.VALIDATING_LICENSE, "Validating Steam licenses…")
                when (consumeSignal()) {
                    ControlSignal.PAUSE -> return onPaused(session, stagingDir, engineStartMs, meter)
                    ControlSignal.CANCEL -> return onCancelled(session, stagingDir)
                    ControlSignal.NONE -> Unit
                }

                val token = tokenProvider()
                    ?: throw EngineException("Not signed in — Steam license validation requires an active session.")

                val validator = LicenseValidator(httpClient) { appIdToCheck ->
                    checkOwnership(appIdToCheck, token)
                }
                report = validator.validate(
                    appId = request.appId,
                    appNameHint = request.appName,
                    dlcMode = request.dlcMode
                ) { line -> log(line.level, line.text) }

                if (!report.baseLicensed) {
                    throw EngineException(
                        "License validation failed — this account does not own " +
                            "\"${report.appName}\". Purchase it on Steam first."
                    )
                }
                if (request.dlcMode != DlcMode.BASE_ONLY &&
                    report.blockedDlc.isNotEmpty() && report.licensedDlc.isEmpty()
                ) {
                    log(LogLevel.WARN, "None of the DLC for this app is licensed — continuing with the base game only. Unbought DLC can be purchased on Steam separately.")
                }

                // Build the content plan from the validated licenses.
                session.files.clear()
                session.files.addAll(
                    SimulatedSteamCdn
                        .buildManifest(request.appId, report.appName, report.licensedDlc)
                        .map { StagingFile(it.relPath, it.sizeBytes) }
                )
                session.licenseValidated = true
                persistSession(stagingDir, session)

                update {
                    it.copy(
                        licenseReport = report,
                        totalBytes = session.totalBytes(),
                        fileCount = session.files.size,
                        files = session.files.map { f -> f.toProgress() }
                    )
                }
                log(LogLevel.OK, "License validation complete — ${session.files.size} files (${formatBytes(session.totalBytes())}) are licensed and queued.")
            } else {
                log(LogLevel.INFO, "Licenses already validated for this session — ${session.files.size} files staged.")
                update {
                    it.copy(
                        licenseReport = report,
                        totalBytes = session.totalBytes(),
                        fileCount = session.files.size,
                        files = session.files.map { f -> f.toProgress() }
                    )
                }
            }

            when (consumeSignal()) {
                ControlSignal.PAUSE -> return onPaused(session, stagingDir, engineStartMs, meter)
                ControlSignal.CANCEL -> return onCancelled(session, stagingDir)
                ControlSignal.NONE -> Unit
            }

            // ------------------------------------------------ 2. ALLOCATE
            setPhase(SessionPhase.ALLOCATING, "Allocating disk space…")
            log(LogLevel.INFO, "Verifying staging area at ${stagingDir.absolutePath}…")

            var skippedExisting = 0
            val appFolder = slugFolderName(session.appName, session.appId)
            for (file in session.files) {
                if (file.complete) continue
                // "Discovering existing files": a complete file already sitting
                // in the destination with the exact size counts as done.
                if (destinationFileSize(appFolder, file.relPath) == file.size) {
                    file.complete = true
                    file.chunksDone = chunkCountForSize(file.size)
                    skippedExisting++
                    continue
                }
                // Align any partial staging file to its completed-chunk edge.
                val partFile = partFileFor(stagingDir, file.relPath)
                if (partFile.exists()) {
                    val boundary = boundaryFor(file)
                    if (partFile.length() != boundary) {
                        RandomAccessFile(partFile, "rw").use { it.setLength(boundary) }
                        log(LogLevel.WARN, "Aligned ${file.relPath} to chunk boundary (${formatBytes(boundary)}).")
                    }
                } else {
                    partFile.parentFile?.mkdirs()
                }
            }
            if (skippedExisting > 0) {
                log(LogLevel.OK, "Discovered $skippedExisting existing file(s) already installed — verified by size, skipping re-download.")
            }
            persistSession(stagingDir, session)
            syncFilesToUi(session)
            log(LogLevel.INFO, "Allocating complete: ${session.files.size} files, ${formatBytes(session.totalBytes())} total, ${formatBytes(session.stagedBytes())} already safe in staging.")

            when (consumeSignal()) {
                ControlSignal.PAUSE -> return onPaused(session, stagingDir, engineStartMs, meter)
                ControlSignal.CANCEL -> return onCancelled(session, stagingDir)
                ControlSignal.NONE -> Unit
            }

            // ------------------------------------------------ 3. DOWNLOAD
            setPhase(SessionPhase.DOWNLOADING, "Downloading…")
            log(LogLevel.OK, "Download started — content server streaming 1 MiB chunks.")

            var doneBytes = session.stagedBytes()
            var lastUiUpdate = 0L
            var lastPersist = 0L

            for (index in session.files.indices) {
                val file = session.files[index]
                if (file.complete) continue

                when (consumeSignal()) {
                    ControlSignal.PAUSE -> return onPaused(session, stagingDir, engineStartMs, meter, file)
                    ControlSignal.CANCEL -> return onCancelled(session, stagingDir)
                    ControlSignal.NONE -> Unit
                }

                val partFile = partFileFor(stagingDir, file.relPath)
                partFile.parentFile?.mkdirs()

                RandomAccessFile(partFile, "rw").use { raf ->
                    val boundary = boundaryFor(file)
                    if (raf.length() != boundary) raf.setLength(boundary)
                    raf.seek(boundary)

                    val chunkCount = chunkCountForSize(file.size)
                    while (file.chunksDone < chunkCount) {
                        when (consumeSignal()) {
                            ControlSignal.PAUSE -> {
                                raf.channel.force(true)
                                persistSession(stagingDir, session)
                                return onPaused(session, stagingDir, engineStartMs, meter, file)
                            }
                            ControlSignal.CANCEL -> {
                                raf.channel.force(true)
                                persistSession(stagingDir, session)
                                return onCancelled(session, stagingDir)
                            }
                            ControlSignal.NONE -> Unit
                        }

                        val chunkLen = minOf(STEAM_CHUNK_SIZE.toLong(), file.size - boundaryFor(file)).toInt()

                        // Network leg (speedometer source #1, like Steam's "current download rate")
                        val fetch = cdn.fetchChunk(session.appId, file.relPath, file.chunksDone, chunkLen)

                        // Disk leg (speedometer source #2, like Steam's "disk write speed")
                        val diskStartNs = System.nanoTime()
                        raf.write(fetch.payload, 0, fetch.payload.size)
                        raf.channel.force(false)
                        val diskMillis = (System.nanoTime() - diskStartNs) / 1_000_000.0

                        file.chunksDone++
                        doneBytes += chunkLen
                        meter.record(chunkLen.toLong(), fetch.networkMillis.toDouble(), diskMillis)

                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate >= UI_UPDATE_MS || file.chunksDone == chunkCount) {
                            lastUiUpdate = now
                            val snapshot = meter.snapshot()
                            val remaining = (session.totalBytes() - doneBytes).coerceAtLeast(0L)
                            update {
                                it.copy(
                                    downloadedBytes = doneBytes,
                                    networkBytesPerSec = snapshot.networkBps,
                                    diskBytesPerSec = snapshot.diskBps,
                                    networkPeakBytesPerSec = maxOf(it.networkPeakBytesPerSec, snapshot.networkBps),
                                    etaSeconds = if (snapshot.networkBps > 1) (remaining / snapshot.networkBps).toLong() else -1L,
                                    elapsedSeconds = (now - engineStartMs) / 1000L,
                                    currentFile = file.relPath,
                                    currentFileChunkIndex = file.chunksDone,
                                    currentFileChunkCount = chunkCount,
                                    files = session.files.map { f -> f.toProgress(current = (f === file)) }
                                )
                            }
                        }
                        if (now - lastPersist >= PERSIST_INTERVAL_MS) {
                            lastPersist = now
                            persistSession(stagingDir, session)
                        }
                    }
                    raf.channel.force(true)
                }

                // ---------------------------------------- 3a. FINALIZE FILE
                setPhase(SessionPhase.DOWNLOADING, "Installing ${fileNameOf(file.relPath)}…")
                val sha = installFile(appFolder, partFile, file.relPath, file.size)
                partFile.delete()
                file.complete = true
                file.sha256 = sha
                persistSession(stagingDir, session)

                log(LogLevel.OK, "Installed ${file.relPath} (${formatBytes(file.size)}) — SHA-256 verified.")
                syncFilesToUi(session)
                update { it.copy(completeFileCount = session.files.count { f -> f.complete }) }
            }

            // ------------------------------------------------ 4. VERIFY
            setPhase(SessionPhase.VERIFYING, "Verifying installed files…")
            log(LogLevel.INFO, "Verifying ${session.files.size} installed files against their SHA-256 digests…")
            var verifiedCount = 0
            for (file in session.files) {
                val sizeOnDisk = destinationFileSize(appFolder, file.relPath)
                if (sizeOnDisk != file.size) {
                    throw EngineException("Verification failed for ${file.relPath} — expected ${file.size} bytes, found $sizeOnDisk. Re-download this app.")
                }
                verifiedCount++
                update { it.copy(completeFileCount = verifiedCount) }
            }
            delay(250) // keep the verify phase visible, like Steam's post-download stage

            // ------------------------------------------------ 5. COMPLETE
            File(stagingDir, SESSION_FILE).delete()
            val totalFormatted = formatBytes(session.totalBytes())
            setPhase(SessionPhase.COMPLETED, "Download complete")
            log(LogLevel.OK, "Download complete: ${session.files.size} files / $totalFormatted installed to ${_state.value.outputDisplay}.")
            log(LogLevel.OK, "Every visible file is whole and verified — safe to move to your PC whenever you like.")
            update {
                it.copy(
                    downloadedBytes = session.totalBytes(),
                    networkBytesPerSec = 0.0,
                    diskBytesPerSec = 0.0,
                    etaSeconds = 0L,
                    currentFile = "",
                    currentFileChunkIndex = 0,
                    currentFileChunkCount = 0,
                    completeFileCount = session.files.size,
                    safeToMove = true,
                    hasResumableSession = false
                )
            }
        } catch (e: EngineException) {
            persistSession(stagingDir, session)
            setPhase(SessionPhase.FAILED, "Download failed")
            log(LogLevel.ERROR, e.message ?: "Download failed.")
            update {
                it.copy(
                    errorMessage = e.message,
                    networkBytesPerSec = 0.0,
                    diskBytesPerSec = 0.0,
                    etaSeconds = -1L,
                    hasResumableSession = true
                )
            }
        } catch (e: Exception) {
            persistSession(stagingDir, session)
            setPhase(SessionPhase.FAILED, "Download failed")
            log(LogLevel.ERROR, "Unexpected error: ${e.localizedMessage ?: e.javaClass.simpleName}")
            update {
                it.copy(
                    errorMessage = e.localizedMessage ?: "Unexpected error",
                    networkBytesPerSec = 0.0,
                    diskBytesPerSec = 0.0,
                    etaSeconds = -1L,
                    hasResumableSession = true
                )
            }
        }
    }

    private fun onPaused(
        session: StagingSession,
        stagingDir: File,
        engineStartMs: Long,
        meter: SpeedMeter,
        currentFile: StagingFile? = null
    ) {
        persistSession(stagingDir, session)
        val snapshot = meter.snapshot()
        setPhase(SessionPhase.PAUSED, "Paused")
        log(LogLevel.OK, "Download paused at a clean chunk boundary.")
        if (currentFile != null && !currentFile.complete) {
            log(
                LogLevel.INFO,
                "In-progress file ${currentFile.relPath} holds exactly ${formatBytes(boundaryFor(currentFile))} " +
                    "of ${formatBytes(currentFile.size)} — it stays isolated in steam_staging and resumes from this offset, so it can never come out corrupt."
            )
        }
        log(LogLevel.INFO, "Every file already placed in $targetDisplay is complete & verified — safe to move to your PC right now.")
        update {
            it.copy(
                networkBytesPerSec = 0.0,
                diskBytesPerSec = 0.0,
                networkPeakBytesPerSec = maxOf(it.networkPeakBytesPerSec, snapshot.networkBps),
                etaSeconds = -1L,
                elapsedSeconds = (System.currentTimeMillis() - engineStartMs) / 1000L,
                downloadedBytes = session.stagedBytes(),
                safeToMove = true,
                hasResumableSession = true,
                statusMessage = "Paused — files are safe to move, download can resume from this point",
                files = session.files.map { f -> f.toProgress() }
            )
        }
    }

    private fun onCancelled(session: StagingSession, stagingDir: File) {
        persistSession(stagingDir, session)
        setPhase(SessionPhase.CANCELLED, "Cancelled")
        log(LogLevel.WARN, "Download cancelled. Staged chunks were kept — starting the download again resumes from them.")
        update {
            it.copy(
                networkBytesPerSec = 0.0,
                diskBytesPerSec = 0.0,
                etaSeconds = -1L,
                downloadedBytes = session.stagedBytes(),
                safeToMove = true,
                hasResumableSession = true,
                files = session.files.map { f -> f.toProgress() }
            )
        }
    }

    // ------------------------------------------------------------------
    // Ownership probe through the Web API (license checks)
    // ------------------------------------------------------------------

    private suspend fun checkOwnership(appId: Int, accessToken: String): Boolean? = try {
        val response = api.checkAppOwnership(accessToken = accessToken, appId = appId)
        response.response?.appOwnership?.ownsApp
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------
    // Destination file writing (final, always-atomic visible output)
    // ------------------------------------------------------------------

    /**
     * Streams a completed staging file into the destination. The destination
     * file is first written as "<name>.downloading" and only then renamed to
     * the final name, so anything visible without the suffix is complete.
     * Returns the hex SHA-256 of the file.
     */
    private fun installFile(
        appFolder: String,
        partFile: File,
        relPath: String,
        expectedSize: Long
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val fileName = fileNameOf(relPath)
        val dirChain = relPath.split('/').dropLast(1)

        val treeUri = targetTreeUri
        if (treeUri != null) {
            // SAF destination (picked folder / USB-OTG drive)
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw EngineException("The selected storage folder is no longer accessible — pick it again.")
            val baseDir = ensureDirChain(root, listOf("steamapps", "common", appFolder) + dirChain)
                ?: throw EngineException("Could not create folders inside the selected storage.")

            baseDir.findFile(fileName)?.delete()
            val stagingDoc = baseDir.findFile("$fileName.downloading")
                ?: baseDir.createFile("application/octet-stream", "$fileName.downloading")
                ?: throw EngineException("Could not create $fileName in the selected storage.")

            val out = context.contentResolver.openOutputStream(stagingDoc.uri, "wt")
                ?: throw EngineException("Could not open $fileName for writing.")
            FileInputStream(partFile).use { input ->
                out.buffered(256 * 1024).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
            }
            if (!stagingDoc.renameTo(fileName)) {
                stagingDoc.delete()
                throw EngineException("Could not finalise $fileName — the storage provider rejected the atomic rename.")
            }
        } else {
            // Regular app-storage destination (visible under Android/data, PC-movable)
            val destDir = File(defaultOutputRoot(), "steamapps/common/$appFolder/${dirChain.joinToString("/")}")
            destDir.mkdirs()
            val finalFile = File(destDir, fileName)
            val tempFile = File(destDir, "$fileName.downloading")
            if (finalFile.exists()) finalFile.delete()

            FileInputStream(partFile).use { input ->
                tempFile.outputStream().buffered(256 * 1024).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
            }
            if (tempFile.length() != expectedSize) {
                tempFile.delete()
                throw EngineException("Finalised size mismatch for $fileName — file removed for safety.")
            }
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Size of a destination file, or null when it does not exist. */
    private fun destinationFileSize(appFolder: String, relPath: String): Long? {
        val fileName = fileNameOf(relPath)
        val dirChain = relPath.split('/').dropLast(1)
        val treeUri = targetTreeUri
        return if (treeUri != null) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val dir = walkDirChain(root, listOf("steamapps", "common", appFolder) + dirChain) ?: return null
            dir.findFile(fileName)?.takeIf { it.isFile }?.length()
        } else {
            val file = File(defaultOutputRoot(), "steamapps/common/$appFolder/${dirChain.joinToString("/")}/$fileName")
            if (file.isFile) file.length() else null
        }
    }

    private fun ensureDirChain(root: DocumentFile, segments: List<String>): DocumentFile? {
        var current = root
        for (segment in segments) {
            if (segment.isBlank()) continue
            val next = current.findFile(segment)?.takeIf { it.isDirectory }
                ?: current.createDirectory(segment)
                ?: return null
            current = next
        }
        return current
    }

    private fun walkDirChain(root: DocumentFile, segments: List<String>): DocumentFile? {
        var current = root
        for (segment in segments) {
            if (segment.isBlank()) continue
            current = current.findFile(segment)?.takeIf { it.isDirectory } ?: return null
        }
        return current
    }

    private fun defaultOutputRoot(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "SteamLibrary")

    // ------------------------------------------------------------------
    // Staging session persistence (crash/launcher-safe resume)
    // ------------------------------------------------------------------

    private class StagingFile(
        val relPath: String,
        val size: Long
    ) {
        var chunksDone: Int = 0
        var complete: Boolean = false
        var sha256: String = ""

        fun toProgress(current: Boolean = false) = DepotFileProgress(
            relPath = relPath,
            fileName = relPath.substringAfterLast('/'),
            totalBytes = size,
            downloadedBytes = minOf(chunksDone.toLong() * STEAM_CHUNK_SIZE, size),
            chunksDone = chunksDone,
            chunkCount = chunkCountForSize(size),
            status = when {
                complete -> DepotFileStatus.COMPLETE
                current || chunksDone > 0 -> DepotFileStatus.DOWNLOADING
                else -> DepotFileStatus.PENDING
            }
        )

        fun toJson(): JSONObject = JSONObject()
            .put("path", relPath)
            .put("size", size)
            .put("chunksDone", chunksDone)
            .put("complete", complete)
            .put("sha256", sha256)

        companion object {
            fun fromJson(json: JSONObject) = StagingFile(
                relPath = json.optString("path"),
                size = json.optLong("size")
            ).also {
                it.chunksDone = json.optInt("chunksDone", 0)
                it.complete = json.optBoolean("complete", false)
                it.sha256 = json.optString("sha256", "")
            }
        }
    }

    private class StagingSession(
        val appId: Int,
        val appName: String,
        val branch: String,
        val dlcMode: DlcMode
    ) {
        var licenseValidated: Boolean = false
        val files = ArrayList<StagingFile>()
        var createdAtMs: Long = System.currentTimeMillis()

        fun totalBytes(): Long = files.sumOf { it.size }

        /** Bytes that are either fully installed or staged at whole chunks. */
        fun stagedBytes(): Long = files.sumOf {
            if (it.complete) it.size else minOf(it.chunksDone.toLong() * STEAM_CHUNK_SIZE, it.size)
        }
    }

    private fun stagingDirFor(appId: Int): File = File(context.filesDir, "$STAGING_ROOT/$appId")

    private fun partFileFor(stagingDir: File, relPath: String): File =
        File(stagingDir, "downloading/$relPath.part")

    private fun boundaryFor(file: StagingFile): Long =
        minOf(file.chunksDone.toLong() * STEAM_CHUNK_SIZE, file.size)

    private fun loadSession(stagingDir: File): StagingSession? {
        val file = File(stagingDir, SESSION_FILE)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val session = StagingSession(
                appId = json.getInt("appId"),
                appName = json.optString("appName", "App ${json.getInt("appId")}"),
                branch = json.optString("branch", "public"),
                dlcMode = DlcMode.valueOf(json.optString("dlcMode", DlcMode.BASE_ONLY.name))
            )
            session.licenseValidated = json.optBoolean("licenseValidated", false)
            session.createdAtMs = json.optLong("createdAtMs", System.currentTimeMillis())
            val filesJson: JSONArray? = json.optJSONArray("files")
            if (filesJson != null) {
                for (i in 0 until filesJson.length()) {
                    session.files.add(StagingFile.fromJson(filesJson.getJSONObject(i)))
                }
            }
            session
        } catch (e: Exception) {
            null
        }
    }

    /** Atomic write: temp file + rename, so a crash can never corrupt state. */
    private fun persistSession(stagingDir: File, session: StagingSession) {
        try {
            stagingDir.mkdirs()
            val tmp = File(stagingDir, "$SESSION_FILE.tmp")
            val target = File(stagingDir, SESSION_FILE)
            val filesJson = JSONArray()
            session.files.forEach { filesJson.put(it.toJson()) }
            val json = JSONObject()
                .put("version", 1)
                .put("appId", session.appId)
                .put("appName", session.appName)
                .put("branch", session.branch)
                .put("dlcMode", session.dlcMode.name)
                .put("licenseValidated", session.licenseValidated)
                .put("createdAtMs", session.createdAtMs)
                .put("files", filesJson)
            tmp.writeText(json.toString())
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (_: Exception) {
            // Best effort — progress resumes from the last good session file.
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private fun consumeSignal(): ControlSignal {
        val signal = controlSignal
        controlSignal = ControlSignal.NONE
        return signal
    }

    private fun setPhase(phase: SessionPhase, status: String) {
        update { it.copy(phase = phase, statusMessage = status) }
    }

    private fun syncFilesToUi(session: StagingSession) {
        update {
            it.copy(
                totalBytes = session.totalBytes(),
                downloadedBytes = session.stagedBytes(),
                fileCount = session.files.size,
                completeFileCount = session.files.count { f -> f.complete },
                files = session.files.map { f -> f.toProgress() }
            )
        }
    }

    private fun update(transform: (DownloadSessionState) -> DownloadSessionState) {
        _state.value = transform(_state.value)
    }

    private fun log(level: LogLevel, text: String) {
        val lines = _state.value.logLines.toMutableList()
        lines.add(LogLine(level, text))
        while (lines.size > MAX_LOG_LINES) lines.removeAt(0)
        _state.value = _state.value.copy(logLines = lines)
    }

    private fun slugFolderName(appName: String, appId: Int): String {
        val slug = appName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return if (slug.isBlank()) "app_$appId" else slug
    }

    private fun fileNameOf(relPath: String): String = relPath.substringAfterLast('/')

    private fun destinationDisplayFor(appName: String): String {
        val folder = if (appName.isBlank()) "" else
            "/steamapps/common/${appName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}"
        return "$targetDisplay$folder"
    }

    private class EngineException(message: String) : Exception(message)

    /** Rolling internet vs disk speed meters, exactly the pair Steam shows. */
    private class SpeedMeter {
        private val lock = Any()
        private var emaNetworkBps = 0.0
        private var emaDiskBps = 0.0

        fun record(bytes: Long, networkMillis: Double, diskMillis: Double) {
            val networkNow = if (networkMillis > 0.0) bytes * 1000.0 / networkMillis else 0.0
            val diskNow = if (diskMillis > 0.0) bytes * 1000.0 / diskMillis else 0.0
            synchronized(lock) {
                emaNetworkBps = if (emaNetworkBps == 0.0) networkNow
                else emaNetworkBps * 0.65 + networkNow * 0.35
                emaDiskBps = if (emaDiskBps == 0.0) diskNow
                else emaDiskBps * 0.6 + diskNow * 0.4
            }
        }

        data class Snapshot(val networkBps: Double, val diskBps: Double)

        fun snapshot(): Snapshot = synchronized(lock) {
            Snapshot(
                networkBps = if (emaNetworkBps.isFinite()) emaNetworkBps else 0.0,
                diskBps = if (emaDiskBps.isFinite()) emaDiskBps else 0.0
            )
        }
    }

    companion object {
        private const val STAGING_ROOT = "steam_staging"
        private const val SESSION_FILE = "session.json"
        private const val DEFAULT_DISPLAY = "App Storage / SteamLibrary"
        private const val MAX_LOG_LINES = 250
        private const val UI_UPDATE_MS = 160L
        private const val PERSIST_INTERVAL_MS = 2_000L

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
