import Foundation

/// GET /api/channels
struct MeshChannel: Codable, Sendable, Identifiable, Hashable {
    let hash: String
    let name: String
    let lastMessage: String?
    let lastSender: String?
    let messageCount: Int
    let lastActivity: Date

    var id: String { hash }
}

struct ChannelsResponse: Codable, Sendable {
    let channels: [MeshChannel]
}

/// GET /api/channels/:hash/messages
struct ChannelMessage: Codable, Sendable, Identifiable, Hashable {
    let sender: String
    let text: String
    let timestamp: Date
    let senderTimestamp: Double?
    let packetId: Int
    let packetHash: String
    let repeats: Int
    let observers: [String]
    let hops: Int
    let snr: Double?

    var id: Int { packetId }

    enum CodingKeys: String, CodingKey {
        case sender, text, timestamp
        case senderTimestamp = "sender_timestamp"
        case packetId, packetHash
        case repeats, observers, hops, snr
    }
}

struct ChannelMessagesResponse: Codable, Sendable {
    let messages: [ChannelMessage]
    let total: Int
}
