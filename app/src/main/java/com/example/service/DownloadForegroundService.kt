package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.db.AppDatabase
import com.example.data.db.DownloadTaskEntity
import com.example.bridge.DepotDownloaderBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DownloadForegroundService : Service() {

    private val binder = LocalBinder()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    lateinit var bridge: DepotDownloaderBridge
        private set

    private var wakeLock: PowerManager.WakeLock? = null
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "depot_downloader_channel"

    inner class LocalBinder : Binder() {
        fun getService(): DownloadForegroundService = this@DownloadForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        bridge = DepotDownloaderBridge(applicationContext)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DepotDownloader:ServiceWakeLock"
        )

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START -> {
                acquireWakeLock()
                val notification = buildNotification("Initializing download...", 0, "0 MB/s")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
            ACTION_PAUSE -> {
                bridge.pauseDownload()
                updateNotification("Download Paused", 0, "0 MB/s", isPaused = true)
                releaseWakeLock()
            }
            ACTION_CANCEL -> {
                bridge.cancelDownload()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    fun startDownloadTask(
        task: DownloadTaskEntity,
        username: String,
        password: String,
        twoFactorCode: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        acquireWakeLock()
        val initialNotif = buildNotification("Downloading ${task.appName}...", 0, "0 MB/s")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotif)
        }

        val db = AppDatabase.getDatabase(applicationContext)

        bridge.startDownload(
            task = task,
            username = username,
            password = password,
            twoFactorCode = twoFactorCode,
            onProgressUpdate = { updatedTask ->
                serviceScope.launch {
                    db.downloadTaskDao().updateTask(updatedTask)
                }
                updateNotification(
                    title = "Downloading ${updatedTask.appName}",
                    progress = updatedTask.progressPercent.toInt(),
                    speed = updatedTask.downloadSpeed
                )
            },
            onComplete = { success, message ->
                releaseWakeLock()
                serviceScope.launch {
                    val finalStatus = if (success) "COMPLETED" else "FAILED"
                    db.downloadTaskDao().updateTask(
                        task.copy(
                            status = finalStatus,
                            progressPercent = if (success) 100f else task.progressPercent,
                            downloadSpeed = "0 MB/s"
                        )
                    )
                }
                val resultText = if (success) "Download Complete!" else "Download Failed: $message"
                updateNotification(resultText, 100, "", isCompleted = true)
                onComplete(success, message)
            }
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes timeout safety
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DepotDownloader Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live status of Steam game downloads."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        progress: Int,
        speed: String,
        isPaused: Boolean = false,
        isCompleted: Boolean = false
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DepotDownloader")
            .setContentText("$title ${if (speed.isNotBlank()) "• $speed" else ""}")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(!isCompleted)
            .setProgress(100, progress, progress == 0 && !isPaused && !isCompleted)

        if (!isCompleted) {
            if (isPaused) {
                val resumeIntent = Intent(this, DownloadForegroundService::class.java).apply { action = ACTION_START }
                val resumePending = PendingIntent.getService(this, 1, resumeIntent, PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(android.R.drawable.ic_media_play, "Resume", resumePending)
            } else {
                val pauseIntent = Intent(this, DownloadForegroundService::class.java).apply { action = ACTION_PAUSE }
                val pausePending = PendingIntent.getService(this, 2, pauseIntent, PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePending)
            }

            val cancelIntent = Intent(this, DownloadForegroundService::class.java).apply { action = ACTION_CANCEL }
            val cancelPending = PendingIntent.getService(this, 3, cancelIntent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPending)
        }

        return builder.build()
    }

    private fun updateNotification(
        title: String,
        progress: Int,
        speed: String,
        isPaused: Boolean = false,
        isCompleted: Boolean = false
    ) {
        val notification = buildNotification(title, progress, speed, isPaused, isCompleted)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.action.START"
        const val ACTION_PAUSE = "com.example.action.PAUSE"
        const val ACTION_CANCEL = "com.example.action.CANCEL"
    }
}
