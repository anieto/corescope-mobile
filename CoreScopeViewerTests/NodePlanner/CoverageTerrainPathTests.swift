import Foundation
import Testing
@testable import CoreScopeViewer

struct CoverageTerrainPathTests {
    @Test func equatorialCrossingsMatchAnalyticDistances() throws {
        let path = try CoverageTerrainPath(latitude: 0, longitude: -0.0005, bearingDegrees: 90, distanceMeters: 200)
        let result = try path.crossings(intervalsPerDegree: 3600)
        let metersPerDegree = CoverageTerrainFootprint.earthRadiusMeters * Double.pi / 180
        for line in -1...4 {
            let distance = (Double(line) / 3600 + 0.0005) * metersPerDegree
            #expect(result.distancesMeters.contains { abs($0 - distance) <= 0.0006 })
        }
        #expect(result.distancesMeters.first == 0)
        #expect(result.distancesMeters.last == 200)
        #expect(result.geometryEvaluations > 0)
    }

    @Test func cellSegmentsCoverDateLineAndLatitudeTurningPoint() throws {
        for (lat, lon, bearing) in [(0.5, 179.99, 90.0), (-0.5, -179.99, 270.0),
                                   (60.0, 10.0, 89.9), (-30.0, 10.0, 180.0)] {
            let path = try CoverageTerrainPath(latitude: lat, longitude: lon, bearingDegrees: bearing, distanceMeters: 20_000)
            let crossings = try path.crossings(intervalsPerDegree: 120)
            for index in 0..<(crossings.distancesMeters.count - 1) {
                let a = crossings.distancesMeters[index], b = crossings.distancesMeters[index + 1]
                if b - a < 0.002 { continue }
                var cells: Set<String> = []
                for t in [0.1, 0.5, 0.9] {
                    let point = try path.point(at: a + (b - a) * t)
                    cells.insert("\(Int(floor(point.latitude * 120))),\(Int(floor(point.longitude * 120)))")
                }
                #expect(cells.count == 1)
            }
            if lat == 60 {
                let critical = atan2(cos(lat * .pi / 180) * cos(bearing * .pi / 180), sin(lat * .pi / 180))
                    * CoverageTerrainFootprint.earthRadiusMeters
                #expect(crossings.distancesMeters.contains { abs($0 - critical) <= 0.0006 })
            }
        }
    }

    @Test func workCancellationAndUnsupportedDomainFailExplicitly() throws {
        let path = try CoverageTerrainPath(latitude: 0, longitude: 0, bearingDegrees: 45, distanceMeters: 1000)
        #expect(throws: CoverageTerrainProfileFailure.workLimit) { try path.crossings(intervalsPerDegree: 3600, geometryLimit: 1) }
        #expect(throws: CoverageTerrainProfileFailure.cancelled) { try path.crossings(intervalsPerDegree: 120, cancelled: { true }) }
        #expect(throws: CoverageTerrainProfileFailure.invalidInput) { try path.crossings(intervalsPerDegree: 7) }
        #expect(throws: CoverageTerrainProfileFailure.invalidInput) {
            try CoverageTerrainPath(latitude: 89, longitude: 0, bearingDegrees: 0, distanceMeters: 100)
        }
        let north = try CoverageTerrainPath(latitude: 84.99, longitude: 0, bearingDegrees: 0, distanceMeters: 2000)
        #expect(throws: CoverageTerrainProfileFailure.accuracyUnresolved) { try north.crossings(intervalsPerDegree: 120) }
        var checks = 0
        _ = try path.crossings(intervalsPerDegree: 1, cancelled: { checks += 1; return false })
        for stop in 1...checks {
            var count = 0
            #expect(throws: CoverageTerrainProfileFailure.cancelled) {
                try path.crossings(intervalsPerDegree: 1, cancelled: { count += 1; return count == stop })
            }
        }
    }
}
