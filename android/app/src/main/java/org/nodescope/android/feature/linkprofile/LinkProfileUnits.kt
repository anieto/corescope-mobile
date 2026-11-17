package org.nodescope.android.feature.linkprofile

import java.text.NumberFormat
import java.util.Locale
import org.nodescope.android.core.storage.DistanceUnit

/** Display conversions only: persisted inputs, terrain and RF calculations remain in meters. */
internal fun profileDisplayHeight(meters: Double, unit: DistanceUnit): Double =
    if (unit.usesFeet) meters / 0.3048 else meters

internal fun profileHeightMeters(value: Double, unit: DistanceUnit): Double =
    if (unit.usesFeet) value * 0.3048 else value

internal fun profileHeightMaximum(unit: DistanceUnit): Float =
    if (unit.usesFeet) 100f else 30f

/** Whole feet or metres: the terrain is ~90 m data and the slider moves in whole units. */
internal fun formatProfileHeight(
    meters: Double,
    unit: DistanceUnit,
    locale: Locale = Locale.getDefault(Locale.Category.FORMAT),
): String {
    val height = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 0
    }.format(profileDisplayHeight(meters, unit))
    return "$height ${if (unit.usesFeet) "ft" else "m"}"
}
