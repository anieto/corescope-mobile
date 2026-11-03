import Foundation

enum CoverageTerrainProfileFailure: Error, Equatable {
    case invalidInput, workLimit, cancelled, missingTerrain, accuracyUnresolved
}

struct CoverageTerrainProfile: Sendable {
    let distancesMeters: [Double]
    let elevationsMeters: [Double]
    let terrainEvaluations: Int
    let featureCount: Int
}

/// Native profile-building portion of the feature-profile study.
/// Caller must supply every DEM cell crossing and geodesic latitude extremum in path order.
/// The 0.01 m fit check is empirical; this does not qualify RF accuracy or discover absent DEM ridges.
enum CoverageTerrainProfileSampler {
    static func build(distanceMeters: Double, cellCrossingsMeters: [Double], spacingMeters: Double,
                      sampleLimit: Int = 250_000, cancelled: () -> Bool = { false },
                      elevation: (Double) throws -> Double?) throws -> CoverageTerrainProfile {
        guard distanceMeters.isFinite, (0.001...100_000).contains(distanceMeters),
              spacingMeters.isFinite, (1...30).contains(spacingMeters),
              (1...250_000).contains(sampleLimit), (2...50_000).contains(cellCrossingsMeters.count),
              cellCrossingsMeters.first == 0, cellCrossingsMeters.last == distanceMeters else {
            throw CoverageTerrainProfileFailure.invalidInput
        }
        var previous = -Double.infinity
        for position in cellCrossingsMeters {
            guard position.isFinite, position >= 0, position <= distanceMeters, position > previous else {
                throw CoverageTerrainProfileFailure.invalidInput
            }
            previous = position
        }
        func check() throws { if cancelled() { throw CoverageTerrainProfileFailure.cancelled } }
        var evaluations = 0
        func height(_ position: Double) throws -> Double {
            try check()
            guard evaluations < sampleLimit else { throw CoverageTerrainProfileFailure.workLimit }
            evaluations += 1
            guard let value = try elevation(position) else { throw CoverageTerrainProfileFailure.missingTerrain }
            guard value.isFinite else { throw CoverageTerrainProfileFailure.invalidInput }
            return value
        }
        func key(_ position: Double) -> Int64 { Int64((position * 1000).rounded(.toNearestOrAwayFromZero)) }
        let end = key(distanceMeters)
        func meters(_ key: Int64) -> Double { key == end ? distanceMeters : Double(key) / 1000 }
        let crossings = Array(Set(cellCrossingsMeters.map(key))).sorted()
        var features = Set(crossings)
        try check()
        for index in 0..<(crossings.count - 1) {
            try check()
            let a = meters(crossings[index])
            let b = meters(crossings[index + 1])
            if b - a < 0.002 { continue }
            let h0 = try height(a), hm = try height((a + b) / 2), h1 = try height(b)
            let quadratic = 2 * (h1 + h0 - 2 * hm)
            let linear = h1 - h0 - quadratic
            guard quadratic.isFinite, linear.isFinite else { throw CoverageTerrainProfileFailure.invalidInput }
            for t in [0.25, 0.75] {
                let actual = try height(a + (b - a) * t)
                guard abs(actual - (h0 + linear * t + quadratic * t * t)) <= 0.01 else {
                    throw CoverageTerrainProfileFailure.accuracyUnresolved
                }
            }
            if abs(quadratic) > 1e-7 {
                let t = -linear / (2 * quadratic)
                if t > 0 && t < 1 { features.insert(key(a + (b - a) * t)) }
            }
            guard features.count <= 50_000 else { throw CoverageTerrainProfileFailure.workLimit }
        }
        let featureCount = features.count
        let intervals = Int(ceil(distanceMeters / spacingMeters))
        guard intervals + 1 <= sampleLimit - evaluations else { throw CoverageTerrainProfileFailure.workLimit }
        for index in 0...intervals {
            if index % 32 == 0 { try check() }
            features.insert(key(distanceMeters * Double(index) / Double(intervals)))
        }
        guard features.count <= sampleLimit - evaluations else { throw CoverageTerrainProfileFailure.workLimit }
        let distances = features.sorted().map(meters)
        var heights: [Double] = []
        heights.reserveCapacity(distances.count)
        for position in distances { heights.append(try height(position)) }
        try check()
        return CoverageTerrainProfile(distancesMeters: distances, elevationsMeters: heights,
            terrainEvaluations: evaluations, featureCount: featureCount)
    }
}
