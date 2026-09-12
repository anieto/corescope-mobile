import Foundation

/// Shared shape for /api/observers (list) and /api/observers/:id (detail).
/// The detail response omits lat/lon/nodeRole; all "extra" fields are
/// optional so one model decodes both.
struct MeshObserver: Codable, Sendable, Identifiable, Hashable {
    let id: String
    let name: String?
    let iata: String?
    let lastSeen: Date
    let firstSeen: Date
    let packetCount: Int
    let model: String?
    let firmware: String?
    let clientVersion: String?
    let radio: String?
    let batteryMv: Int?
    let uptimeSecs: Int?
    let noiseFloor: Double?
    let packetsLastHour: Int
    let lat: Double?
    let lon: Double?
    let nodeRole: String?

    enum CodingKeys: String, CodingKey {
        case id, name, iata
        case lastSeen = "last_seen"
        case firstSeen = "first_seen"
        case packetCount = "packet_count"
        case model, firmware
        case clientVersion = "client_version"
        case radio
        case batteryMv = "battery_mv"
        case uptimeSecs = "uptime_secs"
        case noiseFloor = "noise_floor"
        case packetsLastHour, lat, lon, nodeRole
    }
}

struct ObserversResponse: Codable, Sendable {
    let observers: [MeshObserver]
    let serverTime: Date

    enum CodingKeys: String, CodingKey {
        case observers
        case serverTime = "server_time"
    }
}

/// GET /api/observers/:id/analytics
struct ObserverAnalyticsResponse: Codable, Sendable {
    let timeline: [LabeledCount]
    let packetTypes: [String: Int]
    let nodesTimeline: [LabeledCount]
    let snrDistribution: [SnrBucket]
    let recentPackets: [ObserverRecentPacket]
}

/// The observer analytics endpoint returns a lightweight packet projection,
/// not the complete `/api/packets` shape represented by `Packet`.
struct ObserverRecentPacket: Codable, Sendable, Identifiable {
    let id: Int
    let hash: String
    let timestamp: Date
    let payloadType: Int
    let snr: Double?
    let rssi: Double?
    let direction: String?

    var payloadTypeName: String { PayloadType.name(for: payloadType) }

    enum CodingKeys: String, CodingKey {
        case id, hash, timestamp, snr, rssi, direction
        case payloadType = "payload_type"
    }
}

struct LabeledCount: Codable, Sendable, Identifiable {
    let label: String
    let count: Int
    var id: String { label }
}

struct SnrBucket: Codable, Sendable, Identifiable {
    let range: String
    let count: Int
    var id: String { range }
}
