import Foundation
import Testing
@testable import CoreScopeViewer

struct CoverageGridTests {
    @Test func expansionPreservesExactSamplesAndLeavesNewAreaPending() throws {
        let old = try CoverageGrid.studyArea(radiusMeters: 3000, spacingMeters: 1000)
        let larger = try CoverageGrid.studyArea(radiusMeters: 5000, spacingMeters: 1000)
        let cells = try (0..<old.count).map { index -> CoverageAreaCell in
            let point = try #require(old.point(at: index))
            switch point.state {
            case .nearSite: return CoverageAreaCell(state: .nearSite, lossDb: nil, marginDb: nil)
            case .outsideRadius: return CoverageAreaCell(state: .outsideRadius, lossDb: nil, marginDb: nil)
            case .eligible:
                if point.eastMeters == 0 { return CoverageAreaCell(state: .workLimit, lossDb: nil, marginDb: nil) }
                return CoverageAreaCell(state: .qualified, lossDb: Double(index), marginDb: Double(-index))
            }
        }
        let oldArea = CoverageAreaResult(cells: cells, chargedSamples: 123, chargedGeometry: 456, workLimited: true)
        let expanded = try oldArea.expanded(from: old, to: larger)
        #expect(expanded.pendingCount > 0)
        #expect(expanded.chargedSamples == 0 && expanded.chargedGeometry == 0)
        for index in 0..<old.count {
            let point = try #require(old.point(at: index))
            guard point.state == .eligible else { continue }
            let column = (point.eastMeters - larger.minEastMeters) / larger.spacingMeters
            let row = (larger.maxNorthMeters - point.northMeters) / larger.spacingMeters
            let retained = expanded.cells[row * larger.columns + column]
            #expect(retained.state == cells[index].state)
            #expect(retained.lossDb == cells[index].lossDb)
            #expect(retained.marginDb == cells[index].marginDb)
        }
        // Legacy half-cell grids must never attach a prediction to a different path.
        let shifted = try CoverageGrid(radiusMeters: 3000, spacingMeters: 2000,
            minEastMeters: -3000, maxEastMeters: 3000, minNorthMeters: -3000, maxNorthMeters: 3000)
        let legacy = CoverageAreaResult(cells: Array(repeating: CoverageAreaCell(state: .qualified,
            lossDb: 100, marginDb: 20), count: shifted.count), chargedSamples: 0, chargedGeometry: 0, workLimited: false)
        let mapped = try legacy.expanded(from: shifted, to: CoverageGrid.studyArea(radiusMeters: 5000, spacingMeters: 2000))
        #expect(!mapped.cells.contains { $0.state == .qualified })
    }

    @Test func fractionalMileSpacingsAreSupportedAndAnchored() throws {
        for spacing in [201, 402, 805, 1609] {
            let grid = try CoverageGrid.studyArea(radiusMeters: 16_093, spacingMeters: spacing)
            #expect(grid.minEastMeters % spacing == 0)
            #expect(grid.maxNorthMeters % spacing == 0)
            #expect(grid.point(at: grid.count / 2)?.state == .nearSite)
        }
    }

    @Test func wholeMileRadiiKeepRequestedBoundaryAndSampleSpacing() throws {
        for radius in [8047, 16_093, 40_234, 80_467] {
            for spacing in [1000, 2000] {
                let grid = try CoverageGrid.studyArea(radiusMeters: radius, spacingMeters: spacing)
                #expect(grid.radiusMeters == radius)
                #expect(grid.spacingMeters == spacing)
                #expect(grid.minEastMeters >= -radius && grid.maxNorthMeters <= radius)
                #expect(radius - grid.maxNorthMeters < spacing)
                #expect(grid.point(at: grid.count / 2)?.state == .nearSite)
            }
        }
        let metric = try CoverageGrid.studyArea(radiusMeters: 5000, spacingMeters: 2000)
        #expect(metric.minEastMeters == -4000 && metric.maxNorthMeters == 4000)
        #expect(metric.columns == 5)
        #expect(throws: CoverageGridFailure.invalidParameters) {
            try CoverageGrid.studyArea(radiusMeters: Int.min, spacingMeters: 0)
        }
    }

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
