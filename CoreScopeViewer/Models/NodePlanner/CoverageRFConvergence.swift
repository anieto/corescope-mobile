import Foundation

enum CoverageRFConvergenceFailure: Error, Equatable { case invalidInput, invalidModel }
enum CoverageRFConvergenceState: Sendable { case qualified, accuracyUnresolved, thresholdUnresolved }

struct CoverageRFConvergenceResult: Sendable {
    let state: CoverageRFConvergenceState
    /// Only qualified results expose an estimate suitable for later node classification.
    let lossDb: Double?
    let marginDb: Double?
    let passes: Int
    let spacingMeters: Double
    let terrainEvaluations: Int
    let geometryEvaluations: Int
}

/// Sampling convergence policy from the reviewed study; does not contain a propagation model.
/// The caller supplies one fixed radio/model configuration for every pass.
enum CoverageRFConvergence {
    static func evaluate(distanceMeters: Double, maximumLossDb: Double,
                         sampleLimit: Int = 250_000, geometryLimit: Int = 200_000,
                         cancelled: () -> Bool = { false },
                         profile: (Double, Int, Int) throws -> CoverageTerrainSampledPath,
                         predictLoss: (CoverageTerrainProfile) throws -> Double) throws -> CoverageRFConvergenceResult {
        guard distanceMeters.isFinite, (250...100_000).contains(distanceMeters), maximumLossDb.isFinite,
              (1...250_000).contains(sampleLimit), (1...200_000).contains(geometryLimit) else {
            throw CoverageRFConvergenceFailure.invalidInput
        }
        func check() throws { if cancelled() { throw CoverageTerrainProfileFailure.cancelled } }
        var samples = 0, geometry = 0, passes = 0, stable = 0
        var previous: Double?
        var lastMargin = 0.0
        for spacing in [30.0, 10, 5, 2, 1] {
            try check()
            guard samples < sampleLimit, geometry < geometryLimit else { throw CoverageTerrainProfileFailure.workLimit }
            let sampled = try profile(spacing, sampleLimit - samples, geometryLimit - geometry)
            try check()
            let input = sampled.profile
            guard input.distancesMeters.count >= 2, input.distancesMeters.count == input.elevationsMeters.count,
                  input.terrainEvaluations >= input.distancesMeters.count,
                  input.terrainEvaluations <= sampleLimit - samples,
                  sampled.geometryEvaluations >= 0, sampled.geometryEvaluations <= geometryLimit - geometry,
                  input.distancesMeters.first == 0, input.distancesMeters.last == distanceMeters,
                  input.elevationsMeters.allSatisfy({ $0.isFinite }) else {
                throw CoverageRFConvergenceFailure.invalidInput
            }
            var priorDistance = -Double.infinity
            for distance in input.distancesMeters {
                guard distance.isFinite, distance > priorDistance, distance >= 0, distance <= distanceMeters else {
                    throw CoverageRFConvergenceFailure.invalidInput
                }
                priorDistance = distance
            }
            samples += input.terrainEvaluations
            geometry += sampled.geometryEvaluations
            try check()
            let loss = try predictLoss(input)
            // A synchronous RF kernel cannot be interrupted here; discard its output if cancelled.
            try check()
            let margin = maximumLossDb - loss
            guard loss.isFinite, margin.isFinite else { throw CoverageRFConvergenceFailure.invalidModel }
            passes += 1
            if let previous { stable = abs(loss - previous) <= 0.1 ? stable + 1 : 0 }
            previous = loss
            lastMargin = margin
            if spacing <= 5 && stable >= 2 && abs(margin) > 0.25 {
                return CoverageRFConvergenceResult(state: .qualified, lossDb: loss, marginDb: margin,
                    passes: passes, spacingMeters: spacing, terrainEvaluations: samples, geometryEvaluations: geometry)
            }
        }
        return CoverageRFConvergenceResult(state: abs(lastMargin) <= 0.25 ? .thresholdUnresolved : .accuracyUnresolved,
            lossDb: nil, marginDb: nil, passes: passes, spacingMeters: 1,
            terrainEvaluations: samples, geometryEvaluations: geometry)
    }
}
