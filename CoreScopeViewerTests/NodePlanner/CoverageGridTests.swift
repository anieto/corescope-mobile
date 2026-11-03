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
}
