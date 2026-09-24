package org.nodescope.android.core.design

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.roundToLong
import org.nodescope.android.core.storage.DistanceUnit

fun relativeTime(instant: Instant?, now: Long = System.currentTimeMillis()): String {
    instant ?: return "Unknown"
    val seconds = (now - instant.toEpochMilli()) / 1000
    return when {
        seconds < 45 -> "just now"
        seconds < 3_600 -> "${(seconds + 30) / 60}m ago"
        seconds < 86_400 -> "${seconds / 3_600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}

fun compactCount(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 1_000_000 -> scaled(value / 1_000.0, "K")
    value < 1_000_000_000 -> scaled(value / 1_000_000.0, "M")
    else -> scaled(value / 1_000_000_000.0, "B")
}
private fun scaled(value: Double, suffix: String) =
    (if (value < 10) String.format(Locale.US, "%.1f", value).removeSuffix(".0") else String.format(Locale.US, "%.0f", value)) + suffix

fun formatDuration(seconds: Long): String {
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

fun shortDateTime(instant: Instant?): String = instant?.let {
    DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(it)
} ?: "Unknown time"

/** The distance unit chosen in Settings, available to every screen. */
val LocalDistanceUnit = staticCompositionLocalOf { DistanceUnit.IMPERIAL }

/**
 * Same rule as iOS `DistanceFormat`: miles or kilometers to one decimal place, and short
 * distances in feet (under 0.1 mi) or meters (under 1 km), rounded to 10.
 */
fun formatDistance(km: Double, unit: DistanceUnit): String = when (unit) {
    DistanceUnit.IMPERIAL -> {
        val miles = km * 0.621371
        if (miles < 0.1) "${(km * 3280.84 / 10).roundToLong() * 10} ft" else String.format(Locale.US, "%.1f mi", miles)
    }
    DistanceUnit.METRIC -> if (km < 1) "${(km * 100).roundToLong() * 10} m" else String.format(Locale.US, "%.1f km", km)
}
