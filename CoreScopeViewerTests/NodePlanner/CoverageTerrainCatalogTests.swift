import Foundation
import Testing
@testable import CoreScopeViewer

struct CoverageTerrainCatalogTests {
    @Test func sharedCatalogValidation() throws {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent("shared/fixtures/coverage/terrain-catalog.json").path) {
            root.deleteLastPathComponent()
        }
        let data = try Data(contentsOf: root.appendingPathComponent("shared/fixtures/coverage/terrain-catalog.json"))
        let rows = try #require(JSONSerialization.jsonObject(with: data) as? [[String: Any]])
        #expect(rows.count == 14)
        for row in rows {
            let document = try JSONSerialization.data(withJSONObject: #require(row["document"]))
            let expected = try #require(row["expected"] as? String)
            if expected == "valid" {
                let catalog = try CoverageTerrainCatalog(data: document)
                let area = try CoverageTerrainFootprint.make(latitude: 0.5, longitude: 0.99, radiusMeters: 1500)
                #expect(try catalog.resolve(area).map(\.tile) == ["N00E000", "N00E001"])
                let missing = try CoverageTerrainFootprint.make(latitude: 2.5, longitude: 2.5, radiusMeters: 1000)
                #expect(throws: CoverageTerrainFootprintFailure.self) { try catalog.resolve(missing) }
            } else {
                let failure: CoverageTerrainCatalogFailure = switch expected {
                case "duplicateTile": .duplicateTile
                case "unsupportedVersion": .unsupportedVersion
                default: .invalidDocument
                }
                #expect(throws: failure) { try CoverageTerrainCatalog(data: document) }
            }
        }
    }

    @Test func boundedMalformedInput() {
        #expect(throws: CoverageTerrainCatalogFailure.oversized) {
            try CoverageTerrainCatalog(data: Data(repeating: 32, count: CoverageTerrainCatalog.maximumBytes + 1))
        }
        for data in [Data(), Data("[]".utf8), Data([0xff, 0xfe]), Data("{".utf8)] {
            #expect(throws: CoverageTerrainCatalogFailure.invalidDocument) { try CoverageTerrainCatalog(data: data) }
        }
    }
}
