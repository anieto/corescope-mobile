import Foundation
import Testing
@testable import CoreScopeViewer

struct CoverageNodeBatchTests {
    private let nodes = (1...2).map { CoverageNodeSnapshot(sourceID: "source", nodeID: String($0), latitude: 0, longitude: Double($0) * 0.01) }
    private func evaluate(terrain: Int = 100, geometry: Int = 100, models: Int = 100,
                          cancelled: () -> Bool = { false }, previous: CoverageNodeBatchResult? = nil,
                          profile: ((CoverageTerrainPath, Double, Int, Int) throws -> CoverageTerrainSampledPath)? = nil,
                          predict: (CoverageTerrainProfile) throws -> Double = { _ in 140 }) throws -> CoverageNodeBatchResult {
        let budget = try CoverageLinkBudget(transmitPowerDbm: 22, transmitGainDbi: 3, receiveGainDbi: 2,
            transmitLossDb: 1, receiveLossDb: 1, sensitivityDbm: -130, reserveDb: 5)
        return try CoverageNodeBatch.evaluate(nodes: nodes, catalogComplete: true, evaluationLimit: 2,
            transmitter: CoverageTerrainCoordinate(latitude: 0, longitude: 0), radiusMeters: 5000,
            budget: budget, terrainLimit: terrain, geometryLimit: geometry, modelCallLimit: models,
            cancelled: cancelled, resuming: previous, profile: profile ?? { path, _, samples, geometry in
                guard samples >= 2, geometry >= 1 else { throw CoverageTerrainProfileFailure.workLimit }
                return CoverageTerrainSampledPath(profile: CoverageTerrainProfile(distancesMeters: [0, path.distanceMeters],
                    elevationsMeters: [0, 0], terrainEvaluations: 2, featureCount: 2), geometryEvaluations: 1)
            }, predictLoss: predict)
    }

    @Test func individualPathLimitsAreNotRetriedByContinue() throws {
        var calls = 0
        let first = try evaluate(terrain: 30_000_000, geometry: 30_000_000, profile: { _, _, _, _ in
            calls += 1
            throw CoverageTerrainProfileFailure.workLimit
        })
        #expect(calls == 2)
        #expect(first.run.status(for: nodes[0]).reason == .pathWorkLimit)
        #expect(first.run.status(for: nodes[0]).state == .unknown)
        calls = 0
        let resumed = try evaluate(previous: first, profile: { _, _, _, _ in
            calls += 1
            throw CoverageTerrainProfileFailure.workLimit
        })
        #expect(calls == 0)
        #expect(resumed.run.status(for: nodes[1]).reason == .pathWorkLimit)
    }

    @Test func budgetsAreSharedAcrossNodesAndRefinements() throws {
        for result in [try evaluate(terrain: 6), try evaluate(geometry: 3), try evaluate(models: 3)] {
            #expect(result.run.phase == .completed)
            #expect(result.run.status(for: nodes[0]).state == .inCoverage)
            #expect(result.run.status(for: nodes[1]).reason == .processingLimit)
            #expect(result.chargedTerrainEvaluations == 6)
            #expect(result.chargedGeometryEvaluations == 3)
            #expect(result.modelCalls == 3)
        }
        let complete = try evaluate()
        #expect(complete.modelCalls == 6)
        #expect(complete.chargedTerrainEvaluations == 12)
        #expect(complete.chargedGeometryEvaluations == 6)
        #expect(complete.run.status(for: nodes[1]).state == .inCoverage)
    }

    @Test func continuationOnlyEvaluatesUnfinishedNodes() throws {
        let first = try evaluate(models: 3)
        var calls = 0
        let next = try evaluate(previous: first, predict: { _ in calls += 1; return 140 })
        #expect(calls == 3)
        #expect(next.run.status(for: nodes[0]).adjustedMarginDb == first.run.status(for: nodes[0]).adjustedMarginDb)
        #expect(next.run.status(for: nodes[1]).state == .inCoverage)
        #expect(next.run.id != first.run.id)
        #expect(next.chargedTerrainEvaluations == 6)
        #expect(first.run.status(for: nodes[1]).reason == .processingLimit)
        #expect(throws: CoverageTerrainProfileFailure.cancelled) {
            try evaluate(cancelled: { true }, previous: first)
        }
    }

    @Test func failedProfilesConsumeReservationsAndCannotLoop() throws {
        var calls = 0
        let result = try evaluate(profile: { _, _, _, _ in
            calls += 1
            throw CoverageTerrainProfileFailure.missingTerrain
        })
        #expect(calls == 1)
        #expect(result.modelCalls == 0)
        #expect(result.chargedTerrainEvaluations == 100)
        #expect(result.chargedGeometryEvaluations == 100)
        #expect(result.run.status(for: nodes[0]).reason == .missingTerrain)
        #expect(result.run.status(for: nodes[1]).reason == .processingLimit)
    }

    @Test func cancellationAndModelFailureNeverReturnPartialRuns() {
        var cancelled = false
        #expect(throws: CoverageTerrainProfileFailure.cancelled) {
            try evaluate(cancelled: { cancelled }, predict: { _ in cancelled = true; return 140 })
        }
        #expect(throws: CoverageRFConvergenceFailure.invalidModel) {
            try evaluate(predict: { _ in .nan })
        }
    }
}
