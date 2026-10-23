package org.nodescope.android.core.model

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

enum class FieldCardinalDirection(val label: String) {
    NORTH("N"),
    NORTH_NORTHEAST("NNE"),
    NORTHEAST("NE"),
    EAST_NORTHEAST("ENE"),
    EAST("E"),
    EAST_SOUTHEAST("ESE"),
    SOUTHEAST("SE"),
    SOUTH_SOUTHEAST("SSE"),
    SOUTH("S"),
    SOUTH_SOUTHWEST("SSW"),
    SOUTHWEST("SW"),
    WEST_SOUTHWEST("WSW"),
    WEST("W"),
    WEST_NORTHWEST("WNW"),
    NORTHWEST("NW"),
    NORTH_NORTHWEST("NNW"),
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
