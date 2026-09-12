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

    func load(
        for key: Key,
        using apiClient: APIClient,
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
            var query = [
                URLQueryItem(name: "limit", value: "1000"),
                URLQueryItem(name: "payloadType", value: "5")
            ]
            if let region = key.region {
                query.append(URLQueryItem(name: "region", value: region))
            }
            let response: PacketsResponse = try await apiClient.get("/api/packets", query: query)
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
