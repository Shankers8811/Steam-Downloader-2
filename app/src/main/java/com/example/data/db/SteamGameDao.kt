package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SteamGameDao {
    @Query("SELECT * FROM steam_games ORDER BY name ASC")
    fun getAllGames(): Flow<List<SteamGameEntity>>

    @Query("SELECT * FROM steam_games WHERE name LIKE '%' || :query || '%' OR appId LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchGames(query: String): Flow<List<SteamGameEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGames(games: List<SteamGameEntity>)

    @Query("DELETE FROM steam_games")
    suspend fun clearAll()
}
