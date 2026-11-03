package org.nodescope.android.feature.coverage

import java.util.UUID

data class CoverageNodeSnapshot(val sourceID: String, val nodeID: String, val latitude: Double, val longitude: Double) {
    val isValid: Boolean get() = sourceID.isNotEmpty() && nodeID.isNotEmpty() && latitude.isFinite() &&
        longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}
enum class CoverageNodeState { inCoverage, belowThreshold, unknown, notEvaluated, stale }
enum class CoverageNodeReason {
    predicted, noResult, changedNode, changedInputs, outsideRadius, invalidInput,
    missingTerrain, unsupportedPath, missingAssumptions, accuracyUnresolved, thresholdUnresolved, cancelled, processingLimit
}
sealed interface CoverageNodePrediction {
    /** Only an exact-coordinate, qualified prediction may use this case. */
    data class QualifiedMargin(val margin: Double) : CoverageNodePrediction
    data class Unavailable(val reason: CoverageNodeReason) : CoverageNodePrediction
}
/** New run token required whenever inputs change; not a persistent fingerprint. */
data class CoverageNodeEvaluation(val runID: UUID, val node: CoverageNodeSnapshot,
    val distanceMeters: Double, val prediction: CoverageNodePrediction)
data class CoverageNodeStatus(val state: CoverageNodeState, val reason: CoverageNodeReason, val adjustedMarginDb: Double?) {
    companion object {
        fun resolve(node: CoverageNodeSnapshot, activeRunID: UUID, radiusMeters: Double,
                    evaluation: CoverageNodeEvaluation?): CoverageNodeStatus {
            fun result(state: CoverageNodeState, reason: CoverageNodeReason) = CoverageNodeStatus(state, reason, null)
            if (!node.isValid || !radiusMeters.isFinite() || radiusMeters <= 0) return result(CoverageNodeState.unknown, CoverageNodeReason.invalidInput)
            if (evaluation == null) return result(CoverageNodeState.notEvaluated, CoverageNodeReason.noResult)
            if (evaluation.node != node) return result(CoverageNodeState.notEvaluated, CoverageNodeReason.changedNode)
            if (evaluation.runID != activeRunID) return result(CoverageNodeState.stale, CoverageNodeReason.changedInputs)
            if (!evaluation.distanceMeters.isFinite() || evaluation.distanceMeters < 0) return result(CoverageNodeState.unknown, CoverageNodeReason.invalidInput)
            if (evaluation.distanceMeters > radiusMeters) return result(CoverageNodeState.notEvaluated, CoverageNodeReason.outsideRadius)
            return when (val prediction = evaluation.prediction) {
                is CoverageNodePrediction.QualifiedMargin -> {
                    if (!prediction.margin.isFinite()) result(CoverageNodeState.unknown, CoverageNodeReason.invalidInput)
                    else CoverageNodeStatus(if (prediction.margin >= 0) CoverageNodeState.inCoverage else CoverageNodeState.belowThreshold,
                        CoverageNodeReason.predicted, prediction.margin)
                }
                is CoverageNodePrediction.Unavailable -> when (prediction.reason) {
                    CoverageNodeReason.cancelled, CoverageNodeReason.processingLimit, CoverageNodeReason.noResult -> result(CoverageNodeState.notEvaluated, prediction.reason)
                    CoverageNodeReason.missingTerrain, CoverageNodeReason.unsupportedPath, CoverageNodeReason.missingAssumptions,
                    CoverageNodeReason.accuracyUnresolved, CoverageNodeReason.thresholdUnresolved -> result(CoverageNodeState.unknown, prediction.reason)
                    else -> result(CoverageNodeState.unknown, CoverageNodeReason.invalidInput)
                }
            }
        }
    }
}
