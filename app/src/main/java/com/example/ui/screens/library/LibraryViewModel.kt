package com.example.ui.screens.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DepotApplication
import com.example.data.auth.SteamSession
import com.example.data.db.SteamGameEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SortOrder {
    NAME,
    PLAYTIME,
    APP_ID
}

/**
 * Shows the Steam library of the signed-in user. Games are fetched with the
 * access token issued by the login flow (no API key / SteamID64 typing).
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val services = DepotApplication.get(application)
    private val steamRepo = services.steamRepository

    val session: StateFlow<SteamSession?> = services.authManager.session

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.NAME)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /** True once we attempted the first automatic sync after login. */
    private var autoSyncAttempted = false

    @OptIn(ExperimentalCoroutinesApi::class)
    val games: StateFlow<List<SteamGameEntity>> = _searchQuery
        .flatMapLatest { query ->
            steamRepo.searchGames(query)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        // As soon as a session is (or becomes) available, sync the library.
        viewModelScope.launch {
            var hadSession = false
            session.collect { steamSession ->
                if (steamSession != null && (!autoSyncAttempted || !hadSession)) {
                    autoSyncAttempted = true
                    hadSession = true
                    fetchGames()
                } else if (steamSession == null) {
                    hadSession = false
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onSortOrderChanged(order: SortOrder) {
        _sortOrder.value = order
    }

    fun fetchGames() {
        val steamSession = session.value
        if (steamSession == null) {
            _errorMessage.value = "Sign in with your Steam account first."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            val token = services.authManager.getValidAccessToken()
            if (token == null) {
                _isLoading.value = false
                _errorMessage.value = "Your Steam session expired — please sign in again."
                services.authManager.signOut()
                return@launch
            }

            val result = steamRepo.fetchAndStoreGames(steamSession.steamId, token)
            _isLoading.value = false

            result.onSuccess { count ->
                _statusMessage.value = "Library synced — $count games you own."
            }.onFailure { error ->
                _errorMessage.value = "Failed to fetch your Steam library: ${error.localizedMessage}"
            }
        }
    }

    /** Signs out and forgets the remembered session + cached library. */
    fun signOut() {
        viewModelScope.launch {
            steamRepo.clearCachedLibrary()
            services.authManager.signOut()
        }
    }

    fun clearMessages() {
        _errorMessage.value = null
        _statusMessage.value = null
    }
}
