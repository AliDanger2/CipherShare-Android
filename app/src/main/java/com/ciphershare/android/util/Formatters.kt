package com.ciphershare.android.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** Mirrors CipherShare (desktop) Common/Formatters.cs. */
object Formatters {
    private val sizeUnits = arrayOf("B", "KB", "MB", "GB", "TB")

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < sizeUnits.size - 1) {
            size /= 1024
            unitIndex++
        }
        return "${formatOneDecimal(size)} ${sizeUnits[unitIndex]}"
    }

    fun formatSpeed(mbps: Double): String = "${formatOneDecimal(mbps)} MB/s"

    fun formatDuration(totalSeconds: Int): String {
        if (totalSeconds <= 0) return "-"
        if (totalSeconds < 60) return "${totalSeconds}s"
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes}m ${seconds}s"
    }

    fun formatRelativeTime(epochMillis: Long?): String {
        if (epochMillis == null || epochMillis == 0L) return ""
        val diffMs = System.currentTimeMillis() - epochMillis
        val diffSeconds = diffMs / 1000
        return when {
            diffSeconds < 60 -> "just now"
            diffSeconds < 3600 -> "${diffSeconds / 60}m ago"
            diffSeconds < 86400 -> "${diffSeconds / 3600}h ago"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMillis))
        }
    }

    fun formatDateTime(epochMillis: Long?): String {
        if (epochMillis == null || epochMillis == 0L) return "-"
        return SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(epochMillis))
    }

    private fun formatOneDecimal(value: Double): String {
        val rounded = Math.round(value * 10) / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    fun clampPercent(value: Double): Double = min(100.0, value.coerceAtLeast(0.0))
}
