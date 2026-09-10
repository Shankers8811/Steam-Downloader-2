package com.example.data.steam

import android.content.Context
import android.os.Build
import com.example.CrashLog
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.steam.discovery.FileServerListProvider
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import `in`.dragonbra.javasteam.types.KeyValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.File
import java.util.EnumSet

/**
 * One depot from the app's PICS appinfo, already OS/arch-filtered for a
 * Windows install (this is what a phone should fetch, exactly like the
 * Windows Steam client).
 */
data class DepotPlanEntry(
    val depotId: Int,
    val name: String,
    /** Non-null when the depot belongs to a DLC app. */
    val dlcAppId: Int?,
    /** Compressed bytes Steam will push over the wire (drives network-rate math & ETA). */
    val downloadBytes: Long,
    /** Uncompressed installed size in bytes. */
    val sizeBytes: Long
)

/**
 * The native Steam client session — same architecture GameNative uses
 * (see docs/GAMENATIVE_NOTES.md):
 *
 *  1. A JavaSteam [SteamClient] connected over WebSocket to Valve's CM
 *     network, with an on-disk CM server-list cache.
 *  2. CM-logged-on account via the refresh token (no password re-entry).
 *  3. The account license list (arrives automatically after logon) — the
 *     ownership proof the native downloader enforces.
 *  4. PICS appinfo queries to resolve depot plans (depot ids, sizes, DLC
 *     mapping, branch manifests) before downloads.
 */
class SteamRuntime(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ------------------------------------------------------------------
    // JavaSteam objects
    // ------------------------------------------------------------------

    lateinit var client: SteamClient
        private set

    private var callbackManager: CallbackManager? = null
    private var callbackPumpJob: Job? = null
    private var autoReconnectJob: Job? = null
    private val subscriptions = mutableListOf<Closeable>()

    // ------------------------------------------------------------------
    // Observable state
    // ------------------------------------------------------------------

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _loggedOn = MutableStateFlow(false)
    val loggedOn: StateFlow<Boolean> = _loggedOn.asStateFlow()

    private val _accountSteamId = MutableStateFlow<String?>(null)
    val accountSteamId: StateFlow<String?> = _accountSteamId.asStateFlow()

    /** Account licenses received from CM after logon — proof of ownership. */
    private val _licenses = MutableStateFlow<List<License>>(emptyList())
    val licenses: StateFlow<List<License>> = _licenses.asStateFlow()

    private val _runtimeLog = MutableStateFlow<List<String>>(emptyList())
    val runtimeLog: StateFlow<List<String>> = _runtimeLog.asStateFlow()

    @Volatile
    private var expectConnected = false

    private val loginLock = Mutex()

    @Volatile
    private var pendingLogon = CompletableDeferred<LoggedOnCallback>()

    init {
        buildClient()
    }

    private fun buildClient() {
        val serverListFile = File(context.filesDir, "javasteam_serverlist.bin")

        val configuration = SteamConfiguration.create { config ->
            config.withProtocolTypes(EnumSet.of(ProtocolTypes.WEB_SOCKET))
            config.withServerListProvider(FileServerListProvider(serverListFile.toPath()))
        }

        client = SteamClient(configuration)
        callbackManager = CallbackManager(client)

        subscriptions.forEach { runCatching { it.close() } }
        subscriptions.clear()
        val cm = callbackManager!!
        subscriptions += cm.subscribe(ConnectedCallback::class.java) { onConnected() }
        subscriptions += cm.subscribe(DisconnectedCallback::class.java) { onDisconnected(it) }
        subscriptions += cm.subscribe(LoggedOnCallback::class.java) { onLoggedOn(it) }
        subscriptions += cm.subscribe(LoggedOffCallback::class.java) { onLoggedOff(it) }
        subscriptions += cm.subscribe(LicenseListCallback::class.java) { onLicenseList(it) }
    }

    // ------------------------------------------------------------------
    // Connection management
    // ------------------------------------------------------------------

    /** Connect the CM session (idempotent). */
    fun connect() {
        if (_connected.value) return
        expectConnected = true
        log("Connecting to Steam servers (WebSocket)…")
        runCatching { client.connect() }.onFailure {
            log("Connect attempt failed: ${it.message}")
        }
        startCallbackPump()
    }

    private fun startCallbackPump() {
        if (callbackPumpJob?.isActive == true) return
        callbackPumpJob = scope.launch {
            log("Callback pump started.")
            while (isActive) {
                try {
                    callbackManager?.runWaitCallbacks(1000L)
                } catch (e: Exception) {
                    log("Callback pump error: ${e.message}")
                    CrashLog.record("callback-pump", e)
                    delay(500L)
                } catch (t: Throwable) {
                    // JavaSteam callback machinery can throw Errors (linkage /
                    // serviceloader family) — record and keep the pump alive
                    // rather than silently freezing the whole CM connection.
                    log("Callback pump ERROR: ${t.javaClass.simpleName}: ${t.message}")
                    CrashLog.record("callback-pump (Error)", t)
                    try {
                        delay(2_000L)
                    } catch (ce: CancellationException) {
                        throw ce
                    }
                }
            }
        }
    }

    fun disconnect() {
        expectConnected = false
        autoReconnectJob?.cancel()
        runCatching { client.disconnect() }
        _connected.value = false
        _loggedOn.value = false
    }

    private fun onConnected() {
        _connected.value = true
        log("Connected to a Steam CM server.")
    }

    private fun onDisconnected(callback: DisconnectedCallback) {
        _connected.value = false
        _loggedOn.value = false
        log("Disconnected from Steam${if (callback.isUserInitiated) " (by request)" else ""}.")

        if (!expectConnected || callback.isUserInitiated) return

        // Auto-reconnect with exponential backoff (same recovery pattern
        // GameNative uses). Endpoint blacklisting is skipped deliberately:
        // the public API only exposes marking for WebSocket endpoints and
        // mis-marking TCP endpoints would poison the cached server list.
        if (autoReconnectJob?.isActive != true) {
            autoReconnectJob = scope.launch {
                var delayMs = 2_000L
                while (isActive && expectConnected && !_connected.value) {
                    log("Reconnecting to Steam in ${delayMs / 1000}s…")
                    delay(delayMs)
                    runCatching { client.connect() }
                    delayMs = (delayMs * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Logon / logoff
    // ------------------------------------------------------------------

    private fun onLoggedOn(callback: LoggedOnCallback) {
        if (callback.result == EResult.OK) {
            _loggedOn.value = true
            _accountSteamId.value = callback.clientSteamID?.convertToUInt64()?.toString()
            log("Signed in to Steam CM (${callback.clientSteamID?.render() ?: "?"}).")
        } else {
            val extended = callback.extendedResult?.let { " / $it" } ?: ""
            log("CM logon failed: ${callback.result}$extended")
        }
        val pending = pendingLogon
        if (pending.isActive) pending.complete(callback)
    }

    private fun onLoggedOff(callback: LoggedOffCallback) {
        _loggedOn.value = false
        _licenses.value = emptyList()
        licensedAppIdsCache = null
        log("Signed off from CM (${callback.result}).")
    }

    private fun onLicenseList(callback: LicenseListCallback) {
        _licenses.value = callback.licenseList
        log("Received account licenses: ${callback.licenseList.size} package(s).")
    }

    /** Wait for the account license list (arrives right after logon). */
    suspend fun waitLicenses(timeoutMs: Long = 20_000L): Boolean {
        if (_licenses.value.isNotEmpty()) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (_licenses.value.isNotEmpty()) return true
            delay(250L)
        }
        return _licenses.value.isNotEmpty()
    }

    /**
     * Wait until the runtime is CM-logged-on (used before starting downloads).
     */
    suspend fun waitLoggedOn(timeoutMs: Long = 20_000L): Boolean {
        if (_loggedOn.value) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (_loggedOn.value) return true
            delay(200L)
        }
        return _loggedOn.value
    }

    // ------------------------------------------------------------------
    // High-level session operations
    // ------------------------------------------------------------------

    /**
     * CM-logon using the account's long-lived refresh token — this is how the
     * login is restored without re-entering a password or 2FA code.
     */
    suspend fun logonWithToken(accountName: String, token: String): EResult = loginLock.withLock {
        pendingLogon = CompletableDeferred()
        connect()
        delayUntilConnected(12_000L)
        if (!_connected.value) return@withLock EResult.TryAnotherCM

        val user = requireNotNull(client.getHandler(SteamUser::class.java)) {
            "SteamUser handler unavailable — SteamClient not initialized"
        }

        // Re-login hygiene (SteamKit pattern): if a previous CM login is still
        // active, log off first so the callback can't fire against our old
        // deferred and certificate/token state is rebuilt cleanly.
        if (_loggedOn.value) {
            runCatching { user.logOff() }
            _loggedOn.value = false
            delay(400L)
        }
        val details = LogOnDetails().apply {
            username = accountName.trim()
            accessToken = token
            shouldRememberPassword = true
            loginID = stableLoginId()
            machineName = machineName()
        }
        user.logOn(details)

        val callback = waitLogon(30_000L)
        callback?.result ?: EResult.Timeout
    }

    private suspend fun waitLogon(timeoutMs: Long): LoggedOnCallback? =
        try {
            withTimeout(timeoutMs) { pendingLogon.await() }
        } catch (e: Exception) {
            null
        }

    private suspend fun delayUntilConnected(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!_connected.value && System.currentTimeMillis() < deadline) delay(150L)
    }

    /**
     * Fetches the app's PICS depot list filtered to the same selection the
     * Windows Steam client would install (windows / x64 / language / LV).
     */
    suspend fun fetchDepotPlan(appId: Int, branch: String, language: String?): List<DepotPlanEntry>? =
        withContext(Dispatchers.IO) {
            try {
                val apps = requireNotNull(client.getHandler(SteamApps::class.java)) {
                    "SteamApps handler unavailable — SteamClient not initialized"
                }
                val callback = apps.picsGetProductInfo(
                    apps = listOf(PICSRequest(id = appId)),
                    packages = emptyList()
                ).await()

                val info = callback.results.firstOrNull()?.apps?.get(appId)
                    ?: return@withContext null
                parseDepotPlan(info.keyValues, branch, language)
            } catch (e: Exception) {
                log("PICS query failed for app $appId: ${e.message}")
                null
            }
        }

    /** App's display name straight from PICS appinfo. */
    suspend fun fetchAppName(appId: Int): String? = withContext(Dispatchers.IO) {
        try {
            val apps = requireNotNull(client.getHandler(SteamApps::class.java)) {
                "SteamApps handler unavailable — SteamClient not initialized"
            }
            val callback = apps.picsGetProductInfo(
                apps = listOf(PICSRequest(id = appId)),
                packages = emptyList()
            ).await()
            callback.results.firstOrNull()?.apps?.get(appId)
                ?.keyValues?.get("common")?.get("name")?.value
        } catch (e: Exception) {
            null
        }
    }

    /** Cached set of every app id the account's licensed packages entitle it to. */
    @Volatile
    private var licensedAppIdsCache: Set<Int>? = null

    suspend fun getLicensedAppIds(): Set<Int> = licensedAppIdsCache
        ?: resolveLicensedAppIds().also { licensedAppIdsCache = it }

    /** Phase-1 of the library scan, split out for DLC ownership checks. */
    private suspend fun resolveLicensedAppIds(): Set<Int> = withContext(Dispatchers.IO) {
        val packageIds = _licenses.value.map { it.packageID }.distinct().sorted()
        if (packageIds.isEmpty()) return@withContext emptySet()

        val appsH = requireNotNull(client.getHandler(SteamApps::class.java)) {
            "SteamApps handler unavailable — SteamClient not initialized"
        }

        val grantedAppIds = sortedSetOf<Int>()
        packageIds.chunked(LIBRARY_BATCH).forEachIndexed { index, chunk ->
            val rs = try {
                withTimeoutOrNull(LIBRARY_TIMEOUT_MS) {
                    appsH.picsGetProductInfo(
                        apps = emptyList(),
                        packages = chunk.map { PICSRequest(id = it) }
                    ).await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Library: package batch ${index + 1} failed (${e.message})")
                null
            } catch (t: Throwable) {
                CrashLog.record("library packages batch", t)
                null
            }
            rs?.results?.forEach { info ->
                info.packages.values.forEach { pkg ->
                    pkg.keyValues["appids"].children.forEach { child ->
                        val id = child.value?.trim()?.toIntOrNull()
                            ?: child.name?.trim()?.toIntOrNull()
                        if (id != null && id > 0) grantedAppIds += id
                    }
                }
            }
        }
        grantedAppIds
    }

    /**
     * Builds the complete owned-games inventory exactly the way the Steam
     * client does: account licenses (CM) -> licensed packages -> app ids ->
     * PICS metadata filtered to type == "Game".
     *
     * DLC / soundtracks / tools / dedicated servers are excluded at the
     * source (their common.type is not "game"). Unlike
     * IPlayerService/GetOwnedGames this also includes never-played free
     * titles, i.e. NO silent missing games.
     *
     * @return (appId, displayName) pairs; empty when the CM license list
     *         isn't populated yet (caller decides on a web-API fallback).
     */
    suspend fun buildOwnedGameLibrary(): List<Pair<Int, String>> = withContext(Dispatchers.IO) {
        if (_licenses.value.isEmpty()) {
            log("Native library build skipped — CM license list is empty.")
            return@withContext emptyList()
        }

        val appsH = requireNotNull(client.getHandler(SteamApps::class.java)) {
            "SteamApps handler unavailable — SteamClient not initialized"
        }

        // ---------- Phase 1+2 (phase 1 shared with the DLC ownership cache).
        val grantedAppIds = getLicensedAppIds().toSortedSet()
        if (grantedAppIds.isEmpty()) {
            log("Library: licenses resolved to zero apps.")
            return@withContext emptyList()
        }

        // ---------- Phase 2: PICS metadata per app, keep real games only.
        val owned = linkedMapOf<Int, String>()
        grantedAppIds.chunked(LIBRARY_BATCH).forEachIndexed { index, chunk ->
            val rs = try {
                withTimeoutOrNull(LIBRARY_TIMEOUT_MS) {
                    appsH.picsGetProductInfo(
                        apps = chunk.map { PICSRequest(id = it) },
                        packages = emptyList()
                    ).await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Library: app batch ${index + 1} failed (${e.message})")
                null
            } catch (t: Throwable) {
                CrashLog.record("library apps batch", t)
                null
            }
            rs?.results?.forEach { info ->
                info.apps.forEach { (appId, product) ->
                    val common = product.keyValues["common"]
                    val type = common["type"].value.orEmpty().lowercase()
                    if (type == "game") {
                        val name = common["name"].value?.takeIf { it.isNotBlank() }
                            ?: "App $appId"
                        owned[appId] = name
                    }
                }
            }
        }
        log(
            "Native library: ${owned.size} games from ${grantedAppIds.size} apps, " +
                "${_licenses.value.size} licenses (DLC/tools excluded by type)."
        )
        owned.entries.map { it.key to it.value }
    }

    data class DlcEntry(val appId: Int, val name: String, val owned: Boolean)

    /**
     * DLC list for a game with real display names and per-DLC ownership
     * resolved from the account's licensed packages — the base for the
     * "download each owned DLC whenever you want, one by one" details page.
     */
    suspend fun fetchGameDlcList(baseAppId: Int): List<DlcEntry> = withContext(Dispatchers.IO) {
        val appsH = requireNotNull(client.getHandler(SteamApps::class.java)) {
            "SteamApps handler unavailable — SteamClient not initialized"
        }

        val baseInfo = (try {
            withTimeoutOrNull(LIBRARY_TIMEOUT_MS) {
                appsH.picsGetProductInfo(
                    apps = listOf(PICSRequest(id = baseAppId)),
                    packages = emptyList()
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("DLC: base-app PICS query failed ($baseAppId: ${e.message})")
            null
        } catch (t: Throwable) {
            CrashLog.record("fetchGameDlcList base", t)
            null
        })?.results?.firstOrNull()?.apps?.get(baseAppId) ?: return@withContext emptyList()

        val dlcIds = sortedSetOf<Int>()
        baseInfo.keyValues["common"]["dlc"].children.forEach { child ->
            child.name?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { dlcIds += it }
        }
        baseInfo.keyValues["depots"]["dlc"].children.forEach { child ->
            child.name?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { dlcIds += it }
        }
        if (dlcIds.isEmpty()) return@withContext emptyList()

        val licensed = try {
            getLicensedAppIds()
        } catch (e: Exception) {
            CrashLog.record("fetchGameDlcList licensed ids", e)
            emptySet()
        }

        val result = mutableListOf<DlcEntry>()
        dlcIds.chunked(LIBRARY_BATCH).forEach { chunk ->
            val rs = try {
                withTimeoutOrNull(LIBRARY_TIMEOUT_MS) {
                    appsH.picsGetProductInfo(
                        apps = chunk.map { PICSRequest(id = it) },
                        packages = emptyList()
                    ).await()
                }
            } catch (e: Exception) {
                log("DLC: batch query failed (${e.message})")
                null
            } catch (t: Throwable) {
                CrashLog.record("fetchGameDlcList batch", t)
                null
            }
            rs?.results?.forEach { info ->
                info.apps.forEach { (dlcId, product) ->
                    result += DlcEntry(
                        appId = dlcId,
                        name = product.keyValues["common"]["name"].value
                            ?.takeIf { it.isNotBlank() } ?: "DLC $dlcId",
                        owned = licensed.contains(dlcId)
                    )
                }
            }
        }
        result.sortedWith(compareByDescending<DlcEntry> { it.owned }.thenBy { it.name.lowercase() })
    }

    private fun parseDepotPlan(        keyValues: KeyValue,
        branch: String,
        language: String?
    ): List<DepotPlanEntry> {
        val result = mutableListOf<DepotPlanEntry>()
        val depots = keyValues["depots"]
        for (depot in depots.children) {
            val depotId = depot.name?.toIntOrNull() ?: continue

            // Skip non-depot metadata sections (branches, workshopdepot, …).
            if (depot["manifests"].children.isEmpty() &&
                depot["depotfromapp"] == KeyValue.INVALID
            ) continue

            val config = depot["config"]
            if (config != KeyValue.INVALID) {
                val osList = config["oslist"].value
                if (!osList.isNullOrBlank() &&
                    osList.split(',').none { it.trim().equals("windows", ignoreCase = true) }
                ) continue

                val osArch = config["osarch"].value
                if (!osArch.isNullOrBlank() && osArch.trim() != "64") continue

                val lang = config["language"].value
                if (!lang.isNullOrBlank() && !(language ?: "english").equals(lang, ignoreCase = true)) continue

                if (config["lowviolence"].asBoolean()) continue
            }

            val manifests = depot["manifests"][branch]
            if (manifests["gid"].asLong(0L) == 0L) continue // branch missing on this depot

            val dlcApp = config["dlcappid"].asInteger(0).takeIf { it > 0 }
            result.add(
                DepotPlanEntry(
                    depotId = depotId,
                    name = depot["name"].value ?: "Depot $depotId",
                    dlcAppId = dlcApp,
                    downloadBytes = manifests["download"].asLong(0L),
                    sizeBytes = manifests["size"].asLong(0L)
                )
            )
        }
        return result
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    private fun stableLoginId(): Int = try {
        val prefs = context.getSharedPreferences("depot_runtime_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getInt("login_id", 0)
        if (existing != 0) existing
        else {
            val fresh = (100000..999999).random()
            prefs.edit().putInt("login_id", fresh).apply()
            fresh
        }
    } catch (e: Exception) {
        13579
    }

    private fun machineName(): String = "${Build.MANUFACTURER} ${Build.MODEL} (DepotDownloader)"

    fun log(message: String) {
        val current = _runtimeLog.value.toMutableList()
        current.add(message)
        if (current.size > 120) current.removeAt(0)
        _runtimeLog.value = current
    }

    fun shutdown() {
        expectConnected = false
        autoReconnectJob?.cancel()
        callbackPumpJob?.cancel()
        subscriptions.forEach { runCatching { it.close() } }
        subscriptions.clear()
        runCatching { client.disconnect() }
    }

    companion object {
        /** Max ids per PICS request during the library scan (safe server cap). */
        private const val LIBRARY_BATCH = 100

        /** Per-request cap so one dead PICS reply can't hang the whole scan. */
        private const val LIBRARY_TIMEOUT_MS = 20_000L
    }
}
