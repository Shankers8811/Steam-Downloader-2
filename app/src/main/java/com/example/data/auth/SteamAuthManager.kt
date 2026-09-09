package com.example.data.auth

import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.math.BigInteger
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/**
 * Implements the same sign-in protocol the Steam desktop/mobile client uses:
 *
 *  1. IAuthenticationService/GetPasswordRSAPublicKey  (fetch RSA key + timestamp)
 *  2. RSA-encrypt the password (it is never sent in plain text, never stored)
 *  3. IAuthenticationService/BeginAuthSessionViaCredentials
 *  4. Steam Guard challenge: email code / Steam Mobile App code /
 *     approve-in-app confirmation (whatever the account allows)
 *  5. IAuthenticationService/UpdateAuthSessionWithSteamGuardCode
 *  6. IAuthenticationService/PollAuthSessionStatus until Steam hands over
 *     an access token + refresh token
 *  7. Refresh tokens mint new access tokens (IAuthenticationService/
 *     GenerateAccessTokenForApp) so the user stays signed in across launches.
 */
class SteamAuthManager(
    private val vault: CredentialVault,
    private val onSessionEstablished: (SteamSession) -> Unit = {}
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Restoring)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _session = MutableStateFlow<SteamSession?>(null)
    val session: StateFlow<SteamSession?> = _session.asStateFlow()

    /** Inline failure for the guard-code field (e.g. "incorrect code"). */
    private val _guardError = MutableStateFlow<String?>(null)
    val guardError: StateFlow<String?> = _guardError.asStateFlow()

    private var pollJob: Job? = null

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Called at app start: restores a remembered login ("keep me signed in"). */
    fun restoreSavedSession() {
        val saved = try { vault.loadSession() } catch (e: Exception) { null }
        if (saved != null && saved.refreshToken.isNotBlank()) {
            _session.value = saved
            _authState.value = AuthState.LoggedIn(saved)
        } else {
            _authState.value = AuthState.LoggedOut
        }
    }

    /**
     * Start a password login. The password lives only for the duration of this
     * call — it is RSA-encrypted like in the desktop client and dropped.
     */
    fun beginPasswordLogin(accountName: String, password: String, rememberMe: Boolean) {
        val trimmedName = accountName.trim()
        if (trimmedName.isEmpty() || password.isEmpty()) {
            _authState.value = AuthState.Error("Enter your Steam account name and password.", trimmedName)
            return
        }
        _guardError.value = null
        scope.launch {
            try {
                _authState.value = AuthState.Busy("Contacting Steam…")

                val rsa = fetchPasswordRsaKey(trimmedName)
                val encryptedPassword = encryptPassword(password, rsa.publicKeyMod, rsa.publicKeyExp)

                _authState.value = AuthState.Busy("Signing in as $trimmedName…")
                val begin = beginAuthSession(trimmedName, encryptedPassword, rsa.timestamp)

                val confirmations = begin.allowedConfirmations
                val primary = confirmations.firstOrNull { it.confirmationType == SteamGuardType.DEVICE_CODE }
                    ?: confirmations.firstOrNull { it.confirmationType == SteamGuardType.EMAIL_CODE }
                    ?: confirmations.firstOrNull()

                startPolling(
                    clientId = begin.clientId,
                    requestId = begin.requestId,
                    intervalSec = begin.intervalSec,
                    steamId = begin.steamId,
                    accountName = trimmedName,
                    rememberMe = rememberMe
                )

                if (primary == null) {
                    // No guard challenge required – polling will finish the job.
                    _authState.value = AuthState.Busy("Finalising sign-in…")
                } else {
                    _authState.value = AuthState.AwaitingGuard(
                        accountName = trimmedName,
                        steamId = begin.steamId,
                        clientId = begin.clientId,
                        requestId = begin.requestId,
                        pollIntervalSec = begin.intervalSec,
                        guardType = primary.confirmationType,
                        promptMessage = primary.message.ifBlank {
                            SteamGuardType.defaultPrompt(primary.confirmationType)
                        },
                        availableTypes = confirmations.map { it.confirmationType },
                        rememberMe = rememberMe
                    )
                }
            } catch (e: SteamAuthException) {
                _authState.value = AuthState.Error(e.message ?: "Steam sign-in failed.", trimmedName)
            } catch (e: Exception) {
                _authState.value = AuthState.Error(
                    "Network error while contacting Steam. Check your connection and try again.",
                    trimmedName
                )
            }
        }
    }

    /** Submit a Steam Guard / 2FA code (mobile app code or email code). */
    fun submitGuardCode(code: String) {
        val awaiting = _authState.value as? AuthState.AwaitingGuard ?: return
        val submitted = code.trim().uppercase()
        if (submitted.length < 4) {
            _guardError.value = "Enter the full code."
            return
        }
        if (!awaiting.codeEntrySupported) {
            _guardError.value = "This confirmation type needs approval inside the Steam Mobile App."
            return
        }
        _guardError.value = null
        scope.launch {
            try {
                updateAuthSessionWithCode(
                    clientId = awaiting.clientId,
                    steamId = awaiting.steamId,
                    code = submitted,
                    codeType = awaiting.guardType
                )
            } catch (e: SteamAuthException) {
                _guardError.value = e.message ?: "Steam rejected the code."
            } catch (e: Exception) {
                _guardError.value = "Network error. Try again."
            }
        }
    }

    /** Let the user pick another offered confirmation method ("use a code instead"). */
    fun useGuardType(type: Int) {
        val awaiting = _authState.value as? AuthState.AwaitingGuard ?: return
        if (type !in awaiting.availableTypes || type == awaiting.guardType) return
        _guardError.value = null
        _authState.value = awaiting.copy(
            guardType = type,
            promptMessage = SteamGuardType.defaultPrompt(type)
        )
    }

    fun cancelSignIn() {
        pollJob?.cancel()
        pollJob = null
        _guardError.value = null
        _authState.value = AuthState.LoggedOut
    }

    fun signOut() {
        pollJob?.cancel()
        pollJob = null
        vault.clear()
        _session.value = null
        _guardError.value = null
        _authState.value = AuthState.LoggedOut
    }

    /**
     * Returns an access token that is still valid, transparently renewing it
     * from the stored refresh token when it is about to expire.
     */
    suspend fun getValidAccessToken(): String? = withContext(Dispatchers.IO) {
        val current = _session.value ?: return@withContext null
        val expiresAt = decodeJwtPayload(current.accessToken)?.optLong("exp", 0L) ?: 0L
        val nowSec = System.currentTimeMillis() / 1000L
        if (expiresAt - 60L > nowSec) return@withContext current.accessToken

        if (current.refreshToken.isBlank()) return@withContext null

        try {
            val response = postForm(
                URL_GENERATE_ACCESS_TOKEN,
                mapOf(
                    "refresh_token" to current.refreshToken,
                    "steamid" to current.steamId,
                    "renewal_type" to "0"
                )
            )
            val body = readJson(response)
            val newToken = body?.optJSONObject("response")?.optString("access_token").orEmpty()
            if (newToken.isBlank()) {
                signOut()
                null
            } else {
                val renewed = current.copy(accessToken = newToken, obtainedAtMs = System.currentTimeMillis())
                _session.value = renewed
                if (vault.isRemembered()) vault.saveSession(renewed)
                newToken
            }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Steam auth endpoints
    // ------------------------------------------------------------------

    private data class RsaKey(val publicKeyMod: String, val publicKeyExp: String, val timestamp: Long)

    private data class BeginSessionResult(
        val clientId: String,
        val requestId: String,
        val steamId: String,
        val intervalSec: Long,
        val allowedConfirmations: List<GuardConfirmationOption>
    )

    private suspend fun fetchPasswordRsaKey(accountName: String): RsaKey = withContext(Dispatchers.IO) {
        val url = URL_GET_RSA_KEY + "?account_name=" + URLEncoder.encode(accountName, "UTF-8")
        val response = client.newCall(Request.Builder().url(url).get().build()).execute()
        val body = readJson(response)
            ?: throw SteamAuthException("Steam did not return an encryption key.", eresultOf(response, null))

        val responseObj = body.optJSONObject("response")
            ?: throw SteamAuthException("Unexpected response from Steam.")

        val mod = responseObj.optString("publickey_mod")
        val exp = responseObj.optString("publickey_exp")
        if (mod.isBlank() || exp.isBlank()) {
            val eresult = eresultOf(response, body)
            throw SteamAuthException(
                eresultMessage(eresult, responseObj.optString("error_message")),
                eresult
            )
        }
        RsaKey(mod, exp, responseObj.optString("timestamp", "0").toLongOrNull() ?: 0L)
    }

    private fun encryptPassword(password: String, modulusHex: String, exponentHex: String): String {
        val keySpec = RSAPublicKeySpec(BigInteger(modulusHex, 16), BigInteger(exponentHex, 16))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private suspend fun beginAuthSession(
        accountName: String,
        encryptedPassword: String,
        encryptionTimestamp: Long
    ): BeginSessionResult = withContext(Dispatchers.IO) {
        val response = postForm(
            URL_BEGIN_AUTH_SESSION,
            mapOf(
                "device_friendly_name" to "${Build.MANUFACTURER} ${Build.MODEL} (DepotDownloader)",
                "account_name" to accountName,
                "encrypted_password" to encryptedPassword,
                "encryption_timestamp" to encryptionTimestamp.toString(),
                "remember_login" to "1",
                "platform_type" to "3", // k_EAuthTokenPlatformType_MobileApp
                "persistence" to "1",
                "website_id" to "Mobile"
            )
        )
        val body = readJson(response)
        val responseObj = body?.optJSONObject("response")
        val clientId = responseObj?.optString("client_id").orEmpty()

        if (clientId.isBlank() || clientId == "0") {
            if (response.code == 429 || response.code == 403) {
                throw SteamAuthException(
                    "Steam is rate-limiting sign-in attempts from this device. Wait a few minutes and try again.",
                    response.code
                )
            }
            val eresult = eresultOf(response, body)
            throw SteamAuthException(
                eresultMessage(eresult, responseObj?.optString("extended_error_message")),
                eresult
            )
        }

        val confirmations = mutableListOf<GuardConfirmationOption>()
        val confirmationsJson = responseObj.optJSONArray("allowed_confirmations")
        if (confirmationsJson != null) {
            for (i in 0 until confirmationsJson.length()) {
                val item = confirmationsJson.optJSONObject(i) ?: continue
                val type = item.optInt("confirmation_type", SteamGuardType.NONE)
                if (type == SteamGuardType.NONE) continue
                confirmations.add(
                    GuardConfirmationOption(
                        confirmationType = type,
                        message = item.optString("associated_message")
                    )
                )
            }
        }

        BeginSessionResult(
            clientId = clientId,
            requestId = responseObj.optString("request_id"),
            steamId = responseObj.optString("steamid"),
            intervalSec = responseObj.optDouble("interval", 5.0).toLong().coerceIn(2L, 10L),
            allowedConfirmations = confirmations
        )
    }

    @Throws(SteamAuthException::class)
    private suspend fun updateAuthSessionWithCode(
        clientId: String,
        steamId: String,
        code: String,
        codeType: Int
    ) = withContext(Dispatchers.IO) {
        val response = postForm(
            URL_UPDATE_WITH_GUARD_CODE,
            mapOf(
                "client_id" to clientId,
                "steamid" to steamId,
                "code" to code,
                "code_type" to codeType.toString()
            )
        )
        val body = readJson(response)
        val eresult = eresultOf(response, body)
        if (eresult != null && eresult != ERESULT_OK) {
            throw SteamAuthException(eresultMessage(eresult, null), eresult)
        }
        Unit
    }

    private fun startPolling(
        clientId: String,
        requestId: String,
        intervalSec: Long,
        steamId: String,
        accountName: String,
        rememberMe: Boolean
    ) {
        pollJob?.cancel()
        pollJob = scope.launch {
            val deadlineMs = System.currentTimeMillis() + GUARD_TIMEOUT_MS
            var consecutiveErrors = 0

            while (isActive && System.currentTimeMillis() < deadlineMs) {
                delay(intervalSec * 1000L)
                try {
                    val tokens = pollAuthSessionStatus(clientId, requestId)
                    consecutiveErrors = 0
                    if (tokens != null) {
                        val resolvedSteamId = steamId.ifBlank {
                            decodeJwtPayload(tokens.accessToken)?.optString("sub").orEmpty()
                        }
                        val newSession = SteamSession(
                            steamId = resolvedSteamId,
                            accountName = accountName,
                            refreshToken = tokens.refreshToken,
                            accessToken = tokens.accessToken
                        )
                        if (rememberMe) vault.saveSession(newSession)
                        _session.value = newSession
                        onSessionEstablished(newSession)
                        _guardError.value = null
                        _authState.value = AuthState.LoggedIn(newSession)
                        return@launch
                    }
                } catch (e: SteamAuthException) {
                    // Session expired / invalidated: hard failure, stop polling.
                    _authState.value = AuthState.Error(
                        e.message ?: "The sign-in session expired. Try signing in again.",
                        accountName
                    )
                    return@launch
                } catch (e: Exception) {
                    consecutiveErrors++
                    if (!isActive) return@launch
                    if (consecutiveErrors >= 8) {
                        _authState.value = AuthState.Error(
                            "Lost connection to Steam while waiting for confirmation.",
                            accountName
                        )
                        return@launch
                    }
                }
            }
            if (isActive) {
                _authState.value = AuthState.Error(
                    "Sign-in timed out — the Steam Guard confirmation was not completed.",
                    accountName
                )
            }
        }
    }

    private data class TokenPair(val refreshToken: String, val accessToken: String)

    /** Returns tokens when the user finished all guard steps, null while still waiting. */
    @Throws(SteamAuthException::class)
    private suspend fun pollAuthSessionStatus(clientId: String, requestId: String): TokenPair? =
        withContext(Dispatchers.IO) {
            val response = postForm(
                URL_POLL_AUTH_STATUS,
                mapOf("client_id" to clientId, "request_id" to requestId)
            )

            if (response.code != 200) {
                val eresult = response.header("x-eresult")?.toIntOrNull()
                throw SteamAuthException(eresultMessage(eresult, null), eresult)
            }

            val body = readJson(response)
            val responseObj = body?.optJSONObject("response") ?: return@withContext null
            val refreshToken = responseObj.optString("refresh_token")
            val accessToken = responseObj.optString("access_token")

            if (refreshToken.isBlank() || accessToken.isBlank()) return@withContext null
            TokenPair(refreshToken, accessToken)
        }

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    private fun postForm(url: String, fields: Map<String, String>): Response {
        val builder = FormBody.Builder()
        fields.forEach { (key, value) -> builder.add(key, value) }
        val request = Request.Builder().url(url).post(builder.build()).build()
        return client.newCall(request).execute()
    }

    /** Reads (and closes) the response body as JSON; null when not parseable. */
    private fun readJson(response: Response): JSONObject? {
        val raw = try {
            response.body?.string()
        } catch (e: Exception) {
            null
        } ?: return null
        return try { JSONObject(raw) } catch (e: Exception) { null }
    }

    private fun eresultOf(response: Response, body: JSONObject?): Int? {
        response.header("x-eresult")?.toIntOrNull()?.let { return it }
        val responseObj = body?.optJSONObject("response")
        if (responseObj != null && responseObj.has("eresult")) return responseObj.optInt("eresult")
        if (body != null && body.has("eresult")) return body.optInt("eresult")
        return null
    }

    private fun eresultMessage(eresult: Int?, extended: String?): String = when (eresult) {
        ERESULT_OK -> "OK."
        5 -> "Incorrect account name or password."
        6 -> "The account name or password is not valid."
        50 -> "This account is locked. Contact Steam Support."
        84 -> "Steam is temporarily rate-limiting new sign-ins. Wait a few minutes and try again."
        85 -> "Too many sign-in attempts. Wait a while and try again."
        87 -> "Too many attempts. Wait a few minutes and try again."
        88 -> "Incorrect Steam Guard code. Enter the current code and try again."
        89 -> "The sign-in session expired. Try signing in again."
        90 -> "Two-factor authentication is required for this account."
        else -> extended?.takeIf { it.isNotBlank() }
            ?: if (eresult != null) "Steam sign-in failed (eresult $eresult)." else "Steam sign-in failed."
    }

    private fun decodeJwtPayload(token: String): JSONObject? = try {
        val parts = token.split('.')
        if (parts.size < 2) null
        else JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val BASE = "https://api.steampowered.com"
        private const val URL_GET_RSA_KEY = "$BASE/IAuthenticationService/GetPasswordRSAPublicKey/v1/"
        private const val URL_BEGIN_AUTH_SESSION = "$BASE/IAuthenticationService/BeginAuthSessionViaCredentials/v1/"
        private const val URL_UPDATE_WITH_GUARD_CODE = "$BASE/IAuthenticationService/UpdateAuthSessionWithSteamGuardCode/v1/"
        private const val URL_POLL_AUTH_STATUS = "$BASE/IAuthenticationService/PollAuthSessionStatus/v1/"
        private const val URL_GENERATE_ACCESS_TOKEN = "$BASE/IAuthenticationService/GenerateAccessTokenForApp/v1/"

        private const val ERESULT_OK = 1
        private const val GUARD_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
