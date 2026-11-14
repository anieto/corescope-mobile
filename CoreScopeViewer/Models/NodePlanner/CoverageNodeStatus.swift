import Foundation

/// Identity includes the catalog source so equal IDs from different sources cannot share results.
struct CoverageNodeSnapshot: Codable, Equatable, Sendable {
    let sourceID: String
    let nodeID: String
    let latitude: Double
    let longitude: Double

    var isValid: Bool {
        !sourceID.isEmpty && !nodeID.isEmpty && latitude.isFinite && longitude.isFinite &&
        (-90...90).contains(latitude) && (-180...180).contains(longitude)
    }
}

enum CoverageNodeState: String, Sendable {
    case inCoverage, belowThreshold, unknown, notEvaluated, stale
}

enum CoverageNodeReason: String, Codable, Sendable {
    case predicted, noResult, changedNode, changedInputs, outsideRadius, invalidInput
    case missingTerrain, unsupportedPath, missingAssumptions, accuracyUnresolved, thresholdUnresolved
    case cancelled, processingLimit, pathWorkLimit
}

enum CoverageNodePrediction: Codable, Sendable {
    /// Only an exact-coordinate, qualified prediction may use this case.
    case qualifiedMargin(Double)
    case unavailable(CoverageNodeReason)

    /// Adapt only the convergence result for this exact node path and the run's fixed radio budget.
    static func fromConvergence(_ result: CoverageRFConvergenceResult, budget: CoverageLinkBudget) -> CoverageNodePrediction {
        switch result.state {
        case .accuracyUnresolved:
            return .unavailable(.accuracyUnresolved)
        case .thresholdUnresolved:
            return .unavailable(.thresholdUnresolved)
        case .qualified:
            guard let loss = result.lossDb, let margin = result.marginDb,
                  let reception = try? budget.reception(pathLossDb: loss),
                  margin == reception.adjustedMarginDb,
                  (3...5).contains(result.passes), result.spacingMeters.isFinite,
                  (1...5).contains(result.spacingMeters) else {
                return .unavailable(.invalidInput)
            }
            // A threshold-adjacent estimate must never bypass the convergence guard.
            guard abs(margin) > 0.25 else { return .unavailable(.thresholdUnresolved) }
            return .qualifiedMargin(margin)
        }
    }
}

struct CoverageNodeEvaluation: Sendable {
    /// New token required whenever calculation inputs change; not a persistent fingerprint.
    let runID: UUID
    let node: CoverageNodeSnapshot
    let distanceMeters: Double
    let prediction: CoverageNodePrediction
}

struct CoverageNodeStatus: Sendable {
    let state: CoverageNodeState
    let reason: CoverageNodeReason
    let adjustedMarginDb: Double?

    static func resolve(node: CoverageNodeSnapshot, activeRunID: UUID, radiusMeters: Double,
                        evaluation: CoverageNodeEvaluation?) -> CoverageNodeStatus {
        func result(_ state: CoverageNodeState, _ reason: CoverageNodeReason) -> CoverageNodeStatus {
            CoverageNodeStatus(state: state, reason: reason, adjustedMarginDb: nil)
        }
        guard node.isValid, radiusMeters.isFinite, radiusMeters > 0 else { return result(.unknown, .invalidInput) }
        guard let evaluation else { return result(.notEvaluated, .noResult) }
        guard evaluation.node == node else { return result(.notEvaluated, .changedNode) }
        guard evaluation.runID == activeRunID else { return result(.stale, .changedInputs) }
        guard evaluation.distanceMeters.isFinite, evaluation.distanceMeters >= 0 else { return result(.unknown, .invalidInput) }
        guard evaluation.distanceMeters <= radiusMeters else { return result(.notEvaluated, .outsideRadius) }
        switch evaluation.prediction {
        case .qualifiedMargin(let margin):
            guard margin.isFinite else { return result(.unknown, .invalidInput) }
            return CoverageNodeStatus(state: margin >= 0 ? .inCoverage : .belowThreshold,
                                      reason: .predicted, adjustedMarginDb: margin)
        case .unavailable(let reason):
            switch reason {
            case .cancelled, .processingLimit, .noResult:
                return result(.notEvaluated, reason)
            case .missingTerrain, .unsupportedPath, .missingAssumptions, .accuracyUnresolved, .thresholdUnresolved, .pathWorkLimit:
                return result(.unknown, reason)
            default:
                return result(.unknown, .invalidInput)
            }
        }
    }
}
