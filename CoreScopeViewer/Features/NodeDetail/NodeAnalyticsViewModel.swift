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

    private var apiClient: APIClient?
    private var cacheNamespace = ""
    private var activeLoadID = UUID()
    private let staleCacheLifetime: TimeInterval = 24 * 60 * 60

    func configure(settings: AnalyzerSettings) {
        apiClient = APIClient(settings: settings)
        cacheNamespace = AnalyzerSettings.normalizedHost(settings.host).lowercased()
    }

    func load(pubkey: String, range: NodeAnalyticsRange? = nil) async {
        guard let apiClient else { return }
        let requestedRange = range ?? selectedRange
        selectedRange = requestedRange
        let loadID = UUID()
        activeLoadID = loadID
        let cacheKey = "node-analytics-\(cacheNamespace)-\(pubkey.lowercased())-\(requestedRange.rawValue)"

        if let cached: NodeAnalyticsResponse = await APIResponseCache.shared.value(
            for: cacheKey,
            maximumAge: staleCacheLifetime
        ) {
            analytics = cached
            lastUpdatedAt = await APIResponseCache.shared.savedAt(for: cacheKey)
            errorMessage = nil
            isSupported = true
        } else {
            analytics = nil
        }

        isLoading = analytics == nil
        defer {
            if activeLoadID == loadID {
                isLoading = false
            }
        }

        do {
            let response: NodeAnalyticsResponse = try await APIResponseCache.shared.refresh(for: cacheKey) {
                try await apiClient.get(
                    "/api/nodes/\(pubkey.urlPathComponentEncoded)/analytics",
                    query: [URLQueryItem(name: "days", value: String(requestedRange.rawValue))]
                )
            }
            guard activeLoadID == loadID else { return }
            analytics = response
            lastUpdatedAt = .now
            errorMessage = nil
            isSupported = true
        } catch APIError.http(404) {
            guard activeLoadID == loadID else { return }
            analytics = nil
            errorMessage = nil
            isSupported = false
        } catch {
            guard activeLoadID == loadID else { return }
            if error is CancellationError || (error as? URLError)?.code == .cancelled {
                return
            }
            errorMessage = error.localizedDescription
        }
    }
}
