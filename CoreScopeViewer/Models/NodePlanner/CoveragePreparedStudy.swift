import Foundation

enum CoveragePreparationStage: Sendable { case validating, preparingTerrain, calculating }

struct CoveragePreparedStudyResult: Sendable {
    let study: CoverageStudyResult
    let terrain: [CoverageTerrainDescriptor]
}

/// Run from the study runner's worker. Owns the cache and all terrain readers for one operation.
/// Catalog trust and the qualified RF implementation are explicit caller responsibilities.
enum CoveragePreparedStudy {
    static func evaluate(inputs: CoverageStudyInputs, nodes: [CoverageNodeSnapshot],
                         catalogComplete: Bool, nodeLimit: Int, limits: CoverageStudyLimits,
                         catalog: [CoverageTerrainDescriptor], samplingHaloMeters: Double,
                         cacheRoot: URL, cacheQuota: Int64, downloadLimit: Int64,
                         offline: Bool, executionID: UUID,
                         progress: (CoveragePreparationStage) async -> Void = { _ in },
                         predictLoss: (CoverageTerrainProfile, CoverageStudyInputs) throws -> Double) async throws -> CoveragePreparedStudyResult {
        try Task.checkCancellation()
        await progress(.validating)
        try Task.checkCancellation()
        try limits.validate()
        _ = try CoverageNodeRun(nodes: nodes, catalogComplete: catalogComplete,
            evaluationLimit: nodeLimit, radiusMeters: Double(inputs.grid.radiusMeters))
        _ = try CoverageGridPreflight.estimate(grid: inputs.grid,
            sampleBudget: Int64(limits.areaSamples), geometryBudget: Int64(limits.areaGeometry))
        let footprint = try CoverageTerrainFootprint.make(latitude: inputs.transmitter.latitude,
            longitude: inputs.transmitter.longitude, radiusMeters: Double(inputs.grid.radiusMeters),
            haloMeters: samplingHaloMeters)
        let descriptors = try footprint.resolve(catalog: catalog)
        let budget = try CoverageTerrainDownloadBudget(limit: downloadLimit)
        try Task.checkCancellation()
        await progress(.preparingTerrain)
        try Task.checkCancellation()
        let cache = try CoverageTerrainDiskCache(root: cacheRoot, quota: cacheQuota)
        do {
            let mosaic = try await cache.prepareMosaic(footprint: footprint, catalog: descriptors,
                budget: budget, offline: offline)
            let study: CoverageStudyResult
            do {
                try Task.checkCancellation()
                await progress(.calculating)
                try Task.checkCancellation()
                study = try CoverageStudy.evaluate(inputs: inputs, nodes: nodes, catalogComplete: catalogComplete,
                    nodeLimit: nodeLimit, limits: limits, mosaic: mosaic, executionID: executionID,
                    cancelled: { Task.isCancelled }, predictLoss: predictLoss)
            } catch {
                mosaic.close()
                throw error
            }
            mosaic.close()
            try cache.close()
            try Task.checkCancellation()
            return CoveragePreparedStudyResult(study: study, terrain: descriptors)
        } catch {
            // Retain the calculation/transport failure; close is retried after readers were released.
            try? cache.close()
            throw error
        }
    }
}
