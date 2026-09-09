package com.example.data.repository

import com.example.data.api.SteamApiService
import com.example.data.db.SteamGameDao
import com.example.data.db.SteamGameEntity
import kotlinx.coroutines.flow.Flow

class SteamRepository(
    private val apiService: SteamApiService,
    private val steamGameDao: SteamGameDao
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
     * Fetches the signed-in user's owned games using the access token from the
     * Steam login flow, and refreshes the local Room cache.
     */
    suspend fun fetchAndStoreGames(steamId: String, accessToken: String): Result<Int> {
        return try {
            val response = apiService.getOwnedGames(accessToken = accessToken, steamId = steamId)
            val gamesList = response.response?.games ?: emptyList()

            val entities = gamesList.map { dto ->
                SteamGameEntity(
                    appId = dto.appid,
                    name = dto.name ?: "App ${dto.appid}",
                    playtimeForever = dto.playtimeForever ?: 0,
                    imgIconUrl = dto.getIconImageUrl(),
                    imgHeaderUrl = dto.getHeaderImageUrl()
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
