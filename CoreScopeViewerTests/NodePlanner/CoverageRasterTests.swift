import Foundation
import Testing
@testable import CoreScopeViewer

struct CoverageRasterTests {
    private func grid() throws -> CoverageGrid {
        try CoverageGrid(radiusMeters: 1000, spacingMeters: 500, minEastMeters: -1000,
            maxEastMeters: 1000, minNorthMeters: -1000, maxNorthMeters: 1000)
    }
    private func cells(_ grid: CoverageGrid) throws -> [CoverageAreaCell] {
        try (0..<grid.count).map { index in
            let point = try #require(grid.point(at: index))
            if point.state == .outsideRadius { return .init(state: .outsideRadius, lossDb: nil, marginDb: nil) }
            if point.state == .nearSite { return .init(state: .nearSite, lossDb: nil, marginDb: nil) }
            if point.northMeters > 0 { return .init(state: .missingTerrain, lossDb: nil, marginDb: nil) }
            return .init(state: .qualified, lossDb: 120, marginDb: point.eastMeters < 0 ? -1 : 1)
        }
    }
    private func raster(latitude: Double = 0, longitude: Double = 0) throws -> CoverageRaster {
        let grid = try grid()
        return try CoverageRaster(grid: grid, transmitter: .init(latitude: latitude, longitude: longitude),
            area: .init(cells: cells(grid), chargedSamples: 0, chargedGeometry: 0, workLimited: false))
    }

    @Test func sharedGeographicCheckpoints() throws {
        struct Point: Decodable { let latitude, longitude: Double; let state: String }
        struct Fixture: Decodable { let latitude, longitude: Double; let points: [Point] }
        let path = "shared/fixtures/coverage/raster-geography.json"
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent(path).path) { root.deleteLastPathComponent() }
        let fixtures = try JSONDecoder().decode([Fixture].self, from: Data(contentsOf: root.appendingPathComponent(path)))
        #expect(fixtures.count == 4)
        for fixture in fixtures {
            let image = try raster(latitude: fixture.latitude, longitude: fixture.longitude)
            for point in fixture.points {
                #expect(image.classification(latitude: point.latitude, longitude: point.longitude).rawValue == point.state)
            }
        }
    }

    @Test func projectedTilePreservesMaskAndTransparency() throws {
        let raster = try raster()
        let data = try raster.tile(zoom: 14, x: 8192, y: 8191)
        #expect(data.count == 256 * 256 * 4)
        func pixel(_ x: Int, _ y: Int) -> [UInt8] { Array(data[(y * 256 + x) * 4..<(y * 256 + x + 1) * 4]) }
        // Northeast of the transmitter is unresolved; the distant corner is outside the study.
        #expect(pixel(32, 224) == [0x66, 0x66, 0x66, 0x90])
        #expect(pixel(33, 224) == [0x66, 0x66, 0x66, 0x90])
        #expect(pixel(34, 224) == [0, 0, 0, 0])
        #expect(pixel(255, 0) == [0, 0, 0, 0])
        let southeast = try raster.tile(zoom: 14, x: 8192, y: 8192)
        #expect(Array(southeast[(32 * 256 + 32) * 4..<(32 * 256 + 33) * 4]) == [0x26, 0xB8, 0x5A, 0xA0])
        #expect(CoverageRaster.rgba(.belowThreshold, column: 0, row: 0) == 0)
        #expect(CoverageRaster.rgba(.unresolved, column: 256, row: 0) == CoverageRaster.rgba(.unresolved, column: 0, row: 0))
    }

    @Test func rejectsInvalidAndCancelledRasters() throws {
        let grid = try grid(), raster = try raster()
        var invalid = try cells(grid)
        invalid[12] = .init(state: .qualified, lossDb: 1, marginDb: 1)
        #expect(throws: CoverageRasterFailure.invalidResult) {
            try CoverageRaster(grid: grid, transmitter: .init(latitude: 0, longitude: 0),
                area: .init(cells: invalid, chargedSamples: 0, chargedGeometry: 0, workLimited: false))
        }
        invalid = try cells(grid)
        invalid[17] = .init(state: .qualified, lossDb: .nan, marginDb: 1)
        #expect(throws: CoverageRasterFailure.invalidResult) {
            try CoverageRaster(grid: grid, transmitter: .init(latitude: 0, longitude: 0),
                area: .init(cells: invalid, chargedSamples: 0, chargedGeometry: 0, workLimited: false))
        }
        #expect(throws: CoverageRasterFailure.cancelled) { try raster.tile(zoom: 14, x: 8192, y: 8192, cancelled: { true }) }
        for (z, x, y) in [(-1, 0, 0), (23, 0, 0), (0, 1, 0), (0, 0, -1)] {
            #expect(throws: CoverageRasterFailure.invalidTile) { try raster.tile(zoom: z, x: x, y: y) }
        }
    }
}
