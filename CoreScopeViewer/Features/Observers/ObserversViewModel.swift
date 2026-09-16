import Foundation
import Observation

@Observable
@MainActor
final class ObserversViewModel {
    var observers: [MeshObserver] = []
    var analytics: ObserverAnalyticsResponse?
    var isLoading = false
    var errorMessage: String?
    var lastUpdatedAt: Date?

    private var apiClient: APIClient?
    private var cacheNamespace = ""
    private let staleCacheLifetime: TimeInterval = 7 * 24 * 60 * 60

    func configure(settings: AnalyzerSettings) {
        apiClient = APIClient(settings: settings)
        cacheNamespace = settings.host.lowercased()
    }

    func loadObservers() async {
        guard let apiClient else { return }
        let cacheKey = "observers-\(cacheNamespace)"
        if let cached: ObserversResponse = await APIResponseCache.shared.value(
            for: cacheKey,
            maximumAge: staleCacheLifetime
        ) {
            observers = cached.observers
            lastUpdatedAt = await APIResponseCache.shared.savedAt(for: cacheKey)
            errorMessage = nil
        }
        isLoading = observers.isEmpty
        defer { isLoading = false }
        do {
            let response: ObserversResponse = try await APIResponseCache.shared.refresh(for: cacheKey) {
                try await apiClient.get("/api/observers")
            }
            observers = response.observers
            lastUpdatedAt = .now
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func loadAnalytics(id: String) async {
        guard let apiClient else { return }
        let cacheKey = "observer-analytics-\(cacheNamespace)-\(id.lowercased())"
        if let cached: ObserverAnalyticsResponse = await APIResponseCache.shared.value(
            for: cacheKey,
            maximumAge: 24 * 60 * 60
        ) {
            analytics = cached
            lastUpdatedAt = await APIResponseCache.shared.savedAt(for: cacheKey)
            errorMessage = nil
        }
        isLoading = analytics == nil
        defer { isLoading = false }
        do {
            analytics = try await APIResponseCache.shared.refresh(for: cacheKey) {
                try await apiClient.get("/api/observers/\(id.urlPathComponentEncoded)/analytics")
            }
            lastUpdatedAt = .now
            errorMessage = nil
        } catch {
            if error is CancellationError || (error as? URLError)?.code == .cancelled {
                return
            }
            errorMessage = error.localizedDescription
        }
    }
}
