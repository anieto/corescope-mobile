import Foundation

enum CoverageAreaState: String, Sendable {
    case qualified, nearSite, outsideRadius, missingTerrain, accuracyUnresolved, thresholdUnresolved, workLimit, notComputed
}
struct CoverageAreaCell: Sendable {
    let state: CoverageAreaState
    let lossDb: Double?
    let marginDb: Double?
}
struct CoverageAreaResult: Sendable {
    let cells: [CoverageAreaCell]
    let chargedSamples: Int64
    let chargedGeometry: Int64
    let workLimited: Bool
}

/// Serialized worker execution. Results are returned only after completion or explicit work exhaustion.
enum CoverageArea {
    static func evaluate(grid: CoverageGrid, transmitter: CoverageTerrainCoordinate, budget: CoverageLinkBudget,
                         sampleBudget: Int64, geometryBudget: Int64, cancelled: () -> Bool = { false },
                         profile: (CoverageTerrainPath, Double, Int, Int) throws -> CoverageTerrainSampledPath,
                         predictLoss: (CoverageTerrainProfile) throws -> Double) throws -> CoverageAreaResult {
        guard transmitter.latitude.isFinite, transmitter.longitude.isFinite,
              (-85...85).contains(transmitter.latitude), (-180...180).contains(transmitter.longitude) else {
            throw CoverageGridFailure.invalidParameters
        }
        _ = try CoverageGridPreflight.estimate(grid: grid, sampleBudget: sampleBudget,
                                              geometryBudget: geometryBudget, cancelled: cancelled)
        var samplesRemaining = sampleBudget, geometryRemaining = geometryBudget
        var limited = false
        var cells: [CoverageAreaCell] = []
        cells.reserveCapacity(grid.count)
        func cell(_ state: CoverageAreaState) -> CoverageAreaCell {
            CoverageAreaCell(state: state, lossDb: nil, marginDb: nil)
        }
        for index in 0..<grid.count {
            if cancelled() { throw CoverageTerrainProfileFailure.cancelled }
            if limited { cells.append(cell(.notComputed)); continue }
            guard let point = grid.point(at: index) else { throw CoverageGridFailure.invalidParameters }
            if point.state != .eligible {
                cells.append(cell(point.state == .nearSite ? .nearSite : .outsideRadius))
                continue
            }
            do {
                guard let path = try grid.path(at: index, transmitter: transmitter) else {
                    throw CoverageGridFailure.invalidParameters
                }
                let result = try CoverageRFConvergence.evaluate(distanceMeters: path.distanceMeters,
                    maximumLossDb: budget.maximumLossDb, cancelled: cancelled,
                    profile: { spacing, samples, geometry in
                        let sampleAllowance = min(samples, Int(samplesRemaining))
                        let geometryAllowance = min(geometry, Int(geometryRemaining))
                        guard sampleAllowance > 0, geometryAllowance > 0 else { throw CoverageTerrainProfileFailure.workLimit }
                        // A failed profile retains its reservation: partial work is not reported.
                        samplesRemaining -= Int64(sampleAllowance)
                        geometryRemaining -= Int64(geometryAllowance)
                        let sampled = try profile(path, spacing, sampleAllowance, geometryAllowance)
                        guard (0...sampleAllowance).contains(sampled.profile.terrainEvaluations),
                              (0...geometryAllowance).contains(sampled.geometryEvaluations) else {
                            throw CoverageTerrainProfileFailure.invalidInput
                        }
                        samplesRemaining += Int64(sampleAllowance - sampled.profile.terrainEvaluations)
                        geometryRemaining += Int64(geometryAllowance - sampled.geometryEvaluations)
                        return sampled
                    }, predictLoss: predictLoss)
                switch result.state {
                case .qualified:
                    cells.append(CoverageAreaCell(state: .qualified, lossDb: result.lossDb, marginDb: result.marginDb))
                case .accuracyUnresolved: cells.append(cell(.accuracyUnresolved))
                case .thresholdUnresolved: cells.append(cell(.thresholdUnresolved))
                }
            } catch let error as CoverageTerrainProfileFailure {
                switch error {
                case .missingTerrain: cells.append(cell(.missingTerrain))
                case .accuracyUnresolved: cells.append(cell(.accuracyUnresolved))
                case .workLimit: cells.append(cell(.workLimit)); limited = true
                case .cancelled, .invalidInput: throw error
                }
            }
        }
        if cancelled() { throw CoverageTerrainProfileFailure.cancelled }
        return CoverageAreaResult(cells: cells, chargedSamples: sampleBudget - samplesRemaining,
                                  chargedGeometry: geometryBudget - geometryRemaining, workLimited: limited)
    }
}
