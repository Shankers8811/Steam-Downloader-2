package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "steam_games")
data class SteamGameEntity(
    @PrimaryKey val appId: Int,
    val name: String,
    val playtimeForever: Int,
    val imgIconUrl: String,
    val imgHeaderUrl: String,
    val lastUpdated: Long = System.currentTimeMillis()
)
