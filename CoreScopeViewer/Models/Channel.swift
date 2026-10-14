import Foundation

/// GET /api/channels
struct MeshChannel: Codable, Sendable, Identifiable, Hashable {
    let hash: String
    let name: String
    let lastMessage: String?
    let lastSender: String?
    let messageCount: Int?
    let lastActivity: Date
    let serverID: Int?
    let isHashtag: Bool?

    var id: String { serverID.map { "beacon:\($0)" } ?? hash }
    var requestIdentifier: String { serverID.map(String.init) ?? hash }
    var isLocallyMonitored: Bool { hash.hasPrefix("user:") }
    var allowsBeaconResetRecovery: Bool {
        serverID != nil && (isHashtag == true || name == "Public")
    }

    func beaconReplacement(in channels: [MeshChannel]) -> MeshChannel? {
        guard allowsBeaconResetRecovery else { return nil }
        let matches = channels.filter { $0.serverID != nil && $0.name == name }
        return matches.count == 1 ? matches[0] : nil
    }

    init(
        hash: String, name: String, lastMessage: String?, lastSender: String?,
        messageCount: Int?, lastActivity: Date, serverID: Int? = nil,
        isHashtag: Bool? = nil
    ) {
        self.hash = hash
        self.name = name
        self.lastMessage = lastMessage
        self.lastSender = lastSender
        self.messageCount = messageCount
        self.lastActivity = lastActivity
        self.serverID = serverID
        self.isHashtag = isHashtag
    }
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
    let packetId: Int?
    let packetHash: String
    let repeats: Int
    let observers: [String]
    let hops: Int
    let snr: Double?

    var id: String {
        packetHash.isEmpty ? "server:\(packetId.map(String.init) ?? "")" : packetHash
    }

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
