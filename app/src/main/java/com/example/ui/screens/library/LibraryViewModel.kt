package com.example.ui.screens.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.SteamApiService
import com.example.data.db.AppDatabase
import com.example.data.db.SteamGameEntity
import com.example.data.repository.SteamRepository
import com.example.data.repository.UserPreferencesRepository
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

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val prefsRepo = UserPreferencesRepository(application)
    private val steamRepo = SteamRepository(SteamApiService.create(), db.steamGameDao())

    val steamId: StateFlow<String> = prefsRepo.steamId
    val apiKey: StateFlow<String> = prefsRepo.apiKey

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

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onSortOrderChanged(order: SortOrder) {
        _sortOrder.value = order
    }

    fun saveCredentials(newSteamId: String, newApiKey: String) {
        prefsRepo.saveSteamCredentials(newSteamId.trim(), newApiKey.trim())
        fetchGames()
    }

    fun fetchGames() {
        val currentSteamId = steamId.value
        val currentApiKey = apiKey.value

        if (currentSteamId.isBlank() || currentApiKey.isBlank()) {
            _errorMessage.value = "Please configure your SteamID64 and Steam Web API Key."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _statusMessage.value = "Fetching games from Steam API..."

            val result = steamRepo.fetchAndStoreGames(currentSteamId, currentApiKey)
            _isLoading.value = false

            result.onSuccess { count ->
                _statusMessage.value = "Loaded $count games into library."
            }.onFailure { error ->
                _errorMessage.value = "Failed to fetch Steam library: ${error.localizedMessage}"
            }
        }
    }

    fun clearMessages() {
        _errorMessage.value = null
        _statusMessage.value = null
    }
}
