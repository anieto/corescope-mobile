import Foundation

/// Keeps a short-lived copy of a public channel's `/api/channels/:hash/messages`
/// response, so re-opening a channel you already viewed moments ago doesn't
/// re-hit the network for the same messages.
actor ChannelMessagesCache {
    static let shared = ChannelMessagesCache()

    struct Key: Hashable, Sendable {
        let source: String
        let sessionID: UUID
        let hash: String
        var offset: Int = 0
        var requestIdentifier: String? = nil
    }

    private struct Entry: Sendable {
        let response: ChannelMessagesResponse
        let loadedAt: Date
    }

    private struct RequestKey: Hashable {
        let key: Key
        let sessionID: UUID
    }

    private var generation = UUID()
    private let lifetime: TimeInterval = 30
    private var entries: [Key: Entry] = [:]
    private var inFlightLoads: [RequestKey: Task<ChannelMessagesResponse, Error>] = [:]

    func clear() {
        generation = UUID()
        inFlightLoads.values.forEach { $0.cancel() }
        inFlightLoads.removeAll()
        entries.removeAll()
    }

    func load(
        for key: Key,
        using apiClient: any AnalyzerBackend,
        forceRefresh: Bool = false
    ) async throws -> ChannelMessagesResponse {
        try apiClient.sourceSession.check()
        let generation = self.generation
        guard key.sessionID == apiClient.sourceSession.id else { throw CancellationError() }
        let requestKey = RequestKey(key: key, sessionID: apiClient.sourceSession.id)
        if !forceRefresh,
           let entry = entries[key],
           Date().timeIntervalSince(entry.loadedAt) < lifetime {
            return entry.response
        }

        if let inFlightLoad = inFlightLoads[requestKey] {
            let result = try await inFlightLoad.value
            try apiClient.sourceSession.check()
            guard generation == self.generation else { throw CancellationError() }
            return result
        }

        let load = Task { [apiClient] in
            let response: ChannelMessagesResponse = try await apiClient.channelMessages(
                id: key.requestIdentifier ?? key.hash,
                offset: key.offset
            )
            return response
        }
        inFlightLoads[requestKey] = load

        do {
            let messages = try await load.value
            try apiClient.sourceSession.check()
            guard generation == self.generation else { throw CancellationError() }
            entries[key] = Entry(response: messages, loadedAt: Date())
            inFlightLoads[requestKey] = nil
            return messages
        } catch {
            if generation == self.generation { inFlightLoads[requestKey] = nil }
            throw error
        }
    }
}
