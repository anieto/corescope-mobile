import Foundation

struct CoverageStudyLimits: Codable, Sendable {
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

struct CoverageStudyModel: Codable, Sendable, Equatable {
    let sourceRevision: String
    let scenario: String
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
    let model: CoverageStudyModel?
    let terrainSources: CoverageStudyTerrainSources?
    var hasPendingWork: Bool {
        area.pendingCount > 0 || nodes.run.nodesToEvaluate.contains { nodes.run.status(for: $0).reason == .processingLimit }
    }

    init(executionID: UUID, inputs: CoverageStudyInputs, limits: CoverageStudyLimits,
         area: CoverageAreaResult, nodes: CoverageNodeBatchResult,
         terrainSources: CoverageStudyTerrainSources? = nil, model: CoverageStudyModel? = nil) {
        self.executionID = executionID
        self.inputs = inputs
        self.limits = limits
        self.area = area
        self.nodes = nodes
        self.terrainSources = terrainSources
        self.model = model
    }
}

/// One serialized worker operation borrowing prepared terrain. No area-only result escapes node failure.
enum CoverageStudy {
    static func evaluate(inputs: CoverageStudyInputs,
                         nodes: [CoverageNodeSnapshot], catalogComplete: Bool, nodeLimit: Int,
                         limits: CoverageStudyLimits, mosaic: CoverageTerrainMosaic, executionID: UUID = UUID(),
                         cancelled: () -> Bool = { false },
                         resuming: CoverageAreaResult? = nil, resumingNodes: CoverageNodeBatchResult? = nil,
                         shouldPause: () -> Bool = { false },
                         progress: (CoveragePreparationStage) -> Void = { _ in },
                         diagnostics: CoverageRunDiagnostics? = nil,
                         predictLoss: (CoverageTerrainProfile, CoverageStudyInputs) throws -> Double) throws -> CoverageStudyResult {
        try limits.validate()
        let transmitter = inputs.transmitter, grid = inputs.grid, budget = inputs.budget
        func predict(_ profile: CoverageTerrainProfile) throws -> Double {
            if let diagnostics { return try diagnostics.measure("rfPrediction") { try predictLoss(profile, inputs) } }
            return try predictLoss(profile, inputs)
        }
        // Reject malformed catalogs before spending any area computation.
        _ = try CoverageNodeRun(nodes: nodes, catalogComplete: catalogComplete,
                                evaluationLimit: nodeLimit, radiusMeters: Double(grid.radiusMeters))
        func profile(_ path: CoverageTerrainPath, _ spacing: Double, _ samples: Int, _ geometry: Int) throws -> CoverageTerrainSampledPath {
            let started = ContinuousClock.now
            defer { diagnostics?.recordDuration("pathPreparation", since: started) }
            return try mosaic.profile(path: path, spacingMeters: spacing, sampleLimit: samples,
                               geometryLimit: geometry, cancelled: cancelled)
        }
        let area = try CoverageArea.evaluate(grid: grid, transmitter: transmitter, budget: budget,
            sampleBudget: Int64(limits.areaSamples), geometryBudget: Int64(limits.areaGeometry),
            cancelled: cancelled, resuming: resuming, shouldPause: shouldPause,
            progress: { progress(.mapSamples(completed: $0, total: $1)) }, profile: profile, predictLoss: predict)
        progress(.checkingNodes)
        let nodeResults = try CoverageNodeBatch.evaluate(nodes: nodes, catalogComplete: catalogComplete,
            evaluationLimit: nodeLimit, transmitter: transmitter, radiusMeters: Double(grid.radiusMeters), budget: budget,
            terrainLimit: limits.nodeSamples, geometryLimit: limits.nodeGeometry, modelCallLimit: limits.nodeModelCalls,
            cancelled: cancelled, shouldPause: shouldPause, resuming: resumingNodes, profile: profile, predictLoss: predict)
        if cancelled() { throw CoverageTerrainProfileFailure.cancelled }
        return CoverageStudyResult(executionID: executionID, inputs: inputs, limits: limits,
                                   area: area, nodes: nodeResults)
    }
}

// Reuse only losses from the same terrain/model and propagation inputs. The caller
// verifies the node catalog and retains terrain provenance for any subsequent resume.
extension CoverageStudyResult {
    func updatingBudget(_ updated: CoverageStudyInputs) throws -> CoverageStudyResult {
        guard inputs.matchesPropagation(updated),
              model?.sourceRevision == CoverageRFKernel.sourceRevision,
              model?.scenario == CoverageRFKernel.scenario,
              terrainSources != nil else { throw CoverageTerrainProfileFailure.invalidInput }
        let cells = try area.cells.map { cell -> CoverageAreaCell in
            if cell.state == .qualified, let loss = cell.lossDb {
                let margin = try updated.budget.reception(pathLossDb: loss).adjustedMarginDb
                if abs(margin) > 0.25 {
                    return CoverageAreaCell(state: .qualified, lossDb: loss, marginDb: margin)
                }
                return CoverageAreaCell(state: .notComputed, lossDb: nil, marginDb: nil)
            }
            // These paths may qualify under the new threshold; leave fresh evaluation to Continue.
            if cell.state == .thresholdUnresolved || cell.state == .accuracyUnresolved || cell.state == .workLimit {
                return CoverageAreaCell(state: .notComputed, lossDb: nil, marginDb: nil)
            }
            return cell
        }
        var run = try CoverageNodeRun(nodes: nodes.run.nodesToEvaluate,
            catalogComplete: nodes.run.catalogComplete, evaluationLimit: max(1, nodes.run.nodesToEvaluate.count),
            radiusMeters: nodes.run.radiusMeters)
        for node in run.nodesToEvaluate {
            guard let old = nodes.run.evaluation(for: node) else { throw CoverageTerrainProfileFailure.invalidInput }
            let prediction: CoverageNodePrediction
            switch old.prediction {
            case .qualifiedMargin(let margin):
                let loss = inputs.budget.maximumLossDb - margin
                let next = try updated.budget.reception(pathLossDb: loss).adjustedMarginDb
                prediction = abs(next) > 0.25 ? .qualifiedMargin(next) : .unavailable(.processingLimit)
            case .unavailable(let reason):
                prediction = [.thresholdUnresolved, .accuracyUnresolved, .processingLimit, .pathWorkLimit].contains(reason)
                    ? .unavailable(.processingLimit) : old.prediction
            }
            guard run.accept(CoverageNodeEvaluation(runID: run.id, node: node,
                distanceMeters: old.distanceMeters, prediction: prediction)) else { throw CoverageTerrainProfileFailure.invalidInput }
        }
        guard run.complete() else { throw CoverageTerrainProfileFailure.invalidInput }
        return CoverageStudyResult(executionID: UUID(), inputs: updated, limits: limits,
            area: CoverageAreaResult(cells: cells, chargedSamples: 0, chargedGeometry: 0,
                workLimited: cells.contains { $0.state == .notComputed }),
            nodes: CoverageNodeBatchResult(run: run, chargedTerrainEvaluations: 0,
                chargedGeometryEvaluations: 0, modelCalls: 0), terrainSources: terrainSources, model: model)
    }
}
