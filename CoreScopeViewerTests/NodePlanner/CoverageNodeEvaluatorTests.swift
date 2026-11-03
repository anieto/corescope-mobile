import Foundation
import Testing
@testable import CoreScopeViewer

struct CoverageNodeEvaluatorTests {
    private func budget() throws -> CoverageLinkBudget {
        try CoverageLinkBudget(transmitPowerDbm: 22, transmitGainDbi: 3, receiveGainDbi: 2,
            transmitLossDb: 1, receiveLossDb: 1, sensitivityDbm: -130, reserveDb: 5)
    }
    @Test func exactDatelinePathFeedsConvergenceAndRun() throws {
        let node = CoverageNodeSnapshot(sourceID: "source", nodeID: "node", latitude: 0, longitude: -179.99)
        var run = try CoverageNodeRun(nodes: [node], catalogComplete: true, evaluationLimit: 1, radiusMeters: 5000)
        var calls = 0
        let result = try CoverageNodeEvaluator.evaluate(runID: run.id,
            transmitter: CoverageTerrainCoordinate(latitude: 0, longitude: 179.99), node: node,
            radiusMeters: run.radiusMeters, budget: budget(), profile: { path, _, _, _ in
                calls += 1
                let end = try path.point(at: path.distanceMeters)
                #expect(abs(end.latitude - node.latitude) < 1e-8)
                #expect(abs(end.longitude - node.longitude) < 1e-8)
                #expect(abs(path.bearingDegrees - 90) < 1e-8)
                return CoverageTerrainSampledPath(profile: CoverageTerrainProfile(distancesMeters: [0, path.distanceMeters],
                    elevationsMeters: [0, 0], terrainEvaluations: 2, featureCount: 2), geometryEvaluations: 0)
            }, predictLoss: { _ in 140 })
        #expect(calls == 3)
        #expect(result.distanceMeters > 2200 && result.distanceMeters < 2300)
        let accepted = run.accept(result)
        let completed = run.complete()
        #expect(accepted && completed)
        #expect(run.status(for: node).state == .inCoverage)
    }

    @Test func excludedAndUnsupportedPathsNeverReadTerrain() throws {
        for (longitude, radius, reason) in [(1.0, 1000.0, CoverageNodeReason.outsideRadius),
                                           (0.0001, 1000, .unsupportedPath), (2, 300_000, .unsupportedPath)] {
            let node = CoverageNodeSnapshot(sourceID: "source", nodeID: "node", latitude: 0, longitude: longitude)
            let run = UUID()
            let result = try CoverageNodeEvaluator.evaluate(runID: run,
                transmitter: CoverageTerrainCoordinate(latitude: 0, longitude: 0), node: node,
                radiusMeters: radius, budget: budget(), profile: { _, _, _, _ in
                    Issue.record("Excluded path read terrain")
                    throw CoverageTerrainProfileFailure.invalidInput
                }, predictLoss: { _ in Issue.record("Excluded path called RF model"); return 0 })
            #expect(CoverageNodeStatus.resolve(node: node, activeRunID: run, radiusMeters: radius, evaluation: result).reason == reason)
        }
    }

    @Test func terrainFailuresRemainExplicitAndCancellationThrows() throws {
        let node = CoverageNodeSnapshot(sourceID: "source", nodeID: "node", latitude: 0, longitude: 0.01)
        for (failure, reason) in [(CoverageTerrainProfileFailure.missingTerrain, CoverageNodeReason.missingTerrain),
                                  (.accuracyUnresolved, .accuracyUnresolved), (.workLimit, .processingLimit)] {
            let run = UUID()
            let result = try CoverageNodeEvaluator.evaluate(runID: run,
                transmitter: CoverageTerrainCoordinate(latitude: 0, longitude: 0), node: node,
                radiusMeters: 5000, budget: budget(), profile: { _, _, _, _ in throw failure },
                predictLoss: { _ in Issue.record("Failed terrain reached model"); return 0 })
            #expect(CoverageNodeStatus.resolve(node: node, activeRunID: run, radiusMeters: 5000, evaluation: result).reason == reason)
        }
        #expect(throws: CoverageTerrainProfileFailure.cancelled) {
            try CoverageNodeEvaluator.evaluate(runID: UUID(),
                transmitter: CoverageTerrainCoordinate(latitude: 0, longitude: 0), node: node,
                radiusMeters: 5000, budget: budget(), cancelled: { true },
                profile: { _, _, _, _ in throw CoverageTerrainProfileFailure.invalidInput }, predictLoss: { _ in 0 })
        }
    }
}
