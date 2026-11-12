import Foundation

enum CoveragePreparationStage: Sendable { case validating, preparingTerrain, calculating }

/// Run from the study runner's worker. Owns the cache and all terrain readers for one operation.
/// An omitted catalog uses direct AWS Skadi discovery. Offline runs require retained descriptors.
/// Defaults to the pinned inland terrain-only kernel; injected predictors remain unqualified.
enum CoveragePreparedStudy {
    static func evaluate(inputs: CoverageStudyInputs, nodes: [CoverageNodeSnapshot],
                         catalogComplete: Bool, nodeLimit: Int, limits: CoverageStudyLimits,
                         catalog: [CoverageTerrainDescriptor]? = nil, samplingHaloMeters: Double,
                         cacheRoot: URL, cacheQuota: Int64, downloadLimit: Int64,
                         offline: Bool, executionID: UUID,
                         resuming previous: CoverageStudyResult? = nil, diagnosticURL: URL? = nil,
                         progress: (CoveragePreparationStage) async -> Void = { _ in },
                         predictLoss: ((CoverageTerrainProfile, CoverageStudyInputs) throws -> Double)? = nil) async throws -> CoverageStudyResult {
        let model = predictLoss == nil ? CoverageStudyModel(sourceRevision: CoverageRFKernel.sourceRevision,
            scenario: CoverageRFKernel.scenario) : nil
        let diagnostics = CoverageRunDiagnostics(inputs: inputs, fileURL: diagnosticURL)
        diagnostics.setPhase("validating")
        defer { diagnostics.finishIfNeeded(cancelled: Task.isCancelled) }
        let validatedNodes = try CoverageNodeRun(nodes: nodes, catalogComplete: catalogComplete,
            evaluationLimit: nodeLimit, radiusMeters: Double(inputs.grid.radiusMeters))
        if let previous {
            guard previous.inputs.matches(inputs), previous.model == model,
                  previous.nodes.run.nodesToEvaluate == validatedNodes.nodesToEvaluate,
                  previous.nodes.run.catalogComplete == catalogComplete,
                  previous.terrainSources != nil else { throw CoverageTerrainProfileFailure.invalidInput }
        }
        let predictor = predictLoss ?? { profile, inputs in
            guard let latitude = profile.pathCenterLatitude else { throw CoverageRFConvergenceFailure.invalidInput }
            return try CoverageRFKernel.predict(profile: profile, inputs: inputs, pathCenterLatitude: latitude)
        }
        try Task.checkCancellation()
        await progress(.validating)
        try Task.checkCancellation()
        try limits.validate()
        _ = try CoverageGridPreflight.estimate(grid: inputs.grid,
            sampleBudget: Int64(limits.areaSamples), geometryBudget: Int64(limits.areaGeometry))
        let footprint = try CoverageTerrainFootprint.make(latitude: inputs.transmitter.latitude,
            longitude: inputs.transmitter.longitude, radiusMeters: Double(inputs.grid.radiusMeters),
            haloMeters: samplingHaloMeters)
        let budget = try CoverageTerrainDownloadBudget(limit: downloadLimit)
        try Task.checkCancellation()
        diagnostics.setPhase("preparing terrain")
        await progress(.preparingTerrain)
        try Task.checkCancellation()
        let descriptors: [CoverageTerrainDescriptor]
        if let previous, let sources = previous.terrainSources {
            // Resume against exactly the same immutable terrain identities, even if discovery changes.
            descriptors = try footprint.resolve(catalog: sources.descriptors)
        } else if let catalog { descriptors = try footprint.resolve(catalog: catalog) }
        else {
            guard !offline else { throw CoverageTerrainCacheFailure.missing }
            descriptors = try await CoverageSkadiProvider.discover(footprint: footprint)
        }
        try Task.checkCancellation()
        let cache = try CoverageTerrainDiskCache(root: cacheRoot, quota: cacheQuota)
        do {
            let mosaic = try await cache.prepareMosaic(footprint: footprint, catalog: descriptors,
                budget: budget, offline: offline)
            let study: CoverageStudyResult
            do {
                try Task.checkCancellation()
                diagnostics.setPhase("calculating")
                await progress(.calculating)
                try Task.checkCancellation()
                study = try CoverageStudy.evaluate(inputs: inputs, nodes: nodes, catalogComplete: catalogComplete,
                    nodeLimit: nodeLimit, limits: limits, mosaic: mosaic, executionID: executionID,
                    cancelled: { Task.isCancelled }, resuming: previous?.area, resumingNodes: previous?.nodes,
                    shouldPause: { diagnostics.shouldPause() }, predictLoss: predictor)
            } catch {
                mosaic.close()
                throw error
            }
            mosaic.close()
            try cache.close()
            try Task.checkCancellation()
            let result = CoverageStudyResult(executionID: study.executionID, inputs: study.inputs,
                limits: study.limits, area: study.area, nodes: study.nodes,
                terrainSources: CoverageStudyTerrainSources(descriptors: descriptors), model: model)
            diagnostics.finish(result)
            return result
        } catch {
            diagnostics.setPhase(Task.isCancelled ? "cancelled" : "failed: \(String(describing: error))")
            // Retain the calculation/transport failure; close is retried after readers were released.
            try? cache.close()
            throw error
        }
    }
}
