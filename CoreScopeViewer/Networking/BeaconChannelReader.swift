import Foundation

struct BeaconChannelReader: Sendable {
    let client: BeaconAPIClient
    private let messageCursors = MessageCursorStore()

    init(client: BeaconAPIClient, verifiedServerInfo: BeaconServerInfo) throws {
        try verifiedServerInfo.requireSupported()
        self.client = client
    }

    func channels(pageSize: Int = 200) async throws -> [MeshChannel] {
        guard pageSize > 0 else { throw AnalyzerPagingError.invalidPageSize }
        try client.sourceSession.check()
        var channels: [MeshChannel] = []
        var cursor: String?
        var seenCursors = Set<String>()
        var seenIDs = Set<Int>()

        for _ in 0..<10 {
            var query = [
                URLQueryItem(name: "limit", value: String(min(pageSize, 200))),
                URLQueryItem(name: "keyKnown", value: "true")
            ]
            if let cursor { query.append(URLQueryItem(name: "pageCursor", value: cursor)) }
            let page: Page = try await client.get(["channels"], query: query)
            try client.sourceSession.check()

            for item in page.items {
                guard item.id > 0, item.keyKnown,
                      !item.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      seenIDs.insert(item.id).inserted else {
                    throw APIError.invalidResponse
                }
                channels.append(item.meshChannel)
            }

            guard page.hasMore else { return channels }
            guard !page.items.isEmpty, let next = page.nextPageCursor, !next.isEmpty,
                  seenCursors.insert(next).inserted else {
                throw BeaconAPIError.malformedPage
            }
            cursor = next
        }
        throw BeaconAPIError.malformedPage
    }

    func messages(channelID: String, offset: Int) async throws -> ChannelMessagesResponse {
        guard let channelID = Int(channelID), channelID > 0, offset >= 0 else {
            throw APIError.invalidResponse
        }
        try client.sourceSession.check()
        var cursor: Int?
        var consumed = 0
        var seenCursors = Set<Int>()
        if offset == 0 {
            await messageCursors.reset(channelID: channelID)
        } else if let savedCursor = await messageCursors.cursor(channelID: channelID, offset: offset) {
            cursor = savedCursor
            consumed = offset
            seenCursors.insert(savedCursor)
        }

        for _ in 0..<10 {
            var query = [URLQueryItem(name: "limit", value: "20")]
            if let cursor { query.append(URLQueryItem(name: "cursor", value: String(cursor))) }
            let page: MessagePage = try await client.get(["channels", String(channelID), "messages"], query: query)
            try client.sourceSession.check()
            guard Set(page.items.map(\.id)).count == page.items.count,
                  page.items.allSatisfy({ $0.id > 0 && !$0.packetHash.isEmpty && $0.observationCount >= 0 }) else {
                throw APIError.invalidResponse
            }
            let validatedNextCursor: Int?
            if page.hasMore {
                guard !page.items.isEmpty, let next = page.nextCursor,
                      seenCursors.insert(next).inserted else {
                    throw BeaconAPIError.malformedPage
                }
                validatedNextCursor = next
            } else {
                validatedNextCursor = nil
            }

            let nextConsumed = consumed + page.items.count
            if offset < nextConsumed || (offset == consumed && page.items.isEmpty) {
                let start = max(0, offset - consumed)
                let messages = page.items.dropFirst(start).map(\.channelMessage)
                if let validatedNextCursor {
                    await messageCursors.record(
                        channelID: channelID,
                        offset: offset + messages.count,
                        cursor: validatedNextCursor
                    )
                }
                let total = offset + messages.count + (page.hasMore ? 1 : 0)
                return ChannelMessagesResponse(messages: messages, total: total)
            }
            guard let next = validatedNextCursor else {
                return ChannelMessagesResponse(messages: [], total: consumed)
            }
            consumed = nextConsumed
            cursor = next
        }
        throw BeaconAPIError.malformedPage
    }

    private actor MessageCursorStore {
        private var cursors: [Int: [Int: Int]] = [:]

        func cursor(channelID: Int, offset: Int) -> Int? {
            cursors[channelID]?[offset]
        }

        func record(channelID: Int, offset: Int, cursor: Int) {
            cursors[channelID, default: [:]][offset] = cursor
        }

        func reset(channelID: Int) {
            cursors[channelID] = nil
        }
    }

    private struct Page: Decodable, Sendable {
        let items: [Summary]
        let hasMore: Bool
        let nextPageCursor: String?
    }

    private struct Summary: Decodable, Sendable {
        let id: Int
        let name: String
        let channelHash: String
        let lastSeen: Date
        let isHashtag: Bool
        let keyKnown: Bool

        var meshChannel: MeshChannel {
            MeshChannel(
                hash: channelHash, name: name, lastMessage: nil, lastSender: nil,
                messageCount: nil, lastActivity: lastSeen, serverID: id,
                isHashtag: isHashtag
            )
        }
    }

    private struct MessagePage: Decodable, Sendable {
        let items: [Message]
        let nextCursor: Int?
        let hasMore: Bool
    }

    private struct Message: Decodable, Sendable {
        let id: Int
        let packetHash: String
        let senderName: String
        let content: String
        let sentAt: Date
        let observationCount: Int

        var channelMessage: ChannelMessage {
            ChannelMessage(
                sender: senderName, text: content, timestamp: sentAt,
                senderTimestamp: nil, packetId: id, packetHash: packetHash,
                repeats: observationCount, observers: [], hops: 0, snr: nil
            )
        }
    }
}
