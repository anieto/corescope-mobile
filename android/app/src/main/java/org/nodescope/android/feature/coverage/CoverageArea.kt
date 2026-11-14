package org.nodescope.android.feature.coverage

enum class CoverageAreaState { qualified, nearSite, outsideRadius, missingTerrain, accuracyUnresolved, thresholdUnresolved, workLimit, notComputed }
data class CoverageAreaCell(val state: CoverageAreaState, val lossDb: Double? = null, val marginDb: Double? = null)
data class CoverageAreaResult(val cells: List<CoverageAreaCell>, val chargedSamples: Long,
    val chargedGeometry: Long, val workLimited: Boolean) {
    /** Samples a Continue would still calculate. */
    val pendingCount: Int get() = cells.count { it.state == CoverageAreaState.notComputed }

    /** Matches physical offsets, never array indices, into a larger [grid] with the same spacing, as iOS. */
    fun expanded(old: CoverageGrid, grid: CoverageGrid): CoverageAreaResult {
        if (cells.size != old.count || grid.radiusMeters <= old.radiusMeters || grid.spacingMeters != old.spacingMeters) {
            throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.INVALID_INPUT)
        }
        val mapped = (0 until grid.count).map { index ->
            val point = grid.point(index) ?: throw CoverageGridFailure(CoverageGridFailureState.invalidParameters)
            if (point.state != CoverageGridPointState.eligible) {
                return@map CoverageAreaCell(if (point.state == CoverageGridPointState.nearSite) CoverageAreaState.nearSite else CoverageAreaState.outsideRadius)
            }
            val dx = point.eastMeters - old.minEastMeters
            val dy = old.maxNorthMeters - point.northMeters
            if (dx >= 0 && dy >= 0 && dx % old.spacingMeters == 0 && dy % old.spacingMeters == 0) {
                val column = dx / old.spacingMeters
                val row = dy / old.spacingMeters
                if (column < old.columns && row < old.rows) {
                    val saved = cells[row * old.columns + column]
                    if (saved.state != CoverageAreaState.outsideRadius && saved.state != CoverageAreaState.nearSite) return@map saved
                }
            }
            CoverageAreaCell(CoverageAreaState.notComputed)
        }
        return CoverageAreaResult(java.util.Collections.unmodifiableList(mapped), 0, 0, mapped.any { it.state == CoverageAreaState.notComputed })
    }
}
/**
 * Serialized worker execution, as iOS. Samples run in a spread-out order so a paused batch already
 * covers the whole area; [previous] resumes one, keeping its finished samples after checking them.
 */
object CoverageArea {
    fun evaluate(grid: CoverageGrid, transmitter: CoverageTerrainCoordinate, budget: CoverageLinkBudget,
                 sampleBudget: Long, geometryBudget: Long, cancelled: () -> Boolean = { false },
                 previous: CoverageAreaResult? = null, shouldPause: () -> Boolean = { false },
                 progress: (completed: Int, total: Int) -> Unit = { _, _ -> },
                 profile: (CoverageTerrainPath, Double, Int, Int) -> CoverageTerrainSampledPath,
                 predictLoss: (CoverageTerrainProfile) -> Double): CoverageAreaResult {
        fun invalid(): Nothing = throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.INVALID_INPUT)
        if (!transmitter.latitude.isFinite() || !transmitter.longitude.isFinite() ||
            transmitter.latitude !in -85.0..85.0 || transmitter.longitude !in -180.0..180.0) {
            throw CoverageGridFailure(CoverageGridFailureState.invalidParameters)
        }
        CoverageGridPreflight.estimate(grid, sampleBudget, geometryBudget, cancelled = cancelled)
        var samplesRemaining = sampleBudget
        var geometryRemaining = geometryBudget
        var limited = false
        val cells = previous?.cells?.toMutableList() ?: MutableList(grid.count) { CoverageAreaCell(CoverageAreaState.notComputed) }
        if (cells.size != grid.count) invalid()
        // Check retained evidence against this grid and budget; the prepared study pins inputs, model and terrain.
        for (index in 0 until grid.count) {
            val point = grid.point(index) ?: throw CoverageGridFailure(CoverageGridFailureState.invalidParameters)
            if (previous != null) {
                val saved = cells[index]
                when (point.state) {
                    CoverageGridPointState.nearSite -> if (saved.state != CoverageAreaState.nearSite) invalid()
                    CoverageGridPointState.outsideRadius -> if (saved.state != CoverageAreaState.outsideRadius) invalid()
                    CoverageGridPointState.eligible -> if (saved.state == CoverageAreaState.nearSite || saved.state == CoverageAreaState.outsideRadius) invalid()
                }
                if (saved.state == CoverageAreaState.qualified) {
                    val loss = saved.lossDb ?: invalid()
                    val margin = saved.marginDb ?: invalid()
                    if (!loss.isFinite() || !margin.isFinite() || budget.reception(loss).adjustedMarginDb != margin) invalid()
                } else if (saved.lossDb != null || saved.marginDb != null) invalid()
            } else if (point.state != CoverageGridPointState.eligible) {
                cells[index] = CoverageAreaCell(if (point.state == CoverageGridPointState.nearSite) CoverageAreaState.nearSite else CoverageAreaState.outsideRadius)
            }
        }
        // Progress counts the samples that need a path, as iOS: near-site and outside cells don't.
        val eligible = (0 until grid.count).count { grid.point(it)?.state == CoverageGridPointState.eligible }
        var completed = cells.count { it.state != CoverageAreaState.notComputed && it.state != CoverageAreaState.nearSite && it.state != CoverageAreaState.outsideRadius }
        var lastReport = System.nanoTime()
        progress(completed, eligible)
        for (index in distributedOrder(grid.count)) {
            if (cancelled()) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.CANCELLED)
            if (System.nanoTime() - lastReport >= 250_000_000L) { progress(completed, eligible); lastReport = System.nanoTime() }
            val point = grid.point(index) ?: throw CoverageGridFailure(CoverageGridFailureState.invalidParameters)
            if (point.state != CoverageGridPointState.eligible || cells[index].state != CoverageAreaState.notComputed) continue
            if (shouldPause()) { limited = true; break }
            // A per-path limit does not exhaust the study: other paths continue while aggregate work remains.
            if (limited && (samplesRemaining <= 0 || geometryRemaining <= 0)) continue
            var batchConstrained = false
            try {
                val path = grid.path(index, transmitter) ?: throw CoverageGridFailure(CoverageGridFailureState.invalidParameters)
                val result = CoverageRFConvergence.evaluate(path.distanceMeters, budget.maximumLossDb, cancelled = cancelled,
                    profile = { spacing, samples, geometry ->
                        val sampleAllowance = minOf(samples.toLong(), samplesRemaining).toInt()
                        val geometryAllowance = minOf(geometry.toLong(), geometryRemaining).toInt()
                        batchConstrained = sampleAllowance < samples || geometryAllowance < geometry
                        if (sampleAllowance <= 0 || geometryAllowance <= 0) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.WORK_LIMIT)
                        // A failed profile keeps its reservation: partial work is not reported.
                        samplesRemaining -= sampleAllowance
                        geometryRemaining -= geometryAllowance
                        val sampled = profile(path, spacing, sampleAllowance, geometryAllowance)
                        if (sampled.profile.terrainEvaluations !in 0..sampleAllowance || sampled.geometryEvaluations !in 0..geometryAllowance) invalid()
                        samplesRemaining += sampleAllowance - sampled.profile.terrainEvaluations
                        geometryRemaining += geometryAllowance - sampled.geometryEvaluations
                        sampled
                    }, predictLoss = predictLoss)
                cells[index] = when (result.state) {
                    CoverageRFConvergenceState.QUALIFIED -> CoverageAreaCell(CoverageAreaState.qualified, result.lossDb, result.marginDb)
                    CoverageRFConvergenceState.ACCURACY_UNRESOLVED -> CoverageAreaCell(CoverageAreaState.accuracyUnresolved)
                    CoverageRFConvergenceState.THRESHOLD_UNRESOLVED -> CoverageAreaCell(CoverageAreaState.thresholdUnresolved)
                }
                completed++
            } catch (failure: CoverageTerrainProfileFailure) {
                when (failure.state) {
                    CoverageTerrainProfileState.MISSING_TERRAIN -> { cells[index] = CoverageAreaCell(CoverageAreaState.missingTerrain); completed++ }
                    CoverageTerrainProfileState.ACCURACY_UNRESOLVED -> { cells[index] = CoverageAreaCell(CoverageAreaState.accuracyUnresolved); completed++ }
                    CoverageTerrainProfileState.WORK_LIMIT -> {
                        // Out of batch budget: left for Continue. Otherwise this one path is too costly.
                        if (!batchConstrained) { cells[index] = CoverageAreaCell(CoverageAreaState.workLimit); completed++ }
                        limited = true
                    }
                    CoverageTerrainProfileState.CANCELLED, CoverageTerrainProfileState.INVALID_INPUT -> throw failure
                }
            }
        }
        if (cancelled()) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.CANCELLED)
        progress(completed, eligible)
        return CoverageAreaResult(java.util.Collections.unmodifiableList(cells), sampleBudget - samplesRemaining,
            geometryBudget - geometryRemaining, limited || cells.any { it.state == CoverageAreaState.workLimit })
    }

    /** Bit-reversal spreads successive samples across the grid while keeping row-major storage. */
    fun distributedOrder(count: Int): List<Int> {
        if (count <= 0) return emptyList()
        var width = 1
        var bits = 0
        while (width < count) { width = width shl 1; bits++ }
        return (0 until width).mapNotNull { ordinal ->
            var value = ordinal
            var reversed = 0
            repeat(bits) { reversed = (reversed shl 1) or (value and 1); value = value shr 1 }
            reversed.takeIf { it < count }
        }
    }
}
