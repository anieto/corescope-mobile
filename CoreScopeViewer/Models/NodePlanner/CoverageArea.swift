import Foundation

enum CoverageAreaState: String, Codable, Sendable {
    case qualified, nearSite, outsideRadius, missingTerrain, accuracyUnresolved, thresholdUnresolved, workLimit, notComputed
}
struct CoverageAreaCell: Codable, Sendable {
    let state: CoverageAreaState
    let lossDb: Double?
    let marginDb: Double?
}
struct CoverageAreaResult: Codable, Sendable {
    let cells: [CoverageAreaCell]
    let chargedSamples: Int64
    let chargedGeometry: Int64
    let workLimited: Bool
    var pendingCount: Int { cells.filter { $0.state == .notComputed }.count }
}

/// Serialized worker execution. Results are returned only after completion or explicit work exhaustion.
enum CoverageArea {
    static func evaluate(grid: CoverageGrid, transmitter: CoverageTerrainCoordinate, budget: CoverageLinkBudget,
                         sampleBudget: Int64, geometryBudget: Int64, cancelled: () -> Bool = { false },
                         resuming previous: CoverageAreaResult? = nil, shouldPause: () -> Bool = { false },
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
        var cells = previous?.cells ?? Array(repeating: CoverageAreaCell(state: .notComputed, lossDb: nil, marginDb: nil), count: grid.count)
        guard cells.count == grid.count else { throw CoverageTerrainProfileFailure.invalidInput }
        // Validate retained evidence; the prepared-study layer separately pins inputs/model/terrain.
        for index in 0..<grid.count {
            guard let point = grid.point(at: index) else { throw CoverageGridFailure.invalidParameters }
            if previous != nil {
                let saved = cells[index]
                switch point.state {
                case .nearSite:
                    guard saved.state == .nearSite else { throw CoverageTerrainProfileFailure.invalidInput }
                case .outsideRadius:
                    guard saved.state == .outsideRadius else { throw CoverageTerrainProfileFailure.invalidInput }
                case .eligible:
                    guard saved.state != .nearSite, saved.state != .outsideRadius else { throw CoverageTerrainProfileFailure.invalidInput }
                }
                if saved.state == .qualified {
                    guard let loss = saved.lossDb, let margin = saved.marginDb, loss.isFinite, margin.isFinite,
                          try budget.reception(pathLossDb: loss).adjustedMarginDb == margin else {
                        throw CoverageTerrainProfileFailure.invalidInput
                    }
                } else if saved.lossDb != nil || saved.marginDb != nil {
                    throw CoverageTerrainProfileFailure.invalidInput
                }
            } else if point.state != .eligible {
                cells[index] = CoverageAreaCell(state: point.state == .nearSite ? .nearSite : .outsideRadius, lossDb: nil, marginDb: nil)
            }
        }
        func cell(_ state: CoverageAreaState) -> CoverageAreaCell {
            CoverageAreaCell(state: state, lossDb: nil, marginDb: nil)
        }
        for index in distributedOrder(count: grid.count) {
            if cancelled() { throw CoverageTerrainProfileFailure.cancelled }
            guard let point = grid.point(at: index) else { throw CoverageGridFailure.invalidParameters }
            guard point.state == .eligible, cells[index].state == .notComputed else { continue }
            if shouldPause() { limited = true; break }
            var batchConstrained = false
            // A per-path limit does not exhaust the whole study. Preserve the masks and
            // continue other paths while aggregate work remains available.
            if limited && (samplesRemaining <= 0 || geometryRemaining <= 0) {
                cells[index] = cell(.notComputed)
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
                        batchConstrained = sampleAllowance < samples || geometryAllowance < geometry
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
                    cells[index] = CoverageAreaCell(state: .qualified, lossDb: result.lossDb, marginDb: result.marginDb)
                case .accuracyUnresolved: cells[index] = cell(.accuracyUnresolved)
                case .thresholdUnresolved: cells[index] = cell(.thresholdUnresolved)
                }
            } catch let error as CoverageTerrainProfileFailure {
                switch error {
                case .missingTerrain: cells[index] = cell(.missingTerrain)
                case .accuracyUnresolved: cells[index] = cell(.accuracyUnresolved)
                case .workLimit:
                    cells[index] = cell(batchConstrained ? .notComputed : .workLimit)
                    limited = true
                case .cancelled, .invalidInput: throw error
                }
            }
        }
        if cancelled() { throw CoverageTerrainProfileFailure.cancelled }
        return CoverageAreaResult(cells: cells, chargedSamples: sampleBudget - samplesRemaining,
                                  chargedGeometry: geometryBudget - geometryRemaining, workLimited: limited || cells.contains { $0.state == .workLimit })
    }
    /// Bit-reversal spreads successive samples across the grid while retaining row-major storage.
    /// No interpolation or missing samples are promoted to RF evidence.
    static func distributedOrder(count: Int) -> [Int] {
        guard count > 0 else { return [] }
        var width = 1, bits = 0
        while width < count { width <<= 1; bits += 1 }
        return (0..<width).compactMap { ordinal in
            var value = ordinal, reversed = 0
            for _ in 0..<bits { reversed = (reversed << 1) | (value & 1); value >>= 1 }
            return reversed < count ? reversed : nil
        }
    }
}
