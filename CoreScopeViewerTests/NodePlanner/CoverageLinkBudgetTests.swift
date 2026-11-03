import Foundation
import Testing
@testable import CoreScopeViewer

struct CoverageLinkBudgetTests {
    private struct Fixture: Decodable {
        let inputs: [Double]
        let loss: Double
        let maximum: Double
        let receive: Double
        let margin: Double
    }
    private func budget(_ v: [Double]) throws -> CoverageLinkBudget {
        try CoverageLinkBudget(transmitPowerDbm: v[0], transmitGainDbi: v[1], receiveGainDbi: v[2],
            transmitLossDb: v[3], receiveLossDb: v[4], sensitivityDbm: v[5], reserveDb: v[6])
    }
    @Test func sharedRadioBudgetsPreserveMarginSign() throws {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        let path = "shared/fixtures/coverage/link-budget.json"
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent(path).path) {
            root.deleteLastPathComponent()
        }
        let fixtures = try JSONDecoder().decode([Fixture].self, from: Data(contentsOf: root.appendingPathComponent(path)))
        #expect(fixtures.count == 5)
        for fixture in fixtures {
            let model = try budget(fixture.inputs)
            let result = try model.reception(pathLossDb: fixture.loss)
            #expect(model.maximumLossDb == fixture.maximum)
            #expect(abs(result.receiveLevelDbm - fixture.receive) < 1e-10)
            #expect(abs(result.adjustedMarginDb - fixture.margin) < 1e-10)
            #expect((result.adjustedMarginDb >= 0) == (fixture.margin >= 0))
            #expect(result.adjustedMarginDb == model.maximumLossDb - fixture.loss)
        }
    }
    @Test func rejectsNonfiniteInputsNegativeLossesAndOverflow() throws {
        let valid = [22.0, 3, 2, 1, 1, -130, 5]
        for index in valid.indices {
            for bad in [Double.nan, .infinity, -.infinity] {
                var values = valid
                values[index] = bad
                #expect(throws: CoverageLinkBudgetFailure.invalidInput) { try budget(values) }
            }
        }
        for index in [3, 4, 6] {
            var values = valid
            values[index] = -1
            #expect(throws: CoverageLinkBudgetFailure.invalidInput) { try budget(values) }
        }
        #expect(throws: CoverageLinkBudgetFailure.invalidInput) {
            try budget([Double.greatestFiniteMagnitude, Double.greatestFiniteMagnitude, 0, 0, 0, -100, 0])
        }
        let model = try budget(valid)
        for loss in [Double.nan, .infinity, -.infinity] {
            #expect(throws: CoverageLinkBudgetFailure.invalidInput) { try model.reception(pathLossDb: loss) }
        }
    }
}
