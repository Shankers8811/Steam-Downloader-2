package com.example.data.download

import com.example.data.model.DlcMode
import com.example.data.model.LicenseReport
import com.example.data.model.LogLine

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

/** UI-visible progress for one depot of the app being downloaded. */
data class DepotProgress(
    val depotId: Int,
    val name: String,
    val dlcAppId: Int?,
    val totalCompressedBytes: Long,
    val downloadedCompressedBytes: Long,
    val completed: Boolean
) {
    val fraction: Float
        get() = if (totalCompressedBytes <= 0L) {
            if (completed) 1f else 0f
        } else {
            (downloadedCompressedBytes.toFloat() / totalCompressedBytes.toFloat()).coerceIn(0f, 1f)
        }
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
 * Integrity model of the native engine (javasteam-depotdownloader — the same
 * engine GameNative uses; see docs/GAMENATIVE_NOTES.md):
 *
 *  - Content arrives in ~1 MiB depot chunks. Each chunk is decompressed and
 *    verified (CRC32 + depot manifest checksums) BEFORE being written.
 *  - A per-depot staging ledger in "<installDir>/.DepotDownloader/staging"
 *    records which chunks are already on disk — pause, process death or a
 *    phone reboot resume from exactly those chunk boundaries, and resumed
 *    sessions re-verify (`verify = true`) instead of re-downloading.
 *  - Files are pre-sized and chunk-filled in place. A file that has not yet
 *    reported "completed" is still sparse — so "move to PC" must either
 *    happen at COMPLETED or move only files listed in [recentFiles].
 *  - Pause cancels the download job at the next chunk boundary; nothing can
 *    leave a corrupt byte sequence behind, chunks are the atomic unit.
 */
data class DownloadSessionState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val appId: Int = 0,
    val appName: String = "",
    val branch: String = "public",
    /** Uncompressed installed bytes across all selected depots. */
    val totalBytes: Long = 0L,
    /** Compressed bytes received so far (network-paid bytes). */
    val downloadedBytes: Long = 0L,

    // Steam-style live statistics
    val networkBytesPerSec: Double = 0.0,
    val diskBytesPerSec: Double = 0.0,
    val networkPeakBytesPerSec: Double = 0.0,
    val etaSeconds: Long = -1L,
    val elapsedSeconds: Long = 0L,

    /** Most recently completed file (whole & verified). */
    val currentFile: String = "",
    /** Files that finished writing so far in this session. */
    val completeFileCount: Int = 0,

    /** Per-depot progress rows (the way the Steam client presents content). */
    val depots: List<DepotProgress> = emptyList(),
    /** Newest-first feed of files that completed in this session. */
    val recentFiles: List<String> = emptyList(),

    val licenseReport: LicenseReport? = null,
    val outputDisplay: String = "",

    /** True only once the whole app finished and is verified. */
    val safeToMove: Boolean = false,
    /** True when on-disk staging exists for [appId] (resumable after restart). */
    val hasResumableSession: Boolean = false,

    val statusMessage: String = "Idle",
    val errorMessage: String? = null,
    val logLines: List<LogLine> = emptyList()
) {
    val progressPercent: Float
        get() {
            val totalCompressed = depots.sumOf { it.totalCompressedBytes }
            if (totalCompressed > 0L) {
                return (downloadedBytes.toDouble() * 100.0 / totalCompressed.toDouble())
                    .toFloat().coerceIn(0f, 100f)
            }
            return if (totalBytes > 0L) {
                (downloadedBytes.toDouble() * 100.0 / totalBytes.toDouble())
                    .toFloat().coerceIn(0f, 100f)
            } else 0f
        }

    val isEngineActive: Boolean
        get() = phase == SessionPhase.VALIDATING_LICENSE ||
            phase == SessionPhase.ALLOCATING ||
            phase == SessionPhase.DOWNLOADING ||
            phase == SessionPhase.VERIFYING
}
