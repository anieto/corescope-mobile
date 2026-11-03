import Foundation

/// Exact-node orchestration; the caller owns one fixed model configuration and serialized terrain access.
enum CoverageNodeEvaluator {
    static func evaluate(runID: UUID, transmitter: CoverageTerrainCoordinate, node: CoverageNodeSnapshot,
                         radiusMeters: Double, budget: CoverageLinkBudget,
                         cancelled: () -> Bool = { false },
                         profile: (CoverageTerrainPath, Double, Int, Int) throws -> CoverageTerrainSampledPath,
                         predictLoss: (CoverageTerrainProfile) throws -> Double) throws -> CoverageNodeEvaluation {
        guard node.isValid, transmitter.latitude.isFinite, transmitter.longitude.isFinite,
              (-90...90).contains(transmitter.latitude), (-180...180).contains(transmitter.longitude),
              radiusMeters.isFinite, radiusMeters > 0 else { throw CoverageTerrainProfileFailure.invalidInput }
        if cancelled() { throw CoverageTerrainProfileFailure.cancelled }
        let radians = Double.pi / 180
        let a = transmitter.latitude * radians, b = node.latitude * radians
        let delta = (node.longitude - transmitter.longitude) * radians
        let h = pow(sin((b - a) / 2), 2) + cos(a) * cos(b) * pow(sin(delta / 2), 2)
        let distance = 2 * CoverageTerrainFootprint.earthRadiusMeters * asin(sqrt(min(1, max(0, h))))
        func evaluation(_ prediction: CoverageNodePrediction) -> CoverageNodeEvaluation {
            CoverageNodeEvaluation(runID: runID, node: node, distanceMeters: distance, prediction: prediction)
        }
        // Radius is the study boundary, not a failure of reception.
        guard distance <= radiusMeters else { return evaluation(.unavailable(.outsideRadius)) }
        guard (250...100_000).contains(distance), abs(transmitter.latitude) <= 85, abs(node.latitude) <= 85 else {
            return evaluation(.unavailable(.unsupportedPath))
        }
        let angle = atan2(sin(delta) * cos(b), cos(a) * sin(b) - sin(a) * cos(b) * cos(delta)) / radians
        let bearing = angle - 360 * floor(angle / 360)
        let path = try CoverageTerrainPath(latitude: transmitter.latitude, longitude: transmitter.longitude,
                                          bearingDegrees: bearing, distanceMeters: distance)
        do {
            let result = try CoverageRFConvergence.evaluate(distanceMeters: distance, maximumLossDb: budget.maximumLossDb,
                cancelled: cancelled, profile: { spacing, samples, geometry in
                    try profile(path, spacing, samples, geometry)
                }, predictLoss: predictLoss)
            return evaluation(.fromConvergence(result, budget: budget))
        } catch let failure as CoverageTerrainProfileFailure {
            switch failure {
            case .missingTerrain: return evaluation(.unavailable(.missingTerrain))
            case .accuracyUnresolved: return evaluation(.unavailable(.accuracyUnresolved))
            case .workLimit: return evaluation(.unavailable(.processingLimit))
            case .cancelled, .invalidInput: throw failure
            }
        }
    }
}
