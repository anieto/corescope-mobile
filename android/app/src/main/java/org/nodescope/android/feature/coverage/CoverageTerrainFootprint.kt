package org.nodescope.android.feature.coverage

import kotlin.math.*

enum class CoverageTerrainFootprintState { INVALID_REQUEST, TOO_MANY_TILES, AMBIGUOUS_TILE, INCONSISTENT_SOURCES, MISSING_TILES }
class CoverageTerrainFootprintFailure(
    val state: CoverageTerrainFootprintState,
    val missingTiles: List<CoverageTerrainTile> = emptyList(),
) : Exception(state.name)

/** Conservative spherical study-disk bounds plus explicit sampling halo, not an RF coverage outline. */
class CoverageTerrainFootprint private constructor(val tiles: List<CoverageTerrainTile>) {
    companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
        fun make(latitude: Double, longitude: Double, radiusMeters: Double,
                 haloMeters: Double = 0.0, maximumTiles: Int = 256): CoverageTerrainFootprint {
            val limit = PI * EARTH_RADIUS_METERS
            if (!latitude.isFinite() || !longitude.isFinite() || !radiusMeters.isFinite() || !haloMeters.isFinite() ||
                latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
                radiusMeters !in 0.0..limit || haloMeters !in 0.0..limit ||
                radiusMeters + haloMeters > limit || maximumTiles !in 1..256) {
                throw CoverageTerrainFootprintFailure(CoverageTerrainFootprintState.INVALID_REQUEST)
            }
            val distance = radiusMeters + haloMeters
            if (distance == 0.0) return CoverageTerrainFootprint(listOf(CoverageTerrainTile.containing(latitude, longitude)))
            val radians = PI / 180
            val phi = latitude * radians
            val angular = distance / EARTH_RADIUS_METERS
            val south = maxOf(-90, floor(Math.nextDown(maxOf(-PI / 2, phi - angular) / radians)).toInt())
            val north = minOf(89, floor(Math.nextUp(minOf(PI / 2, phi + angular) / radians)).toInt())
            val longitudes = if (phi - angular <= -PI / 2 || phi + angular >= PI / 2) {
                (-180 until 180).toList()
            } else {
                val halfWidth = asin((sin(angular) / cos(phi)).coerceIn(0.0, 1.0)) / radians
                val center = if (longitude == 180.0) -180.0 else longitude
                val west = floor(Math.nextDown(center - halfWidth)).toInt()
                val east = floor(Math.nextUp(center + halfWidth)).toInt()
                (west..east).map { ((it + 180) % 360 + 360) % 360 - 180 }.distinct().sorted()
            }
            if ((north - south + 1) * longitudes.size > maximumTiles) {
                throw CoverageTerrainFootprintFailure(CoverageTerrainFootprintState.TOO_MANY_TILES)
            }
            return CoverageTerrainFootprint((south..north).flatMap { lat -> longitudes.map { lon -> CoverageTerrainTile(lat, lon) } })
        }
    }

    /** Resolve a bounded manifest; gaps and mixed provenance are explicit errors. */
    fun resolve(catalog: List<CoverageTerrainDescriptor>): List<CoverageTerrainDescriptor> {
        if (catalog.size > 4096) throw CoverageTerrainFootprintFailure(CoverageTerrainFootprintState.INVALID_REQUEST)
        val required = tiles.toSet()
        val selected = mutableMapOf<CoverageTerrainTile, CoverageTerrainDescriptor>()
        for (descriptor in catalog.filter { it.coordinate in required }) {
            val existing = selected[descriptor.coordinate]
            if (existing != null && existing.cacheKey != descriptor.cacheKey) {
                throw CoverageTerrainFootprintFailure(CoverageTerrainFootprintState.AMBIGUOUS_TILE)
            }
            if (existing == null) selected[descriptor.coordinate] = descriptor
        }
        val missing = tiles.filter { it !in selected }
        if (missing.isNotEmpty()) throw CoverageTerrainFootprintFailure(CoverageTerrainFootprintState.MISSING_TILES, missing)
        val result = tiles.map { selected.getValue(it) }
        result.firstOrNull()?.let { first ->
            if (result.any { it.dataset != first.dataset || it.revision != first.revision ||
                    it.verticalDatum != first.verticalDatum || it.side != first.side || it.sourceType != first.sourceType }) {
                throw CoverageTerrainFootprintFailure(CoverageTerrainFootprintState.INCONSISTENT_SOURCES)
            }
        }
        return result
    }
}
