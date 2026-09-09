package com.example.ui.screens.downloader

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DepotApplication
import com.example.data.db.DownloadTaskEntity
import com.example.data.download.DownloadRequest
import com.example.data.download.DownloadSessionState
import com.example.data.download.SessionPhase
import com.example.data.model.DlcMode
import com.example.service.DownloadForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the download configuration form and delegates execution to the
 * app-scoped [DepotDownloadManager] — the engine survives the UI, so pausing,
 * leaving the screen or rotating the phone never breaks a download.
 */
class DownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val services = DepotApplication.get(application)
    private val manager = services.downloadManager
    private val authManager = services.authManager

    val sessionState: StateFlow<DownloadSessionState> = manager.state

    val accountName: StateFlow<String> = services.prefs.steamUsername

    /** The real, on-disk install root (works as a real path for the native
     *  downloader AND shows up over USB MTP — SAF tree-URIs don't). */
    val installRootDisplay: StateFlow<String> =
        MutableStateFlow(manager.installRootDisplay).asStateFlow()

    // ---------------- Download configuration inputs ----------------

    private val _appIdInput = MutableStateFlow("400")
    val appIdInput: StateFlow<String> = _appIdInput.asStateFlow()

    private val _appNameInput = MutableStateFlow("Portal")
    val appNameInput: StateFlow<String> = _appNameInput.asStateFlow()

    private val _depotIdsInput = MutableStateFlow("")
    val depotIdsInput: StateFlow<String> = _depotIdsInput.asStateFlow()

    private val _branchInput = MutableStateFlow("public")
    val branchInput: StateFlow<String> = _branchInput.asStateFlow()

    private val _includeDlc = MutableStateFlow(true)
    val includeDlc: StateFlow<Boolean> = _includeDlc.asStateFlow()

    private val _dlcMode = MutableStateFlow(DlcMode.BASE_AND_DLC)
    val dlcMode: StateFlow<DlcMode> = _dlcMode.asStateFlow()

    private val _statusNotification = MutableStateFlow<String?>(null)
    val statusNotification: StateFlow<String?> = _statusNotification.asStateFlow()

    /** Room row tracking the active session for download history. */
    private var historyTaskId: Int? = null

    init {
        // Keep the Room history row in sync with engine state transitions.
        viewModelScope.launch {
            sessionState.collect { state ->
                if (state.appId == 0) return@collect
                val previous = sessionStatePhaseTracker
                sessionStatePhaseTracker = state.phase
                if (previous == state.phase && state.progressPercent < 100f) return@collect
                upsertHistoryTask(state)
            }
        }
    }

    private var sessionStatePhaseTracker: SessionPhase = SessionPhase.IDLE

    private suspend fun upsertHistoryTask(state: DownloadSessionState) {
        val status = when (state.phase) {
            SessionPhase.DOWNLOADING, SessionPhase.VALIDATING_LICENSE,
            SessionPhase.ALLOCATING, SessionPhase.VERIFYING -> "DOWNLOADING"
            SessionPhase.PAUSED -> "PAUSED"
            SessionPhase.COMPLETED -> "COMPLETED"
            SessionPhase.FAILED -> "FAILED"
            SessionPhase.CANCELLED -> "CANCELLED"
            SessionPhase.IDLE -> return
        }
        val dao = services.database.downloadTaskDao()
        val existing = historyTaskId?.let { dao.getTaskById(it) }
        val entity = (existing ?: DownloadTaskEntity(appId = state.appId, appName = state.appName)).copy(
            appId = state.appId,
            appName = state.appName,
            branch = state.branch,
            targetUriString = "",
            targetPathDisplay = state.outputDisplay,
            status = status,
            progressPercent = state.progressPercent,
            downloadSpeed = "%.1f MB/s".format(state.networkBytesPerSec / (1024.0 * 1024.0)),
            downloadedBytes = state.downloadedBytes,
            totalBytes = state.totalBytes,
            totalSizeFormatted = com.example.data.download.DepotDownloadManager.formatBytes(state.totalBytes),
            timestamp = System.currentTimeMillis()
        )
        val id = dao.insertTask(entity).toInt()
        if (historyTaskId == null && entity.id == 0) historyTaskId = id
    }

    // ---------------- Input handlers ----------------

    fun onAppIdChanged(value: String) { _appIdInput.value = value }
    fun onAppNameChanged(value: String) { _appNameInput.value = value }
    fun onDepotIdsChanged(value: String) { _depotIdsInput.value = value }
    fun onBranchChanged(value: String) { _branchInput.value = value }

    fun onIncludeDlcChanged(enabled: Boolean) {
        _includeDlc.value = enabled
        _dlcMode.value = if (enabled) DlcMode.BASE_AND_DLC else DlcMode.BASE_ONLY
    }

    fun onDlcModeChanged(mode: DlcMode) {
        _dlcMode.value = mode
        _includeDlc.value = mode != DlcMode.BASE_ONLY
    }

    /** The install location is fixed (real filesystem path, USB-visible). */
    fun describeInstallLocation(): String =
        "Installs to a real folder so the native Steam engine can write directly: " +
            manager.installRootDisplay + " — visible to your PC over USB (Android/data). " +
            "SAF tree picking was removed: document-provider URIs are not real paths " +
            "and silently break native file writers."

    fun prefillFromLibrary(appId: Int, gameName: String) {
        _appIdInput.value = appId.toString()
        _appNameInput.value = gameName
        _statusNotification.value = "Pre-filled $gameName (app $appId) — press Start to validate licenses & download."
    }

    // ---------------- Engine controls ----------------

    fun startDownloadTask() {
        val appId = _appIdInput.value.toIntOrNull()
        if (appId == null || appId <= 0) {
            _statusNotification.value = "Enter a valid Steam App ID first."
            return
        }
        if (authManager.session.value == null) {
            _statusNotification.value = "Sign in with Steam first — licenses can only be validated with an active session."
            return
        }
        if (manager.state.value.isEngineActive || manager.state.value.phase == SessionPhase.PAUSED) {
            _statusNotification.value = "A session is already active — pause or cancel it first."
            return
        }

        historyTaskId = null
        sessionStatePhaseTracker = SessionPhase.IDLE

        manager.start(
            DownloadRequest(
                appId = appId,
                appName = _appNameInput.value.ifBlank { "App $appId" },
                branch = _branchInput.value.ifBlank { "public" },
                dlcMode = _dlcMode.value,
                depotIds = _depotIdsInput.value,
                dlcDepotId = ""
            )
        ) { authManager.getValidAccessToken() }

        startForegroundServiceSafely()
    }

    fun pauseDownload() {
        manager.pause()
        _statusNotification.value = "Pausing at the next chunk boundary…"
    }

    fun resumeDownload() {
        manager.resume { authManager.getValidAccessToken() }
        startForegroundServiceSafely()
    }

    fun cancelDownload() {
        manager.cancel()
    }

    fun clearPartialData() {
        val appId = sessionState.value.appId.takeIf { it != 0 } ?: _appIdInput.value.toIntOrNull() ?: return
        manager.clearStaging(appId)
        historyTaskId = null
        _statusNotification.value = "Staged partial data for app $appId cleared."
    }

    private fun startForegroundServiceSafely() {
        try {
            val intent = Intent(getApplication(), DownloadForegroundService::class.java)
                .setAction(DownloadForegroundService.ACTION_START)
            ContextCompat.startForegroundService(getApplication(), intent)
        } catch (e: Exception) {
            // Notification permission denied / FGS restrictions — the engine
            // keeps running in-process anyway, so the download is unaffected.
        }
    }

    fun clearNotification() {
        _statusNotification.value = null
    }
}
