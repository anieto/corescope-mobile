import Foundation

/// Keeps a short-lived channel list per analyzer and region. This makes the
/// Channels tab immediately useful after launch without treating fast-moving
/// mesh activity as permanently cached data.
actor ChannelListCache {
    static let shared = ChannelListCache()

    struct Key: Hashable, Sendable {
        let source: String
        let region: String?
    }

    private struct Entry: Sendable {
        let channels: [MeshChannel]
        let loadedAt: Date
    }

    private struct RequestKey: Hashable {
        let key: Key
        let sessionID: UUID
    }

    private var generation = UUID()
    private let lifetime: TimeInterval = 90
    private var entries: [Key: Entry] = [:]
    private var inFlightLoads: [RequestKey: Task<[MeshChannel], Error>] = [:]

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
    ) async throws -> [MeshChannel] {
        try apiClient.sourceSession.check()
        let generation = self.generation
        let requestKey = RequestKey(key: key, sessionID: apiClient.sourceSession.id)
        if !forceRefresh,
           let entry = entries[key],
           Date().timeIntervalSince(entry.loadedAt) < lifetime {
            return entry.channels
        }

        if let inFlightLoad = inFlightLoads[requestKey] {
            let result = try await inFlightLoad.value
            try apiClient.sourceSession.check()
            guard generation == self.generation else { throw CancellationError() }
            return result
        }

        let load = Task { [apiClient] in
            let response = try await apiClient.channels(region: key.region)
            return response.channels
        }
        inFlightLoads[requestKey] = load

        do {
            let channels = try await load.value
            try apiClient.sourceSession.check()
            guard generation == self.generation else { throw CancellationError() }
            entries[key] = Entry(channels: channels, loadedAt: Date())
            inFlightLoads[requestKey] = nil
            return channels
        } catch {
            if generation == self.generation { inFlightLoads[requestKey] = nil }
            throw error
        }
    }
}
