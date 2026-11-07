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
/// Sources selected for a prepared study, not a complete RF reproducibility record.
struct CoverageStudyTerrainSources: Sendable {
    let descriptors: [CoverageTerrainDescriptor]
}

struct CoverageStudyResult: Sendable {
    let executionID: UUID
    let inputs: CoverageStudyInputs
    var transmitter: CoverageTerrainCoordinate { inputs.transmitter }
    var grid: CoverageGrid { inputs.grid }
    var budget: CoverageLinkBudget { inputs.budget }
    let limits: CoverageStudyLimits
    let area: CoverageAreaResult
    let nodes: CoverageNodeBatchResult
    let terrainSources: CoverageStudyTerrainSources?

    init(executionID: UUID, inputs: CoverageStudyInputs, limits: CoverageStudyLimits,
         area: CoverageAreaResult, nodes: CoverageNodeBatchResult,
         terrainSources: CoverageStudyTerrainSources? = nil) {
        self.executionID = executionID
        self.inputs = inputs
        self.limits = limits
        self.area = area
        self.nodes = nodes
        self.terrainSources = terrainSources
    }
}

/// One serialized worker operation borrowing prepared terrain. No area-only result escapes node failure.
enum CoverageStudy {
    static func evaluate(inputs: CoverageStudyInputs,
                         nodes: [CoverageNodeSnapshot], catalogComplete: Bool, nodeLimit: Int,
                         limits: CoverageStudyLimits, mosaic: CoverageTerrainMosaic, executionID: UUID = UUID(),
                         cancelled: () -> Bool = { false },
                         predictLoss: (CoverageTerrainProfile, CoverageStudyInputs) throws -> Double) throws -> CoverageStudyResult {
        try limits.validate()
        let transmitter = inputs.transmitter, grid = inputs.grid, budget = inputs.budget
        func predict(_ profile: CoverageTerrainProfile) throws -> Double { try predictLoss(profile, inputs) }
        // Reject malformed catalogs before spending any area computation.
        _ = try CoverageNodeRun(nodes: nodes, catalogComplete: catalogComplete,
                                evaluationLimit: nodeLimit, radiusMeters: Double(grid.radiusMeters))
        func profile(_ path: CoverageTerrainPath, _ spacing: Double, _ samples: Int, _ geometry: Int) throws -> CoverageTerrainSampledPath {
            try mosaic.profile(path: path, spacingMeters: spacing, sampleLimit: samples,
                               geometryLimit: geometry, cancelled: cancelled)
        }
        let area = try CoverageArea.evaluate(grid: grid, transmitter: transmitter, budget: budget,
            sampleBudget: Int64(limits.areaSamples), geometryBudget: Int64(limits.areaGeometry),
            cancelled: cancelled, profile: profile, predictLoss: predict)
        let nodeResults = try CoverageNodeBatch.evaluate(nodes: nodes, catalogComplete: catalogComplete,
            evaluationLimit: nodeLimit, transmitter: transmitter, radiusMeters: Double(grid.radiusMeters), budget: budget,
            terrainLimit: limits.nodeSamples, geometryLimit: limits.nodeGeometry, modelCallLimit: limits.nodeModelCalls,
            cancelled: cancelled, profile: profile, predictLoss: predict)
        if cancelled() { throw CoverageTerrainProfileFailure.cancelled }
        return CoverageStudyResult(executionID: executionID, inputs: inputs, limits: limits,
                                   area: area, nodes: nodeResults)
    }
}
