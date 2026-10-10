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

    private let lifetime: TimeInterval = 30
    private var entries: [Key: Entry] = [:]
    private var inFlightLoads: [Key: Task<[Packet], Error>] = [:]

    func clear() {
        inFlightLoads.values.forEach { $0.cancel() }
        inFlightLoads.removeAll()
        entries.removeAll()
    }

    func load(
        for key: Key,
        using apiClient: any AnalyzerBackend,
        forceRefresh: Bool = false
    ) async throws -> [Packet] {
        if !forceRefresh,
           let entry = entries[key],
           Date().timeIntervalSince(entry.loadedAt) < lifetime {
            return entry.packets
        }

        if let inFlightLoad = inFlightLoads[key] {
            return try await inFlightLoad.value
        }

        let load = Task { [apiClient] in
            let response = try await apiClient.packets(region: key.region, limit: 1000, payloadType: 5)
            return response.packets
        }
        inFlightLoads[key] = load

        do {
            let packets = try await load.value
            entries[key] = Entry(packets: packets, loadedAt: Date())
            inFlightLoads[key] = nil
            return packets
        } catch {
            inFlightLoads[key] = nil
            throw error
        }
    }

}
