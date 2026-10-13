import Foundation

enum NeighborDirection: String, Codable, Sendable, Equatable {
    case incoming
    case outgoing
    case bidirectional
    case unknown
}

struct NeighborIdentity: Codable, Sendable, Equatable, Hashable {
    let publicKey: String?
    let displayPrefix: String?
    let sourceID: String?

    var linkablePublicKey: String? {
        guard let publicKey, Self.isFullPublicKey(publicKey) else { return nil }
        return publicKey.lowercased()
    }

    private static func isFullPublicKey(_ value: String) -> Bool {
        value.utf8.count == 64 && value.utf8.allSatisfy {
            (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0)
        }
    }
}

struct NeighborSignal: Codable, Sendable, Equatable {
    let snr: Double?
    let rssi: Double?
}

struct NeighborInsight: Codable, Sendable, Equatable, Identifiable {
    let identity: NeighborIdentity
    let name: String?
    let role: String?
    let lastHeard: Date?
    let observationCount: Int?
    let signal: NeighborSignal
    let direction: NeighborDirection
    let latitude: Double?
    let longitude: Double?

    var id: String {
        identity.linkablePublicKey ?? identity.sourceID ?? identity.displayPrefix ?? name ?? "unknown"
    }

    var hasValidCoordinate: Bool {
        guard let latitude, let longitude else { return false }
        return (-90...90).contains(latitude)
            && (-180...180).contains(longitude)
            && (latitude != 0 || longitude != 0)
    }
}

struct NeighborCollection: Codable, Sendable, Equatable {
    let neighbors: [NeighborInsight]
    let completeness: AnalyticsCompleteness
    let explanation: String?
}
