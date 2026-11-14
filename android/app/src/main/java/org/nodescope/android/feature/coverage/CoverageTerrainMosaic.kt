package org.nodescope.android.feature.coverage

import kotlin.math.floor

/** Southwest corner of a one-degree HGT tile. Repository must enforce common dataset/revision/datum. */
data class CoverageTerrainTile(val south: Int, val west: Int) {
    init {
        if (south !in -90..89 || west !in -180..179) throw TerrainReadFailure(TerrainReadState.INVALID_DESCRIPTOR)
    }
    companion object {
        /** North/east tile owns a seam. +180 becomes -180; north pole uses tile 89's north edge. */
        fun containing(latitude: Double, longitude: Double): CoverageTerrainTile {
            if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
                throw TerrainReadFailure(TerrainReadState.INVALID_RANGE)
            }
            return CoverageTerrainTile(minOf(89, floor(latitude).toInt()),
                floor(if (longitude == 180.0) -180.0 else longitude).toInt())
        }
    }
}

sealed interface CoverageTerrainSample {
    data class ElevationMeters(val value: Double) : CoverageTerrainSample
    data class MissingTile(val tile: CoverageTerrainTile) : CoverageTerrainSample
    data object NoData : CoverageTerrainSample
}

/**
 * One background worker owns all readers, including on failed construction. Pinned files remain
 * immutable until close. Point interpolation does not perform terrain-profile ridge preservation.
 */
class CoverageTerrainMosaic(readers: Map<CoverageTerrainTile, LeasedTerrainReader>,
    /** Off only for comparison runs and parity tests. */
    private val reusePreparedPaths: Boolean = true) : AutoCloseable {
    private val readers = readers.toMap()
    private var closed = false
    // The current path's crossings, features and heights, reused across its refinement passes as on iOS.
    private var pathKey: List<Double>? = null
    private var cachedCrossings: CoverageTerrainCrossings? = null
    private var preparation = CoverageTerrainProfileSampler.Preparation()
    private val elevations = HashMap<Double, Double>()
    var crossingCacheHits = 0; private set
    var elevationCacheHits = 0L; private set
    var elevationReads = 0L; private set
    val profilePassCounts = sortedMapOf<String, Int>()
    /** Reader counters for the calculation report. */
    val readerCounters: Map<String, Long> get() = mapOf(
        "terrainMappedReaders" to readers.values.count { it.isMapped }.toLong(),
        "terrainFallbackReaders" to readers.values.count { !it.isMapped }.toLong(),
        "terrainMappedReadCalls" to readers.values.sumOf { it.mappedReadCalls },
        "terrainMappedAddressBytes" to readers.values.sumOf { it.mappedBytes },
        "terrainBlockHits" to readers.values.sumOf { it.blockHits },
        "terrainBlockMisses" to readers.values.sumOf { it.blockMisses },
        "terrainFileReadBytes" to readers.values.sumOf { it.fileReadBytes })
    init {
        if (readers.size > MAXIMUM_TILES || readers.values.toSet().size != readers.size) {
            close()
            throw TerrainReadFailure(TerrainReadState.INVALID_DESCRIPTOR)
        }
    }
    companion object { const val MAXIMUM_TILES = 256 }

    /** Nonzero bilinear contributors only; missing owner tiles never borrow a neighbor's border. */
    fun sample(latitude: Double, longitude: Double, cancelled: () -> Boolean = { false }): CoverageTerrainSample {
        if (closed) throw TerrainReadFailure(TerrainReadState.CLOSED)
        if (cancelled()) throw TerrainReadFailure(TerrainReadState.CANCELLED)
        val tile = CoverageTerrainTile.containing(latitude, longitude)
        val reader = readers[tile] ?: return CoverageTerrainSample.MissingTile(tile)
        val canonicalLongitude = if (longitude == 180.0) -180.0 else longitude
        val intervals = reader.side - 1
        val x = ((canonicalLongitude - tile.west) * intervals).coerceIn(0.0, intervals.toDouble())
        val y = ((tile.south + 1 - latitude) * intervals).coerceIn(0.0, intervals.toDouble())
        val column = floor(x).toInt()
        val row = floor(y).toInt()
        val dx = x - column
        val dy = y - row
        // Up to four heights read in place: no file call or allocation per sample (iOS's mapped reader).
        var elevation = 0.0
        for (step in 0..1) {
            val r = if (step == 0) row else minOf(row + 1, intervals)
            val weight = if (step == 0) 1 - dy else dy
            if (weight <= 0) continue
            val base = r * reader.side + column
            val first = reader.value(base)
            if (first == Short.MIN_VALUE) return CoverageTerrainSample.NoData
            var rowElevation = first * (1 - dx)
            if (dx > 0) {
                val second = reader.value(base + 1)
                if (second == Short.MIN_VALUE) return CoverageTerrainSample.NoData
                rowElevation += second * dx
            }
            elevation += rowElevation * weight
        }
        if (cancelled()) throw TerrainReadFailure(TerrainReadState.CANCELLED)
        return CoverageTerrainSample.ElevationMeters(elevation)
    }

    /** Build a path while retaining this mosaic's reader ownership. */
    fun profile(path: CoverageTerrainPath, spacingMeters: Double, sampleLimit: Int = 250_000,
                geometryLimit: Int = 200_000, cancelled: () -> Boolean = { false }): CoverageTerrainSampledPath {
        if (closed) throw TerrainReadFailure(TerrainReadState.CLOSED)
        val dimensions = readers.values.map { it.side }.toSet()
        val side = dimensions.firstOrNull() ?: throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.MISSING_TERRAIN)
        if (dimensions.size != 1) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.INVALID_INPUT)
        if (cancelled()) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.CANCELLED)
        if (spacingMeters.isFinite() && spacingMeters in 1.0..30.0) {
            val name = "profilePassesAt${spacingMeters.toInt()}m"
            profilePassCounts[name] = (profilePassCounts[name] ?: 0) + 1
        }
        val key = listOf(path.latitude, path.longitude, path.bearingDegrees, path.distanceMeters)
        if (!reusePreparedPaths || pathKey != key) {
            pathKey = key
            cachedCrossings = null
            preparation = CoverageTerrainProfileSampler.Preparation()
            elevations.clear()
        }
        val crossings = cachedCrossings?.also {
            // Logical work limits still apply when the work is served from cache.
            if (geometryLimit !in 1..200_000 || it.geometryEvaluations > geometryLimit) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.WORK_LIMIT)
            crossingCacheHits++
        } ?: path.crossings(side - 1, geometryLimit, cancelled).also { cachedCrossings = it }
        val profile = CoverageTerrainProfileSampler.build(path.distanceMeters, crossings.distancesMeters,
            spacingMeters, sampleLimit, cancelled, if (reusePreparedPaths) preparation else null) { distance ->
            if (reusePreparedPaths) elevations[distance]?.let { elevationCacheHits++; return@build it }
            elevationReads++
            val point = path.point(distance)
            when (val sample = sample(point.latitude, point.longitude, cancelled)) {
                is CoverageTerrainSample.ElevationMeters -> sample.value.also {
                    // Exact distance keys, bounded per path, so no two positions ever share a height.
                    if (reusePreparedPaths && elevations.size < 250_000) elevations[distance] = it
                }
                else -> null
            }
        }
        return CoverageTerrainSampledPath(profile.copy(pathCenterLatitude = path.point(path.distanceMeters / 2).latitude), crossings.geometryEvaluations)
    }

    override fun close() {
        if (closed) return
        closed = true
        // Attempt every release even if closing one OS file fails.
        var firstError: Exception? = null
        for (reader in readers.values) {
            try { reader.close() } catch (error: Exception) { if (firstError == null) firstError = error }
        }
        firstError?.let { throw it }
    }
}
