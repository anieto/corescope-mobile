import Foundation

struct PacketDetailResponse: Codable, Sendable {
    let packet: Packet
    let path: [String]
    let observationCount: Int
    let observations: [PacketObservation]

    enum CodingKeys: String, CodingKey {
        case packet, path, observations
        case observationCount = "observation_count"
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
