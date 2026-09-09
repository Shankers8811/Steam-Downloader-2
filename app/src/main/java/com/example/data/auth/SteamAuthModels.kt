package com.example.data.auth

/**
 * A fully established Steam session, equivalent to what the Steam desktop
 * client keeps on disk after a successful sign-in.
 *
 * The refresh token is what lets the app "remember the login" — it can mint
 * new access tokens without asking for the password or Steam Guard again.
 */
data class SteamSession(
    val steamId: String,
    val accountName: String,
    val refreshToken: String,
    val accessToken: String,
    val obtainedAtMs: Long = System.currentTimeMillis()
)

/** Steam's EAuthSessionGuardType values. */
object SteamGuardType {
    const val NONE = 0
    const val EMAIL_CODE = 1
    const val DEVICE_CODE = 2          // Code shown in the Steam Mobile App (Steam Guard page)
    const val DEVICE_CONFIRMATION = 3  // Approve/decline prompt inside the Steam Mobile App
    const val EMAIL_CONFIRMATION = 4
    const val MACHINE_TOKEN = 5

    fun supportsCodeEntry(type: Int): Boolean =
        type == EMAIL_CODE || type == DEVICE_CODE || type == MACHINE_TOKEN

    fun defaultPrompt(type: Int): String = when (type) {
        EMAIL_CODE -> "Enter the access code Steam emailed to your email address."
        DEVICE_CODE -> "Enter the current code from the Steam Guard page of your Steam Mobile App."
        DEVICE_CONFIRMATION -> "Open the Steam Mobile App and approve this sign-in request."
        EMAIL_CONFIRMATION -> "Confirm this sign-in using the email Steam sent to your email address."
        MACHINE_TOKEN -> "Enter the code from a device you have already authorised."
        else -> "Additional confirmation is required to finish signing in."
    }
}

data class GuardConfirmationOption(
    val confirmationType: Int,
    val message: String
)

/**
 * State machine for the sign-in UI — mirrors the Steam desktop login window:
 * credentials -> Steam Guard challenge -> signed in.
 */
sealed class AuthState {

    /** App just launched; we are restoring a remembered session. */
    object Restoring : AuthState()

    /** Nobody is signed in. */
    object LoggedOut : AuthState()

    /** A network round-trip is in flight (fetching RSA key, beginning session…). */
    data class Busy(val message: String) : AuthState()

    /** Steam Guard / 2FA step. Poll keeps running in the background. */
    data class AwaitingGuard(
        val accountName: String,
        val steamId: String,
        val clientId: String,
        val requestId: String,
        val pollIntervalSec: Long,
        val guardType: Int,
        val promptMessage: String,
        val availableTypes: List<Int>,
        val rememberMe: Boolean,
        /** True while device-confirmation is polling but the user asked for a
         *  manual code field ("enter code instead" — sent via the CM session
         *  with sendSteamGuardCode, same as the desktop client). */
        val showCodeField: Boolean = false
    ) : AuthState() {
        val codeEntrySupported: Boolean
            get() = SteamGuardType.supportsCodeEntry(guardType) || showCodeField
    }

    /** Signed in and able to use the library/downloads. */
    data class LoggedIn(val session: SteamSession) : AuthState()

    /** Something failed. [message] is user presentable. */
    data class Error(val message: String, val accountName: String = "") : AuthState()
}

/** Internal transport-level failure from the Steam auth endpoints. */
class SteamAuthException(message: String, val eresult: Int? = null) : Exception(message)
