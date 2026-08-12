package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserPreferencesRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("depot_downloader_prefs", Context.MODE_PRIVATE)

    private val _steamId = MutableStateFlow(prefs.getString(KEY_STEAM_ID, "") ?: "")
    val steamId: StateFlow<String> = _steamId.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "") ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _targetUri = MutableStateFlow(prefs.getString(KEY_TARGET_URI, "") ?: "")
    val targetUri: StateFlow<String> = _targetUri.asStateFlow()

    private val _targetDisplayPath = MutableStateFlow(prefs.getString(KEY_TARGET_DISPLAY_PATH, "Internal Storage / Downloads") ?: "Internal Storage / Downloads")
    val targetDisplayPath: StateFlow<String> = _targetDisplayPath.asStateFlow()

    private val _steamUsername = MutableStateFlow(prefs.getString(KEY_STEAM_USERNAME, "") ?: "")
    val steamUsername: StateFlow<String> = _steamUsername.asStateFlow()

    fun saveSteamCredentials(steamId: String, apiKey: String) {
        prefs.edit()
            .putString(KEY_STEAM_ID, steamId)
            .putString(KEY_API_KEY, apiKey)
            .apply()
        _steamId.value = steamId
        _apiKey.value = apiKey
    }

    fun saveTargetDirectory(uriString: String, displayPath: String) {
        prefs.edit()
            .putString(KEY_TARGET_URI, uriString)
            .putString(KEY_TARGET_DISPLAY_PATH, displayPath)
            .apply()
        _targetUri.value = uriString
        _targetDisplayPath.value = displayPath
    }

    fun saveSteamUsername(username: String) {
        prefs.edit()
            .putString(KEY_STEAM_USERNAME, username)
            .apply()
        _steamUsername.value = username
    }

    companion object {
        private const val KEY_STEAM_ID = "steam_id"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_TARGET_URI = "target_uri"
        private const val KEY_TARGET_DISPLAY_PATH = "target_display_path"
        private const val KEY_STEAM_USERNAME = "steam_username"
    }
}
