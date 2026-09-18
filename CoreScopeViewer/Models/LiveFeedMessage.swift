import Foundation

/// WebSocket message envelope: { "type": "packet" | "message" | "heartbeat", "data": {...} }
/// "packet" and "message" share the same `data` shape. "heartbeat" carries
/// no `data` field at all — the server writes `{"type":"heartbeat"}` on
/// every ping tick (websocket.go's `wsHeartbeat`, added upstream so
/// public/app.js can detect a silent connection and reconnect) — so `data`
/// must stay Optional. LiveFeedService skips inserting heartbeat envelopes
/// into the feed; `id` falls back to -1 for that case since it's never
/// actually used for a heartbeat.
///
/// The published docs/api-spec.md documents this field as `observer`
/// (described as "observer_id"), but the server actually sends the key
/// literally named `observer_id` — decoding `observer` against real traffic
/// silently produced nil every time. Verified directly against the live
/// feed's raw JSON rather than trusting the docs a second time.
struct LiveEnvelope: Codable, Sendable, Identifiable {
    let type: String
    let data: LivePacketData?
    let receivedAt: Date

    var id: Int { data?.id ?? -1 }

    enum CodingKeys: String, CodingKey {
        case type, data
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = try container.decode(String.self, forKey: .type)
        data = try container.decodeIfPresent(LivePacketData.self, forKey: .data)
        receivedAt = .now
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(type, forKey: .type)
        try container.encodeIfPresent(data, forKey: .data)
    }
}

struct LivePacketData: Codable, Sendable {
    let id: Int
    let raw: String?
    let decoded: LiveDecoded?
    let snr: Double?
    let rssi: Double?
    let hash: String?
    let observerId: String?
    let observerName: String?
    let pathJson: String?
    let observationCount: Int?
    /// Full lowercase pubkeys aligned 1:1 with `decoded.path.hops`, already
    /// disambiguated server-side — undocumented in api-spec.md but present
    /// on every live packet with a non-empty hop path (confirmed against
    /// raw WS traffic). A `nil` entry means the server itself couldn't
    /// resolve that hop. This is what CoreScope's own frontend prefers
    /// (`hop-resolver.js`'s `resolveFromServer`) over re-deriving it from
    /// hex prefixes, which collide unpredictably once there are more than a
    /// couple hundred nodes.
    let resolvedPath: [String?]?

    enum CodingKeys: String, CodingKey {
        case id, raw, decoded, snr, rssi, hash
        case observerId = "observer_id"
        case observerName = "observer_name"
        case pathJson = "path_json"
        case observationCount = "observation_count"
        case resolvedPath = "resolved_path"
    }
}

struct LiveDecoded: Codable, Sendable {
    let header: LiveHeader?
    let path: LivePath?
    let payload: LivePayload?
}

struct LivePayload: Codable, Sendable {
    let text: String?
    let name: String?
    let channel: String?
    let publicKey: String?

    enum CodingKeys: String, CodingKey {
        case text, name, channel
        case publicKey = "pubKey"
    }
}

struct LiveHeader: Codable, Sendable {
    let routeType: Int?
    let payloadType: Int?
    let payloadVersion: Int?
    private let rawPayloadTypeName: String?

    var payloadTypeName: String {
        if let rawPayloadTypeName { return rawPayloadTypeName }
        if let payloadType { return PayloadType.name(for: payloadType) }
        return "UNKNOWN"
    }

    enum CodingKeys: String, CodingKey {
        case routeType, payloadType, payloadVersion
        case rawPayloadTypeName = "payloadTypeName"
    }

    init(routeType: Int? = nil, payloadType: Int? = nil, payloadVersion: Int? = nil, payloadTypeName: String? = nil) {
        self.routeType = routeType
        self.payloadType = payloadType
        self.payloadVersion = payloadVersion
        self.rawPayloadTypeName = payloadTypeName
    }
}

struct LivePath: Codable, Sendable {
    let hops: [String]?
}
