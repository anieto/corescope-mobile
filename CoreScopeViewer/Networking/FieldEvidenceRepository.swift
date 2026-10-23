import Foundation

struct FieldTargetAnalyticsEvidence: Codable, Equatable, Sendable {
    enum Kind: String, Codable, Sendable {
        case node
        case observer
    }

    let kind: Kind
    let activityCount: Int
    let receiverCount: Int?
    let latestObservedAt: Date?
    let latestSnr: Double?
    let latestRssi: Double?
    let latestPacketHash: String?
    let latestPacketTimestamp: Date?
    let strongestReceiverName: String?
    let strongestReceiverSnr: Double?

    init(node analytics: NodeAnalyticsResponse) {
        kind = .node
        activityCount = analytics.computedStats.totalPackets
        receiverCount = analytics.computedStats.uniqueObservers ?? analytics.observerCoverage.count
        let latest = analytics.snrTrend.max { $0.timestamp < $1.timestamp }
        latestObservedAt = latest?.timestamp
        latestSnr = latest?.snr
        latestRssi = latest?.rssi
        latestPacketHash = nil
        latestPacketTimestamp = nil
        let strongest = analytics.observerCoverage
            .filter { $0.avgSnr?.isFinite == true }
            .max { ($0.avgSnr ?? -.infinity) < ($1.avgSnr ?? -.infinity) }
        strongestReceiverName = strongest?.observerName ?? strongest?.observerID
        strongestReceiverSnr = strongest?.avgSnr
    }

    init(observer analytics: ObserverAnalyticsResponse) {
        kind = .observer
        activityCount = analytics.timeline.reduce(0) { $0 + $1.count }
        receiverCount = nil
        let latest = analytics.recentPackets.max { $0.timestamp < $1.timestamp }
        latestObservedAt = latest?.timestamp
        latestSnr = latest?.snr
        latestRssi = latest?.rssi
        latestPacketHash = latest?.hash.isEmpty == false ? latest?.hash : nil
        latestPacketTimestamp = latest?.timestamp
        strongestReceiverName = nil
        strongestReceiverSnr = nil
    }
}

struct FieldEvidenceValue: Equatable, Sendable {
    enum Freshness: Equatable, Sendable {
        case fetched
        case cached
        case staleFallback
    }

    enum FallbackReason: Equatable, Sendable {
        case refreshFailed
        case rateLimited(until: Date?)
    }

    let evidence: FieldTargetAnalyticsEvidence
    let fetchedAt: Date
    let freshness: Freshness
    let fallbackReason: FallbackReason?
}

actor FieldEvidenceRepository {
    static let shared = FieldEvidenceRepository()

    private let cache: APIResponseCache
    private let now: @Sendable () -> Date

    init(cache: APIResponseCache = .shared, now: @escaping @Sendable () -> Date = { .now }) {
        self.cache = cache
        self.now = now
    }

    func load(
        item: FieldCatalogItem,
        from backend: any AnalyzerBackend,
        forceRefresh: Bool = false
    ) async throws -> FieldEvidenceValue {
        try backend.sourceSession.check()
        let key = cacheKey(item: item, backend: backend)
        let cached: FieldTargetAnalyticsEvidence? = await cache.value(
            for: key,
            maximumAge: FieldRequestBudget.staleCatalogLifetime
        )
        let cachedAt = await cache.savedAt(for: key)
        if !forceRefresh, let cached, let cachedAt,
           now().timeIntervalSince(cachedAt) < FieldRequestBudget.pinnedEvidenceFreshLifetime {
            return FieldEvidenceValue(
                evidence: cached,
                fetchedAt: cachedAt,
                freshness: .cached,
                fallbackReason: nil
            )
        }

        do {
            let evidence: FieldTargetAnalyticsEvidence = try await cache.refresh(
                for: key,
                session: backend.sourceSession
            ) {
                switch item {
                case .node(let node):
                    guard backend.capabilities.contains(.nodeAnalytics) else {
                        throw FieldEvidenceError.unsupported(.nodeAnalytics)
                    }
                    let analytics = try await backend.nodeAnalytics(publicKey: node.publicKey, days: 1)
                    guard analytics.node.publicKey.caseInsensitiveCompare(node.publicKey) == .orderedSame else {
                        throw APIError.invalidResponse
                    }
                    return FieldTargetAnalyticsEvidence(node: analytics)
                case .observer(let observer):
                    guard backend.capabilities.contains(.observerAnalytics) else {
                        throw FieldEvidenceError.unsupported(.observerAnalytics)
                    }
                    return FieldTargetAnalyticsEvidence(
                        observer: try await backend.observerAnalytics(id: observer.id)
                    )
                }
            }
            try backend.sourceSession.check()
            return FieldEvidenceValue(
                evidence: evidence,
                fetchedAt: await cache.savedAt(for: key) ?? now(),
                freshness: .fetched,
                fallbackReason: nil
            )
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            try backend.sourceSession.check()
            if let cached, let cachedAt {
                return FieldEvidenceValue(
                    evidence: cached,
                    fetchedAt: cachedAt,
                    freshness: .staleFallback,
                    fallbackReason: Self.fallbackReason(for: error)
                )
            }
            if case .rateLimited = Self.fallbackReason(for: error) {
                throw FieldEvidenceError.rateLimited
            }
            throw error
        }
    }

    private func cacheKey(item: FieldCatalogItem, backend: any AnalyzerBackend) -> String {
        "field-evidence-\(backend.cacheIdentifier)-\(item.id)"
    }

    nonisolated static func fallbackReason(for error: Error) -> FieldEvidenceValue.FallbackReason {
        if case BeaconAPIError.rateLimited(let until) = error {
            return .rateLimited(until: until)
        }
        if case APIError.http(429) = error {
            return .rateLimited(until: nil)
        }
        return .refreshFailed
    }
}

enum FieldEvidenceError: LocalizedError {
    case unsupported(BackendCapability)
    case rateLimited

    var errorDescription: String? {
        switch self {
        case .unsupported(let capability): capability.beaconExplanation
        case .rateLimited: "This analyzer is rate limiting target evidence. Try again later."
        }
    }
}
