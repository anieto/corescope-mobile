import Foundation
import Observation

@Observable
@MainActor
final class ObserversViewModel {
    var observers: [MeshObserver] = []
    var analytics: ObserverAnalyticsResponse?
    var isLoading = false
    var errorMessage: String?

    private var apiClient: APIClient?

    func configure(settings: AnalyzerSettings) {
        apiClient = APIClient(settings: settings)
    }

    func loadObservers() async {
        guard let apiClient else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            let response: ObserversResponse = try await apiClient.get("/api/observers")
            observers = response.observers
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func loadAnalytics(id: String) async {
        guard let apiClient else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            analytics = try await apiClient.get("/api/observers/\(id.urlPathComponentEncoded)/analytics")
            errorMessage = nil
        } catch {
            if error is CancellationError || (error as? URLError)?.code == .cancelled {
                return
            }
            errorMessage = error.localizedDescription
        }
    }
}
