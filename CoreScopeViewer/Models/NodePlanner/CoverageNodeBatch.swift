import Foundation

struct CoverageNodeBatchResult: Sendable {
    let run: CoverageNodeRun
    /// Failed profile calls retain their full reservation because partial work is not reported.
    let chargedTerrainEvaluations: Int
    let chargedGeometryEvaluations: Int
    let modelCalls: Int
}

/// Synchronous, serialized worker operation. The caller must run it off the UI thread.
enum CoverageNodeBatch {
    static func evaluate(nodes: [CoverageNodeSnapshot], catalogComplete: Bool, evaluationLimit: Int,
                         transmitter: CoverageTerrainCoordinate, radiusMeters: Double, budget: CoverageLinkBudget,
                         terrainLimit: Int, geometryLimit: Int, modelCallLimit: Int,
                         cancelled: () -> Bool = { false }, shouldPause: () -> Bool = { false },
                         resuming previous: CoverageNodeBatchResult? = nil,
                         profile: (CoverageTerrainPath, Double, Int, Int) throws -> CoverageTerrainSampledPath,
                         predictLoss: (CoverageTerrainProfile) throws -> Double) throws -> CoverageNodeBatchResult {
        guard (0...2_000_000_000).contains(terrainLimit), (0...2_000_000_000).contains(geometryLimit),
              (0...50_000).contains(modelCallLimit), transmitter.latitude.isFinite,
              transmitter.longitude.isFinite, (-90...90).contains(transmitter.latitude),
              (-180...180).contains(transmitter.longitude) else { throw CoverageTerrainProfileFailure.invalidInput }
        var run = try CoverageNodeRun(nodes: nodes, catalogComplete: catalogComplete,
                                      evaluationLimit: evaluationLimit, radiusMeters: radiusMeters)
        if let previous {
            guard previous.run.phase == .completed, previous.run.nodesToEvaluate == run.nodesToEvaluate,
                  previous.run.radiusMeters == run.radiusMeters else { throw CoverageTerrainProfileFailure.invalidInput }
        }
        var terrainRemaining = terrainLimit, geometryRemaining = geometryLimit, modelCalls = 0
        for node in run.nodesToEvaluate {
            if cancelled() { throw CoverageTerrainProfileFailure.cancelled }
            if let old = previous?.run.evaluation(for: node),
               previous?.run.status(for: node).reason != .processingLimit {
                guard run.accept(CoverageNodeEvaluation(runID: run.id, node: node,
                    distanceMeters: old.distanceMeters, prediction: old.prediction)) else {
                    throw CoverageTerrainProfileFailure.invalidInput
                }
                continue
            }
            let evaluation = try CoverageNodeEvaluator.evaluate(runID: run.id, transmitter: transmitter,
                node: node, radiusMeters: radiusMeters, budget: budget, cancelled: cancelled,
                profile: { path, spacing, samples, geometry in
                    guard !shouldPause() else { throw CoverageTerrainProfileFailure.workLimit }
                    let terrainAllowance = min(samples, terrainRemaining)
                    let geometryAllowance = min(geometry, geometryRemaining)
                    guard terrainAllowance > 0, geometryAllowance > 0, modelCalls < modelCallLimit else {
                        throw CoverageTerrainProfileFailure.workLimit
                    }
                    // Reserve before invoking work. Refund only validated, successfully reported work.
                    terrainRemaining -= terrainAllowance
                    geometryRemaining -= geometryAllowance
                    let sampled = try profile(path, spacing, terrainAllowance, geometryAllowance)
                    guard (0...terrainAllowance).contains(sampled.profile.terrainEvaluations),
                          (0...geometryAllowance).contains(sampled.geometryEvaluations) else {
                        throw CoverageTerrainProfileFailure.invalidInput
                    }
                    terrainRemaining += terrainAllowance - sampled.profile.terrainEvaluations
                    geometryRemaining += geometryAllowance - sampled.geometryEvaluations
                    return sampled
                }, predictLoss: { input in
                    guard modelCalls < modelCallLimit else { throw CoverageTerrainProfileFailure.workLimit }
                    modelCalls += 1
                    return try predictLoss(input)
                })
            guard run.accept(evaluation) else { throw CoverageTerrainProfileFailure.invalidInput }
        }
        // No partially completed run escapes cancellation or any unhandled failure.
        if cancelled() { throw CoverageTerrainProfileFailure.cancelled }
        guard run.complete() else { throw CoverageTerrainProfileFailure.invalidInput }
        return CoverageNodeBatchResult(run: run, chargedTerrainEvaluations: terrainLimit - terrainRemaining,
            chargedGeometryEvaluations: geometryLimit - geometryRemaining, modelCalls: modelCalls)
    }
}
