import Foundation

/// GET /api/config/regions returns a flat { "IATA": "Display Name" } object.
typealias RegionsMap = [String: String]

/// GET /api/config/areas
struct AreaFilter: Codable, Sendable, Identifiable, Hashable {
    let key: String
    let label: String

    var id: String { key }
}

/// GET /api/config/map
struct MapDefaults: Codable, Sendable {
    let center: [Double]
    let zoom: Double

    var latitude: Double { center.first ?? 37.45 }
    var longitude: Double { center.count > 1 ? center[1] : -122.0 }
}

/// GET /api/iata-coords — a center + rough radius per region, used to zoom
/// the map to a region when it's selected in the filter.
struct IataCoordinate: Codable, Sendable {
    let lat: Double
    let lon: Double
    let radiusKm: Double?
}

struct IataCoordsResponse: Codable, Sendable {
    let coords: [String: IataCoordinate]
}
