import Foundation

struct CoverageStudyLimits: Sendable {
    let areaSamples: Int
    let areaGeometry: Int
    let nodeSamples: Int
    let nodeGeometry: Int
    let nodeModelCalls: Int

    func validate() throws {
        guard [areaSamples, areaGeometry, nodeSamples, nodeGeometry].allSatisfy({ (0...150_000_000).contains($0) }),
              areaSamples + nodeSamples <= 150_000_000, areaGeometry + nodeGeometry <= 150_000_000,
              (0...50_000).contains(nodeModelCalls) else { throw CoverageTerrainProfileFailure.invalidInput }
    }
}
struct CoverageStudyResult: Sendable {
    let transmitter: CoverageTerrainCoordinate
    let grid: CoverageGrid
    let budget: CoverageLinkBudget
    let limits: CoverageStudyLimits
    let area: CoverageAreaResult
    let nodes: CoverageNodeBatchResult
}

/// One serialized worker operation borrowing prepared terrain. No area-only result escapes node failure.
enum CoverageStudy {
    static func evaluate(transmitter: CoverageTerrainCoordinate, grid: CoverageGrid, budget: CoverageLinkBudget,
                         nodes: [CoverageNodeSnapshot], catalogComplete: Bool, nodeLimit: Int,
                         limits: CoverageStudyLimits, mosaic: CoverageTerrainMosaic,
                         cancelled: () -> Bool = { false },
                         predictLoss: (CoverageTerrainProfile) throws -> Double) throws -> CoverageStudyResult {
        try limits.validate()
        // Reject malformed catalogs before spending any area computation.
        _ = try CoverageNodeRun(nodes: nodes, catalogComplete: catalogComplete,
                                evaluationLimit: nodeLimit, radiusMeters: Double(grid.radiusMeters))
        func profile(_ path: CoverageTerrainPath, _ spacing: Double, _ samples: Int, _ geometry: Int) throws -> CoverageTerrainSampledPath {
            try mosaic.profile(path: path, spacingMeters: spacing, sampleLimit: samples,
                               geometryLimit: geometry, cancelled: cancelled)
        }
        let area = try CoverageArea.evaluate(grid: grid, transmitter: transmitter, budget: budget,
            sampleBudget: Int64(limits.areaSamples), geometryBudget: Int64(limits.areaGeometry),
            cancelled: cancelled, profile: profile, predictLoss: predictLoss)
        let nodeResults = try CoverageNodeBatch.evaluate(nodes: nodes, catalogComplete: catalogComplete,
            evaluationLimit: nodeLimit, transmitter: transmitter, radiusMeters: Double(grid.radiusMeters), budget: budget,
            terrainLimit: limits.nodeSamples, geometryLimit: limits.nodeGeometry, modelCallLimit: limits.nodeModelCalls,
            cancelled: cancelled, profile: profile, predictLoss: predictLoss)
        if cancelled() { throw CoverageTerrainProfileFailure.cancelled }
        return CoverageStudyResult(transmitter: transmitter, grid: grid, budget: budget, limits: limits,
                                   area: area, nodes: nodeResults)
    }
}
