package org.nodescope.android.feature.linkprofile

import java.util.Locale
import org.nodescope.android.core.storage.DistanceUnit

/** Display conversions only: persisted inputs, terrain and RF calculations remain in meters. */
internal fun profileDisplayHeight(meters: Double, unit: DistanceUnit): Double =
    if (unit == DistanceUnit.IMPERIAL) meters / 0.3048 else meters

internal fun profileHeightMeters(value: Double, unit: DistanceUnit): Double =
    if (unit == DistanceUnit.IMPERIAL) value * 0.3048 else value

internal fun profileHeightMaximum(unit: DistanceUnit): Float =
    if (unit == DistanceUnit.IMPERIAL) 100f else 30f

internal fun formatProfileHeight(meters: Double, unit: DistanceUnit): String =
    String.format(Locale.getDefault(), "%.2f %s", profileDisplayHeight(meters, unit),
        if (unit == DistanceUnit.IMPERIAL) "ft" else "m")
