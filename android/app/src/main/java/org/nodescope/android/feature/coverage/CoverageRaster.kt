package org.nodescope.android.feature.coverage

import kotlin.math.*

enum class CoverageRasterState { invalidResult, invalidTile, cancelled }
class CoverageRasterFailure(val state: CoverageRasterState) : Exception(state.name)
enum class CoverageRasterClass { outsideAnalysis, unsupportedNearSite, unresolved, belowThreshold, aboveThreshold }

/** Immutable nearest-sample Web Mercator rasterization, with no interpolation of RF evidence. */
class CoverageRaster(val grid: CoverageGrid, val transmitter: CoverageTerrainCoordinate, area: CoverageAreaResult) {
    private val cells = area.cells.toList()
    init {
        if (!transmitter.latitude.isFinite() || !transmitter.longitude.isFinite() ||
            transmitter.latitude !in -85.0..85.0 || transmitter.longitude !in -180.0..180.0 || cells.size != grid.count) invalid()
        for ((index, cell) in cells.withIndex()) {
            if (cell.state == CoverageAreaState.qualified) {
                if (grid.point(index)?.state != CoverageGridPointState.eligible ||
                    cell.lossDb?.isFinite() != true || cell.marginDb?.isFinite() != true) invalid()
            } else if (cell.lossDb != null || cell.marginDb != null) invalid()
        }
    }

    fun classification(latitude: Double, longitude: Double): CoverageRasterClass {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -85.0..85.0 || longitude !in -180.0..180.0)
            return CoverageRasterClass.outsideAnalysis
        val phi = Math.toRadians(transmitter.latitude)
        val target = Math.toRadians(latitude)
        val delta = Math.toRadians(longitude - transmitter.longitude)
        val h = sin((target - phi) / 2).pow(2) + cos(phi) * cos(target) * sin(delta / 2).pow(2)
        val distance = 2 * asin(sqrt(h.coerceIn(0.0, 1.0))) * CoverageTerrainFootprint.EARTH_RADIUS_METERS
        if (distance > grid.radiusMeters) return CoverageRasterClass.outsideAnalysis
        if (distance < 250) return CoverageRasterClass.unsupportedNearSite
        val bearing = atan2(sin(delta) * cos(target), cos(phi) * sin(target) - sin(phi) * cos(target) * cos(delta))
        val east = distance * sin(bearing)
        val north = distance * cos(bearing)
        val column = floor((east - grid.minEastMeters) / grid.spacingMeters + 0.5).toInt()
        val row = floor((grid.maxNorthMeters - north) / grid.spacingMeters + 0.5).toInt()
        if (column !in 0 until grid.columns || row !in 0 until grid.rows) return CoverageRasterClass.outsideAnalysis
        val cell = cells[row * grid.columns + column]
        return when (cell.state) {
            CoverageAreaState.outsideRadius -> CoverageRasterClass.outsideAnalysis
            CoverageAreaState.nearSite -> CoverageRasterClass.unsupportedNearSite
            CoverageAreaState.qualified -> if ((cell.marginDb ?: Double.NEGATIVE_INFINITY) >= 0) CoverageRasterClass.aboveThreshold else CoverageRasterClass.belowThreshold
            else -> CoverageRasterClass.unresolved
        }
    }

    /** Straight-alpha RGBA bytes; render off the UI thread. Tile dimensions are always 256×256. */
    fun tile(zoom: Int, x: Int, y: Int, cancelled: () -> Boolean = { false }): ByteArray {
        if (zoom !in 0..22) throw CoverageRasterFailure(CoverageRasterState.invalidTile)
        val count = 1 shl zoom
        if (x !in 0 until count || y !in 0 until count) throw CoverageRasterFailure(CoverageRasterState.invalidTile)
        val output = ByteArray(256 * 256 * 4)
        val scale = (count * 256).toDouble()
        for (row in 0 until 256) {
            if (cancelled()) throw CoverageRasterFailure(CoverageRasterState.cancelled)
            val worldY = (y * 256 + row + 0.5) / scale
            val latitude = Math.toDegrees(atan(sinh(PI * (1 - 2 * worldY))))
            for (column in 0 until 256) {
                val longitude = (x * 256 + column + 0.5) / scale * 360 - 180
                val color = rgba(classification(latitude, longitude), column, row)
                val offset = (row * 256 + column) * 4
                output[offset] = (color ushr 24).toByte()
                output[offset + 1] = (color ushr 16).toByte()
                output[offset + 2] = (color ushr 8).toByte()
                output[offset + 3] = color.toByte()
            }
        }
        if (cancelled()) throw CoverageRasterFailure(CoverageRasterState.cancelled)
        return output
    }

    companion object {
        /** A legend must distinguish qualified below-threshold pixels, excluded areas and unresolved hatching. */
        fun rgba(state: CoverageRasterClass, column: Int, row: Int): Int = when (state) {
            CoverageRasterClass.aboveThreshold -> 0x26B85AA0
            CoverageRasterClass.unresolved, CoverageRasterClass.unsupportedNearSite -> if ((column + row) % 8 < 2) 0x66666690 else 0
            CoverageRasterClass.outsideAnalysis, CoverageRasterClass.belowThreshold -> 0
        }
        private fun invalid(): Nothing = throw CoverageRasterFailure(CoverageRasterState.invalidResult)
    }
}
