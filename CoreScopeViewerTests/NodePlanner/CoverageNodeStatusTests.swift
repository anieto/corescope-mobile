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
}
