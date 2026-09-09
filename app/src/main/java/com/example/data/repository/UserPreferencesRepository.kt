package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Non-secret user preferences. The remembered login itself (refresh token)
 * lives in the encrypted [com.example.data.auth.CredentialVault]; here we only
 * keep the account name so the sign-in form can greet the user by name.
 */
class UserPreferencesRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("depot_downloader_prefs", Context.MODE_PRIVATE)

    private val _targetUri = MutableStateFlow(prefs.getString(KEY_TARGET_URI, "") ?: "")
    val targetUri: StateFlow<String> = _targetUri.asStateFlow()

    private val _targetDisplayPath = MutableStateFlow(
        prefs.getString(KEY_TARGET_DISPLAY_PATH, "App Storage / SteamLibrary")
            ?: "App Storage / SteamLibrary"
    )
    val targetDisplayPath: StateFlow<String> = _targetDisplayPath.asStateFlow()

    /** Last successfully signed-in account name (for the login form greeting). */
    private val _steamUsername = MutableStateFlow(prefs.getString(KEY_STEAM_USERNAME, "") ?: "")
    val steamUsername: StateFlow<String> = _steamUsername.asStateFlow()

    fun saveTargetDirectory(uriString: String, displayPath: String) {
        prefs.edit()
            .putString(KEY_TARGET_URI, uriString)
            .putString(KEY_TARGET_DISPLAY_PATH, displayPath)
            .apply()
        _targetUri.value = uriString
        _targetDisplayPath.value = displayPath
    }

    fun saveSteamUsername(username: String) {
        prefs.edit().putString(KEY_STEAM_USERNAME, username).apply()
        _steamUsername.value = username
    }

    companion object {
        private const val KEY_TARGET_URI = "target_uri"
        private const val KEY_TARGET_DISPLAY_PATH = "target_display_path"
        private const val KEY_STEAM_USERNAME = "steam_username"
    }
}
