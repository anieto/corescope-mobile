package org.nodescope.android.feature.coverage

enum class CoverageAreaState { qualified, nearSite, outsideRadius, missingTerrain, accuracyUnresolved, thresholdUnresolved, workLimit, notComputed }
data class CoverageAreaCell(val state: CoverageAreaState, val lossDb: Double? = null, val marginDb: Double? = null)
data class CoverageAreaResult(val cells: List<CoverageAreaCell>, val chargedSamples: Long,
    val chargedGeometry: Long, val workLimited: Boolean)
/** Serialized worker execution. No partial result escapes cancellation or unhandled failure. */
object CoverageArea {
    fun evaluate(grid: CoverageGrid, transmitter: CoverageTerrainCoordinate, budget: CoverageLinkBudget,
                 sampleBudget: Long, geometryBudget: Long, cancelled: () -> Boolean = { false },
                 progress: (completed: Int, total: Int) -> Unit = { _, _ -> },
                 profile: (CoverageTerrainPath, Double, Int, Int) -> CoverageTerrainSampledPath,
                 predictLoss: (CoverageTerrainProfile) -> Double): CoverageAreaResult {
        if (!transmitter.latitude.isFinite() || !transmitter.longitude.isFinite() ||
            transmitter.latitude !in -85.0..85.0 || transmitter.longitude !in -180.0..180.0) {
            throw CoverageGridFailure(CoverageGridFailureState.invalidParameters)
        }
        CoverageGridPreflight.estimate(grid, sampleBudget, geometryBudget, cancelled = cancelled)
        var samplesRemaining = sampleBudget
        var geometryRemaining = geometryBudget
        var limited = false
        val cells = ArrayList<CoverageAreaCell>(grid.count)
        // Progress counts the samples that need a path, as iOS: near-site and outside cells don't.
        val total = (0 until grid.count).count { grid.point(it)?.state == CoverageGridPointState.eligible }
        var completed = 0
        var lastReport = System.nanoTime()
        progress(completed, total)
        for (index in 0 until grid.count) {
            if (cancelled()) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.CANCELLED)
            if (System.nanoTime() - lastReport >= 250_000_000L) { progress(completed, total); lastReport = System.nanoTime() }
            if (limited) { cells.add(CoverageAreaCell(CoverageAreaState.notComputed)); continue }
            val point = requireNotNull(grid.point(index))
            if (point.state != CoverageGridPointState.eligible) {
                cells.add(CoverageAreaCell(if (point.state == CoverageGridPointState.nearSite) CoverageAreaState.nearSite else CoverageAreaState.outsideRadius))
                continue
            }
            try {
                val path = requireNotNull(grid.path(index, transmitter))
                val result = CoverageRFConvergence.evaluate(path.distanceMeters, budget.maximumLossDb, cancelled = cancelled,
                    profile = { spacing, samples, geometry ->
                        val sampleAllowance = minOf(samples, samplesRemaining.toInt())
                        val geometryAllowance = minOf(geometry, geometryRemaining.toInt())
                        if (sampleAllowance <= 0 || geometryAllowance <= 0) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.WORK_LIMIT)
                        samplesRemaining -= sampleAllowance
                        geometryRemaining -= geometryAllowance
                        val sampled = profile(path, spacing, sampleAllowance, geometryAllowance)
                        if (sampled.profile.terrainEvaluations !in 0..sampleAllowance || sampled.geometryEvaluations !in 0..geometryAllowance) {
                            throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.INVALID_INPUT)
                        }
                        samplesRemaining += sampleAllowance - sampled.profile.terrainEvaluations
                        geometryRemaining += geometryAllowance - sampled.geometryEvaluations
                        sampled
                    }, predictLoss = predictLoss)
                cells.add(when (result.state) {
                    CoverageRFConvergenceState.QUALIFIED -> CoverageAreaCell(CoverageAreaState.qualified, result.lossDb, result.marginDb)
                    CoverageRFConvergenceState.ACCURACY_UNRESOLVED -> CoverageAreaCell(CoverageAreaState.accuracyUnresolved)
                    CoverageRFConvergenceState.THRESHOLD_UNRESOLVED -> CoverageAreaCell(CoverageAreaState.thresholdUnresolved)
                })
                completed++
            } catch (failure: CoverageTerrainProfileFailure) {
                when (failure.state) {
                    CoverageTerrainProfileState.MISSING_TERRAIN -> { cells.add(CoverageAreaCell(CoverageAreaState.missingTerrain)); completed++ }
                    CoverageTerrainProfileState.ACCURACY_UNRESOLVED -> { cells.add(CoverageAreaCell(CoverageAreaState.accuracyUnresolved)); completed++ }
                    CoverageTerrainProfileState.WORK_LIMIT -> { cells.add(CoverageAreaCell(CoverageAreaState.workLimit)); limited = true }
                    CoverageTerrainProfileState.CANCELLED, CoverageTerrainProfileState.INVALID_INPUT -> throw failure
                }
            }
        }
        if (cancelled()) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.CANCELLED)
        progress(completed, total)
        return CoverageAreaResult(java.util.Collections.unmodifiableList(cells), sampleBudget - samplesRemaining,
            geometryBudget - geometryRemaining, limited)
    }
}
