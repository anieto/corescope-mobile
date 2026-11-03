import Testing
@testable import CoreScopeViewer

struct CoverageGridPreflightTests {
    private func point(_ east: Int) throws -> CoverageGrid {
        try CoverageGrid(radiusMeters: 1000, spacingMeters: 50, minEastMeters: east, maxEastMeters: east,
                         minNorthMeters: 0, maxNorthMeters: 0)
    }
    @Test func analyticalBoundsAndExcludedPoints() throws {
        let result = try CoverageGridPreflight.estimate(grid: point(250), sampleBudget: 87, geometryBudget: 12)
        #expect(result.eligiblePoints == 1)
        #expect(result.minimumSamples == 87 && result.minimumGeometry == 12)
        #expect(result.maximumSamples == 250_000 && result.maximumGeometry == 200_000)
        let empty = try CoverageGridPreflight.estimate(grid: point(0), sampleBudget: 0, geometryBudget: 0)
        #expect(empty.eligiblePoints == 0 && empty.maximumSamples == 0)
    }
    @Test func independentBudgetFailuresAndCancellation() throws {
        let grid = try point(250)
        #expect(throws: CoverageGridPreflightFailure.sampleBudget) {
            try CoverageGridPreflight.estimate(grid: grid, sampleBudget: 86, geometryBudget: 12)
        }
        #expect(throws: CoverageGridPreflightFailure.geometryBudget) {
            try CoverageGridPreflight.estimate(grid: grid, sampleBudget: 87, geometryBudget: 11)
        }
        #expect(throws: CoverageGridPreflightFailure.pathSampleBudget) {
            try CoverageGridPreflight.estimate(grid: grid, sampleBudget: 1000, geometryBudget: 1000, pathSampleBudget: 86)
        }
        #expect(throws: CoverageGridPreflightFailure.pathGeometryBudget) {
            try CoverageGridPreflight.estimate(grid: grid, sampleBudget: 1000, geometryBudget: 1000, pathGeometryBudget: 11)
        }
        #expect(throws: CoverageGridPreflightFailure.cancelled) {
            try CoverageGridPreflight.estimate(grid: grid, sampleBudget: 1000, geometryBudget: 1000, cancelled: { true })
        }
    }
    @Test func aggregateCapsDoNotOverflow32BitCounters() throws {
        let grid = try CoverageGrid(radiusMeters: 10_000, spacingMeters: 100,
            minEastMeters: -10_000, maxEastMeters: 10_000, minNorthMeters: -10_000, maxNorthMeters: 10_000)
        let result = try CoverageGridPreflight.estimate(grid: grid, sampleBudget: 150_000_000, geometryBudget: 150_000_000)
        #expect(result.maximumSamples > Int64(Int32.max))
        #expect(result.maximumGeometry > Int64(Int32.max))
        #expect(result.minimumGeometry == Int64(result.eligiblePoints) * 12)
    }
}
