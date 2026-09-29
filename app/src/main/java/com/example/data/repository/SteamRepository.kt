package com.example.data.repository

import com.example.data.api.SteamApiService
import com.example.data.db.SteamGameDao
import com.example.data.db.SteamGameEntity
import com.example.data.steam.SteamRuntime
import kotlinx.coroutines.flow.Flow

class SteamRepository(
    private val apiService: SteamApiService,
    private val steamGameDao: SteamGameDao,
    private val runtime: SteamRuntime
) {
    val allGames: Flow<List<SteamGameEntity>> = steamGameDao.getAllGames()

    fun searchGames(query: String): Flow<List<SteamGameEntity>> {
        return if (query.isBlank()) {
            steamGameDao.getAllGames()
        } else {
            steamGameDao.searchGames(query)
        }
    }

    /**
     * Syncs the owned-games library. Primary source: the native CM license
     * list + PICS metadata (the exact complete "games only" view the Steam
     * client itself builds — no silent omissions, no DLC/tool rows). The web
     * API GetOwnedGames is still queried, best-effort, but only to enrich
     * with playtime + icons, never as the inventory itself. Falls back to the
     * web list only if the native scan yields nothing.
     */
    suspend fun fetchAndStoreGames(steamId: String, accessToken: String): Result<Int> {
        return try {
            val webGames = runCatching {
                apiService.getOwnedGames(accessToken = accessToken, steamId = steamId)
            }.getOrNull()?.response?.games ?: emptyList()
            val playtimeByApp = webGames.associate { it.appid to (it.playtimeForever ?: 0) }
            val iconByApp = webGames.associate { it.appid to it.getIconImageUrl() }

            val native = runCatching { runtime.buildOwnedGameLibrary() }
                .getOrDefault(emptyList())

            val chosen: List<Pair<Int, String>> = if (native.isNotEmpty()) {
                native
            } else {
                webGames.map { it.appid to (it.name ?: "App ${it.appid}") }
            }

            val entities = chosen.map { (appId, name) ->
                SteamGameEntity(
                    appId = appId,
                    name = name,
                    playtimeForever = playtimeByApp[appId] ?: 0,
                    imgIconUrl = iconByApp[appId] ?: "",
                    // Windows Steam client library art: tall 2:3 poster capsule.
                    imgHeaderUrl = "https://cdn.cloudflare.steamstatic.com/steam/apps/$appId/library_600x900.jpg",
                    lastUpdated = System.currentTimeMillis()
                )
            }

            steamGameDao.clearAll()
            steamGameDao.insertGames(entities)
            Result.success(entities.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * License probe used by the download engine before it schedules content.
     * Returns true/false when Steam gave a definitive answer, null when the
     * check could not be completed (network error etc.).
     */
    suspend fun checkAppOwnership(appId: Int, accessToken: String): Boolean? {
        return try {
            val response = apiService.checkAppOwnership(accessToken = accessToken, appId = appId)
            response.response?.appOwnership?.ownsApp
        } catch (e: Exception) {
            null
        }
    }

    suspend fun clearCachedLibrary() {
        steamGameDao.clearAll()
    }
}
