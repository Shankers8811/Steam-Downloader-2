package com.example.ui.screens.downloader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.DownloadTaskEntity
import com.example.data.model.DlcMode
import com.example.data.repository.UserPreferencesRepository
import com.example.bridge.BridgeState
import com.example.bridge.DepotDownloaderBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DownloaderViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val prefsRepo = UserPreferencesRepository(application)
    val bridge = DepotDownloaderBridge(application)

    // Credentials (In-memory raw state)
    val steamUsername: StateFlow<String> = prefsRepo.steamUsername
    private val _usernameInput = MutableStateFlow(prefsRepo.steamUsername.value)
    val usernameInput: StateFlow<String> = _usernameInput.asStateFlow()

    private val _passwordInput = MutableStateFlow("") // Strictly in-memory, never persisted!
    val passwordInput: StateFlow<String> = _passwordInput.asStateFlow()

    private val _twoFactorInput = MutableStateFlow("")
    val twoFactorInput: StateFlow<String> = _twoFactorInput.asStateFlow()

    // Download Configuration
    private val _appIdInput = MutableStateFlow("400") // Default Portal 1 App ID
    val appIdInput: StateFlow<String> = _appIdInput.asStateFlow()

    private val _appNameInput = MutableStateFlow("Portal")
    val appNameInput: StateFlow<String> = _appNameInput.asStateFlow()

    private val _depotIdsInput = MutableStateFlow("")
    val depotIdsInput: StateFlow<String> = _depotIdsInput.asStateFlow()

    private val _manifestIdInput = MutableStateFlow("")
    val manifestIdInput: StateFlow<String> = _manifestIdInput.asStateFlow()

    private val _branchInput = MutableStateFlow("public")
    val branchInput: StateFlow<String> = _branchInput.asStateFlow()

    // DLC Configuration
    private val _includeDlc = MutableStateFlow(false)
    val includeDlc: StateFlow<Boolean> = _includeDlc.asStateFlow()

    private val _dlcDepotId = MutableStateFlow("")
    val dlcDepotId: StateFlow<String> = _dlcDepotId.asStateFlow()

    private val _dlcMode = MutableStateFlow(DlcMode.BASE_ONLY)
    val dlcMode: StateFlow<DlcMode> = _dlcMode.asStateFlow()

    // Storage Management (SAF)
    val targetUri: StateFlow<String> = prefsRepo.targetUri
    val targetDisplayPath: StateFlow<String> = prefsRepo.targetDisplayPath

    // Bridge State
    val bridgeState: StateFlow<BridgeState> = bridge.state

    private val _statusNotification = MutableStateFlow<String?>(null)
    val statusNotification: StateFlow<String?> = _statusNotification.asStateFlow()

    fun onUsernameChanged(value: String) {
        _usernameInput.value = value
        prefsRepo.saveSteamUsername(value)
    }

    fun onPasswordChanged(value: String) {
        _passwordInput.value = value
    }

    fun onTwoFactorChanged(value: String) {
        _twoFactorInput.value = value
    }

    fun onAppIdChanged(value: String) {
        _appIdInput.value = value
    }

    fun onAppNameChanged(value: String) {
        _appNameInput.value = value
    }

    fun onDepotIdsChanged(value: String) {
        _depotIdsInput.value = value
    }

    fun onManifestIdChanged(value: String) {
        _manifestIdInput.value = value
    }

    fun onBranchChanged(value: String) {
        _branchInput.value = value
    }

    fun onIncludeDlcChanged(enabled: Boolean) {
        _includeDlc.value = enabled
        if (!enabled) {
            _dlcMode.value = DlcMode.BASE_ONLY
        } else if (_dlcMode.value == DlcMode.BASE_ONLY) {
            _dlcMode.value = DlcMode.BASE_AND_DLC
        }
    }

    fun onDlcDepotIdChanged(value: String) {
        _dlcDepotId.value = value
    }

    fun onDlcModeChanged(mode: DlcMode) {
        _dlcMode.value = mode
        _includeDlc.value = mode != DlcMode.BASE_ONLY
    }

    fun setTargetDirectory(uri: Uri, displayPath: String) {
        prefsRepo.saveTargetDirectory(uri.toString(), displayPath)
        _statusNotification.value = "Storage Location updated: $displayPath"
    }

    fun prefillFromLibrary(appId: Int, gameName: String) {
        _appIdInput.value = appId.toString()
        _appNameInput.value = gameName
        _statusNotification.value = "Pre-filled App ID $appId ($gameName)"
    }

    fun startDownloadTask() {
        val appIdInt = _appIdInput.value.toIntOrNull()
        if (appIdInt == null || appIdInt <= 0) {
            _statusNotification.value = "Please enter a valid Steam App ID."
            return
        }

        val task = DownloadTaskEntity(
            appId = appIdInt,
            appName = _appNameInput.value.ifBlank { "App $appIdInt" },
            depotIds = _depotIdsInput.value,
            manifestId = _manifestIdInput.value,
            branch = _branchInput.value.ifBlank { "public" },
            dlcMode = _dlcMode.value.name,
            dlcDepotId = _dlcDepotId.value,
            includeDlc = _includeDlc.value,
            targetUriString = targetUri.value,
            targetPathDisplay = targetDisplayPath.value,
            status = "DOWNLOADING"
        )

        viewModelScope.launch {
            db.downloadTaskDao().insertTask(task)
            bridge.startDownload(
                task = task,
                username = _usernameInput.value,
                password = _passwordInput.value,
                twoFactorCode = _twoFactorInput.value,
                onProgressUpdate = { updatedTask ->
                    viewModelScope.launch { db.downloadTaskDao().updateTask(updatedTask) }
                },
                onComplete = { success, msg ->
                    _statusNotification.value = if (success) "Download finished!" else "Failed: $msg"
                }
            )
        }
    }

    fun pauseDownload() {
        bridge.pauseDownload()
        _statusNotification.value = "Download paused. Partial files preserved."
    }

    fun resumeDownload() {
        val appIdInt = _appIdInput.value.toIntOrNull() ?: 400
        val task = DownloadTaskEntity(
            appId = appIdInt,
            appName = _appNameInput.value.ifBlank { "App $appIdInt" },
            depotIds = _depotIdsInput.value,
            manifestId = _manifestIdInput.value,
            branch = _branchInput.value.ifBlank { "public" },
            dlcMode = _dlcMode.value.name,
            dlcDepotId = _dlcDepotId.value,
            includeDlc = _includeDlc.value,
            targetUriString = targetUri.value,
            targetPathDisplay = targetDisplayPath.value,
            status = "DOWNLOADING",
            progressPercent = bridgeState.value.progressPercent
        )
        bridge.resumeDownload(
            task = task,
            username = _usernameInput.value,
            password = _passwordInput.value,
            twoFactorCode = _twoFactorInput.value,
            onProgressUpdate = { updatedTask ->
                viewModelScope.launch { db.downloadTaskDao().updateTask(updatedTask) }
            },
            onComplete = { success, msg ->
                _statusNotification.value = if (success) "Download finished!" else "Failed: $msg"
            }
        )
    }

    fun cancelDownload() {
        bridge.cancelDownload()
        _statusNotification.value = "Download cancelled."
    }

    fun clearNotification() {
        _statusNotification.value = null
    }
}
