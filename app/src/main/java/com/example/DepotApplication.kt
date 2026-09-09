package com.example

import android.app.Application
import android.content.Context
import com.example.data.api.SteamApiService
import com.example.data.auth.CredentialVault
import com.example.data.auth.SteamAuthManager
import com.example.data.db.AppDatabase
import com.example.data.download.DepotDownloadManager
import com.example.data.repository.SteamRepository
import com.example.data.repository.UserPreferencesRepository
import com.example.data.steam.SteamRuntime

/**
 * Application class acting as the single, app-scoped service locator.
 *
 * Holds long-lived singletons (auth session, native Steam CM runtime,
 * download engine, database) so the login session and an active / paused
 * download survive navigation and UI recreation, exactly like the Steam
 * desktop client.
 */
class DepotApplication : Application() {

    lateinit var prefs: UserPreferencesRepository
        private set

    lateinit var database: AppDatabase
        private set

    lateinit var steamApi: SteamApiService
        private set

    /** Native Steam client (CM protocol connection + licenses + PICS). */
    lateinit var steamRuntime: SteamRuntime
        private set

    lateinit var authManager: SteamAuthManager
        private set

    lateinit var steamRepository: SteamRepository
        private set

    lateinit var downloadManager: DepotDownloadManager
        private set

    override fun onCreate() {
        super.onCreate()

        prefs = UserPreferencesRepository(this)
        database = AppDatabase.getDatabase(this)
        steamApi = SteamApiService.create()

        // Native CM runtime first — auth and downloads both ride on it.
        steamRuntime = SteamRuntime(this)

        authManager = SteamAuthManager(
            vault = CredentialVault(this),
            runtime = steamRuntime,
            onSessionEstablished = { session -> prefs.saveSteamUsername(session.accountName) }
        )

        steamRepository = SteamRepository(steamApi, database.steamGameDao())
        downloadManager = DepotDownloadManager(
            context = this,
            runtime = steamRuntime,
            api = steamApi
        )

        // Restore a remembered Steam session (kept login) if one exists.
        authManager.restoreSavedSession()
    }

    companion object {
        fun get(context: Context): DepotApplication =
            context.applicationContext as DepotApplication
    }
}
