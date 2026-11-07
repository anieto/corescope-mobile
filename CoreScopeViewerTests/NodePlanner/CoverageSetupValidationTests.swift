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

    @Test func completeStudyInputsAreFrozenAndRequireEveryAssumption() throws {
        let fields = ["latitude": "50.96", "longitude": "-115.16", "frequency": "915", "power": "22",
            "txHeight": "30", "txGain": "3", "txLoss": "1", "rxHeight": "1.5", "rxGain": "2",
            "rxLoss": "1", "sensitivity": "-130", "reserve": "5"]
        var edited = fields
        let inputs = try CoverageStudyInputs.fromEditor(fields: edited, radiusMeters: 20_000, spacingMeters: 1000)
        edited["frequency"] = "868"
        edited["txHeight"] = "10"
        #expect(inputs.frequencyMHz == 915 && inputs.transmitHeightMeters == 30 && inputs.receiveHeightMeters == 1.5)
        #expect(inputs.transmitter.latitude == 50.96 && inputs.transmitter.longitude == -115.16)
        #expect(inputs.grid.radiusMeters == 20_000 && inputs.grid.spacingMeters == 1000)
        #expect(inputs.budget.maximumLossDb == 150)
        for field in fields.keys {
            var missing = fields
            missing.removeValue(forKey: field)
            #expect(throws: CoverageStudyInputFailure.field(field, .required)) {
                try CoverageStudyInputs.fromEditor(fields: missing, radiusMeters: 20_000, spacingMeters: 1000)
            }
        }
        var overflow = fields
        overflow["power"] = "1e308"
        overflow["txGain"] = "1e308"
        #expect(throws: CoverageLinkBudgetFailure.invalidInput) {
            try CoverageStudyInputs.fromEditor(fields: overflow, radiusMeters: 20_000, spacingMeters: 1000)
        }
        #expect(throws: CoverageGridFailure.invalidParameters) {
            try CoverageStudyInputs.fromEditor(fields: fields, radiusMeters: Int.min, spacingMeters: 1000)
        }
        #expect(throws: CoverageStudyInputFailure.invalidRadio) {
            try CoverageStudyInputs(transmitter: inputs.transmitter, grid: inputs.grid, budget: inputs.budget,
                frequencyMHz: .nan, transmitHeightMeters: 30, receiveHeightMeters: 1.5)
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
