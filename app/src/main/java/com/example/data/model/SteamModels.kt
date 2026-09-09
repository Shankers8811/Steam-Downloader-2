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
        return if (!imgIconUrl.isNullOrBlank()) {
            "https://media.steampowered.com/steamcommunity/public/images/apps/$appid/$imgIconUrl.jpg"
        } else {
            getHeaderImageUrl()
        }
    }
}

/** IUserService/CheckAppOwnership — per-app license lookup for the signed-in account. */
@JsonClass(generateAdapter = true)
data class CheckAppOwnershipResponse(
    @Json(name = "response") val response: AppOwnershipWrap?
)

@JsonClass(generateAdapter = true)
data class AppOwnershipWrap(
    @Json(name = "appownership") val appOwnership: AppOwnership?
)

@JsonClass(generateAdapter = true)
data class AppOwnership(
    @Json(name = "ownsapp") val ownsApp: Boolean? = null,
    @Json(name = "permanent") val permanent: Boolean? = null,
    @Json(name = "ownersteamid") val ownerSteamId: String? = null
)

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

// ------------------------------------------------------------------
// Engine / log / license domain models
// ------------------------------------------------------------------

enum class LogLevel { INFO, OK, WARN, ERROR }

data class LogLine(
    val level: LogLevel,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/** A single piece of downloadable content attached to a base app. */
data class DlcInfo(
    val appId: Int,
    val name: String
)

/**
 * Result of license validation for one app, performed exactly like the Steam
 * client does before it starts downloading: the base app license is verified
 * first, and every DLC the account does NOT own is excluded and flagged as
 * requiring a separate purchase.
 */
data class LicenseReport(
    val appId: Int,
    val appName: String,
    val storeType: String,
    val baseLicensed: Boolean,
    val isFreeToPlay: Boolean,
    val licensedDlc: List<DlcInfo>,
    val blockedDlc: List<DlcInfo>
)
