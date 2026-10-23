import Foundation

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
}

private extension Double {
    var radians: Double { self * .pi / 180 }
    var degrees: Double { self * 180 / .pi }
}
