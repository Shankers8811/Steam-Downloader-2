package com.example.data.license

import com.example.data.model.DlcInfo
import com.example.data.model.DlcMode
import com.example.data.model.LicenseReport
import com.example.data.model.LogLevel
import com.example.data.model.LogLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Verifies that the signed-in Steam account actually holds licenses for the
 * content about to be downloaded — the same gate the Steam client applies.
 *
 *  - Base app: license must exist (or the app is free-to-play).
 *  - DLC: every single one is checked against the account (some DLC have to be
 *    bought separately). Unlicensed DLC is excluded from the download plan and
 *    reported as "purchase required" instead of silently failing mid-download.
 */
class LicenseValidator(
    private val httpClient: OkHttpClient,
    /** true/false = definitive answer from Steam, null = could not verify. */
    private val ownershipChecker: suspend (appId: Int) -> Boolean?
) {

    suspend fun validate(
        appId: Int,
        appNameHint: String,
        dlcMode: DlcMode,
        log: (LogLine) -> Unit
    ): LicenseReport = withContext(Dispatchers.IO) {

        log(LogLine(LogLevel.INFO, "Requesting store package info for app $appId…"))
        val details = fetchStoreDetails(listOf(appId))
        val info = details[appId]

        if (info == null) {
            log(LogLine(LogLevel.WARN, "Store returned no metadata for app $appId — treating as base-game-only."))
        } else {
            log(LogLine(LogLevel.OK, "Store info received: \"${info.name}\" (${info.type})"))
        }

        val resolvedName = info?.name?.takeIf { it.isNotBlank() && it != "App $appId" } ?: appNameHint
        val isFree = info?.isFree == true

        // ---------------- Base game license ----------------
        var baseLicensed = isFree
        var baseUnverified = false
        if (isFree) {
            log(LogLine(LogLevel.OK, "Free-to-play title — license granted automatically."))
        } else {
            log(LogLine(LogLevel.INFO, "Checking base game license for \"$resolvedName\"…"))
            when (ownershipChecker(appId)) {
                true -> {
                    baseLicensed = true
                    log(LogLine(LogLevel.OK, "License confirmed: you own \"$resolvedName\"."))
                }
                false -> {
                    baseLicensed = false
                    log(LogLine(LogLevel.ERROR, "License missing: \"$resolvedName\" is not on this account."))
                }
                null -> {
                    baseLicensed = false
                    baseUnverified = true
                    log(LogLine(LogLevel.WARN, "Web license check unavailable — Steam's download servers will enforce ownership directly."))
                }
            }
        }

        // ---------------- DLC licenses ----------------
        val licensedDlc = mutableListOf<DlcInfo>()
        val blockedDlc = mutableListOf<DlcInfo>()

        if (baseLicensed && dlcMode != DlcMode.BASE_ONLY && info != null && info.dlcIds.isNotEmpty()) {
            val dlcIds = info.dlcIds
            val trimmed = if (dlcIds.size > MAX_DLC_PROBED) {
                log(LogLine(LogLevel.WARN, "This app has ${dlcIds.size} DLC — validating the first $MAX_DLC_PROBED."))
                dlcIds.take(MAX_DLC_PROBED)
            } else {
                dlcIds
            }

            log(LogLine(LogLevel.INFO, "Validating licenses for ${trimmed.size} downloadable content item(s)…"))

            // Resolve DLC display names in one batched store call.
            val dlcNames = fetchStoreDetails(trimmed).mapValues { it.value.name }

            for (dlcId in trimmed) {
                val dlcName = dlcNames[dlcId]?.takeIf { it.isNotBlank() } ?: "DLC $dlcId"
                when (ownershipChecker(dlcId)) {
                    true -> {
                        licensedDlc.add(DlcInfo(dlcId, dlcName))
                        log(LogLine(LogLevel.OK, "DLC license OK: $dlcName"))
                    }
                    false -> {
                        blockedDlc.add(DlcInfo(dlcId, dlcName))
                        log(LogLine(LogLevel.WARN, "DLC \"$dlcName\" is not licensed — purchase required, excluded from download."))
                    }
                    null -> {
                        blockedDlc.add(DlcInfo(dlcId, dlcName))
                        log(LogLine(LogLevel.WARN, "Could not verify DLC \"$dlcName\" — excluded for safety."))
                    }
                }
                // Be gentle with Steam's license endpoint — spread calls even
                // for short DLC lists (audit fix: rapid bursts were seen to
                // trip rate limiting on some accounts).
                if (trimmed.size > 4) delay(90L)
                else if (trimmed.size > 1) delay(30L)
            }
        } else if (dlcMode != DlcMode.BASE_ONLY && (info == null || info.dlcIds.isEmpty())) {
            log(LogLine(LogLevel.INFO, "No downloadable content attached to this app."))
        }

        LicenseReport(
            appId = appId,
            appName = resolvedName,
            storeType = info?.type ?: "unknown",
            baseLicensed = baseLicensed,
            baseUnverified = baseUnverified,
            isFreeToPlay = isFree,
            licensedDlc = licensedDlc,
            blockedDlc = blockedDlc
        )
    }

    // ------------------------------------------------------------------
    // Steam Store metadata (name / type / is_free / dlc list)
    // ------------------------------------------------------------------

    private data class StoreAppInfo(
        val appId: Int,
        val name: String,
        val type: String,
        val isFree: Boolean,
        val dlcIds: List<Int>
    )

    private fun fetchStoreDetails(appIds: List<Int>): Map<Int, StoreAppInfo> {
        if (appIds.isEmpty()) return emptyMap()
        val url = STORE_APP_DETAILS +
            "?appids=" + appIds.joinToString(",") +
            "&filters=name,type,is_free,dlc"

        return try {
            val response = httpClient.newCall(Request.Builder().url(url).get().build()).execute()
            if (response.code != 200) return emptyMap()
            val raw = response.body?.string() ?: return emptyMap()
            val root = JSONObject(raw)
            val result = mutableMapOf<Int, StoreAppInfo>()
            for (appId in appIds) {
                val node = root.optJSONObject(appId.toString()) ?: continue
                if (!node.optBoolean("success", false)) continue
                val data = node.optJSONObject("data") ?: continue
                val dlcList = mutableListOf<Int>()
                val dlcArray = data.optJSONArray("dlc")
                if (dlcArray != null) {
                    for (i in 0 until dlcArray.length()) {
                        dlcList.add(dlcArray.optInt(i))
                    }
                }
                result[appId] = StoreAppInfo(
                    appId = appId,
                    name = data.optString("name", "App $appId"),
                    type = data.optString("type", "game"),
                    isFree = data.optBoolean("is_free", false),
                    dlcIds = dlcList
                )
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    companion object {
        private const val STORE_APP_DETAILS = "https://store.steampowered.com/api/appdetails"
        private const val MAX_DLC_PROBED = 64
    }
}
