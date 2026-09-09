package com.example.data.auth

import android.util.Base64
import com.example.data.steam.SteamRuntime
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.EAuthSessionGuardType
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
import `in`.dragonbra.javasteam.steam.authentication.CredentialsAuthSession
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.CrashLog
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Sign-in flow — the same flow the Steam desktop app (and GameNative) runs:
 *
 *  CM connect → beginAuthSessionViaCredentials(RSA-encrypted password)
 *            → Steam Guard challenge surfaced through [IAuthenticator]
 *              (code from the Steam Mobile App / email code /
 *               approve-in-app confirmation)
 *            → pollingWaitForResult() yields access + refresh tokens
 *            → CM logOn with the refresh token  (session created)
 *
 * The password exists only for the duration of the sign-in call and is never
 * stored. Remembered logins reuse only the refresh token (kept AES/GCM
 * encrypted in [CredentialVault]).
 *
 * The UI contract ([AuthState]) is unchanged from the previous web flow,
 * so the login screen didn't have to change — but everything now happens
 * over the real CM protocol connection, which is also what downloads use.
 */
class SteamAuthManager(
    private val vault: CredentialVault,
    private val runtime: SteamRuntime,
    private val onSessionEstablished: (SteamSession) -> Unit = {}
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Restoring)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _session = MutableStateFlow<SteamSession?>(null)
    val session: StateFlow<SteamSession?> = _session.asStateFlow()

    /** Inline failure for the guard-code field (e.g. "incorrect code"). */
    private val _guardError = MutableStateFlow<String?>(null)
    val guardError: StateFlow<String?> = _guardError.asStateFlow()

    /** Pending authenticator-stage code submission (mobile-app / email code). */
    @Volatile
    private var pendingGuardCode: CompletableDeferred<String>? = null

    /** Active credentials session — lets us submit a code while device
     *  confirmation polling is underway ("enter a code instead"). */
    @Volatile
    private var activeAuthSession: CredentialsAuthSession? = null

    private var loginJob: Job? = null

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Called at app start: restores a remembered login ("keep me signed in"). */
    fun restoreSavedSession() {
        val saved = try { vault.loadSession() } catch (e: Exception) { null }
        if (saved == null || saved.refreshToken.isBlank()) {
            _authState.value = AuthState.LoggedOut
            return
        }

        _authState.value = AuthState.Busy("Restoring your signed-in session…")
        loginJob = scope.launch {
            try {
                val result = runtime.logonWithToken(saved.accountName, saved.refreshToken)
                if (result == EResult.OK) {
                    val steamId = runtime.accountSteamId.value?.takeIf { it.isNotBlank() } ?: saved.steamId
                    val restored = saved.copy(steamId = steamId)
                    _session.value = restored
                    if (vault.isRemembered()) vault.saveSession(restored)
                    onSessionEstablished(restored)
                    _authState.value = AuthState.LoggedIn(restored)
                } else {
                    runtime.log("Session restore failed ($result) — asking for credentials.")
                    _authState.value = AuthState.Error(logonErrorMessage(result), saved.accountName)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                CrashLog.record("restoreSavedSession", t)
                _authState.value = AuthState.Error(
                    "Session restore hit an internal error (${t.javaClass.simpleName}) — sign in again.",
                    saved.accountName
                )
            }
        }
    }

    /** Password login over the CM auth protocol (RSA-protected by JavaSteam). */
    fun beginPasswordLogin(accountName: String, password: String, rememberMe: Boolean) {
        val trimmedName = accountName.trim()
        if (trimmedName.isEmpty() || password.isEmpty()) {
            _authState.value = AuthState.Error("Enter your Steam account name and password.", trimmedName)
            return
        }
        if (loginJob?.isActive == true) return

        _guardError.value = null
        loginJob = scope.launch {
            try {
                _authState.value = AuthState.Busy("Connecting to Steam…")
                runtime.connect()
                if (!waitConnected(15_000L)) {
                    throw SteamAuthException("Could not reach a Steam server. Check your internet connection and try again.")
                }

                _authState.value = AuthState.Busy("Signing in as $trimmedName…")

                val details = AuthSessionDetails().apply {
                    username = trimmedName
                    this.password = password // never stored anywhere
                    persistentSession = rememberMe
                    authenticator = UiAuthenticator(trimmedName)
                    clientOSType = EOSType.WinUnknown
                    deviceFriendlyName =
                        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (DepotDownloader)"
                    guardData = "DepotDownloader Android"
                }

                val authSession = runtime.client.authentication
                    .beginAuthSessionViaCredentials(details).await()
                activeAuthSession = authSession

                val pollResult = try {
                    authSession.pollingWaitForResult().await()
                } catch (e: AuthenticationException) {
                    throw SteamAuthException(
                        authResultMessage(e.result?.name, e.message),
                        null
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    throw SteamAuthException("Sign-in failed: ${e.message ?: e.javaClass.simpleName}", null)
                }

                if (pollResult.refreshToken.isBlank() || pollResult.accountName.isBlank()) {
                    throw SteamAuthException("Steam did not return a session — try again.")
                }

                _authState.value = AuthState.Busy("Establishing secure CM session…")
                val cmResult = runtime.logonWithToken(pollResult.accountName, pollResult.refreshToken)
                if (cmResult != EResult.OK) {
                    throw SteamAuthException(logonErrorMessage(cmResult), null)
                }

                val steamId = runtime.accountSteamId.value?.takeIf { it.isNotBlank() }
                    ?: decodeJwtSubject(pollResult.accessToken)
                val newSession = SteamSession(
                    steamId = steamId,
                    accountName = pollResult.accountName,
                    refreshToken = pollResult.refreshToken,
                    accessToken = pollResult.accessToken
                )
                if (rememberMe) vault.saveSession(newSession)
                _session.value = newSession
                onSessionEstablished(newSession)
                _guardError.value = null
                _authState.value = AuthState.LoggedIn(newSession)
                runtime.log("Password sign-in complete for ${pollResult.accountName} (rememberMe=$rememberMe).")
            } catch (e: CancellationException) {
                if (_authState.value !is AuthState.LoggedIn) _authState.value = AuthState.LoggedOut
            } catch (e: SteamAuthException) {
                _authState.value = AuthState.Error(e.message ?: "Steam sign-in failed.", trimmedName)
            } catch (e: IllegalArgumentException) {
                _authState.value = AuthState.Error("Steam is not ready yet — try again.", trimmedName)
            } catch (e: Exception) {
                runtime.log("Sign-in error: ${e.javaClass.simpleName}: ${e.message}")
                CrashLog.record("beginPasswordLogin", e)
                _authState.value = AuthState.Error(
                    "Sign-in failed (${e.javaClass.simpleName}). Check your connection and try again.",
                    trimmedName
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                CrashLog.record("beginPasswordLogin (Error)", t)
                runtime.log("Sign-in CRASH class: ${t.javaClass.simpleName}: ${t.message}")
                _authState.value = AuthState.Error(
                    "Sign-in hit an internal error (${t.javaClass.simpleName}). Open Diagnostics (🐞) and share the log.",
                    trimmedName
                )
            } finally {
                pendingGuardCode = null
                activeAuthSession = null
            }
        }
    }

    /**
     * Submit a Steam Guard / 2FA code:
     *  - while the authenticator waits for a code (mobile-app/email stage):
     *    completes its waiting future;
     *  - while device-confirmation is polling: sends the code straight into
     *    the active session via sendSteamGuardCode ("enter a code instead"),
     *    exactly like the Steam desktop client.
     */
    fun submitGuardCode(code: String) {
        val submitted = code.trim().uppercase()
        if (submitted.length < 4) {
            _guardError.value = "Enter the full code."
            return
        }

        val st = _authState.value

        val pending = pendingGuardCode
        if (pending != null && pending.isActive) {
            _guardError.value = null
            pending.complete(submitted)
            if (st is AuthState.AwaitingGuard) {
                _authState.value = st.copy(promptMessage = "Code submitted — verifying with Steam…")
            }
            return
        }

        val session = activeAuthSession
        if (session != null && st is AuthState.AwaitingGuard &&
            st.guardType == SteamGuardType.DEVICE_CONFIRMATION
        ) {
            scope.launch {
                try {
                    session.sendSteamGuardCode(
                        submitted,
                        EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode
                    ).await()
                    _guardError.value = null
                    _authState.value = st.copy(promptMessage = "Code accepted — confirming with Steam…")
                } catch (e: AuthenticationException) {
                    _guardError.value = "That code was rejected (${e.result?.name ?: "error"}) — try the current code."
                } catch (e: Exception) {
                    _guardError.value = "Could not submit the code (${e.javaClass.simpleName}) — try again."
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    CrashLog.record("submitGuardCode (Error)", t)
                    _guardError.value = "Code submission hit an internal error (${t.javaClass.simpleName})."
                }
            }
            return
        }

        _guardError.value = "No confirmation is currently pending."
    }

    /** Switch between guard input modes where applicable. */
    fun useGuardType(type: Int) {
        if (type == SteamGuardType.DEVICE_CODE) {
            val st = _authState.value
            if (st is AuthState.AwaitingGuard &&
                st.guardType == SteamGuardType.DEVICE_CONFIRMATION
            ) {
                // Keep polling the confirmation request, but surface the
                // code-entry field. Submission goes through sendSteamGuardCode
                // on the active session ("enter a code instead").
                _authState.value = st.copy(
                    promptMessage = "The Steam Mobile App approval request is still open — or type the current code from the app below to use it instead.",
                    showCodeField = true
                )
            }
        }
        _guardError.value = null
    }

    fun cancelSignIn() {
        pendingGuardCode?.completeExceptionally(CancellationException("Cancelled by user"))
        loginJob?.cancel()
        loginJob = null
        _guardError.value = null
        pendingGuardCode = null
        activeAuthSession = null
        if (_authState.value !is AuthState.LoggedIn) _authState.value = AuthState.LoggedOut
    }

    fun signOut() {
        cancelSignIn()
        vault.clear()
        _session.value = null
        runtime.disconnect()
        _authState.value = AuthState.LoggedOut
    }

    /**
     * A still-valid web API access token (used by the library screen). The CM
     * session is the source of truth for downloads; the web token is renewed
     * from the refresh token when it lapses.
     */
    suspend fun getValidAccessToken(): String? = withContext(Dispatchers.IO) {
        val current = _session.value ?: return@withContext null
        val expiresAt = decodeJwtExpiry(current.accessToken)
        val nowSec = System.currentTimeMillis() / 1000L
        if (expiresAt == null || expiresAt - 60L > nowSec) return@withContext current.accessToken

        val renewed = WebTokenRefresh.renew(current)
        if (renewed != null) {
            _session.value = renewed
            if (vault.isRemembered()) vault.saveSession(renewed)
            return@withContext renewed.accessToken
        }
        null
    }

    // ------------------------------------------------------------------
    // IAuthenticator bridge between JavaSteam and the Compose UI
    // ------------------------------------------------------------------

    private inner class UiAuthenticator(private val accountName: String) : IAuthenticator {

        override fun getDeviceCode(previousCodeWasIncorrect: Boolean): java.util.concurrent.CompletableFuture<String> {
            setGuardState(
                guardType = SteamGuardType.DEVICE_CODE,
                promptMessage = if (previousCodeWasIncorrect)
                    "Incorrect code — try the current code from your Steam Mobile App again."
                else
                    "Enter the current code from the Steam Guard page of your Steam Mobile App.",
                accountName = accountName,
                previousCodeWasIncorrect = previousCodeWasIncorrect
            )
            return awaitUiCode()
        }

        override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): java.util.concurrent.CompletableFuture<String> {
            setGuardState(
                guardType = SteamGuardType.EMAIL_CODE,
                promptMessage = if (previousCodeWasIncorrect)
                    "Incorrect code — try again with the new code Steam emailed${email?.let { " to $it" } ?: ""}."
                else
                    "Enter the access code Steam emailed${email?.let { " to $it" } ?: " to your email address"}.",
                accountName = accountName,
                previousCodeWasIncorrect = previousCodeWasIncorrect
            )
            return awaitUiCode()
        }

        override fun acceptDeviceConfirmation(): java.util.concurrent.CompletableFuture<Boolean> {
            // Show "approve this sign-in in your Steam Mobile App" and let
            // polling proceed. The user may still type a code — handled via
            // sendSteamGuardCode on the active session.
            setGuardState(
                guardType = SteamGuardType.DEVICE_CONFIRMATION,
                promptMessage = "Open your Steam Mobile App and approve this sign-in request.",
                accountName = accountName,
                previousCodeWasIncorrect = false
            )
            return java.util.concurrent.CompletableFuture.completedFuture(true)
        }
    }

    private fun setGuardState(
        guardType: Int,
        promptMessage: String,
        accountName: String,
        previousCodeWasIncorrect: Boolean
    ) {
        if (previousCodeWasIncorrect) {
            _guardError.value = "That code was incorrect — check the current code and try again."
        }
        _authState.value = AuthState.AwaitingGuard(
            accountName = accountName,
            steamId = "",
            clientId = "",
            requestId = "",
            pollIntervalSec = 2L,
            guardType = guardType,
            promptMessage = promptMessage,
            availableTypes = listOf(
                SteamGuardType.DEVICE_CODE,
                SteamGuardType.EMAIL_CODE,
                SteamGuardType.DEVICE_CONFIRMATION
            ),
            rememberMe = true
        )
    }

    private fun awaitUiCode(): java.util.concurrent.CompletableFuture<String> {
        val deferred = CompletableDeferred<String>()
        pendingGuardCode = deferred

        val future = java.util.concurrent.CompletableFuture<String>()
        scope.launch {
            try {
                val submitted = deferred.await()
                if (submitted.isBlank()) {
                    future.completeExceptionally(AuthenticationException("Code entry cancelled"))
                } else {
                    future.complete(submitted)
                }
            } catch (e: CancellationException) {
                future.completeExceptionally(AuthenticationException("Code entry cancelled"))
            } catch (e: Exception) {
                future.completeExceptionally(e)
            } catch (t: Throwable) {
                CrashLog.record("awaitUiCode (Error)", t)
                future.completeExceptionally(
                    AuthenticationException("Guard flow internal error (${t.javaClass.simpleName})")
                )
            }
        }
        return future
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private suspend fun waitConnected(timeoutMs: Long): Boolean {
        if (runtime.connected.value) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
            if (runtime.connected.value) return true
            delay(150L)
        }
        return runtime.connected.value
    }

    private fun decodeJwtSubject(token: String): String =
        decodeJwtPayload(token)?.optString("sub").orEmpty()

    private fun decodeJwtExpiry(token: String): Long? =
        decodeJwtPayload(token)?.optLong("exp", 0L)?.takeIf { it > 0L }

    private fun decodeJwtPayload(token: String): JSONObject? = try {
        val parts = token.split('.')
        if (parts.size < 2) null
        else JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    private fun logonErrorMessage(result: EResult): String = when (result) {
        EResult.InvalidPassword -> "The remembered session was rejected by Steam (invalid credentials) — sign in again."
        EResult.AccountLogonDenied -> "Steam denied the sign-in — check Steam Guard on your account."
        EResult.AccountLoginDeniedNeedTwoFactor -> "Steam Guard requires a fresh two-factor sign-in."
        EResult.NoConnection, EResult.TryAnotherCM, EResult.ServiceUnavailable ->
            "Could not reach Steam servers — check your internet connection."
        EResult.Timeout -> "Steam did not respond in time — check your connection and try again."
        EResult.RateLimitExceeded -> "Too many attempts — wait a few minutes and try again."
        else -> "Sign-in failed (${result.name})."
    }

    private fun authResultMessage(resultName: String?, originalMessage: String?): String {
        val name = resultName.orEmpty()
        return when {
            name.contains("InvalidPassword", ignoreCase = true) -> "Incorrect account name or password."
            name.contains("RateLimit", ignoreCase = true) -> "Too many attempts — wait a few minutes and try again."
            name.contains("Expired", ignoreCase = true) -> "The sign-in session expired — try again."
            name.contains("Timeout", ignoreCase = true) -> "No response from Steam — check your connection and try again."
            name.contains("Denied", ignoreCase = true) -> "Steam denied the sign-in (check Steam Guard)."
            else -> originalMessage ?: "Steam sign-in failed ($name)."
        }
    }
}
