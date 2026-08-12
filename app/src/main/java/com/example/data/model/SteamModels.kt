package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GetOwnedGamesResponse(
    @Json(name = "response") val response: OwnedGamesData?
)

@JsonClass(generateAdapter = true)
data class OwnedGamesData(
    @Json(name = "game_count") val gameCount: Int? = 0,
    @Json(name = "games") val games: List<SteamGameDto>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class SteamGameDto(
    @Json(name = "appid") val appid: Int,
    @Json(name = "name") val name: String? = null,
    @Json(name = "playtime_forever") val playtimeForever: Int? = 0,
    @Json(name = "img_icon_url") val imgIconUrl: String? = null,
    @Json(name = "has_community_visible_stats") val hasCommunityVisibleStats: Boolean? = false
) {
    fun getHeaderImageUrl(): String {
        return "https://cdn.akamai.steamstatic.com/steam/apps/$appid/header.jpg"
    }

    fun getIconImageUrl(): String {
        return if (!imgIconUrl.isNull_or_empty()) {
            "https://media.steampowered.com/steamcommunity/public/images/apps/$appid/$imgIconUrl.jpg"
        } else {
            getHeaderImageUrl()
        }
    }
}

private fun String?.isNull_or_empty(): Boolean = this == null || this.trim().isEmpty()

enum class DlcMode {
    BASE_ONLY,
    BASE_AND_DLC,
    DLC_ONLY
}

enum class DownloadStatus {
    IDLE,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
