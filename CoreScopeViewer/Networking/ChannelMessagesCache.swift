import Foundation

/// Keeps a short-lived copy of a public channel's `/api/channels/:hash/messages`
/// response, so re-opening a channel you already viewed moments ago doesn't
/// re-hit the network for the same messages.
actor ChannelMessagesCache {
    static let shared = ChannelMessagesCache()

    struct Key: Hashable, Sendable {
        let source: String
        let hash: String
        var offset: Int = 0
    }

    private struct Entry: Sendable {
        let response: ChannelMessagesResponse
        let loadedAt: Date
    }

    private let lifetime: TimeInterval = 30
    private var entries: [Key: Entry] = [:]
    private var inFlightLoads: [Key: Task<ChannelMessagesResponse, Error>] = [:]

    func clear() {
        inFlightLoads.values.forEach { $0.cancel() }
        inFlightLoads.removeAll()
        entries.removeAll()
    }

    func load(
        for key: Key,
        using apiClient: APIClient,
        forceRefresh: Bool = false
    ) async throws -> ChannelMessagesResponse {
        if !forceRefresh,
           let entry = entries[key],
           Date().timeIntervalSince(entry.loadedAt) < lifetime {
            return entry.response
        }

        if let inFlightLoad = inFlightLoads[key] {
            return try await inFlightLoad.value
        }

        let load = Task { [apiClient] in
            let response: ChannelMessagesResponse = try await apiClient.get(
                "/api/channels/\(key.hash.urlPathComponentEncoded)/messages",
                query: [
                    URLQueryItem(name: "limit", value: "500"),
                    URLQueryItem(name: "offset", value: String(key.offset))
                ]
            )
            return response
        }
        inFlightLoads[key] = load

        do {
            let messages = try await load.value
            entries[key] = Entry(response: messages, loadedAt: Date())
            inFlightLoads[key] = nil
            return messages
        } catch {
            inFlightLoads[key] = nil
            throw error
        }
    }
}
