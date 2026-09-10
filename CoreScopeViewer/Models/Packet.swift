import Foundation

/// Payload type codes shared across the packet, live-feed, and analytics
/// endpoints (see "Payload Type Reference" in docs/api-spec.md).
enum PayloadType: Int, CaseIterable, Sendable {
    case req = 0
    case response = 1
    case txtMsg = 2
    case ack = 3
    case advert = 4
    case grpTxt = 5
    case anonReq = 7
    case path = 8
    case trace = 9
    case control = 11

    var name: String {
        switch self {
        case .req: "REQ"
        case .response: "RESPONSE"
        case .txtMsg: "TXT_MSG"
        case .ack: "ACK"
        case .advert: "ADVERT"
        case .grpTxt: "GRP_TXT"
        case .anonReq: "ANON_REQ"
        case .path: "PATH"
        case .trace: "TRACE"
        case .control: "CONTROL"
        }
    }

    static func name(for raw: Int) -> String {
        PayloadType(rawValue: raw)?.name ?? "UNKNOWN(\(raw))"
    }
}

/// The "Packet Object" shared shape returned by /api/packets, /api/nodes/:pubkey
/// (recentAdverts), /api/nodes/:pubkey/health (recentPackets), etc.
struct Packet: Codable, Sendable, Identifiable, Hashable {
    let id: Int
    let rawHex: String?
    let hash: String
    let firstSeen: Date
    let timestamp: Date
    let routeType: Int
    let payloadType: Int
    let payloadVersion: Int?
    let decodedJson: String?
    let observationCount: Int
    let observerId: String?
    let observerName: String?
    let snr: Double?
    let rssi: Double?
    let pathJson: String?
    let direction: String?
    let score: Double?

    var payloadTypeName: String { PayloadType.name(for: payloadType) }

    enum CodingKeys: String, CodingKey {
        case id
        case rawHex = "raw_hex"
        case hash
        case firstSeen = "first_seen"
        case timestamp
        case routeType = "route_type"
        case payloadType = "payload_type"
        case payloadVersion = "payload_version"
        case decodedJson = "decoded_json"
        case observationCount = "observation_count"
        case observerId = "observer_id"
        case observerName = "observer_name"
        case snr, rssi
        case pathJson = "path_json"
        case direction, score
    }
}

struct PacketsResponse: Codable, Sendable {
    let packets: [Packet]
    let total: Int
}
