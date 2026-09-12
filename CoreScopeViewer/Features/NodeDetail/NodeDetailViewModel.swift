import Foundation
import Observation

@Observable
@MainActor
final class NodeDetailViewModel {
    var health: NodeHealthResponse?
    var paths: NodePathsResponse?
    var reach: NodeReachResponse?
    var isLoading = true

    private var apiClient: APIClient?

    func configure(settings: AnalyzerSettings) {
        apiClient = APIClient(settings: settings)
    }

    func load(pubkey: String) async {
        guard let apiClient else { return }
        isLoading = true
        defer { isLoading = false }

        let encodedPubkey = pubkey.urlPathComponentEncoded
        async let healthTask: NodeHealthResponse? = try? apiClient.get("/api/nodes/\(encodedPubkey)/health")
        async let pathsTask: NodePathsResponse? = try? apiClient.get("/api/nodes/\(encodedPubkey)/paths")
        async let reachTask: NodeReachResponse? = try? apiClient.get("/api/nodes/\(encodedPubkey)/reach")

        health = await healthTask
        paths = await pathsTask
        reach = await reachTask
    }
}
