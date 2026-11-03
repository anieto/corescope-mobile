import Foundation
import Testing
@testable import CoreScopeViewer

struct CoverageGridTests {
    @Test func studyLayoutsPreserveRadiusSpacingAndRowOrder() throws {
        for (radius, spacing, extent, expected) in [(20_000, 1000, 20_000, 1681), (100_000, 20_000, 100_000, 121), (20_000, 100, 2000, 1681)] {
            let grid = try CoverageGrid(radiusMeters: radius, spacingMeters: spacing,
                minEastMeters: -extent, maxEastMeters: extent, minNorthMeters: -extent, maxNorthMeters: extent)
            #expect(grid.count == expected)
            #expect(grid.radiusMeters == radius && grid.spacingMeters == spacing)
            #expect(grid.point(at: 0)?.eastMeters == -extent)
            #expect(grid.point(at: 0)?.northMeters == extent)
            #expect(grid.point(at: expected - 1)?.northMeters == -extent)
            #expect(grid.point(at: expected / 2)?.state == .nearSite)
            #expect(grid.point(at: -1) == nil)
            #expect(grid.point(at: expected) == nil)
        }
        let grid = try CoverageGrid(radiusMeters: 1000, spacingMeters: 50,
            minEastMeters: -1000, maxEastMeters: 1000, minNorthMeters: -1000, maxNorthMeters: 1000)
        #expect(grid.point(at: 0)?.state == .outsideRadius)
        #expect(grid.point(at: 20)?.state == .eligible)
        #expect(grid.point(at: 20 * 41 + 25)?.distanceMeters == 250)
        #expect(grid.point(at: 20 * 41 + 25)?.state == .eligible)
    }

    @Test func excessiveOrMisalignedRequestsNeverCoarsenSilently() {
        #expect(throws: CoverageGridFailure.cellBudget) {
            try CoverageGrid(radiusMeters: 100_000, spacingMeters: 200,
                minEastMeters: -100_000, maxEastMeters: 100_000, minNorthMeters: -100_000, maxNorthMeters: 100_000)
        }
        #expect(throws: CoverageGridFailure.unalignedExtent) {
            try CoverageGrid(radiusMeters: 1000, spacingMeters: 200,
                minEastMeters: -999, maxEastMeters: 1000, minNorthMeters: -1000, maxNorthMeters: 1000)
        }
        #expect(throws: CoverageGridFailure.invalidBounds) {
            try CoverageGrid(radiusMeters: 1000, spacingMeters: 200,
                minEastMeters: Int.min, maxEastMeters: Int.max, minNorthMeters: -1000, maxNorthMeters: 1000)
        }
        #expect(throws: CoverageGridFailure.invalidParameters) {
            try CoverageGrid(radiusMeters: 200_000, spacingMeters: 200,
                minEastMeters: -1000, maxEastMeters: 1000, minNorthMeters: -1000, maxNorthMeters: 1000)
        }
    }
    @Test func geographicPathsMatchCardinalsAndWrapDateLine() throws {
        let origin = CoverageTerrainCoordinate(latitude: 0, longitude: 179.999)
        let degrees = 1000 / CoverageTerrainFootprint.earthRadiusMeters * 180 / Double.pi
        for (east, north, bearing) in [(1000, 0, 90.0), (-1000, 0, 270), (0, 1000, 0), (0, -1000, 180)] {
            let grid = try CoverageGrid(radiusMeters: 1000, spacingMeters: 50,
                minEastMeters: east, maxEastMeters: east, minNorthMeters: north, maxNorthMeters: north)
            let path = try #require(try grid.path(at: 0, transmitter: origin))
            #expect(path.bearingDegrees == bearing)
            #expect(path.distanceMeters == 1000)
            let end = try path.point(at: 1000)
            let longitude = origin.longitude + Double(east) / 1000 * degrees
            let wrapped = longitude - 360 * floor((longitude + 180) / 360)
            #expect(abs(end.longitude - wrapped) < 1e-9)
            #expect(abs(end.latitude - Double(north) / 1000 * degrees) < 1e-9)
        }
    }
    @Test func excludedAndUnsupportedGridPathsRemainDistinct() throws {
        let grid = try CoverageGrid(radiusMeters: 1000, spacingMeters: 1000,
            minEastMeters: -1000, maxEastMeters: 1000, minNorthMeters: -1000, maxNorthMeters: 1000)
        let origin = CoverageTerrainCoordinate(latitude: 0, longitude: 0)
        #expect(try grid.path(at: 0, transmitter: origin) == nil)
        #expect(try grid.path(at: 4, transmitter: origin) == nil)
        #expect(throws: CoverageGridFailure.invalidParameters) { try grid.path(at: -1, transmitter: origin) }
        #expect(throws: CoverageGridFailure.invalidParameters) {
            try grid.path(at: 1, transmitter: CoverageTerrainCoordinate(latitude: 90, longitude: 0))
        }
        #expect(throws: CoverageTerrainProfileFailure.accuracyUnresolved) {
            try grid.path(at: 1, transmitter: CoverageTerrainCoordinate(latitude: 85, longitude: 0))
        }
    }

}
