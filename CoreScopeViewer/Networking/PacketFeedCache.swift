import Foundation

/// Keeps a short-lived copy of the raw `/api/packets` GRP_TXT/CHAN feed per
/// analyzer and region. `ChannelsListScreen` refreshes every monitored
/// channel's summary from this same feed, and opening a channel's detail
/// screen re-fetches it again moments later — without this cache each of
/// those would independently pull up to 1000 packets over the network.
actor PacketFeedCache {
    static let shared = PacketFeedCache()

    struct Key: Hashable, Sendable {
        let source: String
        let region: String?
    }

    private struct Entry: Sendable {
        let packets: [Packet]
        let loadedAt: Date
    }

    private struct RequestKey: Hashable {
        let key: Key
        let sessionID: UUID
    }

    private var generation = UUID()
    private let lifetime: TimeInterval = 30
    private var entries: [Key: Entry] = [:]
    private var inFlightLoads: [RequestKey: Task<[Packet], Error>] = [:]

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
    ) async throws -> [Packet] {
        try apiClient.sourceSession.check()
        let generation = self.generation
        let requestKey = RequestKey(key: key, sessionID: apiClient.sourceSession.id)
        if !forceRefresh,
           let entry = entries[key],
           Date().timeIntervalSince(entry.loadedAt) < lifetime {
            return entry.packets
        }

        if let inFlightLoad = inFlightLoads[requestKey] {
            let result = try await inFlightLoad.value
            try apiClient.sourceSession.check()
            guard generation == self.generation else { throw CancellationError() }
            return result
        }

        let load = Task { [apiClient] in
            let response = try await apiClient.packets(region: key.region, limit: 1000, payloadType: 5)
            return response
        }
        inFlightLoads[requestKey] = load

        do {
            let packets = try await load.value
            try apiClient.sourceSession.check()
            guard generation == self.generation else { throw CancellationError() }
            entries[key] = Entry(packets: packets, loadedAt: Date())
            inFlightLoads[requestKey] = nil
            return packets
        } catch {
            if generation == self.generation { inFlightLoads[requestKey] = nil }
            throw error
        }
    }

}
