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

    suspend fun fetchAndStoreGames(steamId: String, apiKey: String): Result<Int> {
        return try {
            val response = apiService.getOwnedGames(apiKey = apiKey, steamId = steamId)
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
}
