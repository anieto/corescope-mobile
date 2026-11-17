package org.nodescope.android.core.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.roundToLong
import org.nodescope.android.core.storage.DistanceUnit

@Composable
fun relativeTime(instant: Instant?, now: Long = System.currentTimeMillis()): String =
    relativeTimeText(instant, now).asString()

internal fun relativeTimeText(instant: Instant?, now: Long): UiText.Resource {
    instant ?: return UiText.Resource(R.string.common_relative_time_unknown)
    val seconds = (now - instant.toEpochMilli()) / 1000
    return when {
        seconds < 45 -> UiText.Resource(R.string.common_relative_time_now)
        seconds < 3_600 -> UiText.Resource(R.string.common_relative_time_minutes, (seconds + 30) / 60)
        seconds < 86_400 -> UiText.Resource(R.string.common_relative_time_hours, seconds / 3_600)
        else -> UiText.Resource(R.string.common_relative_time_days, seconds / 86_400)
    }
}

fun compactCount(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 1_000_000 -> scaled(value / 1_000.0, "K")
    value < 1_000_000_000 -> scaled(value / 1_000_000.0, "M")
    else -> scaled(value / 1_000_000_000.0, "B")
}
private fun scaled(value: Double, suffix: String): String =
    formatDisplayDecimal(value, if (value < 10) 1 else 0) + suffix

/** Optional fractional digits for compact labels that do not group thousands. */
internal fun formatDisplayDecimal(
    value: Double,
    maximumDecimals: Int,
    locale: Locale = Locale.getDefault(Locale.Category.FORMAT),
): String = NumberFormat.getNumberInstance(locale).apply {
    isGroupingUsed = false
    maximumFractionDigits = maximumDecimals
}.format(value)

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

@Composable
fun shortDateTime(instant: Instant?): String = instant?.let {
    DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(it)
} ?: stringResource(R.string.common_unknown_time)

/** The distance unit chosen in Settings, available to every screen. */
val LocalDistanceUnit = staticCompositionLocalOf { DistanceUnit.defaultUnit() }

/**
 * Same rule as iOS `DistanceFormat`: miles or kilometers to one decimal place, and short
 * distances in feet (under 0.1 mi) or meters (under 1 km), rounded to 10.
 */
fun formatDistance(km: Double, unit: DistanceUnit, locale: Locale = Locale.getDefault(Locale.Category.FORMAT)): String {
    fun oneDecimal(value: Double): String = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(value)

    if (unit.usesMiles) {
        val miles = km * 0.621371
        if (miles >= 0.1) return "${oneDecimal(miles)} mi"
    } else if (km >= 1) {
        return "${oneDecimal(km)} km"
    }
    return if (unit.usesFeet) "${(km * 3280.84 / 10).roundToLong() * 10} ft"
        else "${(km * 100).roundToLong() * 10} m"
}
