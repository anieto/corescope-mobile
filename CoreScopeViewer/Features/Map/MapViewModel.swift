import Foundation
import Observation

private actor MapResponseCache {
    static let shared = MapResponseCache()

    private struct Entry<Value: Codable>: Codable {
        let savedAt: Date
        let value: Value
    }

    func value<Value: Codable>(
        for key: String,
        maximumAge: TimeInterval
    ) -> Value? {
        let url = fileURL(for: key)
        guard let data = try? Data(contentsOf: url),
              let entry = try? JSONDecoder().decode(Entry<Value>.self, from: data),
              Date().timeIntervalSince(entry.savedAt) <= maximumAge else {
            return nil
        }
        return entry.value
    }

    func store<Value: Codable>(_ value: Value, for key: String) {
        let url = fileURL(for: key)
        let entry = Entry(savedAt: .now, value: value)
        guard let data = try? JSONEncoder().encode(entry) else { return }
        try? data.write(to: url, options: .atomic)
    }

    private func fileURL(for key: String) -> URL {
        let fileName = key.data(using: .utf8)?
            .base64EncodedString()
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")
            ?? UUID().uuidString
        let directory = FileManager.default.urls(
            for: .cachesDirectory,
            in: .userDomainMask
        )[0].appendingPathComponent("MapResponseCache", isDirectory: true)
        try? FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        return directory.appendingPathComponent(fileName).appendingPathExtension("json")
    }
}

@Observable
@MainActor
final class MapViewModel {
    var nodes: [MeshNode] = [] {
        didSet {
            // `uniquingKeysWith` rather than `uniqueKeysWithValues:` — a
            // crash here would take down the whole map over a single
            // duplicate pubkey from the server, which isn't worth the risk.
            nodesByPubkey = Dictionary(nodes.map { ($0.publicKey.lowercased(), $0) }, uniquingKeysWith: { first, _ in first })
        }
    }
    var recentPackets: [Packet] = []
    var mapDefaults: MapDefaults?
    var isLoading = false
    var errorMessage: String?

    /// O(1) exact-pubkey lookup for resolving `resolved_path` entries on
    /// every live packet, instead of scanning up to ~1,000 nodes per hop.
    private(set) var nodesByPubkey: [String: MeshNode] = [:]

    private var apiClient: APIClient?
    private var cacheNamespace = ""
    private let automaticRefreshInterval: TimeInterval = 30

    func configure(settings: AnalyzerSettings) {
        apiClient = APIClient(settings: settings)
        cacheNamespace = settings.host.lowercased()
    }

    /// Analyzer hosts have independent node sets and map defaults. Clear the
    /// displayed state before loading a new source so old-host data cannot
    /// briefly appear or influence the next camera position.
    func resetForAnalyzerSource() {
        nodes = []
        recentPackets = []
        mapDefaults = nil
        errorMessage = nil
        isLoading = false
    }

    func loadMapDefaults() async {
        let cacheKey = "map-defaults-\(cacheNamespace)"
        if let cached: MapDefaults = await MapResponseCache.shared.value(
            for: cacheKey,
            maximumAge: 24 * 60 * 60
        ) {
            mapDefaults = cached
        }

        guard let apiClient else { return }
        // Non-fatal if this fails: the map just falls back to cached defaults.
        if let defaults: MapDefaults = try? await apiClient.get("/api/config/map") {
            mapDefaults = defaults
            await MapResponseCache.shared.store(defaults, for: cacheKey)
        }
    }

    func loadNodes(region: String?, forceRefresh: Bool = false) async {
        let cacheKey = "map-nodes-\(cacheNamespace)-\(region ?? "all")"
        let hadCachedNodes: Bool
        if let cached: NodesResponse = await MapResponseCache.shared.value(
            for: cacheKey,
            maximumAge: 10 * 60
        ) {
            nodes = cached.nodes
            errorMessage = nil
            hadCachedNodes = true
        } else {
            hadCachedNodes = false
            errorMessage = nil
        }

        guard let apiClient,
              forceRefresh || refreshIsDue(for: cacheKey) else {
            return
        }

        // Cached content remains interactive while the background refresh runs.
        isLoading = !hadCachedNodes && nodes.isEmpty
        defer { isLoading = false }
        do {
            // Request enough to cover the whole node set, not just a
            // display-sized page — the live map's ping animation resolves
            // hop prefixes against this same list, and a packet routed
            // through a node outside a small page silently fails to
            // resolve. The host has ~1,000 nodes today; ask for well above
            // that rather than re-checking `total` on every load.
            var query: [URLQueryItem] = [URLQueryItem(name: "limit", value: "5000")]
            if let region {
                query.append(URLQueryItem(name: "region", value: region))
            }
            let response: NodesResponse = try await apiClient.get("/api/nodes", query: query)
            nodes = response.nodes
            errorMessage = nil
            await MapResponseCache.shared.store(response, for: cacheKey)
            recordRefresh(for: cacheKey)
        } catch {
            if error is CancellationError || (error as? URLError)?.code == .cancelled {
                return
            }
            if nodes.isEmpty {
                errorMessage = error.localizedDescription
            }
        }
    }

    func loadPackets(region: String?, forceRefresh: Bool = false) async {
        let cacheKey = "map-packets-\(cacheNamespace)-\(region ?? "all")"
        if let cached: PacketsResponse = await MapResponseCache.shared.value(
            for: cacheKey,
            maximumAge: 10 * 60
        ) {
            recentPackets = cached.packets
        }

        guard let apiClient,
              forceRefresh || refreshIsDue(for: cacheKey) else {
            return
        }

        do {
            var query: [URLQueryItem] = [URLQueryItem(name: "limit", value: "200")]
            if let region {
                query.append(URLQueryItem(name: "region", value: region))
            }
            let response: PacketsResponse = try await apiClient.get("/api/packets", query: query)
            recentPackets = response.packets
            await MapResponseCache.shared.store(response, for: cacheKey)
            recordRefresh(for: cacheKey)
        } catch {
            // Non-fatal if packets endpoint is unavailable
        }
    }

    private func refreshIsDue(for cacheKey: String) -> Bool {
        let key = "last-map-refresh-\(cacheKey)"
        guard let lastRefresh = UserDefaults.standard.object(forKey: key) as? Date else {
            return true
        }
        return Date().timeIntervalSince(lastRefresh) >= automaticRefreshInterval
    }

    private func recordRefresh(for cacheKey: String) {
        UserDefaults.standard.set(Date(), forKey: "last-map-refresh-\(cacheKey)")
    }
}
