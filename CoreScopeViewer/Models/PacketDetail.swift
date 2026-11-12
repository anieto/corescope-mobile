import Foundation

struct PacketDetailResponse: Codable, Sendable {
    let packet: Packet
    let path: [String]
    let observationCount: Int
    let observations: [PacketObservation]
    /// A channel message the server decrypted (Beacon), not part of CoreScope's response.
    var decryptedMessage: DecryptedPacketMessage? = nil

    enum CodingKeys: String, CodingKey {
        case packet, path, observations
        case observationCount = "observation_count"
    }
}

/// Text Beacon decrypted for a packet on a channel whose key the server holds.
struct DecryptedPacketMessage: Equatable, Sendable {
    let sender: String?
    let content: String
    let sentAt: Date?

    /// "Sender: message", as CoreScope's channel text reads.
    var line: String {
        guard let sender, !sender.isEmpty else { return content }
        return "\(sender): \(content)"
    }
}

struct PacketObservation: Codable, Sendable, Identifiable {
    let id: Int
    let observerName: String?
    let observerIata: String?
    let snr: Double?
    let rssi: Double?
    let timestamp: Date
    // CoreScope uses null for individual hops it couldn't resolve to a node.
    let resolvedPath: [String?]?

    enum CodingKeys: String, CodingKey {
        case id, snr, rssi, timestamp
        case observerName = "observer_name"
        case observerIata = "observer_iata"
        case resolvedPath = "resolved_path"
    }
}
