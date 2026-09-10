import CoreLocation
import Foundation

/// Matches the node shapes returned by /api/nodes, /api/nodes/search, and
/// /api/nodes/:pubkey (see docs/api-spec.md in Kpa-clawbot/CoreScope).
/// Fields that aren't present on every variant are optional so one model
/// can decode all of them.
struct MeshNode: Codable, Identifiable, Hashable, Sendable {
    let publicKey: String
    let name: String?
    let role: String
    let lat: Double?
    let lon: Double?
    let lastSeen: Date
    let firstSeen: Date
    let advertCount: Int?
    let hashSize: Int?
    let hashSizeInconsistent: Bool?
    let lastHeard: Date?
    let defaultScope: String?

    var id: String { publicKey }

    var coordinate: CLLocationCoordinate2D? {
        guard let lat, let lon else { return nil }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    enum CodingKeys: String, CodingKey {
        case publicKey = "public_key"
        case name, role, lat, lon
        case lastSeen = "last_seen"
        case firstSeen = "first_seen"
        case advertCount = "advert_count"
        case hashSize = "hash_size"
        case hashSizeInconsistent = "hash_size_inconsistent"
        case lastHeard = "last_heard"
        case defaultScope = "default_scope"
    }
}

struct NodesResponse: Codable, Sendable {
    let nodes: [MeshNode]
    let total: Int
    let counts: RoleCounts
}

struct RoleCounts: Codable, Sendable {
    let repeaters: Int
    let rooms: Int
    let companions: Int
    let sensors: Int
}

struct NodeDetailResponse: Codable, Sendable {
    let node: MeshNode
    let recentAdverts: [Packet]
}
