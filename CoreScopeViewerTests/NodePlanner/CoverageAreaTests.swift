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
        #expect(exhausted.cells[1].state == .workLimit)
        #expect(exhausted.cells.dropFirst(2).allSatisfy { $0.state == .notComputed && $0.lossDb == nil })
        #expect(exhausted.chargedSamples == 10_000 && exhausted.chargedGeometry == 10_000)
        let missing = try evaluate(failure: .missingTerrain)
        #expect(missing.cells[1].state == .missingTerrain)
        #expect(missing.cells[3].state == .workLimit)
        #expect(missing.cells.allSatisfy { $0.lossDb == nil })
    }
    @Test func cancellationAndInvalidModelDiscardArea() {
        var cancelled = false
        #expect(throws: CoverageTerrainProfileFailure.cancelled) {
            try evaluate(cancelled: { cancelled }, loss: { _ in cancelled = true; return 140 })
        }
        #expect(throws: CoverageRFConvergenceFailure.invalidModel) { try evaluate(loss: { _ in .nan }) }
    }
}
