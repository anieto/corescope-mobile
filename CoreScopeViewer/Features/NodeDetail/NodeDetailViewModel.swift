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

    private var apiClient: APIClient?
    private var cacheNamespace = ""

    func configure(settings: AnalyzerSettings) {
        apiClient = APIClient(settings: settings)
        cacheNamespace = settings.host.lowercased()
    }

    func load(pubkey: String) async {
        guard let apiClient else { return }
        let cacheKey = "node-detail-\(cacheNamespace)-\(pubkey.lowercased())"
        if let cached: NodeDetailCacheValue = await APIResponseCache.shared.value(
            for: cacheKey,
            maximumAge: 24 * 60 * 60
        ) {
            health = cached.health
            paths = cached.paths
            reach = cached.reach
        }
        isLoading = health == nil && paths == nil && reach == nil
        defer { isLoading = false }

        let encodedPubkey = pubkey.urlPathComponentEncoded
        guard let fresh: NodeDetailCacheValue = try? await APIResponseCache.shared.refresh(
            for: cacheKey,
            loader: {
                async let healthTask: NodeHealthResponse? = try? apiClient.get("/api/nodes/\(encodedPubkey)/health")
                async let pathsTask: NodePathsResponse? = try? apiClient.get("/api/nodes/\(encodedPubkey)/paths")
                async let reachTask: NodeReachResponse? = try? apiClient.get("/api/nodes/\(encodedPubkey)/reach")
                let result = await NodeDetailCacheValue(
                    health: healthTask,
                    paths: pathsTask,
                    reach: reachTask
                )
                guard result.health != nil || result.paths != nil || result.reach != nil else {
                    throw URLError(.cannotLoadFromNetwork)
                }
                return result
            }
        ) else { return }
        health = fresh.health
        paths = fresh.paths
        reach = fresh.reach
    }
}
