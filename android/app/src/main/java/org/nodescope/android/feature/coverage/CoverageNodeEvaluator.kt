package org.nodescope.android.feature.coverage

import java.util.UUID
import kotlin.math.*

/** Exact-node orchestration. Caller fixes model configuration and serializes terrain access. */
object CoverageNodeEvaluator {
    /** Borrows the prepared mosaic; its owner closes pinned readers after the run. */
    fun evaluate(runID: UUID, transmitter: CoverageTerrainCoordinate, node: CoverageNodeSnapshot,
                 radiusMeters: Double, budget: CoverageLinkBudget, mosaic: CoverageTerrainMosaic,
                 cancelled: () -> Boolean = { false },
                 predictLoss: (CoverageTerrainProfile) -> Double): CoverageNodeEvaluation =
        evaluate(runID, transmitter, node, radiusMeters, budget, cancelled,
            profile = { path, spacing, samples, geometry ->
                mosaic.profile(path, spacing, samples, geometry, cancelled)
            }, predictLoss = predictLoss)


    fun evaluate(runID: UUID, transmitter: CoverageTerrainCoordinate, node: CoverageNodeSnapshot,
                 radiusMeters: Double, budget: CoverageLinkBudget, cancelled: () -> Boolean = { false },
                 profile: (CoverageTerrainPath, Double, Int, Int) -> CoverageTerrainSampledPath,
                 predictLoss: (CoverageTerrainProfile) -> Double): CoverageNodeEvaluation {
        if (!node.isValid || !transmitter.latitude.isFinite() || !transmitter.longitude.isFinite() ||
            transmitter.latitude !in -90.0..90.0 || transmitter.longitude !in -180.0..180.0 ||
            !radiusMeters.isFinite() || radiusMeters <= 0) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.INVALID_INPUT)
        if (cancelled()) throw CoverageTerrainProfileFailure(CoverageTerrainProfileState.CANCELLED)
        val radians = PI / 180
        val a = transmitter.latitude * radians
        val b = node.latitude * radians
        val delta = (node.longitude - transmitter.longitude) * radians
        val h = sin((b - a) / 2).pow(2) + cos(a) * cos(b) * sin(delta / 2).pow(2)
        val distance = 2 * CoverageTerrainFootprint.EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
        fun evaluation(prediction: CoverageNodePrediction) = CoverageNodeEvaluation(runID, node, distance, prediction)
        if (distance > radiusMeters) return evaluation(CoverageNodePrediction.Unavailable(CoverageNodeReason.outsideRadius))
        if (distance !in 250.0..100_000.0 || abs(transmitter.latitude) > 85 || abs(node.latitude) > 85) {
            return evaluation(CoverageNodePrediction.Unavailable(CoverageNodeReason.unsupportedPath))
        }
        val angle = atan2(sin(delta) * cos(b), cos(a) * sin(b) - sin(a) * cos(b) * cos(delta)) / radians
        val path = CoverageTerrainPath(transmitter.latitude, transmitter.longitude, angle - 360 * floor(angle / 360), distance)
        return try {
            val result = CoverageRFConvergence.evaluate(distance, budget.maximumLossDb, cancelled = cancelled,
                profile = { spacing, samples, geometry -> profile(path, spacing, samples, geometry) }, predictLoss = predictLoss)
            evaluation(CoverageNodePrediction.fromConvergence(result, budget))
        } catch (failure: CoverageTerrainProfileFailure) {
            when (failure.state) {
                CoverageTerrainProfileState.MISSING_TERRAIN -> evaluation(CoverageNodePrediction.Unavailable(CoverageNodeReason.missingTerrain))
                CoverageTerrainProfileState.ACCURACY_UNRESOLVED -> evaluation(CoverageNodePrediction.Unavailable(CoverageNodeReason.accuracyUnresolved))
                CoverageTerrainProfileState.WORK_LIMIT -> evaluation(CoverageNodePrediction.Unavailable(CoverageNodeReason.processingLimit))
                CoverageTerrainProfileState.CANCELLED, CoverageTerrainProfileState.INVALID_INPUT -> throw failure
            }
        }
    }
}
