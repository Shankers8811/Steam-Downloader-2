package com.example.ui.util

import java.util.Locale

/** Formatting helpers for the Steam-style download statistics. */
object FormatUtils {

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.size - 1) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.US, if (unit >= 3) "%.2f %s" else "%.1f %s", value, units[unit])
    }

    /** "23.4 MB/s" style speed readout. */
    fun formatSpeed(bytesPerSec: Double): String {
        if (bytesPerSec < 512.0) return "0 B/s"
        return formatBytes(bytesPerSec.toLong()) + "/s"
    }

    fun formatBytesPerSec(bytesPerSec: Long): String = formatBytes(bytesPerSec) + "/s"

    /** Steam-style time remaining: "12:34", "1:05:23", "--:--" when unknown. */
    fun formatEta(seconds: Long): String {
        if (seconds < 0L) return "--:--"
        if (seconds > 99L * 3600L) return "> 99 h"
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val secs = seconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, secs)
        }
    }

    /** "3 minutes", "1 hour 12 minutes"-style duration text. */
    fun formatDurationWords(seconds: Long): String {
        if (seconds < 60) return "$seconds second${if (seconds == 1L) "" else "s"}"
        val minutes = seconds / 60
        if (minutes < 60) return "$minutes minute${if (minutes == 1L) "" else "s"}"
        val hours = minutes / 60
        val restMinutes = minutes % 60
        return if (restMinutes == 0L) {
            "$hours hour${if (hours == 1L) "" else "s"}"
        } else {
            "$hours h $restMinutes min"
        }
    }
}
