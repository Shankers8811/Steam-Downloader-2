package com.example.data.api

import com.example.data.model.CheckAppOwnershipResponse
import com.example.data.model.GetOwnedGamesResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Steam Web API endpoints that work with the user access token obtained from
 * the password + Steam Guard sign-in flow (no manual API key needed anymore).
 */
interface SteamApiService {

    /** Owned games for the signed-in user, authorised by their access token. */
    @GET("IPlayerService/GetOwnedGames/v0001/")
    suspend fun getOwnedGames(
        @Query("access_token") accessToken: String,
        @Query("steamid") steamId: String,
        @Query("include_appinfo") includeAppInfo: Int = 1,
        @Query("include_played_free_games") includePlayedFreeGames: Int = 1,
        @Query("format") format: String = "json"
    ): GetOwnedGamesResponse

    /** License check for a single app id against the signed-in account. */
    @GET("IUserService/CheckAppOwnership/v1/")
    suspend fun checkAppOwnership(
        @Query("access_token") accessToken: String,
        @Query("appid") appId: Int,
        @Query("format") format: String = "json"
    ): CheckAppOwnershipResponse

    companion object {
        private const val BASE_URL = "https://api.steampowered.com/"

        fun create(): SteamApiService {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()

            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(SteamApiService::class.java)
        }
    }
}
