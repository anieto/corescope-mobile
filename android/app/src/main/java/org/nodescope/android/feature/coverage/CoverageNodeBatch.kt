package org.nodescope.android.feature.coverage

data class CoverageNodeBatchResult(val run: CoverageNodeRun, val chargedTerrainEvaluations: Int,
    val chargedGeometryEvaluations: Int, val modelCalls: Int)

/** Serialized worker operation: run off the UI thread. Failed profiles retain their full reservation. */
object CoverageNodeBatch {
    fun evaluate(nodes: List<CoverageNodeSnapshot>, catalogComplete: Boolean, evaluationLimit: Int,
                 transmitter: CoverageTerrainCoordinate, radiusMeters: Double, budget: CoverageLinkBudget,
                 terrainLimit: Int, geometryLimit: Int, modelCallLimit: Int,
                 cancelled: () -> Boolean = { false },
                 profile: (CoverageTerrainPath, Double, Int, Int) -> CoverageTerrainSampledPath,
                 predictLoss: (CoverageTerrainProfile) -> Double): CoverageNodeBatchResult {
        if (terrainLimit !in 0..2_000_000_000 || geometryLimit !in 0..2_000_000_000 || modelCallLimit !in 0..50_000 ||
            !transmitter.latitude.isFinite() || !transmitter.longitude.isFinite() ||
            transmitter.latitude !in -90.0..90.0 || transmitter.longitude !in -180.0..180.0) {
            throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.INVALID_INPUT)
        }
        val run = CoverageNodeRun(nodes, catalogComplete, evaluationLimit, radiusMeters)
        var terrainRemaining = terrainLimit
        var geometryRemaining = geometryLimit
        var modelCalls = 0
        for (node in run.nodesToEvaluate) {
            if (cancelled()) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.CANCELLED)
            val evaluation = CoverageNodeEvaluator.evaluate(run.id, transmitter, node, radiusMeters, budget, cancelled,
                profile = { path, spacing, samples, geometry ->
                    val terrainAllowance = minOf(samples, terrainRemaining)
                    val geometryAllowance = minOf(geometry, geometryRemaining)
                    if (terrainAllowance <= 0 || geometryAllowance <= 0 || modelCalls >= modelCallLimit) {
                        throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.WORK_LIMIT)
                    }
                    terrainRemaining -= terrainAllowance
                    geometryRemaining -= geometryAllowance
                    val sampled = profile(path, spacing, terrainAllowance, geometryAllowance)
                    if (sampled.profile.terrainEvaluations !in 0..terrainAllowance || sampled.geometryEvaluations !in 0..geometryAllowance) {
                        throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.INVALID_INPUT)
                    }
                    terrainRemaining += terrainAllowance - sampled.profile.terrainEvaluations
                    geometryRemaining += geometryAllowance - sampled.geometryEvaluations
                    sampled
                }, predictLoss = { input ->
                    if (modelCalls >= modelCallLimit) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.WORK_LIMIT)
                    modelCalls++
                    predictLoss(input)
                })
            if (!run.accept(evaluation)) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.INVALID_INPUT)
        }
        if (cancelled()) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.CANCELLED)
        check(run.complete())
        return CoverageNodeBatchResult(run, terrainLimit - terrainRemaining, geometryLimit - geometryRemaining, modelCalls)
    }
}
