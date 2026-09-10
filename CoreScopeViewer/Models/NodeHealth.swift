import Foundation

/// GET /api/nodes/:pubkey/health
struct NodeHealthResponse: Codable, Sendable {
    let node: MeshNode
    let observers: [NodeObserverStat]
    let stats: NodeHealthStats
    let recentPackets: [Packet]
}

struct NodeObserverStat: Codable, Sendable, Identifiable {
    let observerId: String
    let observerName: String?
    let packetCount: Int
    let avgSnr: Double?
    let avgRssi: Double?
    let iata: String?

    var id: String { observerId }

    enum CodingKeys: String, CodingKey {
        case observerId = "observer_id"
        case observerName = "observer_name"
        case packetCount, avgSnr, avgRssi, iata
    }
}

struct NodeHealthStats: Codable, Sendable {
    let totalTransmissions: Int
    let totalObservations: Int
    let totalPackets: Int
    let packetsToday: Int
    let avgSnr: Double?
    let avgHops: Double?
    let lastHeard: Date?
}

/// GET /api/nodes/:pubkey/paths
struct NodePathsResponse: Codable, Sendable {
    let node: NodeRef
    let paths: [NodePath]
    let totalPaths: Int
    let totalTransmissions: Int
}

struct NodeRef: Codable, Sendable {
    let publicKey: String?
    let name: String?
    let lat: Double?
    let lon: Double?

    enum CodingKeys: String, CodingKey {
        case publicKey = "public_key"
        case name, lat, lon
    }
}

struct NodePath: Codable, Sendable, Identifiable {
    let hops: [PathHop]
    let count: Int
    let lastSeen: Date?
    let sampleHash: String

    var id: String { sampleHash }
}

struct PathHop: Codable, Sendable {
    let prefix: String
    let name: String
    let pubkey: String?
    let lat: Double?
    let lon: Double?
}

/// GET /api/nodes/:pubkey/reach
struct NodeReachResponse: Codable, Sendable {
    let node: ReachNodeRef
    let window: ReachWindow
    let reliableTokens: [String]
    let importance: ReachImportance
    let directObservers: [ReachDirectObserver]
    let links: [ReachLink]

    enum CodingKeys: String, CodingKey {
        case node, window
        case reliableTokens = "reliable_tokens"
        case importance
        case directObservers = "direct_observers"
        case links
    }
}

struct ReachNodeRef: Codable, Sendable {
    let pubkey: String
    let name: String
    let role: String
    let lat: Double?
    let lon: Double?
    let firstSeen: Date

    enum CodingKeys: String, CodingKey {
        case pubkey, name, role, lat, lon
        case firstSeen = "first_seen"
    }
}

struct ReachWindow: Codable, Sendable {
    let days: Int
    let since: Date
}

struct ReachImportance: Codable, Sendable {
    let neighborDegree: Int
    let degreeRank: Int
    let nodesWithEdges: Int
    let relayObservations: Int
    let bidirectionalLinks: Int
    let directObservers: Int

    enum CodingKeys: String, CodingKey {
        case neighborDegree = "neighbor_degree"
        case degreeRank = "degree_rank"
        case nodesWithEdges = "nodes_with_edges"
        case relayObservations = "relay_observations"
        case bidirectionalLinks = "bidirectional_links"
        case directObservers = "direct_observers"
    }
}

struct ReachDirectObserver: Codable, Sendable, Identifiable {
    let pubkey: String
    let name: String
    let count: Int
    let avgSnr: Double?
    let lat: Double?
    let lon: Double?
    let distanceKm: Double?

    var id: String { pubkey }

    enum CodingKeys: String, CodingKey {
        case pubkey, name, count
        case avgSnr = "avg_snr"
        case lat, lon
        case distanceKm = "distance_km"
    }
}

struct ReachLink: Codable, Sendable, Identifiable {
    let pubkey: String
    let name: String
    let role: String
    let lat: Double?
    let lon: Double?
    let weHear: Int
    let theyHear: Int
    let bottleneck: Int
    let bidir: Bool
    let distanceKm: Double?

    var id: String { pubkey }

    enum CodingKeys: String, CodingKey {
        case pubkey, name, role, lat, lon
        case weHear = "we_hear"
        case theyHear = "they_hear"
        case bottleneck, bidir
        case distanceKm = "distance_km"
    }
}
