import Foundation
import Testing
@testable import CoreScopeViewer

struct CoverageNodeStatusTests {
    private struct Fixture: Decodable {
        let margin: Double?
        let unavailable: String?
        let flag: String?
        let state: String
        let reason: String
    }
    @Test func sharedStatusDecisionsNeverLeakObsoleteMargins() throws {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        let path = "shared/fixtures/coverage/node-status.json"
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent(path).path) {
            root.deleteLastPathComponent()
        }
        let fixtures = try JSONDecoder().decode([Fixture].self, from: Data(contentsOf: root.appendingPathComponent(path)))
        #expect(fixtures.count == 18)
        let run = UUID()
        let node = CoverageNodeSnapshot(sourceID: "source", nodeID: "node", latitude: 29, longitude: -98)
        for f in fixtures {
            let current = CoverageNodeSnapshot(sourceID: f.flag == "sourceChanged" ? "other" : "source",
                nodeID: "node", latitude: f.flag == "moved" ? 30 : 29, longitude: -98)
            let prediction: CoverageNodePrediction
            if let unavailable = f.unavailable {
                prediction = .unavailable(try #require(CoverageNodeReason(rawValue: unavailable)))
            } else {
                prediction = .qualifiedMargin(f.flag == "invalid" ? .nan : try #require(f.margin))
            }
            let evaluation = CoverageNodeEvaluation(runID: run, node: node,
                distanceMeters: f.flag == "outside" ? 1001 : 1000, prediction: prediction)
            let result = CoverageNodeStatus.resolve(node: current,
                activeRunID: f.flag == "stale" ? UUID() : run, radiusMeters: 1000,
                evaluation: f.flag == "absent" ? nil : evaluation)
            #expect(result.state.rawValue == f.state)
            #expect(result.reason.rawValue == f.reason)
            #expect(result.adjustedMarginDb == (f.reason == "predicted" ? f.margin : nil))
        }
    }
    @Test func convergenceFeedsNodeStatusWithoutBypassingGuard() throws {
        let node = CoverageNodeSnapshot(sourceID: "source", nodeID: "node", latitude: 29, longitude: -98)
        let run = UUID()
        let budget = try CoverageLinkBudget(transmitPowerDbm: 22, transmitGainDbi: 3, receiveGainDbi: 2,
            transmitLossDb: 1, receiveLossDb: 1, sensitivityDbm: -130, reserveDb: 5)
        for (loss, expected) in [(140.0, CoverageNodeState.inCoverage), (160, .belowThreshold),
                                 (150, .unknown), (149.75, .unknown), (150.25, .unknown)] {
            let convergence = try CoverageRFConvergence.evaluate(distanceMeters: 1000, maximumLossDb: budget.maximumLossDb,
                profile: { _, _, _ in
                    CoverageTerrainSampledPath(profile: CoverageTerrainProfile(distancesMeters: [0, 1000],
                        elevationsMeters: [0, 0], terrainEvaluations: 2, featureCount: 2), geometryEvaluations: 0)
                }, predictLoss: { _ in loss })
            let evaluation = CoverageNodeEvaluation(runID: run, node: node, distanceMeters: 1000,
                prediction: .fromConvergence(convergence, budget: budget))
            let status = CoverageNodeStatus.resolve(node: node, activeRunID: run, radiusMeters: 1000, evaluation: evaluation)
            #expect(status.state == expected)
            if expected == .unknown {
                #expect(status.reason == .thresholdUnresolved)
                #expect(status.adjustedMarginDb == nil)
            } else {
                #expect(status.adjustedMarginDb == budget.maximumLossDb - loss)
            }
        }
    }

    @Test func inconsistentOrPrematureResultsCannotBecomePredictions() throws {
        let budget = try CoverageLinkBudget(transmitPowerDbm: 22, transmitGainDbi: 3, receiveGainDbi: 2,
            transmitLossDb: 1, receiveLossDb: 1, sensitivityDbm: -130, reserveDb: 5)
        for (margin, passes, spacing) in [(11.0, 3, 5.0), (10, 1, 5), (10, 3, 30)] {
            let result = CoverageRFConvergenceResult(state: .qualified, lossDb: 140, marginDb: margin,
                passes: passes, spacingMeters: spacing, terrainEvaluations: 6, geometryEvaluations: 0)
            guard case .unavailable(.invalidInput) = CoverageNodePrediction.fromConvergence(result, budget: budget) else {
                Issue.record("Invalid or mismatched convergence result was accepted")
                continue
            }
        }
    }

}
