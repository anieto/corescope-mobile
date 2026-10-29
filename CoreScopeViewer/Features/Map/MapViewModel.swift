import Foundation
import Observation

private actor MapResponseCache {
    static let shared = MapResponseCache()

    private var memoryData: [String: Data] = [:]

    private struct Entry<Value: Codable>: Codable {
        let savedAt: Date
        let value: Value
    }

    private struct TimestampEntry: Decodable {
        let savedAt: Date
    }

    func value<Value: Codable>(
        for key: String,
        maximumAge: TimeInterval
    ) -> Value? {
        let url = fileURL(for: key)
        guard let data = memoryData[key] ?? (try? Data(contentsOf: url)),
              let entry = try? JSONDecoder().decode(Entry<Value>.self, from: data),
              Date().timeIntervalSince(entry.savedAt) <= maximumAge else {
            return nil
        }
        memoryData[key] = data
        return entry.value
    }

    func clear() {
        memoryData.removeAll()
        try? FileManager.default.removeItem(at: cacheDirectory)
    }

    func store<Value: Codable>(_ value: Value, for key: String) {
        let url = fileURL(for: key)
        let entry = Entry(savedAt: .now, value: value)
        guard let data = try? JSONEncoder().encode(entry) else { return }
        memoryData[key] = data
        try? data.write(to: url, options: .atomic)
    }

    func savedAt(for key: String) -> Date? {
        let data = memoryData[key] ?? (try? Data(contentsOf: fileURL(for: key)))
        guard let data,
              let entry = try? JSONDecoder().decode(TimestampEntry.self, from: data) else {
            return nil
        }
        memoryData[key] = data
        return entry.savedAt
    }

    private var cacheDirectory: URL {
        FileManager.default.urls(
            for: .cachesDirectory,
            in: .userDomainMask
        )[0].appendingPathComponent("MapResponseCache", isDirectory: true)
    }

    private func fileURL(for key: String) -> URL {
        let fileName = key.data(using: .utf8)?
            .base64EncodedString()
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")
            ?? UUID().uuidString
        try? FileManager.default.createDirectory(
            at: cacheDirectory,
            withIntermediateDirectories: true
        )
        return cacheDirectory.appendingPathComponent(fileName).appendingPathExtension("json")
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
    var lastUpdatedAt: Date?

    /// O(1) exact-pubkey lookup for resolving `resolved_path` entries on
    /// every live packet, instead of scanning up to ~1,000 nodes per hop.
    private(set) var nodesByPubkey: [String: MeshNode] = [:]

    private var apiClient: (any AnalyzerBackend)?
    private var cacheNamespace = ""
    private var nodeLoadID = UUID()
    private var loadedNodeRegion: String?
    private var hasLoadedNodeRegion = false
    private var appliedCatalogID: UUID?
    private var appliedCatalogRegion: String?
    private let staleCacheLifetime: TimeInterval = 7 * 24 * 60 * 60

    static func clearCachedResponses() async {
        await MapResponseCache.shared.clear()
    }

    func configure(settings: AnalyzerSettings) {
        let backend = AnalyzerBackendFactory.make(settings: settings)
        apiClient = backend
        cacheNamespace = backend.cacheIdentifier
    }

    /// Analyzer hosts have independent node sets and map defaults. Clear the
    /// displayed state before loading a new source so old-host data cannot
    /// briefly appear or influence the next camera position.
    func resetForAnalyzerSource() {
        nodeLoadID = UUID()
        hasLoadedNodeRegion = false
        appliedCatalogID = nil
        nodes = []
        recentPackets = []
        mapDefaults = nil
        errorMessage = nil
        lastUpdatedAt = nil
        isLoading = false
    }

    func loadMapDefaults() async {
        guard let apiClient, apiClient.capabilities.contains(.mapDefaults),
              apiClient.sourceSession.isCurrent, !Task.isCancelled else { return }
        guard mapDefaults == nil else { return }
        let cacheKey = "map-defaults-\(cacheNamespace)"
        if let cached: MapDefaults = await MapResponseCache.shared.value(
            for: cacheKey,
            maximumAge: staleCacheLifetime
        ) {
            guard apiClient.sourceSession.isCurrent, !Task.isCancelled else { return }
            mapDefaults = cached
            return
        }

        // Non-fatal if this fails: the map just falls back to cached defaults.
        if let defaults: MapDefaults = try? await apiClient.mapDefaults() {
            guard apiClient.sourceSession.isCurrent, !Task.isCancelled else { return }
            mapDefaults = defaults
            await MapResponseCache.shared.store(defaults, for: cacheKey)
        }
    }

    func loadNodes(region: String?, forceRefresh: Bool = false) async {
        guard let apiClient, apiClient.sourceSession.isCurrent, !Task.isCancelled else { return }
        let requestID = UUID()
        nodeLoadID = requestID
        hasLoadedNodeRegion = false
        let hadCachedNodes: Bool
        if let cached = await FieldCatalogRepository.shared.cachedNodes(for: apiClient, region: region) {
            guard apiClient.sourceSession.isCurrent, nodeLoadID == requestID, !Task.isCancelled else { return }
            nodes = cached.value.nodes
            loadedNodeRegion = region
            hasLoadedNodeRegion = true
            lastUpdatedAt = cached.savedAt
            errorMessage = nil
            hadCachedNodes = true
        } else {
            guard apiClient.sourceSession.isCurrent, nodeLoadID == requestID, !Task.isCancelled else { return }
            hadCachedNodes = false
            errorMessage = nil
        }

        guard await FieldCatalogRepository.shared.nodesNeedRefresh(
            for: apiClient, region: region, force: forceRefresh
        ) else {
            return
        }

        // Cached content remains interactive while the background refresh runs.
        isLoading = !hadCachedNodes && nodes.isEmpty
        defer {
            if apiClient.sourceSession.isCurrent, nodeLoadID == requestID { isLoading = false }
        }
        do {
            // Request enough to cover the whole node set, not just a
            // display-sized page — the live map's ping animation resolves
            // hop prefixes against this same list, and a packet routed
            // through a node outside a small page silently fails to
            // resolve. The host has ~1,000 nodes today; ask for well above
            // that rather than re-checking `total` on every load.
            let catalog = try await FieldCatalogRepository.shared.refreshNodes(for: apiClient, region: region)
            try apiClient.sourceSession.check()
            guard nodeLoadID == requestID, !Task.isCancelled else { return }
            nodes = catalog.value.nodes
            loadedNodeRegion = region
            hasLoadedNodeRegion = true
            lastUpdatedAt = catalog.savedAt
            errorMessage = nil
        } catch {
            guard apiClient.sourceSession.isCurrent, nodeLoadID == requestID, !Task.isCancelled else { return }
            if error is CancellationError || (error as? URLError)?.code == .cancelled {
                return
            }
            errorMessage = error.localizedDescription
        }
    }

    func applyBeaconNodes(
        _ states: [String: BeaconLiveProtocol.NodeState], region: String?, sourceSessionID: UUID,
        catalog: [BeaconNodeReader.Node]? = nil, catalogID: UUID? = nil
    ) {
        guard let apiClient, apiClient.sourceSession.id == sourceSessionID,
              apiClient.sourceSession.isCurrent else { return }
        let baseline: [MeshNode]
        if let catalog, catalogID == nil || catalogID != appliedCatalogID || region != appliedCatalogRegion {
            let selected = Set(region?.split(separator: ",").map { String($0).uppercased() } ?? [])
            baseline = catalog.filter { node in
                selected.isEmpty || (node.iatas ?? []).contains { selected.contains($0.iata.uppercased()) }
            }.map { record in
                let node = record.meshNode
                if loadedNodeRegion == region, let existing = nodesByPubkey[node.publicKey.lowercased()],
                   let existingDate = existing.latestActivity, let date = node.latestActivity,
                   existingDate > date { return existing }
                return node
            }
            appliedCatalogID = catalogID
            appliedCatalogRegion = region
            loadedNodeRegion = region
            hasLoadedNodeRegion = true
        } else {
            guard hasLoadedNodeRegion, loadedNodeRegion == region else { return }
            baseline = nodes
        }
        let updated = Self.mergingBeaconNodes(baseline, states: states, region: region)
        if updated != nodes { nodes = updated }
    }

    static func mergingBeaconNodes(
        _ nodes: [MeshNode], states: [String: BeaconLiveProtocol.NodeState], region: String?
    ) -> [MeshNode] {
        var result = nodes.map { node in
            states[node.publicKey.lowercased()]?.applying(to: node) ?? node
        }
        let existingKeys = Set(nodes.map { $0.publicKey.lowercased() })
        let selectedRegions = Set(region?.split(separator: ",").map { String($0).uppercased() } ?? [])
        for key in states.keys.sorted() where !existingKeys.contains(key) {
            guard let state = states[key],
                  selectedRegions.isEmpty || !selectedRegions.isDisjoint(with: state.regions.keys) else { continue }
            result.append(state.applying(to: nil))
        }
        return result
    }

    func loadPackets(region: String?, forceRefresh: Bool = false) async {
        guard let apiClient, apiClient.sourceSession.isCurrent, !Task.isCancelled else { return }
        let cacheKey = "map-packets-\(cacheNamespace)-\(region ?? "all")"
        if let cached: [Packet] = await MapResponseCache.shared.value(
            for: cacheKey,
            maximumAge: staleCacheLifetime
        ) {
            guard apiClient.sourceSession.isCurrent, !Task.isCancelled else { return }
            recentPackets = cached
        }

        guard forceRefresh || refreshIsDue(for: cacheKey) else {
            return
        }

        do {
            let response = try await apiClient.packets(region: region, limit: 200, payloadType: nil)
            try apiClient.sourceSession.check()
            recentPackets = response
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
        return Date().timeIntervalSince(lastRefresh) >= FieldRequestBudget.automaticCatalogRefreshInterval
    }

    private func recordRefresh(for cacheKey: String) {
        UserDefaults.standard.set(Date(), forKey: "last-map-refresh-\(cacheKey)")
    }
}
