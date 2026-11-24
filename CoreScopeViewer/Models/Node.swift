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
    let lastSeen: Date?
    let firstSeen: Date?
    let advertCount: Int?
    let hashSize: Int?
    let hashSizeInconsistent: Bool?
    let lastHeard: Date?
    let defaultScope: String?

    var id: String { publicKey }

    var latestActivity: Date? { [lastSeen, lastHeard].compactMap { $0 }.max() }

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
    let recentAdverts: [Packet]?
}

/// Public community-maintained metadata from CoreScope's separate repeater catalog.
struct RepeaterMetadata: Codable, Sendable {
    struct Claim: Codable, Sendable {
        let displayName: String
        let claimedAt: Date?

        enum CodingKeys: String, CodingKey { case displayName, claimedAt }

        init(displayName: String, claimedAt: Date?) {
            self.displayName = displayName
            self.claimedAt = claimedAt
        }

        init(from decoder: Decoder) throws {
            let fields = try decoder.container(keyedBy: CodingKeys.self)
            displayName = try fields.decode(String.self, forKey: .displayName)
            claimedAt = try? fields.decode(Date.self, forKey: .claimedAt)
        }
    }

    let pubkey: String
    let heightM: Double?
    let gainDbi: Double?
    let status: String?
    let power: String?
    let notes: String?
    let claimers: [Claim]
    let updatedAt: Date?
    let updatedBy: String?

    func applicableHeightMeters(minimum: Double = 0) -> Double? {
        guard !claimers.isEmpty, let heightM, (minimum...300).contains(heightM) else { return nil }
        return heightM
    }

    // Upstream uses zero even when the edit omitted gain.
    var applicableGainDbi: Double? { gainDbi.flatMap { $0 == 0 ? nil : $0 } }

    var hasDetails: Bool {
        heightM != nil || gainDbi != nil || status != nil || power != nil
            || notes != nil || !claimers.isEmpty || updatedAt != nil || updatedBy != nil
    }

    enum CodingKeys: String, CodingKey {
        case pubkey, heightM, gainDbi, status, power, notes, claimers, updatedAt, updatedBy
    }

    init(from decoder: Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        pubkey = try fields.decode(String.self, forKey: .pubkey).lowercased()
        guard pubkey.utf8.count == 64,
              pubkey.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else {
            throw DecodingError.dataCorruptedError(forKey: .pubkey, in: fields, debugDescription: "Expected a full hexadecimal node key")
        }
        heightM = (try? fields.decode(Double.self, forKey: .heightM)).flatMap { (0...300).contains($0) ? $0 : nil }
        updatedAt = try? fields.decode(Date.self, forKey: .updatedAt)
        let hasUpdate = updatedAt != nil
        gainDbi = (try? fields.decode(Double.self, forKey: .gainDbi)).flatMap {
            (-10...30).contains($0) && ($0 != 0 || hasUpdate) ? $0 : nil
        }
        status = (try? fields.decode(String.self, forKey: .status)).flatMap {
            ["active", "maintenance", "decommissioned"].contains($0) ? $0 : nil
        }
        power = (try? fields.decode(String.self, forKey: .power)).flatMap {
            ["mains", "battery", "solar"].contains($0) ? $0 : nil
        }
        notes = (try? fields.decode(String.self, forKey: .notes)).flatMap(Self.cleanText)
        updatedBy = (try? fields.decode(String.self, forKey: .updatedBy)).flatMap(Self.cleanText)
        claimers = (try? fields.decode([LossyRepeaterValue<Claim>].self, forKey: .claimers))?
            .compactMap(\.value).compactMap { claim in
                Self.cleanText(claim.displayName).map { Claim(displayName: $0, claimedAt: claim.claimedAt) }
            } ?? []
    }

    private static func cleanText(_ value: String) -> String? {
        let cleaned = String(String.UnicodeScalarView(value.unicodeScalars.filter {
            ($0 == "\n" || !CharacterSet.controlCharacters.contains($0))
                && !(0x202A...0x202E).contains($0.value) && !(0x2066...0x2069).contains($0.value)
        }.prefix(500))).trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? nil : cleaned
    }
}

struct RepeaterMetadataCatalog: Codable, Sendable {
    let repeaters: [RepeaterMetadata]
    var reportedStatuses: [String: String] {
        repeaters.reduce(into: [:]) { $0[$1.pubkey] = $1.status }
    }

    static let empty = Self(repeaters: [])

    private init(repeaters: [RepeaterMetadata]) { self.repeaters = repeaters }

    enum CodingKeys: String, CodingKey { case repeaters }

    init(from decoder: Decoder) throws {
        let fields = try decoder.container(keyedBy: CodingKeys.self)
        repeaters = try fields.decode([LossyRepeaterValue<RepeaterMetadata>].self, forKey: .repeaters)
            .compactMap(\.value).filter(\.hasDetails)
    }

    subscript(publicKey: String) -> RepeaterMetadata? {
        let normalized = publicKey.lowercased()
        return repeaters.first { $0.pubkey == normalized }
    }
}

private struct LossyRepeaterValue<Value: Decodable>: Decodable {
    let value: Value?
    init(from decoder: Decoder) { value = try? Value(from: decoder) }
}
