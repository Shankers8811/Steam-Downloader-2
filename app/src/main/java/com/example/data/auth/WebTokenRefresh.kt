package com.example.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Mints a fresh **web API** access token from the long-lived refresh token
 * (`IAuthenticationService/GenerateAccessTokenForApp`) — the same call the
 * Steam desktop client makes to keep web endpoints usable.
 *
 * This is only for the web library screens; downloads ride the CM session,
 * which has its own token lifecycle inside JavaSteam.
 */
object WebTokenRefresh {

    private const val URL_GENERATE_ACCESS_TOKEN =
        "https://api.steampowered.com/IAuthenticationService/GenerateAccessTokenForApp/v1/"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Returns a session with a renewed access token, or null on failure. */
    suspend fun renew(current: SteamSession): SteamSession? = withContext(Dispatchers.IO) {
        if (current.refreshToken.isBlank()) return@withContext null
        try {
            val body = FormBody.Builder()
                .add("refresh_token", current.refreshToken)
                .add("steamid", current.steamId.ifBlank { "0" })
                .add("renewal_type", "0")
                .build()
            val response = client.newCall(
                Request.Builder().url(URL_GENERATE_ACCESS_TOKEN).post(body).build()
            ).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext null
            }
            val raw = response.body?.string().orEmpty()
            val newToken = try {
                JSONObject(raw).optJSONObject("response")?.optString("access_token").orEmpty()
            } catch (e: JSONException) {
                ""
            }
            if (newToken.isBlank()) null
            else current.copy(accessToken = newToken, obtainedAtMs = System.currentTimeMillis())
        } catch (e: Exception) {
            null
        }
    }
}
