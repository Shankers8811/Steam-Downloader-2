package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.DepotApplication
import com.example.MainActivity
import com.example.data.download.DepotDownloadManager
import com.example.data.download.DownloadSessionState
import com.example.data.download.SessionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the download session alive while the app is in the background and
 * mirrors Steam-style progress (size, speed, ETA) into a notification with
 * Pause / Resume / Cancel actions wired straight into the engine.
 */
class DownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val manager: DepotDownloadManager
        get() = DepotApplication.get(this).downloadManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> manager.pause()
            ACTION_RESUME -> {
                manager.resume { null }
                startCollecting()
                return START_STICKY
            }
            ACTION_CANCEL -> manager.cancel()
        }

        acquireWakeLock()
        startForegroundCompat(buildNotification(manager.state.value))
        startCollecting()
        return START_STICKY
    }

    private fun startCollecting() {
        if (collectJob?.isActive == true) return
        collectJob = serviceScope.launch {
            manager.state.collect { state ->
                updateNotification(state)
                when (state.phase) {
                    SessionPhase.COMPLETED, SessionPhase.FAILED, SessionPhase.CANCELLED -> {
                        delay(TERMINAL_NOTIFICATION_MS)
                        stopSelfSafely()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun stopSelfSafely() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(state: DownloadSessionState) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: DownloadSessionState): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = when {
            state.appName.isBlank() -> "DepotDownloader"
            state.phase == SessionPhase.PAUSED -> "${state.appName} — paused"
            state.phase == SessionPhase.COMPLETED -> "${state.appName} — download complete"
            state.phase == SessionPhase.FAILED -> "${state.appName} — failed"
            state.phase == SessionPhase.CANCELLED -> "${state.appName} — cancelled"
            state.phase == SessionPhase.VALIDATING_LICENSE -> "${state.appName} — validating licenses…"
            state.phase == SessionPhase.VERIFYING -> "${state.appName} — verifying…"
            else -> "${state.appName} — downloading"
        }

        val text = when (state.phase) {
            SessionPhase.DOWNLOADING ->
                "${DepotDownloadManager.formatBytes(state.downloadedBytes)} / " +
                    "${DepotDownloadManager.formatBytes(state.totalBytes)}  •  " +
                    "${DepotDownloadManager.formatBytes(state.networkBytesPerSec.toLong())}/s" +
                    if (state.etaSeconds >= 0) "  •  ETA ${formatEtaShort(state.etaSeconds)}" else ""
            SessionPhase.PAUSED -> "Paused at chunk boundary — files safe to move"
            SessionPhase.COMPLETED -> DepotDownloadManager.formatBytes(state.totalBytes) + " installed"
            else -> state.statusMessage
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(state.isEngineActive || state.phase == SessionPhase.PAUSED)
            .setOnlyAlertOnce(true)
            .setProgress(100, state.progressPercent.toInt(), state.progressPercent <= 0f && state.isEngineActive)

        when {
            state.phase == SessionPhase.DOWNLOADING -> {
                builder.addAction(
                    0, "Pause",
                    servicePendingIntent(1, ACTION_PAUSE)
                )
                builder.addAction(0, "Cancel", servicePendingIntent(2, ACTION_CANCEL))
            }
            state.phase == SessionPhase.PAUSED -> {
                builder.addAction(0, "Resume", servicePendingIntent(3, ACTION_RESUME))
                builder.addAction(0, "Cancel", servicePendingIntent(2, ACTION_CANCEL))
            }
            state.isEngineActive -> {
                builder.addAction(0, "Cancel", servicePendingIntent(2, ACTION_CANCEL))
            }
        }
        return builder.build()
    }

    private fun servicePendingIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, DownloadForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld != true) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "depot:download")
            wakeLock?.acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live status of Steam game downloads"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        collectJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun formatEtaShort(seconds: Long): String {
        if (seconds < 0) return "--:--"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    companion object {
        const val ACTION_START = "com.example.action.START_DOWNLOAD"
        const val ACTION_PAUSE = "com.example.action.PAUSE_DOWNLOAD"
        const val ACTION_RESUME = "com.example.action.RESUME_DOWNLOAD"
        const val ACTION_CANCEL = "com.example.action.CANCEL_DOWNLOAD"

        private const val CHANNEL_ID = "depot_downloads"
        private const val NOTIFICATION_ID = 1001
        private const val TERMINAL_NOTIFICATION_MS = 3500L
    }
}
