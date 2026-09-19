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

    private let lifetime: TimeInterval = 90
    private var entries: [Key: Entry] = [:]
    private var inFlightLoads: [Key: Task<[MeshChannel], Error>] = [:]

    func clear() {
        inFlightLoads.values.forEach { $0.cancel() }
        inFlightLoads.removeAll()
        entries.removeAll()
    }

    func load(
        for key: Key,
        using apiClient: APIClient,
        forceRefresh: Bool = false
    ) async throws -> [MeshChannel] {
        if !forceRefresh,
           let entry = entries[key],
           Date().timeIntervalSince(entry.loadedAt) < lifetime {
            return entry.channels
        }

        if let inFlightLoad = inFlightLoads[key] {
            return try await inFlightLoad.value
        }

        let load = Task { [apiClient] in
            var query: [URLQueryItem] = []
            if let region = key.region {
                query.append(URLQueryItem(name: "region", value: region))
            }
            let response: ChannelsResponse = try await apiClient.get("/api/channels", query: query)
            return response.channels
        }
        inFlightLoads[key] = load

        do {
            let channels = try await load.value
            entries[key] = Entry(channels: channels, loadedAt: Date())
            inFlightLoads[key] = nil
            return channels
        } catch {
            inFlightLoads[key] = nil
            throw error
        }
    }
}
