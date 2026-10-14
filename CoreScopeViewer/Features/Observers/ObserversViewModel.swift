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
    private(set) var beaconDetail: MeshObserver?
    private(set) var beaconDetailSource: UUID?

    private var apiClient: (any AnalyzerBackend)?
    private var cacheNamespace = ""
    private var activeLoadID = UUID()
    private let staleCacheLifetime: TimeInterval = 7 * 24 * 60 * 60

    func loadBeaconDetail(id: String, sourceSessionID: UUID, service: LiveFeedService) async {
        if let previous = beaconDetailSource, previous != sourceSessionID {
            activeLoadID = UUID()
            beaconDetail = nil
            analytics = nil
            lastUpdatedAt = nil
            isLoading = false
            errorMessage = String(localized: "The server changed. Return to the observer list.")
            return
        }
        beaconDetailSource = sourceSessionID
        if beaconDetail?.id.lowercased() != id.lowercased() {
            beaconDetail = nil
            lastUpdatedAt = nil
        }
        let loadID = UUID()
        activeLoadID = loadID
        isLoading = true
        errorMessage = nil
        analytics = nil
        defer { if activeLoadID == loadID { isLoading = false } }
        do {
            let detail = try await service.observerDetail(id: id, sourceSessionID: sourceSessionID)
            guard activeLoadID == loadID, !Task.isCancelled else { return }
            beaconDetail = detail
            lastUpdatedAt = .now
        } catch {
            guard activeLoadID == loadID, !Task.isCancelled else { return }
            if error is CancellationError {
                errorMessage = String(localized: "The live connection changed. Retry or return to the observer list.")
            } else {
                errorMessage = error.localizedDescription
            }
        }
    }

    func configure(settings: AnalyzerSettings) {
        let backend = AnalyzerBackendFactory.make(settings: settings)
        if apiClient?.sourceSession.id != backend.sourceSession.id {
            activeLoadID = UUID()
            observers = []
            analytics = nil
            lastUpdatedAt = nil
            errorMessage = nil
            isLoading = false
        }
        apiClient = backend
        cacheNamespace = backend.cacheIdentifier
    }

    func loadObservers() async {
        guard let apiClient else { return }
        let loadID = UUID()
        activeLoadID = loadID
        let cacheKey = "observers-\(cacheNamespace)-\(apiClient.sourceSession.id)"
        if let cached: ObserversResponse = await APIResponseCache.shared.value(
            for: cacheKey,
            maximumAge: staleCacheLifetime
        ) {
            let savedAt = await APIResponseCache.shared.savedAt(for: cacheKey)
            guard isCurrent(loadID, backend: apiClient) else { return }
            observers = cached.observers
            lastUpdatedAt = savedAt
            errorMessage = nil
        }
        guard isCurrent(loadID, backend: apiClient) else { return }
        isLoading = observers.isEmpty
        defer {
            if activeLoadID == loadID, apiClient.sourceSession.isCurrent { isLoading = false }
        }
        do {
            let response: ObserversResponse = try await APIResponseCache.shared.refresh(for: cacheKey, session: apiClient.sourceSession) {
                try await apiClient.observers()
            }
            guard isCurrent(loadID, backend: apiClient) else { return }
            observers = response.observers
            lastUpdatedAt = .now
            errorMessage = nil
        } catch {
            guard isCurrent(loadID, backend: apiClient), !(error is CancellationError) else { return }
            errorMessage = error.localizedDescription
        }
    }

    func loadAnalytics(id: String) async {
        guard let apiClient else { return }
        let loadID = UUID()
        activeLoadID = loadID
        guard apiClient.capabilities.contains(.observerAnalytics) else {
            analytics = nil
            lastUpdatedAt = nil
            isLoading = false
            errorMessage = apiClient is BeaconBackend ? BackendCapability.observerAnalytics.beaconExplanation
                : String(localized: "Observer analytics is not available on this server.")
            return
        }
        let cacheKey = "observer-analytics-\(cacheNamespace)-\(apiClient.sourceSession.id)-\(id.lowercased())"
        if let cached: ObserverAnalyticsResponse = await APIResponseCache.shared.value(
            for: cacheKey,
            maximumAge: 5 * 60
        ) {
            let savedAt = await APIResponseCache.shared.savedAt(for: cacheKey)
            guard isCurrent(loadID, backend: apiClient) else { return }
            analytics = cached
            lastUpdatedAt = savedAt
            errorMessage = nil
        }
        guard isCurrent(loadID, backend: apiClient) else { return }
        isLoading = analytics == nil
        defer {
            if activeLoadID == loadID, apiClient.sourceSession.isCurrent { isLoading = false }
        }
        do {
            let response: ObserverAnalyticsResponse = try await APIResponseCache.shared.refresh(for: cacheKey, session: apiClient.sourceSession) {
                try await apiClient.observerAnalytics(id: id)
            }
            guard isCurrent(loadID, backend: apiClient) else { return }
            analytics = response
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
