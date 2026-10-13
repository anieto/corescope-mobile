import Foundation
import Observation

private struct NodeDetailCacheValue: Codable, Sendable {
    let health: NodeHealthResponse?
    let paths: NodePathsResponse?
    let reach: NodeReachResponse?
}

@Observable
@MainActor
final class NodeDetailViewModel {
    var health: NodeHealthResponse?
    var paths: NodePathsResponse?
    var reach: NodeReachResponse?
    var isLoading = true
    var errorMessage: String?
    var lastUpdatedAt: Date?

    private var apiClient: (any AnalyzerBackend)?
    private var cacheNamespace = ""
    private var activeLoadID = UUID()

    func configure(settings: AnalyzerSettings) {
        let backend = AnalyzerBackendFactory.make(settings: settings)
        if apiClient?.sourceSession.id != backend.sourceSession.id {
            activeLoadID = UUID()
            health = nil
            paths = nil
            reach = nil
            lastUpdatedAt = nil
            errorMessage = nil
            isLoading = false
        }
        apiClient = backend
        cacheNamespace = backend.cacheIdentifier
    }

    func load(pubkey: String) async {
        guard let apiClient else { return }
        let loadID = UUID()
        activeLoadID = loadID
        guard !apiClient.capabilities.isDisjoint(with: [.nodeHealth, .nodePaths, .nodeReach]) else {
            health = nil
            paths = nil
            reach = nil
            lastUpdatedAt = nil
            isLoading = false
            errorMessage = apiClient is BeaconBackend ? BackendCapability.nodeHealth.beaconExplanation
                : String(localized: "Detailed node statistics are not available on this server.")
            return
        }
        let cacheKey = "node-detail-\(cacheNamespace)-\(pubkey.lowercased())"
        if let cached: NodeDetailCacheValue = await APIResponseCache.shared.value(
            for: cacheKey,
            maximumAge: 24 * 60 * 60
        ) {
            let savedAt = await APIResponseCache.shared.savedAt(for: cacheKey)
            guard isCurrent(loadID, backend: apiClient) else { return }
            health = apiClient.capabilities.contains(.nodeHealth) ? cached.health : nil
            paths = apiClient.capabilities.contains(.nodePaths) ? cached.paths : nil
            reach = apiClient.capabilities.contains(.nodeReach) ? cached.reach : nil
            lastUpdatedAt = savedAt
            errorMessage = nil
        }
        guard isCurrent(loadID, backend: apiClient) else { return }
        isLoading = health == nil && paths == nil && reach == nil
        defer {
            if activeLoadID == loadID, apiClient.sourceSession.isCurrent { isLoading = false }
        }

        do {
            let fresh: NodeDetailCacheValue = try await APIResponseCache.shared.refresh(
                for: cacheKey,
                session: apiClient.sourceSession,
                loader: {
                async let healthTask: NodeHealthResponse? = apiClient.capabilities.contains(.nodeHealth)
                    ? try? apiClient.nodeHealth(publicKey: pubkey) : nil
                async let pathsTask: NodePathsResponse? = apiClient.capabilities.contains(.nodePaths)
                    ? try? apiClient.nodePaths(publicKey: pubkey) : nil
                async let reachTask: NodeReachResponse? = apiClient.capabilities.contains(.nodeReach)
                    ? try? apiClient.nodeReach(publicKey: pubkey) : nil
                let result = await NodeDetailCacheValue(
                    health: healthTask,
                    paths: pathsTask,
                    reach: reachTask
                )
                try apiClient.sourceSession.check()
                guard result.health != nil || result.paths != nil || result.reach != nil else {
                    throw URLError(.cannotLoadFromNetwork)
                }
                return result
                }
            )
            guard isCurrent(loadID, backend: apiClient) else { return }
            health = fresh.health
            paths = fresh.paths
            reach = fresh.reach
            lastUpdatedAt = .now
            errorMessage = nil
        } catch {
            guard isCurrent(loadID, backend: apiClient) else { return }
            if error is CancellationError || (error as? URLError)?.code == .cancelled {
                return
            }
            errorMessage = error.localizedDescription
        }
    }

    private func isCurrent(_ loadID: UUID, backend: any AnalyzerBackend) -> Bool {
        activeLoadID == loadID && backend.sourceSession.isCurrent && !Task.isCancelled
    }
}
