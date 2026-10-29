import Foundation

/// One foreground dashboard visit. Keep this instance when navigating to nested detail.
/// Finish on dashboard departure/background/source change; foregrounding opens a new instance.
actor MyNodesVisitSession {
    let visit: MyNodesSnapshotStore.Visit
    private let store: MyNodesSnapshotStore
    private let source: AnalyzerSourceSession
    private let lifetime: AnalyzerSourceSession
    private let cancellationID: UUID

    init(store: MyNodesSnapshotStore, source: AnalyzerSourceSession, namespace: MyNodesNamespace,
         nodes: Set<String>, favoriteObservers: Set<String>, coveringObservers: Set<String> = [],
         at now: Date) async throws {
        try source.check()
        let lifetime = AnalyzerSourceSession()
        let cancellationID = source.registerCancellation { lifetime.invalidate() }
        do {
            let visit = try await store.begin(namespace: namespace, nodes: nodes, favoriteObservers: favoriteObservers,
                                              coveringObservers: coveringObservers, at: now, lifetime: lifetime)
            do {
                try source.check()
                try lifetime.check()
            } catch {
                try? await store.end(visit)
                throw error
            }
            self.visit = visit
        } catch {
            lifetime.invalidate()
            source.removeCancellation(cancellationID)
            throw error
        }
        self.store = store
        self.source = source
        self.lifetime = lifetime
        self.cancellationID = cancellationID
    }

    deinit {
        lifetime.invalidate()
        source.removeCancellation(cancellationID)
    }

    /// Cancellation removes this consumer only; shared repository waiters remain independent.
    func perform<Value: Sendable>(_ operation: @escaping @Sendable () async throws -> Value) async throws -> Value {
        try lifetime.check()
        let task = Task {
            try lifetime.check()
            return try await operation()
        }
        let id = lifetime.registerCancellation { task.cancel() }
        defer { lifetime.removeCancellation(id) }
        return try await withTaskCancellationHandler {
            let value = try await task.value
            try lifetime.check()
            return value
        } onCancel: { task.cancel() }
    }

    func loadAnalytics(repository: MyNodesAnalyticsRepository, publicKeys: [String],
                       forceRefresh: Bool = false) async throws -> [MyNodesAnalyticsRow] {
        try await perform { try await repository.load(publicKeys: publicKeys, forceRefresh: forceRefresh) }
    }

    func loadBase(repository: MyNodesBaseRepository, publicKeys: [String], forceRefresh: Bool = false,
                  onRow: (@Sendable (MyNodesBaseRow) -> Void)? = nil) async throws -> [MyNodesBaseRow] {
        let lifetime = self.lifetime
        return try await perform {
            try await repository.load(publicKeys: publicKeys, forceRefresh: forceRefresh) { row in
                if lifetime.isCurrent { onRow?(row) }
            }
        }
    }

    func rendered(entity: MyNodesEntity, metric: MyNodesMetric, freshFetch: Bool,
                  at now: Date) async throws -> MyNodesEvent? {
        try lifetime.check()
        let event = try await store.rendered(visit: visit, entity: entity, metric: metric,
                                             freshFetch: freshFetch, at: now)
        try lifetime.check()
        return event
    }

    func reviewed(entity: MyNodesEntity, metric: MyNodesMetric, freshFetch: Bool,
                  at now: Date) async throws -> MyNodesReviewResult {
        try lifetime.check()
        let result = try await store.reviewed(visit: visit, entity: entity, metric: metric,
                                             freshFetch: freshFetch, at: now)
        try lifetime.check()
        return result
    }

    /// Invalidate before awaiting disk work, so queued responses cannot race promotion.
    /// May be retried if persistence fails; already-rendered candidates remain recoverable.
    func finish() async throws {
        lifetime.invalidate()
        source.removeCancellation(cancellationID)
        try await store.end(visit)
    }
}
