import Foundation
import Testing
@testable import CoreScopeViewer

struct CoverageTerrainProfileTests {
    @Test func retainsNarrowRidgeAndWithinCellPeak() throws {
        let ridge = try CoverageTerrainProfileSampler.build(distanceMeters: 1000,
            cellCrossingsMeters: [0, 400, 400.75, 401.5, 1000], spacingMeters: 30) {
                200 + 100 * max(0, 1 - abs($0 - 400.75) / 0.75)
            }
        #expect(ridge.distancesMeters.contains(400.75))
        #expect(ridge.elevationsMeters.max() == 300)
        let quadratic = try CoverageTerrainProfileSampler.build(distanceMeters: 30,
            cellCrossingsMeters: [0, 30], spacingMeters: 30) { 200 - 0.1 * pow($0 - 13.7, 2) }
        #expect(quadratic.distancesMeters == [0, 13.7, 30])
        #expect(quadratic.elevationsMeters[1] == 200)
        #expect(quadratic.terrainEvaluations == 8)
    }

    @Test func exactEndpointAndMillimeterDeduplication() throws {
        let distance = 30.0006
        let profile = try CoverageTerrainProfileSampler.build(distanceMeters: distance,
            cellCrossingsMeters: [0, 0.0001, 0.0002, distance], spacingMeters: 30) {
                #expect($0 <= distance)
                return -12
            }
        #expect(profile.distancesMeters.first == 0)
        #expect(profile.distancesMeters.last == distance)
        #expect(Set(profile.distancesMeters).count == profile.distancesMeters.count)
        #expect(profile.elevationsMeters.allSatisfy { $0 == -12 })
    }

    @Test func unresolvedFitMissingDataAndBudgetNeverReturnPartialProfile() {
        #expect(throws: CoverageTerrainProfileFailure.accuracyUnresolved) {
            try CoverageTerrainProfileSampler.build(distanceMeters: 30, cellCrossingsMeters: [0, 30], spacingMeters: 30) {
                pow($0, 3)
            }
        }
        #expect(throws: CoverageTerrainProfileFailure.missingTerrain) {
            try CoverageTerrainProfileSampler.build(distanceMeters: 30, cellCrossingsMeters: [0, 30], spacingMeters: 30) { _ in nil }
        }
        var calls = 0
        #expect(throws: CoverageTerrainProfileFailure.workLimit) {
            try CoverageTerrainProfileSampler.build(distanceMeters: 30, cellCrossingsMeters: [0, 30],
                spacingMeters: 30, sampleLimit: 4) { _ in calls += 1; return 0 }
        }
        #expect(calls == 4)
        #expect(throws: CoverageTerrainProfileFailure.invalidInput) {
            try CoverageTerrainProfileSampler.build(distanceMeters: 30, cellCrossingsMeters: [0, 30], spacingMeters: 30) { _ in .infinity }
        }
    }

    @Test func cancellationAtEveryCheckpointAndInvalidOrdering() throws {
        var checkpoints = 0
        _ = try CoverageTerrainProfileSampler.build(distanceMeters: 30, cellCrossingsMeters: [0, 30], spacingMeters: 10,
            cancelled: { checkpoints += 1; return false }, elevation: { _ in 0 })
        for stop in 1...checkpoints {
            var count = 0
            #expect(throws: CoverageTerrainProfileFailure.cancelled) {
                try CoverageTerrainProfileSampler.build(distanceMeters: 30, cellCrossingsMeters: [0, 30], spacingMeters: 10,
                    cancelled: { count += 1; return count == stop }, elevation: { _ in 0 })
            }
        }
        for crossings in [[0.0, 20, 10, 30], [0, 10, 10, 30], [1, 30], [0, .nan, 30]] {
            #expect(throws: CoverageTerrainProfileFailure.invalidInput) {
                try CoverageTerrainProfileSampler.build(distanceMeters: 30, cellCrossingsMeters: crossings, spacingMeters: 10) { _ in 0 }
            }
        }
    }
}
