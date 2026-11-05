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
    let serverID: Int?
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
    /// The analyzer's node for each hop (CoreScope's top-level `resolved_path`); nil when the
    /// source doesn't send one. Unresolved hops are nil entries.
    var resolvedPath: ResolvedPathField? = nil

    var id: String { hash.isEmpty ? "server:\(serverID.map(String.init) ?? "")" : hash }

    var payloadTypeName: String { PayloadType.name(for: payloadType) }

    enum CodingKeys: String, CodingKey {
        case serverID = "id"
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
        case resolvedPath = "resolved_path"
    }
}

/// A packet's resolved hop keys, read leniently: an array (`["ab…", null]`) or that array as a
/// JSON string. Anything else reads as no keys rather than failing the whole packet.
struct ResolvedPathField: Codable, Sendable, Hashable {
    let keys: [String?]

    init(keys: [String?]) { self.keys = keys }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let keys = try? container.decode([String?].self) {
            self.keys = keys
        } else if let text = try? container.decode(String.self),
                  let data = text.data(using: .utf8),
                  let keys = try? JSONDecoder().decode([String?].self, from: data) {
            self.keys = keys
        } else {
            self.keys = []
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(keys)
    }
}

struct PacketsResponse: Codable, Sendable {
    let packets: [Packet]
    let total: Int
}
