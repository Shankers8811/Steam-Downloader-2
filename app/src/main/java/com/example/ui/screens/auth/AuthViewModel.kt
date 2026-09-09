package com.example.ui.screens.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.DepotApplication
import com.example.data.auth.AuthState
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin bridge between the sign-in UI and the app-scoped [SteamAuthManager].
 */
class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val services = DepotApplication.get(application)

    val authState: StateFlow<AuthState> = services.authManager.authState
    val guardError: StateFlow<String?> = services.authManager.guardError

    /** Account name the user previously signed in with (for the greeting). */
    val rememberedAccountName: String
        get() = services.prefs.steamUsername.value

    fun signIn(accountName: String, password: String, rememberMe: Boolean) {
        services.authManager.beginPasswordLogin(accountName, password, rememberMe)
    }

    fun submitGuardCode(code: String) {
        services.authManager.submitGuardCode(code)
    }

    fun useGuardType(type: Int) {
        services.authManager.useGuardType(type)
    }

    fun cancelSignIn() {
        services.authManager.cancelSignIn()
    }
}
