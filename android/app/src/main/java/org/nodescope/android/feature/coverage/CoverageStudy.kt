package org.nodescope.android.feature.coverage

import java.util.UUID

data class CoverageStudyLimits(val areaSamples: Int, val areaGeometry: Int, val nodeSamples: Int,
    val nodeGeometry: Int, val nodeModelCalls: Int) {
    fun validate() {
        if (listOf(areaSamples, areaGeometry, nodeSamples, nodeGeometry).any { it !in 0..150_000_000 } ||
            areaSamples + nodeSamples > 150_000_000 || areaGeometry + nodeGeometry > 150_000_000 || nodeModelCalls !in 0..50_000) {
            throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.INVALID_INPUT)
        }
    }
}
/** Defensive snapshot of selected sources, not a complete RF reproducibility record. */
class CoverageStudyTerrainSources(descriptors: List<CoverageTerrainDescriptor>) {
    val descriptors: List<CoverageTerrainDescriptor> = java.util.Collections.unmodifiableList(descriptors.toList())
}

data class CoverageStudyModel(val sourceRevision: String, val scenario: String)

data class CoverageStudyResult(val executionID: UUID, val inputs: CoverageStudyInputs,
    val limits: CoverageStudyLimits, val area: CoverageAreaResult, val nodes: CoverageNodeBatchResult,
    val terrainSources: CoverageStudyTerrainSources? = null, val model: CoverageStudyModel? = null) {
    val transmitter get() = inputs.transmitter
    val grid get() = inputs.grid
    val budget get() = inputs.budget
}
/** Serialized worker operation borrowing terrain. No area-only result escapes node failure. */
object CoverageStudy {
    fun evaluate(inputs: CoverageStudyInputs,
                 nodes: List<CoverageNodeSnapshot>, catalogComplete: Boolean, nodeLimit: Int,
                 limits: CoverageStudyLimits, mosaic: CoverageTerrainMosaic, executionID: UUID = UUID.randomUUID(), cancelled: () -> Boolean = { false },
                 predictLoss: (CoverageTerrainProfile, CoverageStudyInputs) -> Double): CoverageStudyResult {
        limits.validate()
        val transmitter = inputs.transmitter
        val grid = inputs.grid
        val budget = inputs.budget
        val predict: (CoverageTerrainProfile) -> Double = { predictLoss(it, inputs) }
        require(nodes.size <= 10_000)
        val snapshot = nodes.toList()
        CoverageNodeRun(snapshot, catalogComplete, nodeLimit, grid.radiusMeters.toDouble())
        val profile: (CoverageTerrainPath, Double, Int, Int) -> CoverageTerrainSampledPath = { path, spacing, samples, geometry ->
            mosaic.profile(path, spacing, samples, geometry, cancelled)
        }
        val area = CoverageArea.evaluate(grid, transmitter, budget, limits.areaSamples.toLong(), limits.areaGeometry.toLong(),
            cancelled, profile, predict)
        val nodeResults = CoverageNodeBatch.evaluate(snapshot, catalogComplete, nodeLimit, transmitter,
            grid.radiusMeters.toDouble(), budget, limits.nodeSamples, limits.nodeGeometry, limits.nodeModelCalls,
            cancelled, profile, predict)
        if (cancelled()) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.CANCELLED)
        return CoverageStudyResult(executionID, inputs, limits, area, nodeResults)
    }
}
