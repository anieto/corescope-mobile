import Foundation

protocol FieldCatalogBackend: Sendable {
    var sourceSession: AnalyzerSourceSession { get }
    var cacheIdentifier: String { get }

    func nodes(region: String?) async throws -> NodesResponse
    func observers() async throws -> ObserversResponse
}

struct FieldCatalogValue<Value: Sendable>: Sendable {
    let value: Value
    let savedAt: Date
}

actor FieldCatalogRepository {
    static let shared = FieldCatalogRepository()

    private let cache: APIResponseCache
    private let now: @Sendable () -> Date

    init(
        cache: APIResponseCache = .shared,
        now: @escaping @Sendable () -> Date = { .now }
    ) {
        self.cache = cache
        self.now = now
    }

    func cachedNodes(
        for backend: any FieldCatalogBackend,
        region: String?
    ) async -> FieldCatalogValue<NodesResponse>? {
        await cachedValue(
            for: nodesKey(backend: backend, region: region),
            maximumAge: FieldRequestBudget.staleCatalogLifetime
        )
    }

    func cachedObservers(
        for backend: any FieldCatalogBackend
    ) async -> FieldCatalogValue<ObserversResponse>? {
        await cachedValue(
            for: observersKey(backend: backend),
            maximumAge: FieldRequestBudget.staleCatalogLifetime
        )
    }

    func nodesNeedRefresh(
        for backend: any FieldCatalogBackend,
        region: String?,
        force: Bool
    ) async -> Bool {
        await refreshIsDue(for: nodesKey(backend: backend, region: region), force: force)
    }

    func observersNeedRefresh(
        for backend: any FieldCatalogBackend,
        force: Bool
    ) async -> Bool {
        await refreshIsDue(for: observersKey(backend: backend), force: force)
    }

    func refreshNodes(
        for backend: any FieldCatalogBackend,
        region: String?
    ) async throws -> FieldCatalogValue<NodesResponse> {
        let key = nodesKey(backend: backend, region: region)
        let response: NodesResponse = try await cache.refresh(for: key, session: backend.sourceSession) {
            try await backend.nodes(region: region)
        }
        return FieldCatalogValue(value: response, savedAt: now())
    }

    func refreshObservers(
        for backend: any FieldCatalogBackend
    ) async throws -> FieldCatalogValue<ObserversResponse> {
        let key = observersKey(backend: backend)
        let response: ObserversResponse = try await cache.refresh(for: key, session: backend.sourceSession) {
            try await backend.observers()
        }
        return FieldCatalogValue(value: response, savedAt: now())
    }

    func seedNodes(
        _ response: NodesResponse,
        for backend: any FieldCatalogBackend,
        region: String?,
        savedAt: Date = .now
    ) async {
        guard backend.sourceSession.isCurrent else { return }
        await cache.store(response, for: nodesKey(backend: backend, region: region), savedAt: savedAt)
    }

    func seedObservers(
        _ response: ObserversResponse,
        for backend: any FieldCatalogBackend,
        savedAt: Date = .now
    ) async {
        guard backend.sourceSession.isCurrent else { return }
        await cache.store(response, for: observersKey(backend: backend), savedAt: savedAt)
    }

    private func cachedValue<Value: Codable & Sendable>(
        for key: String,
        maximumAge: TimeInterval
    ) async -> FieldCatalogValue<Value>? {
        guard let value: Value = await cache.value(for: key, maximumAge: maximumAge),
              let savedAt = await cache.savedAt(for: key) else {
            return nil
        }
        return FieldCatalogValue(value: value, savedAt: savedAt)
    }

    private func refreshIsDue(for key: String, force: Bool) async -> Bool {
        if force { return true }
        guard let savedAt = await cache.savedAt(for: key) else { return true }
        return now().timeIntervalSince(savedAt) >= FieldRequestBudget.automaticCatalogRefreshInterval
    }

    private func nodesKey(backend: any FieldCatalogBackend, region: String?) -> String {
        "field-nodes-\(backend.cacheIdentifier)-\(region ?? "all")"
    }

    private func observersKey(backend: any FieldCatalogBackend) -> String {
        "field-observers-\(backend.cacheIdentifier)"
    }
}
