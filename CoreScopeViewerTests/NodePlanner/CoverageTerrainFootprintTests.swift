import Foundation
import Testing
@testable import CoreScopeViewer

struct CoverageTerrainFootprintTests {
    private struct Fixture: Decodable {
        let name: String
        let latitude, longitude, radius, halo: Double
        let tiles: [[Int]]
    }
    @Test func sharedFootprints() throws {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent("shared/fixtures/coverage/terrain-footprint.json").path) {
            root.deleteLastPathComponent()
        }
        let fixtures = try JSONDecoder().decode([Fixture].self,
            from: Data(contentsOf: root.appendingPathComponent("shared/fixtures/coverage/terrain-footprint.json")))
        #expect(fixtures.count == 8)
        for fixture in fixtures {
            let footprint = try CoverageTerrainFootprint.make(latitude: fixture.latitude, longitude: fixture.longitude,
                radiusMeters: fixture.radius, haloMeters: fixture.halo)
            #expect(footprint.tiles.map { [$0.south, $0.west] } == fixture.tiles, "\(fixture.name)")
        }
    }

    @Test func radialPathsRemainWithinSelectedTiles() throws {
        // Independently project bearings and intermediate path distances, as the coverage grid does.
        let radians = Double.pi / 180
        for (latitude, longitude) in [(29.43, -98.62), (50.96, -115.16), (70.0, 179.9), (-60.0, -179.9)] {
            for radius in [1000.0, 20000, 200000] {
                let footprint = try CoverageTerrainFootprint.make(latitude: latitude, longitude: longitude,
                    radiusMeters: radius, haloMeters: 100)
                let tiles = Set(footprint.tiles)
                for bearing in stride(from: 0, to: 360, by: 3) {
                    for fraction in [0.0, 0.25, 0.5, 0.75, 1] {
                        let arc = (radius + 100) * fraction / CoverageTerrainFootprint.earthRadiusMeters
                        let phi = latitude * radians
                        let theta = Double(bearing) * radians
                        let lat = asin(sin(phi) * cos(arc) + cos(phi) * sin(arc) * cos(theta))
                        let lon = longitude * radians + atan2(sin(theta) * sin(arc) * cos(phi),
                            cos(arc) - sin(phi) * sin(lat))
                        let degrees = lon / radians
                        let normalized = degrees - 360 * floor((degrees + 180) / 360)
                        #expect(tiles.contains(try CoverageTerrainTile.containing(latitude: lat / radians, longitude: normalized)))
                    }
                }
            }
        }
    }

    @Test func invalidInputsAndOversizedAreasFailWithoutTruncation() throws {
        for (lat, lon, radius, halo) in [(Double.nan, 0.0, 1.0, 0.0), (0, .infinity, 1, 0),
            (91, 0, 1, 0), (0, 181, 1, 0), (0, 0, -1, 0), (0, 0, 1, -1), (0, 0, .infinity, 0)] {
            #expect(throws: CoverageTerrainFootprintFailure.invalidRequest) {
                try CoverageTerrainFootprint.make(latitude: lat, longitude: lon, radiusMeters: radius, haloMeters: halo)
            }
        }
        for latitude in [-90.0, 90] {
            #expect(throws: CoverageTerrainFootprintFailure.tooManyTiles) {
                try CoverageTerrainFootprint.make(latitude: latitude, longitude: 0, radiusMeters: 1)
            }
        }
        #expect(throws: CoverageTerrainFootprintFailure.tooManyTiles) {
            try CoverageTerrainFootprint.make(latitude: 0, longitude: 0, radiusMeters: 1000, maximumTiles: 3)
        }
        #expect(throws: CoverageTerrainFootprintFailure.tooManyTiles) {
            try CoverageTerrainFootprint.make(latitude: 0, longitude: 0, radiusMeters: 10_000_000)
        }
    }

    private func descriptor(_ tile: String, revision: String = "1", url: String = "https://example.com/tile") throws -> CoverageTerrainDescriptor {
        try CoverageTerrainDescriptor(dataset: "test", revision: revision, tile: tile,
            compressedSHA256: String(repeating: "a", count: 64), packedBytes: 100, side: 2,
            url: url, verticalDatum: "test", attribution: "test")
    }

    @Test func manifestResolutionRejectsGapsAmbiguityAndMixedSources() throws {
        let area = try CoverageTerrainFootprint.make(latitude: 0.5, longitude: 0.99, radiusMeters: 1500)
        let west = try descriptor("N00E000"), east = try descriptor("N00E001")
        #expect(try area.resolve(catalog: [east, west]).map(\.cacheKey) == [west.cacheKey, east.cacheKey])
        let mirror = try descriptor("N00E000", url: "https://mirror.example.com/tile")
        #expect(try area.resolve(catalog: [west, mirror, east]).count == 2)
        #expect(throws: CoverageTerrainFootprintFailure.missingTiles([east.coordinate])) { try area.resolve(catalog: [west]) }
        #expect(throws: CoverageTerrainFootprintFailure.ambiguousTile) {
            try area.resolve(catalog: [west, descriptor("N00E000", revision: "2"), east])
        }
        #expect(throws: CoverageTerrainFootprintFailure.inconsistentSources) {
            try area.resolve(catalog: [west, descriptor("N00E001", revision: "2")])
        }
    }
}
