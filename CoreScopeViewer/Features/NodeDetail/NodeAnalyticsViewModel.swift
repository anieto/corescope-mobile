import Foundation
import Observation

enum NodeAnalyticsRange: Int, CaseIterable, Identifiable, Sendable {
    case day = 1
    case week = 7
    case month = 30
    case year = 365

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .day: "24h"
        case .week: "7d"
        case .month: "30d"
        case .year: "1y"
        }
    }
}

@Observable
@MainActor
final class NodeAnalyticsViewModel {
    var analytics: NodeAnalyticsResponse?
    var selectedRange: NodeAnalyticsRange = .week
    var isLoading = false
    var errorMessage: String?
    var lastUpdatedAt: Date?
    var isSupported = true

    private var apiClient: (any AnalyzerBackend)?
    private var cacheNamespace = ""
    private var activeLoadID = UUID()
    private let staleCacheLifetime: TimeInterval = 24 * 60 * 60

    func configure(settings: AnalyzerSettings) {
        let backend = AnalyzerBackendFactory.make(settings: settings)
        if apiClient?.sourceSession.id != backend.sourceSession.id {
            activeLoadID = UUID()
            analytics = nil
            isSupported = true
            lastUpdatedAt = nil
            errorMessage = nil
            isLoading = false
        }
        apiClient = backend
        cacheNamespace = backend.cacheIdentifier
    }

    func load(pubkey: String, range: NodeAnalyticsRange? = nil) async {
        guard let apiClient else { return }
        let requestedRange = range ?? selectedRange
        selectedRange = requestedRange
        let loadID = UUID()
        activeLoadID = loadID
        guard apiClient.capabilities.contains(.nodeAnalytics) else {
            analytics = nil
            lastUpdatedAt = nil
            errorMessage = nil
            isLoading = false
            isSupported = false
            return
        }
        let cacheKey = "node-analytics-\(cacheNamespace)-\(pubkey.lowercased())-\(requestedRange.rawValue)"

        if let cached: NodeAnalyticsResponse = await APIResponseCache.shared.value(
            for: cacheKey,
            maximumAge: staleCacheLifetime
        ) {
            let savedAt = await APIResponseCache.shared.savedAt(for: cacheKey)
            guard isCurrent(loadID, backend: apiClient) else { return }
            analytics = cached
            lastUpdatedAt = savedAt
            errorMessage = nil
            isSupported = true
        } else {
            guard isCurrent(loadID, backend: apiClient) else { return }
            analytics = nil
        }

        guard isCurrent(loadID, backend: apiClient) else { return }
        isLoading = analytics == nil
        defer {
            if activeLoadID == loadID {
                isLoading = false
            }
        }

        do {
            let response: NodeAnalyticsResponse = try await APIResponseCache.shared.refresh(for: cacheKey, session: apiClient.sourceSession) {
                try await apiClient.nodeAnalytics(publicKey: pubkey, days: requestedRange.rawValue)
            }
            guard isCurrent(loadID, backend: apiClient) else { return }
            analytics = response
            lastUpdatedAt = .now
            errorMessage = nil
            isSupported = true
        } catch APIError.http(404) {
            guard isCurrent(loadID, backend: apiClient) else { return }
            analytics = nil
            errorMessage = nil
            isSupported = false
        } catch {
            guard isCurrent(loadID, backend: apiClient) else { return }
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
