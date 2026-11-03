import Foundation
import Testing
@testable import CoreScopeViewer

struct CoverageRFConvergenceTests {
    private func sample(_ spacing: Double, _ remainingSamples: Int, _ remainingGeometry: Int) throws -> CoverageTerrainSampledPath {
        guard remainingSamples >= 100, remainingGeometry >= 10 else { throw CoverageTerrainProfileFailure.workLimit }
        return CoverageTerrainSampledPath(profile: CoverageTerrainProfile(distancesMeters: [0, 1000],
            elevationsMeters: [200, 200], terrainEvaluations: 100, featureCount: 2), geometryEvaluations: 10)
    }
    private struct Fixture: Decodable {
        let name: String
        let losses: [Double]
        let maximumLoss: Double
        let state: String
        let passes: Int
    }
    @Test func sharedConvergenceDecisionsDoNotPublishUnresolvedLosses() throws {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent("shared/fixtures/coverage/rf-convergence.json").path) {
            root.deleteLastPathComponent()
        }
        let cases = try JSONDecoder().decode([Fixture].self,
            from: Data(contentsOf: root.appendingPathComponent("shared/fixtures/coverage/rf-convergence.json")))
        #expect(cases.count == 8)
        for fixture in cases {
            var pass = 0
            let result = try CoverageRFConvergence.evaluate(distanceMeters: 1000, maximumLossDb: fixture.maximumLoss,
                profile: sample) { _ in defer { pass += 1 }; return fixture.losses[pass] }
            #expect(String(describing: result.state) == fixture.state, "\(fixture.name)")
            #expect(result.passes == fixture.passes)
            #expect(result.terrainEvaluations == pass * 100)
            #expect(result.geometryEvaluations == pass * 10)
            if fixture.state == "qualified" {
                #expect(result.lossDb == fixture.losses[pass - 1])
                #expect(result.marginDb == fixture.maximumLoss - fixture.losses[pass - 1])
            } else {
                #expect(result.lossDb == nil)
                #expect(result.marginDb == nil)
            }
        }
    }
    @Test func workBudgetIsSharedAcrossPasses() {
        for (samples, geometry) in [(250, 100), (1000, 25)] {
            var calls = 0
            #expect(throws: CoverageTerrainProfileFailure.workLimit) {
                try CoverageRFConvergence.evaluate(distanceMeters: 1000, maximumLossDb: 152,
                    sampleLimit: samples, geometryLimit: geometry, profile: sample) { _ in calls += 1; return 100 }
            }
            #expect(calls == 2)
        }
    }
    @Test func invalidModelAndCancellationCannotPublish() {
        #expect(throws: CoverageRFConvergenceFailure.invalidModel) {
            try CoverageRFConvergence.evaluate(distanceMeters: 1000, maximumLossDb: 152, profile: sample) { _ in .nan }
        }
        var cancelled = false
        #expect(throws: CoverageTerrainProfileFailure.cancelled) {
            try CoverageRFConvergence.evaluate(distanceMeters: 1000, maximumLossDb: 152,
                cancelled: { cancelled }, profile: sample) { _ in cancelled = true; return 100 }
        }
        #expect(throws: CoverageRFConvergenceFailure.invalidInput) {
            try CoverageRFConvergence.evaluate(distanceMeters: 1000, maximumLossDb: 152,
                profile: { _, _, _ in
                    CoverageTerrainSampledPath(profile: CoverageTerrainProfile(distancesMeters: [0, 999],
                        elevationsMeters: [200, 200], terrainEvaluations: 2, featureCount: 2), geometryEvaluations: 0)
                }, predictLoss: { _ in Issue.record("Invalid profile reached model"); return 100 })
        }
    }
}
