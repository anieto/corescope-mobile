import Foundation

struct CoverageTerrainCoordinate: Sendable {
    let latitude: Double
    let longitude: Double
}

struct CoverageTerrainSampledPath: Sendable {
    let profile: CoverageTerrainProfile
    let geometryEvaluations: Int
}

struct CoverageTerrainCrossings: Sendable {
    let distancesMeters: [Double]
    let geometryEvaluations: Int
}

/// Short spherical path matching the reviewed profile domain. Longitude stays unwrapped internally.
/// Paths outside ±85° latitude fail explicitly; polar geometry is not qualified by this adapter.
struct CoverageTerrainPath: Sendable {
    let latitude: Double
    let longitude: Double
    let bearingDegrees: Double
    let distanceMeters: Double
    private let radians = Double.pi / 180

    init(latitude: Double, longitude: Double, bearingDegrees: Double, distanceMeters: Double) throws {
        guard latitude.isFinite, longitude.isFinite, bearingDegrees.isFinite, distanceMeters.isFinite,
              (-85...85).contains(latitude), (-180...180).contains(longitude),
              (0..<360).contains(bearingDegrees), (0.001...100_000).contains(distanceMeters) else {
            throw CoverageTerrainProfileFailure.invalidInput
        }
        self.latitude = latitude
        self.longitude = longitude == 180 ? -180 : longitude
        self.bearingDegrees = bearingDegrees
        self.distanceMeters = distanceMeters
    }

    private func unwrappedPoint(at distance: Double) throws -> CoverageTerrainCoordinate {
        guard distance.isFinite, distance >= 0, distance <= distanceMeters else { throw CoverageTerrainProfileFailure.invalidInput }
        let phi = latitude * radians, theta = bearingDegrees * radians
        let arc = distance / CoverageTerrainFootprint.earthRadiusMeters
        let lat = asin(min(1, max(-1, sin(phi) * cos(arc) + cos(phi) * sin(arc) * cos(theta))))
        let lon = longitude + atan2(sin(theta) * sin(arc) * cos(phi),
            cos(arc) - sin(phi) * sin(lat)) / radians
        guard abs(lat / radians) <= 85 else { throw CoverageTerrainProfileFailure.accuracyUnresolved }
        return CoverageTerrainCoordinate(latitude: lat / radians, longitude: lon)
    }

    func point(at distance: Double) throws -> CoverageTerrainCoordinate {
        let point = try unwrappedPoint(at: distance)
        return CoverageTerrainCoordinate(latitude: point.latitude,
            longitude: point.longitude - 360 * floor((point.longitude + 180) / 360))
    }

    func crossings(intervalsPerDegree: Int, geometryLimit: Int = 200_000,
                   cancelled: () -> Bool = { false }) throws -> CoverageTerrainCrossings {
        guard (1...3600).contains(intervalsPerDegree), 3600 % intervalsPerDegree == 0,
              (1...200_000).contains(geometryLimit) else { throw CoverageTerrainProfileFailure.invalidInput }
        func check() throws { if cancelled() { throw CoverageTerrainProfileFailure.cancelled } }
        var evaluations = 0
        func coordinate(_ distance: Double, axis: Int) throws -> Double {
            try check()
            guard evaluations < geometryLimit else { throw CoverageTerrainProfileFailure.workLimit }
            evaluations += 1
            let point = try unwrappedPoint(at: distance)
            return (axis == 0 ? point.longitude : point.latitude) * Double(intervalsPerDegree)
        }
        // Split at great-circle latitude extrema; both grid coordinates are monotone per segment.
        let phi = latitude * radians, theta = bearingDegrees * radians
        let critical = atan2(cos(phi) * cos(theta), sin(phi))
        var splits = [0.0, distanceMeters]
        for k in -1...1 {
            let position = (critical + Double(k) * Double.pi) * CoverageTerrainFootprint.earthRadiusMeters
            if position > 0 && position < distanceMeters { splits.append(position) }
        }
        splits.sort()
        var positions = splits
        for axis in 0...1 {
            for index in 0..<(splits.count - 1) {
                try check()
                let start = splits[index], end = splits[index + 1]
                let a = try coordinate(start, axis: axis), b = try coordinate(end, axis: axis)
                if a == b { continue } // A path along a grid line does not cross every point on it.
                let first = Int(ceil(min(a, b))), last = Int(floor(max(a, b)))
                if first > last { continue }
                guard last - first + 1 <= 50_000 - positions.count else { throw CoverageTerrainProfileFailure.workLimit }
                for line in first...last {
                    var low = start, high = end
                    while high - low > 0.0001 {
                        let middle = (low + high) / 2
                        let value = try coordinate(middle, axis: axis)
                        if (value < Double(line)) == (b > a) { low = middle } else { high = middle }
                    }
                    positions.append((low + high) / 2)
                }
            }
        }
        try check()
        let endKey = Int64((distanceMeters * 1000).rounded(.toNearestOrAwayFromZero))
        let keys = Set(positions.map { Int64(($0 * 1000).rounded(.toNearestOrAwayFromZero)) })
        let result = keys.sorted().map { $0 == endKey ? distanceMeters : Double($0) / 1000 }
        return CoverageTerrainCrossings(distancesMeters: result, geometryEvaluations: evaluations)
    }
}
