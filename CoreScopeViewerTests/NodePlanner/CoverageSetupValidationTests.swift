import Foundation
import Testing
@testable import CoreScopeViewer

struct CoverageSetupValidationTests {
    private struct Fixture: Decodable {
        let field, text: String
        let issue: String?
    }
    @Test func sharedInputBoundaries() throws {
        let path = "shared/fixtures/coverage/setup-validation.json"
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent(path).path) {
            root.deleteLastPathComponent()
        }
        let fixtures = try JSONDecoder().decode([Fixture].self, from: Data(contentsOf: root.appendingPathComponent(path)))
        #expect(fixtures.count == 28)
        for fixture in fixtures {
            #expect(CoverageSetupValidation.issue(field: fixture.field, text: fixture.text)?.rawValue == fixture.issue,
                    "\(fixture.field): \(fixture.text)")
        }
    }

    @Test func editorChoicesCanExceedWorkBeforeGridSize() throws {
        let grid = try CoverageGrid(radiusMeters: 100_000, spacingMeters: 1000,
            minEastMeters: -100_000, maxEastMeters: 100_000, minNorthMeters: -100_000, maxNorthMeters: 100_000)
        #expect(grid.count <= 60_000)
        #expect(throws: CoverageGridPreflightFailure.sampleBudget) {
            try CoverageGridPreflight.estimate(grid: grid, sampleBudget: 150_000_000, geometryBudget: 150_000_000)
        }
        let ordinary = try CoverageGrid(radiusMeters: 20_000, spacingMeters: 1000,
            minEastMeters: -20_000, maxEastMeters: 20_000, minNorthMeters: -20_000, maxNorthMeters: 20_000)
        #expect(try CoverageGridPreflight.estimate(grid: ordinary, sampleBudget: 150_000_000,
            geometryBudget: 150_000_000).eligiblePoints > 0)
    }
}
