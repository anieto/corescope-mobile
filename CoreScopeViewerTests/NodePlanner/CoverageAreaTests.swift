import Foundation
import Testing
@testable import CoreScopeViewer

struct CoverageAreaTests {
    private func evaluate(failure: CoverageTerrainProfileFailure? = nil, cancelled: () -> Bool = { false },
                          loss: (CoverageTerrainProfile) throws -> Double = { _ in 140 }) throws -> CoverageAreaResult {
        let grid = try CoverageGrid(radiusMeters: 1000, spacingMeters: 1000,
            minEastMeters: -1000, maxEastMeters: 1000, minNorthMeters: -1000, maxNorthMeters: 1000)
        let budget = try CoverageLinkBudget(transmitPowerDbm: 22, transmitGainDbi: 3, receiveGainDbi: 2,
            transmitLossDb: 1, receiveLossDb: 1, sensitivityDbm: -130, reserveDb: 5)
        return try CoverageArea.evaluate(grid: grid, transmitter: CoverageTerrainCoordinate(latitude: 0, longitude: 0),
            budget: budget, sampleBudget: 10_000, geometryBudget: 10_000, cancelled: cancelled,
            profile: { path, _, _, _ in
                if let failure { throw failure }
                return CoverageTerrainSampledPath(profile: CoverageTerrainProfile(distancesMeters: [0, path.distanceMeters],
                    elevationsMeters: [0, 0], terrainEvaluations: 2, featureCount: 2), geometryEvaluations: 4)
            }, predictLoss: loss)
    }
    @Test func completeAreaPreservesMasksAndRawMargins() throws {
        let result = try evaluate()
        #expect(result.cells.count == 9)
        #expect(result.cells.filter { $0.state == .qualified }.count == 4)
        #expect(result.cells.filter { $0.state == .outsideRadius }.count == 4)
        #expect(result.cells[4].state == .nearSite)
        #expect(result.cells[1].marginDb == 10)
        #expect(result.chargedSamples == 24 && result.chargedGeometry == 48)
        #expect(!result.workLimited)
        let unresolved = try evaluate(loss: { _ in 150 })
        #expect(unresolved.cells.filter { $0.state == .thresholdUnresolved }.count == 4)
        #expect(unresolved.cells.allSatisfy { $0.lossDb == nil && $0.marginDb == nil })
    }
    @Test func exhaustionAndMissingTerrainNeverBecomeCoverage() throws {
        let exhausted = try evaluate(failure: .workLimit)
        #expect(exhausted.workLimited)
        #expect(exhausted.cells[0].state == .outsideRadius)
        #expect(exhausted.cells[1].state == .notComputed)
        #expect(exhausted.pendingCount == 4)
        #expect([3, 5, 7].allSatisfy { exhausted.cells[$0].state == .notComputed })
        #expect(exhausted.cells.filter { $0.state == .outsideRadius }.count == 4)
        #expect(exhausted.cells[4].state == .nearSite)
        #expect(exhausted.cells.allSatisfy { $0.lossDb == nil })
        #expect(exhausted.chargedSamples == 10_000 && exhausted.chargedGeometry == 10_000)
        let missing = try evaluate(failure: .missingTerrain)
        #expect(missing.cells[1].state == .missingTerrain)
        #expect(missing.cells[5].state == .notComputed)
        #expect(missing.cells.allSatisfy { $0.lossDb == nil })
    }
    @Test func onePathLimitDoesNotStopOtherPathsWithRemainingBudget() throws {
        let grid = try CoverageGrid.studyArea(radiusMeters: 1000, spacingMeters: 1000)
        let budget = try CoverageLinkBudget(transmitPowerDbm: 22, transmitGainDbi: 3, receiveGainDbi: 2,
            transmitLossDb: 1, receiveLossDb: 1, sensitivityDbm: -130, reserveDb: 5)
        var calls = 0
        let result = try CoverageArea.evaluate(grid: grid,
            transmitter: CoverageTerrainCoordinate(latitude: 0, longitude: 0), budget: budget,
            sampleBudget: 1_000_000, geometryBudget: 1_000_000,
            profile: { path, _, _, _ in
                calls += 1
                if calls == 1 { throw CoverageTerrainProfileFailure.workLimit }
                return CoverageTerrainSampledPath(profile: CoverageTerrainProfile(
                    distancesMeters: [0, path.distanceMeters], elevationsMeters: [0, 0],
                    terrainEvaluations: 2, featureCount: 2), geometryEvaluations: 4)
            }, predictLoss: { _ in 140 })
        #expect(result.workLimited)
        #expect(result.cells[1].state == .workLimit)
        #expect(result.cells.filter { $0.state == .qualified }.count == 3)
        #expect(!result.cells.contains { $0.state == .notComputed })
        #expect(result.chargedSamples == 250_018)
        #expect(result.chargedGeometry == 200_036)
    }

    @Test func distributedBatchesResumeWithoutRecalculatingCompletedSamples() throws {
        let grid = try CoverageGrid.studyArea(radiusMeters: 5000, spacingMeters: 1000)
        let budget = try CoverageLinkBudget(transmitPowerDbm: 22, transmitGainDbi: 3, receiveGainDbi: 2,
            transmitLossDb: 1, receiveLossDb: 1, sensitivityDbm: -130, reserveDb: 5)
        var paths = 0
        func batch(previous: CoverageAreaResult? = nil, pause: Bool = false) throws -> CoverageAreaResult {
            try CoverageArea.evaluate(grid: grid, transmitter: .init(latitude: 0, longitude: 0),
                budget: budget, sampleBudget: 1_000_000, geometryBudget: 1_000_000,
                resuming: previous, shouldPause: { pause && paths >= 8 },
                profile: { path, spacing, _, _ in
                    if spacing == 30 { paths += 1 }
                    return CoverageTerrainSampledPath(profile: CoverageTerrainProfile(
                        distancesMeters: [0, path.distanceMeters], elevationsMeters: [0, 0],
                        terrainEvaluations: 2, featureCount: 2), geometryEvaluations: 4)
                }, predictLoss: { _ in 140 })
        }
        let first = try batch(pause: true)
        #expect(first.pendingCount > 0)
        let completed = first.cells.indices.filter { first.cells[$0].state == .qualified }
        #expect(completed.count == 8)
        #expect(completed.contains { (grid.point(at: $0)?.northMeters ?? 0) < 0 })
        #expect(completed.contains { (grid.point(at: $0)?.northMeters ?? 0) > 0 })
        let remaining = first.pendingCount
        paths = 0
        let resumed = try batch(previous: first)
        #expect(paths == remaining)
        #expect(resumed.pendingCount == 0)
        let full = try batch()
        #expect(resumed.cells.map(\.state) == full.cells.map(\.state))
        #expect(resumed.cells.map(\.marginDb) == full.cells.map(\.marginDb))
        #expect(first.cells.filter { $0.state == .qualified }.count == 8)

        var invalid = first.cells
        invalid[completed[0]] = CoverageAreaCell(state: .qualified, lossDb: 140, marginDb: 999)
        #expect(throws: CoverageTerrainProfileFailure.invalidInput) {
            try batch(previous: CoverageAreaResult(cells: invalid, chargedSamples: 0, chargedGeometry: 0, workLimited: true))
        }
        for count in [1, 9, 121, 60_000] {
            let order = CoverageArea.distributedOrder(count: count)
            #expect(order.count == count && Set(order).count == count)
            #expect(order.allSatisfy { (0..<count).contains($0) })
        }
    }

    @Test func cancellationAndInvalidModelDiscardArea() {
        var cancelled = false
        #expect(throws: CoverageTerrainProfileFailure.cancelled) {
            try evaluate(cancelled: { cancelled }, loss: { _ in cancelled = true; return 140 })
        }
        #expect(throws: CoverageRFConvergenceFailure.invalidModel) { try evaluate(loss: { _ in .nan }) }
    }
}
