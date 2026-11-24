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

    /// Abbreviation for display. The raw value stays English because it's stored.
    var label: String {
        switch self {
        case .north: String(localized: "N", comment: "Compass point abbreviation (north)")
        case .northNortheast: String(localized: "NNE", comment: "Compass point abbreviation (northNortheast)")
        case .northeast: String(localized: "NE", comment: "Compass point abbreviation (northeast)")
        case .eastNortheast: String(localized: "ENE", comment: "Compass point abbreviation (eastNortheast)")
        case .east: String(localized: "E", comment: "Compass point abbreviation (east)")
        case .eastSoutheast: String(localized: "ESE", comment: "Compass point abbreviation (eastSoutheast)")
        case .southeast: String(localized: "SE", comment: "Compass point abbreviation (southeast)")
        case .southSoutheast: String(localized: "SSE", comment: "Compass point abbreviation (southSoutheast)")
        case .south: String(localized: "S", comment: "Compass point abbreviation (south)")
        case .southSouthwest: String(localized: "SSW", comment: "Compass point abbreviation (southSouthwest)")
        case .southwest: String(localized: "SW", comment: "Compass point abbreviation (southwest)")
        case .westSouthwest: String(localized: "WSW", comment: "Compass point abbreviation (westSouthwest)")
        case .west: String(localized: "W", comment: "Compass point abbreviation (west)")
        case .westNorthwest: String(localized: "WNW", comment: "Compass point abbreviation (westNorthwest)")
        case .northwest: String(localized: "NW", comment: "Compass point abbreviation (northwest)")
        case .northNorthwest: String(localized: "NNW", comment: "Compass point abbreviation (northNorthwest)")
        }
    }
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

    /// A tilt-responsive bearing guide. At zero pitch its tip sits at 43% of the preview.
    static func cameraBearingGuideOffset(pitch: Double, halfHeightTangent: Double) -> Double {
        let levelAngle = atan(0.14 * halfHeightTangent)
        let angle = min(1.55, max(-1.55, levelAngle - pitch.radians))
        return tan(angle) / halfHeightTangent
    }

    static func cameraElevationUncertainty(distance: Double, phoneAccuracy: Double) -> Double {
        atan2(phoneAccuracy + 15, distance).degrees
    }

    /// Project a height estimate only when combined vertical uncertainty is small enough to be useful.
    static func cameraVerticalOffset(
        distance: Double, antennaElevation: Double, phoneElevation: Double,
        phoneAccuracy: Double, pitch: Double, halfHeightTangent: Double
    ) -> Double? {
        guard [distance, antennaElevation, phoneElevation, phoneAccuracy, pitch, halfHeightTangent].allSatisfy(\.isFinite),
              distance > 0, phoneAccuracy > 0, halfHeightTangent > 0,
              cameraElevationUncertainty(distance: distance, phoneAccuracy: phoneAccuracy) <= 30 else { return nil }
        let curvature = distance * distance / (2 * earthRadiusMeters)
        let angle = atan2(antennaElevation - phoneElevation - curvature, distance).degrees - pitch
        guard abs(angle) < 89 else { return nil }
        return tan(angle.radians) / halfHeightTangent
    }

    /// Core Motion true-north coordinates are X north, Y west, Z up.
    static func rearCameraHeadingDegrees(deviceScreenAxis screen: SIMD3<Double>) -> Double? {
        let rear = -screen
        guard hypot(rear.x, rear.y) >= 0.35 else { return nil }
        return (atan2(-rear.y, rear.x).degrees + 360).truncatingRemainder(dividingBy: 360)
    }

    /// Position as a fraction of the camera pane's half width, with values outside ±1 offscreen.
    static func cameraHorizontalOffset(bearing: Double, heading: Double, halfWidthTangent: Double) -> Double? {
        guard halfWidthTangent.isFinite, halfWidthTangent > 0,
              let clockwise = relativeHeadingRotation(trueBearingDegrees: bearing, deviceTrueHeadingDegrees: heading)
        else { return nil }
        let signed = clockwise > 180 ? clockwise - 360 : clockwise
        if abs(signed) >= 89 { return signed < 0 ? -Double.infinity : Double.infinity }
        return tan(signed.radians) / halfWidthTangent
    }

    static func cameraScreenAxis(rows: [SIMD3<Double>], useRows: Bool?) -> SIMD3<Double> {
        let row = rows[2]
        let column = SIMD3(rows[0].z, rows[1].z, rows[2].z)
        // When gravity cannot distinguish the layouts, their screen axes are nearly identical.
        switch useRows {
        case true?: return row
        case false?: return column
        case nil: return (row + column) / 2
        }
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
