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

    @Test func reviewedRegionalCatalogResolvesAndPreflights() throws {
        let path = "docs/node-planner/feasibility/regional-terrain-catalog.json"
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent(path).path) {
            root.deleteLastPathComponent()
        }
        let catalog = try CoverageTerrainCatalog(data: Data(contentsOf: root.appendingPathComponent(path)))
        #expect(catalog.descriptors.count == 8)
        #expect(catalog.descriptors.reduce(Int64(0)) { $0 + $1.packedBytes } == 84_062_769)
        let footprint = try CoverageTerrainFootprint.make(latitude: 50.96, longitude: -115.16,
            radiusMeters: 20_000, haloMeters: 100)
        let selected = try catalog.resolve(footprint)
        #expect(Set(selected.map(\.tile)) == Set(["N50W116", "N50W115", "N51W116", "N51W115"]))
        let preflight = try CoverageTerrainPipelinePreflight.make(requested: selected, inventory: [],
            quota: 300_000_000, remainingDownloadBytes: 50_000_000)
        #expect(preflight.cache.expectedDownloadBytes == 45_503_666)
        #expect(preflight.cache.fitsDownloadBudget)
        #expect(preflight.fitsPipelineQuota)
        let tooSmall = try CoverageTerrainPipelinePreflight.make(requested: selected, inventory: [],
            quota: 100_000_000, remainingDownloadBytes: 40_000_000)
        #expect(!tooSmall.cache.fitsDownloadBudget)
        #expect(!tooSmall.fitsPipelineQuota)
        let sanAntonio = try CoverageTerrainFootprint.make(latitude: 29.43, longitude: -98.62, radiusMeters: 20_000)
        #expect(throws: CoverageTerrainFootprintFailure.self) { try catalog.resolve(sanAntonio) }
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
