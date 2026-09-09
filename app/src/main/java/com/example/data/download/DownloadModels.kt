package com.example.data.download

import com.example.data.model.DlcMode
import com.example.data.model.LicenseReport
import com.example.data.model.LogLine

/** Steam content is transferred in fixed-size chunks; mirrors the 1 MiB depot chunk size. */
const val STEAM_CHUNK_SIZE: Int = 1024 * 1024

fun chunkCountForSize(sizeBytes: Long): Int =
    if (sizeBytes <= 0L) 1 else ((sizeBytes + STEAM_CHUNK_SIZE - 1L) / STEAM_CHUNK_SIZE).toInt()

enum class SessionPhase {
    IDLE,
    VALIDATING_LICENSE,
    ALLOCATING,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class DepotFileStatus {
    PENDING,
    DOWNLOADING,
    COMPLETE
}

/** UI-visible progress for one file inside the depot. */
data class DepotFileProgress(
    val relPath: String,
    val fileName: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val chunksDone: Int,
    val chunkCount: Int,
    val status: DepotFileStatus
) {
    val progressFraction: Float
        get() = if (totalBytes <= 0L) 1f else downloadedBytes.toFloat() / totalBytes.toFloat()
}

/** Everything the user picked on the download configuration card. */
data class DownloadRequest(
    val appId: Int,
    val appName: String,
    val branch: String,
    val dlcMode: DlcMode,
    val depotIds: String,
    val dlcDepotId: String
)

/**
 * Full observable state of the (single) download session — drives the whole
 * downloader UI, including Steam-style live statistics.
 *
 * Integrity contract (this is what makes moving files to a PC safe):
 *  - Bytes only ever land in staging at exact chunk boundaries.
 *  - A file appears in the destination folder ONLY after it is 100%
 *    downloaded, streamed through SHA-256 and atomically renamed from
 *    "*.downloading" to its final name.
 *  - Pause stops at the next chunk boundary, flushes and re-truncates the
 *    partial file to the last completed chunk, then persists session.json.
 *  So a "partially downloaded" visible output file can never exist.
 */
data class DownloadSessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val appId: Int = 0,
    val appName: String = "",
    val branch: String = "public",
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,

    // Steam-style live statistics
    val networkBytesPerSec: Double = 0.0,
    val diskBytesPerSec: Double = 0.0,
    val networkPeakBytesPerSec: Double = 0.0,
    val etaSeconds: Long = -1L,
    val elapsedSeconds: Long = 0L,

    val currentFile: String = "",
    val currentFileChunkIndex: Int = 0,
    val currentFileChunkCount: Int = 0,
    val completeFileCount: Int = 0,
    val fileCount: Int = 0,

    val licenseReport: LicenseReport? = null,
    val outputDisplay: String = "",

    /** True when every file visible in the destination is complete & verified. */
    val safeToMove: Boolean = false,
    /** True when on-disk staging exists for [appId] (resumable after restart). */
    val hasResumableSession: Boolean = false,

    val statusMessage: String = "Idle",
    val errorMessage: String? = null,
    val logLines: List<LogLine> = emptyList(),
    val files: List<DepotFileProgress> = emptyList()
) {
    val progressPercent: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toDouble() * 100.0 / totalBytes.toDouble()).toFloat().coerceIn(0f, 100f)
        } else 0f

    val isEngineActive: Boolean
        get() = phase == SessionPhase.VALIDATING_LICENSE ||
            phase == SessionPhase.ALLOCATING ||
            phase == SessionPhase.DOWNLOADING ||
            phase == SessionPhase.VERIFYING
}
