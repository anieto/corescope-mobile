package org.nodescope.android.core.model

import androidx.annotation.StringRes
import org.nodescope.android.R

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@ConsistentCopyVisibility
data class FieldCoordinate private constructor(
    val latitude: Double,
    val longitude: Double,
) {
    companion object {
        fun create(latitude: Double, longitude: Double): FieldCoordinate? =
            if (latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                FieldCoordinate(latitude, longitude)
            } else {
                null
            }
    }
}

enum class FieldCardinalDirection(val label: String, @param:StringRes val displayLabel: Int) {
    NORTH("N", R.string.field_direction_n),
    NORTH_NORTHEAST("NNE", R.string.field_direction_nne),
    NORTHEAST("NE", R.string.field_direction_ne),
    EAST_NORTHEAST("ENE", R.string.field_direction_ene),
    EAST("E", R.string.field_direction_e),
    EAST_SOUTHEAST("ESE", R.string.field_direction_ese),
    SOUTHEAST("SE", R.string.field_direction_se),
    SOUTH_SOUTHEAST("SSE", R.string.field_direction_sse),
    SOUTH("S", R.string.field_direction_s),
    SOUTH_SOUTHWEST("SSW", R.string.field_direction_ssw),
    SOUTHWEST("SW", R.string.field_direction_sw),
    WEST_SOUTHWEST("WSW", R.string.field_direction_wsw),
    WEST("W", R.string.field_direction_w),
    WEST_NORTHWEST("WNW", R.string.field_direction_wnw),
    NORTHWEST("NW", R.string.field_direction_nw),
    NORTH_NORTHWEST("NNW", R.string.field_direction_nnw),
}

data class FieldGeometryResult(
    val distanceMeters: Double,
    val initialBearingDegrees: Double?,
) {
    val cardinalDirection: FieldCardinalDirection?
        get() = initialBearingDegrees?.let(FieldGeometry::cardinalDirection)
}

object FieldGeometry {
    /** IUGG mean Earth radius. iOS uses the same value for cross-platform parity. */
    const val EARTH_RADIUS_METERS = 6_371_008.8

    fun geometry(origin: FieldCoordinate, destination: FieldCoordinate): FieldGeometryResult {
        val originLatitude = Math.toRadians(origin.latitude)
        val destinationLatitude = Math.toRadians(destination.latitude)
        val latitudeDelta = Math.toRadians(destination.latitude - origin.latitude)
        val longitudeDelta = Math.toRadians(destination.longitude - origin.longitude)

        val haversine = sin(latitudeDelta / 2).pow(2) +
            cos(originLatitude) * cos(destinationLatitude) * sin(longitudeDelta / 2).pow(2)
        val angularDistance = 2 * asin(sqrt(haversine).coerceAtMost(1.0))
        val distance = EARTH_RADIUS_METERS * angularDistance
        if (distance == 0.0) return FieldGeometryResult(0.0, null)

        val y = sin(longitudeDelta) * cos(destinationLatitude)
        val x = cos(originLatitude) * sin(destinationLatitude) -
            sin(originLatitude) * cos(destinationLatitude) * cos(longitudeDelta)
        val bearing = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        return FieldGeometryResult(distance, bearing)
    }

    fun cardinalDirection(bearingDegrees: Double): FieldCardinalDirection {
        val normalized = ((bearingDegrees % 360.0) + 360.0) % 360.0
        val index = ((normalized + 11.25) / 22.5).toInt() % FieldCardinalDirection.entries.size
        return FieldCardinalDirection.entries[index]
    }

    fun relativeHeadingRotation(trueBearingDegrees: Double, deviceTrueHeadingDegrees: Double): Double? {
        if (!trueBearingDegrees.isFinite() || !deviceTrueHeadingDegrees.isFinite()) return null
        return ((trueBearingDegrees - deviceTrueHeadingDegrees) % 360.0 + 360.0) % 360.0
    }
}
