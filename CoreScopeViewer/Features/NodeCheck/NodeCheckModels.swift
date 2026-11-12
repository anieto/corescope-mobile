import Foundation

/// Everything Node Check needs about one node, already read from the analyzer.
/// Built by the backend; turned into findings by `NodeCheckRules`.
struct NodeCheckInput: Sendable {
    struct Node: Sendable, Hashable {
        let publicKey: String
        let name: String?
        let role: String
        let lat: Double?
        let lon: Double?
        let lastHeard: Date?
        let lastSeen: Date?
        let lastRelayed: Date?
        let firstSeen: Date?
    }

    struct Observation: Sendable, Hashable {
        let observerID: String
        let observerName: String?
        let region: String?
        let hops: Int?
        let snr: Double?
        let rssi: Double?
    }

    struct Advert: Sendable, Hashable {
        let id: Int
        let sentAt: Date?
        /// "flood", "zero_hop", or nil when the analyzer doesn't say.
        let kind: String?
        let observations: [Observation]
    }

    struct DirectObserver: Sendable, Hashable {
        let observerID: String
        let name: String?
        let averageSNR: Double?
        let averageRSSI: Double?
        let packets: Int?
    }

    struct Clock: Sendable, Hashable {
        /// CoreScope's severity: ok, warning, critical, absurd, no_clock, bimodal_clock.
        let severity: String
        /// Seconds the node's clock is ahead (positive) or behind (negative).
        let offsetSeconds: Double?
    }

    let node: Node
    /// Newest first. Only adverts; other transmissions are dropped by the reader.
    let adverts: [Advert]
    let directObservers: [DirectObserver]
    let healthLastHeard: Date?
    let clock: Clock?
    /// Observer id → region code, for observations and direct observers that don't carry one.
    let observerRegions: [String: String]
}

enum NodeCheckError: Error, LocalizedError {
    case unsupported
    case ambiguousKey

    var errorDescription: String? {
        switch self {
        case .unsupported:
            BackendCapability.nodeCheck.beaconExplanation
        case .ambiguousKey:
            String(localized: "More than one node starts with that key. Enter more of it.")
        }
    }
}

// MARK: - CoreScope wire shapes

/// `GET /api/nodes/{key}` as Node Check reads it: the node plus its recent
/// transmissions with every observer's report. Dates are read as text so one
/// odd timestamp can't fail the whole check.
struct CoreScopeNodeCheckDetail: Decodable, Sendable {
    struct Node: Decodable, Sendable {
        let publicKey: String
        let name: String?
        let role: String?
        let lat: Double?
        let lon: Double?
        let lastSeen: String?
        let lastHeard: String?
        let lastRelayed: String?
        let firstSeen: String?

        enum CodingKeys: String, CodingKey {
            case publicKey = "public_key"
            case name, role, lat, lon
            case lastSeen = "last_seen"
            case lastHeard = "last_heard"
            case lastRelayed = "last_relayed"
            case firstSeen = "first_seen"
        }
    }

    struct Transmission: Decodable, Sendable {
        let id: Int?
        let timestamp: String?
        let firstSeen: String?
        let payloadType: Int?
        let advertKind: String?
        let observations: [Observation]?

        enum CodingKeys: String, CodingKey {
            case id, timestamp, observations
            case firstSeen = "first_seen"
            case payloadType = "payload_type"
            case advertKind = "advert_kind"
        }
    }

    struct Observation: Decodable, Sendable {
        let observerID: String?
        let observerName: String?
        let observerIATA: String?
        let pathJSON: String?
        let snr: Double?
        let rssi: Double?

        enum CodingKeys: String, CodingKey {
            case observerID = "observer_id"
            case observerName = "observer_name"
            case observerIATA = "observer_iata"
            case pathJSON = "path_json"
            case snr, rssi
        }
    }

    let node: Node
    let recentAdverts: [Transmission]?
}

/// `GET /api/nodes/{key}/health`: only the parts Node Check uses. `observers`
/// is CoreScope's direct-RF set, the observers that heard the node itself.
struct CoreScopeNodeCheckHealth: Decodable, Sendable {
    struct Observer: Decodable, Sendable {
        let observerID: String
        let observerName: String?
        let avgSnr: Double?
        let avgRssi: Double?
        let packetCount: Int?

        enum CodingKeys: String, CodingKey {
            case observerID = "observer_id"
            case observerName = "observer_name"
            case avgSnr, avgRssi, packetCount
        }
    }

    struct Stats: Decodable, Sendable {
        let lastHeard: String?
    }

    let observers: [Observer]?
    let stats: Stats?
}

/// `GET /api/nodes/{key}/clock-skew`.
struct CoreScopeNodeCheckClock: Decodable, Sendable {
    let severity: String?
    let medianSkewSec: Double?
    let recentMedianSkewSec: Double?
}

struct CoreScopeNodeSearch: Decodable, Sendable {
    let nodes: [MeshNode]
}

enum NodeCheckDates {
    nonisolated(unsafe) private static let fractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    nonisolated(unsafe) private static let plain: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    static func parse(_ text: String?) -> Date? {
        guard let text, !text.isEmpty else { return nil }
        return fractional.date(from: text) ?? plain.date(from: text)
    }
}

extension CoreScopeNodeCheckDetail.Observation {
    /// Repeaters the copy passed through; 0 means the observer heard the node itself.
    var hops: Int? {
        guard let pathJSON, let data = pathJSON.data(using: .utf8),
              let hops = try? JSONDecoder().decode([String].self, from: data) else { return nil }
        return hops.count
    }
}
