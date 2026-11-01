import Foundation
import simd

struct FieldCoordinate: Codable, Equatable, Sendable {
    let latitude: Double
    let longitude: Double

    init?(latitude: Double, longitude: Double) {
        guard latitude.isFinite,
              longitude.isFinite,
              (-90...90).contains(latitude),
              (-180...180).contains(longitude) else {
            return nil
        }

        self.latitude = latitude
        self.longitude = longitude
    }
}

enum FieldCardinalDirection: String, Codable, CaseIterable, Sendable {
    case north = "N"
    case northNortheast = "NNE"
    case northeast = "NE"
    case eastNortheast = "ENE"
    case east = "E"
    case eastSoutheast = "ESE"
    case southeast = "SE"
    case southSoutheast = "SSE"
    case south = "S"
    case southSouthwest = "SSW"
    case southwest = "SW"
    case westSouthwest = "WSW"
    case west = "W"
    case westNorthwest = "WNW"
    case northwest = "NW"
    case northNorthwest = "NNW"
}

struct FieldGeometryResult: Equatable, Sendable {
    let distanceMeters: Double
    let initialBearingDegrees: Double?

    var cardinalDirection: FieldCardinalDirection? {
        initialBearingDegrees.map(FieldGeometry.cardinalDirection)
    }
}

enum FieldGeometry {
    /// IUGG mean Earth radius. Android uses the same value for cross-platform parity.
    static let earthRadiusMeters = 6_371_008.8

    static func geometry(from origin: FieldCoordinate, to destination: FieldCoordinate) -> FieldGeometryResult {
        let originLatitude = origin.latitude.radians
        let destinationLatitude = destination.latitude.radians
        let latitudeDelta = (destination.latitude - origin.latitude).radians
        let longitudeDelta = (destination.longitude - origin.longitude).radians

        let haversine = pow(sin(latitudeDelta / 2), 2)
            + cos(originLatitude) * cos(destinationLatitude) * pow(sin(longitudeDelta / 2), 2)
        let angularDistance = 2 * asin(min(1, sqrt(haversine)))
        let distance = earthRadiusMeters * angularDistance

        guard distance > 0 else {
            return FieldGeometryResult(distanceMeters: 0, initialBearingDegrees: nil)
        }

        let y = sin(longitudeDelta) * cos(destinationLatitude)
        let x = cos(originLatitude) * sin(destinationLatitude)
            - sin(originLatitude) * cos(destinationLatitude) * cos(longitudeDelta)
        let bearing = (atan2(y, x).degrees + 360).truncatingRemainder(dividingBy: 360)

        return FieldGeometryResult(distanceMeters: distance, initialBearingDegrees: bearing)
    }

    static func cardinalDirection(for bearingDegrees: Double) -> FieldCardinalDirection {
        let normalized = (bearingDegrees.truncatingRemainder(dividingBy: 360) + 360)
            .truncatingRemainder(dividingBy: 360)
        let index = Int((normalized + 11.25) / 22.5) % FieldCardinalDirection.allCases.count
        return FieldCardinalDirection.allCases[index]
    }

    static func relativeHeadingRotation(
        trueBearingDegrees: Double,
        deviceTrueHeadingDegrees: Double
    ) -> Double? {
        guard trueBearingDegrees.isFinite, deviceTrueHeadingDegrees.isFinite else { return nil }
        return ((trueBearingDegrees - deviceTrueHeadingDegrees).truncatingRemainder(dividingBy: 360) + 360)
            .truncatingRemainder(dividingBy: 360)
    }

    /// The angle equal to `target` (mod 360) that is closest to `current`, so an animated rotation
    /// crossing north turns a few degrees instead of spinning most of the way around.
    static func nearestEquivalentAngle(to target: Double, from current: Double?) -> Double {
        guard let current else { return target }
        let delta = ((target - current).truncatingRemainder(dividingBy: 360) + 540)
            .truncatingRemainder(dividingBy: 360) - 180
        return current + delta
    }

    /// The true heading of the direction the phone is pointing: its top edge when held flat and the
    /// direction its back faces when held upright, blended in between so tilting the phone doesn't
    /// swing or flip the result. Both axes are unit vectors in Core Motion's x-true-north,
    /// z-vertical frame (x north, y west, z up). Returns nil when that direction is nearly vertical.
    static func pointingHeadingDegrees(deviceTopAxis top: SIMD3<Double>, deviceScreenAxis screen: SIMD3<Double>) -> Double? {
        let flatness = min(1, abs(screen.z))
        let forward = top * flatness - screen * (1 - flatness)
        let north = forward.x
        let east = -forward.y
        guard (north * north + east * east).squareRoot() > 0.05 else { return nil }
        return (atan2(east, north).degrees + 360).truncatingRemainder(dividingBy: 360)
    }

    /// Core Motion documents the attitude matrix only as the device's rotation relative to the
    /// reference frame, so work out from gravity (known in device coordinates) whether its rows or
    /// its columns hold the device axes. Returns nil while the phone is too close to flat to tell.
    static func deviceAxesUseMatrixRows(rows: [SIMD3<Double>], gravity: SIMD3<Double>) -> Bool? {
        guard rows.count == 3 else { return nil }
        let columns = (0..<3).map { index in SIMD3(rows[0][index], rows[1][index], rows[2][index]) }
        // If rows are the device axes in the reference frame, the matrix maps reference vectors
        // into the device frame, so gravity (0, 0, -1) lands on minus the third column.
        let gravityIfRows = -columns[2]
        let gravityIfColumns = -rows[2]
        guard simd_distance(gravityIfRows, gravityIfColumns) > 0.2 else { return nil }
        let normalized = simd_normalize(gravity)
        return simd_dot(normalized, gravityIfRows) > simd_dot(normalized, gravityIfColumns)
    }
}

private extension Double {
    var radians: Double { self * .pi / 180 }
    var degrees: Double { self * 180 / .pi }
}
