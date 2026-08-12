package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val appId: Int,
    val appName: String,
    val depotIds: String = "",
    val manifestId: String = "",
    val branch: String = "public",
    val dlcMode: String = "BASE_ONLY",
    val dlcDepotId: String = "",
    val includeDlc: Boolean = false,
    val targetUriString: String = "",
    val targetPathDisplay: String = "",
    val status: String = "IDLE",
    val progressPercent: Float = 0f,
    val downloadSpeed: String = "0 MB/s",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val totalSizeFormatted: String = "0 MB",
    val logs: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
