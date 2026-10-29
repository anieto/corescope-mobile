import Foundation

struct MyNodesAnalyticsSnapshot: Sendable {
    let analytics: NodeAnalyticsResponse
    let evidence: MyNodesEvidence

    func isReusable(at now: Date) -> Bool {
        let age = now.timeIntervalSince(evidence.fetchedAt)
        return age >= 0 && age <= 15 * 60
    }
}

struct MyNodesAnalyticsRow: Sendable {
    enum State: Sendable { case fetched, cached, deferred, failed, rateLimited }
    let publicKey: String
    let snapshot: MyNodesAnalyticsSnapshot?
    let state: State
}

/// One shared dashboard repository per source selection. Detail screens keep their existing cache.
/// A concurrent caller joins the current batch; additional keys wait for an explicit next batch.
actor MyNodesAnalyticsRepository {
    typealias Fetch = @Sendable (String, Date, MyNodesRequestBudget) async throws -> NodeAnalyticsResponse

    let source: String
    private let session: AnalyzerSourceSession
    private let fetch: Fetch
    private let now: @Sendable () -> Date
    private var cache: [String: MyNodesAnalyticsSnapshot] = [:]
    private var attempted: Set<String> = []
    private var active: Batch?

    var activeConsumerCount: Int { active?.waiters.count ?? 0 }

    private struct Waiter {
        let keys: [String]
        let continuation: CheckedContinuation<[MyNodesAnalyticsRow], Error>
    }

    private struct Batch {
        let id: UUID
        let task: Task<Void, Never>
        let cancellationID: UUID
        var waiters: [UUID: Waiter]
    }

    init(source: String, session: AnalyzerSourceSession,
         now: @escaping @Sendable () -> Date = { .now }, fetch: @escaping Fetch) {
        self.source = source
        self.session = session
        self.now = now
        self.fetch = fetch
    }

    /// Includes stale snapshots for immediate display; timestamps are never rewritten on reuse.
    func cached(publicKeys: [String]) throws -> [String: MyNodesAnalyticsSnapshot] {
        try session.check()
        let selected = Set(publicKeys.map { $0.lowercased() })
        return cache.filter { selected.contains($0.key) }
    }

    func load(publicKeys: [String], forceRefresh: Bool = false) async throws -> [MyNodesAnalyticsRow] {
        try session.check()
        var seen: Set<String> = []
        let keys = publicKeys.map { $0.lowercased() }.filter { seen.insert($0).inserted }
        guard keys.allSatisfy({ key in
            key.utf8.count == 64 && key.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
        }) else { throw APIError.invalidResponse }
        guard !keys.isEmpty else { return [] }
        let waiterID = UUID()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                guard !Task.isCancelled else {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                let waiter = Waiter(keys: keys, continuation: continuation)
                if active != nil {
                    active?.waiters[waiterID] = waiter
                    return
                }
                let batchID = UUID()
                let task = Task {
                    do {
                        let rows = try await runBatch(id: batchID, keys: keys, forceRefresh: forceRefresh)
                        finish(id: batchID, result: .success(rows))
                    } catch {
                        finish(id: batchID, result: .failure(error))
                    }
                }
                let cancellationID = session.registerCancellation {
                    task.cancel()
                    Task { await self.cancelBatch(id: batchID) }
                }
                active = Batch(id: batchID, task: task, cancellationID: cancellationID, waiters: [waiterID: waiter])
            }
        } onCancel: {
            Task { await self.cancel(waiterID: waiterID) }
        }
    }

    /// Cache clear and view-owner teardown must cancel transport and reject late cache writes.
    func clear() {
        if let batch = active { cancelBatch(id: batch.id) }
        cache.removeAll()
        attempted.removeAll()
    }

    private func cancelBatch(id: UUID) {
        guard let batch = active, batch.id == id else { return }
        active = nil
        batch.task.cancel()
        session.removeCancellation(batch.cancellationID)
        batch.waiters.values.forEach { $0.continuation.resume(throwing: CancellationError()) }
    }

    private func cancel(waiterID: UUID) {
        guard let waiter = active?.waiters.removeValue(forKey: waiterID) else { return }
        waiter.continuation.resume(throwing: CancellationError())
        if let batch = active, batch.waiters.isEmpty {
            active = nil
            batch.task.cancel()
            session.removeCancellation(batch.cancellationID)
        }
    }

    private func check(_ id: UUID) throws {
        try session.check()
        guard active?.id == id else { throw CancellationError() }
    }

    private func finish(id: UUID, result: Result<[String: MyNodesAnalyticsRow], Error>) {
        guard let batch = active, batch.id == id else { return }
        active = nil
        session.removeCancellation(batch.cancellationID)
        let result = session.isCurrent && !Task.isCancelled ? result : .failure(CancellationError())
        for waiter in batch.waiters.values {
            waiter.continuation.resume(with: result.map { rows in
                waiter.keys.map { key in
                    rows[key] ?? MyNodesAnalyticsRow(publicKey: key, snapshot: cache[key], state: .deferred)
                }
            })
        }
    }

    private func runBatch(id: UUID, keys: [String], forceRefresh: Bool) async throws -> [String: MyNodesAnalyticsRow] {
        try check(id)
        let budget = MyNodesRequestBudget()
        var rows: [String: MyNodesAnalyticsRow] = [:]
        var pending: [String] = []
        for key in keys {
            if !forceRefresh, let snapshot = cache[key], snapshot.isReusable(at: now()) {
                rows[key] = MyNodesAnalyticsRow(publicKey: key, snapshot: snapshot, state: .cached)
            } else {
                pending.append(key)
            }
        }
        // Load More favors never-attempted selections before retrying old failures or partial data.
        pending = pending.filter { !attempted.contains($0) } + pending.filter { attempted.contains($0) }
        let work = pending
        try await withThrowingTaskGroup(of: MyNodesAnalyticsRow.self) { group in
            var next = 0
            func enqueue() {
                guard next < work.count else { return }
                let key = work[next]
                next += 1
                let previous = cache[key]
                let fetch = self.fetch
                let now = self.now
                group.addTask {
                    try Task.checkCancellation()
                    let requestedTo = now()
                    do {
                        let analytics = try await fetch(key, requestedTo, budget)
                        try Task.checkCancellation()
                        guard analytics.node.publicKey.lowercased() == key, analytics.timeRange.days == 1,
                              analytics.timeRange.from < analytics.timeRange.to else { throw APIError.invalidResponse }
                        let snapshot = MyNodesAnalyticsSnapshot(analytics: analytics,
                            evidence: MyNodesEvidence(context: analytics.context, fetchedAt: now(),
                                requestedFrom: requestedTo.addingTimeInterval(-86400), requestedTo: requestedTo))
                        return MyNodesAnalyticsRow(publicKey: key, snapshot: snapshot, state: .fetched)
                    } catch is CancellationError {
                        throw CancellationError()
                    } catch {
                        try Task.checkCancellation()
                        let state: MyNodesAnalyticsRow.State
                        switch error {
                        case MyNodesBudgetError.exhausted, MyNodesBudgetError.nodeExhausted:
                            state = .deferred
                        case MyNodesBudgetError.invalidated:
                            throw CancellationError()
                        case BeaconAPIError.rateLimited, APIError.http(429):
                            state = .rateLimited
                        default:
                            state = .failed
                        }
                        return MyNodesAnalyticsRow(publicKey: key, snapshot: previous, state: state)
                    }
                }
            }
            enqueue()
            enqueue()
            var stopped = false
            while let row = try await group.next() {
                try check(id)
                rows[row.publicKey] = row
                if row.state != .deferred { attempted.insert(row.publicKey) }
                if row.state == .fetched { cache[row.publicKey] = row.snapshot }
                if row.state == .deferred || row.state == .rateLimited { stopped = true }
                if !stopped { enqueue() }
            }
        }
        try check(id)
        for key in keys where rows[key] == nil {
            rows[key] = MyNodesAnalyticsRow(publicKey: key, snapshot: cache[key], state: .deferred)
        }
        return rows
    }
}

extension MyNodesAnalyticsRepository {
    static func coreScope(client: APIClient, session: AnalyzerSourceSession,
                          now: @escaping @Sendable () -> Date = { .now }) -> MyNodesAnalyticsRepository {
        MyNodesAnalyticsRepository(source: "corescope|\(client.cacheIdentifier)", session: session, now: now) { key, _, budget in
            try await CoreScopeBackend(client: client.budgetingRequests(budget, node: key), sourceSession: session)
                .nodeAnalytics(publicKey: key, days: 1)
        }
    }

    static func beacon(client: BeaconAPIClient, serverInfo: BeaconServerInfo,
                       now: @escaping @Sendable () -> Date = { .now }) throws -> MyNodesAnalyticsRepository {
        let reader = try BeaconNodeReader(client: client, verifiedServerInfo: serverInfo)
        return MyNodesAnalyticsRepository(source: client.cacheIdentifier, session: client.sourceSession, now: now) { key, to, budget in
            guard let history = try await reader.myNodesObservationHistory(publicKey: key, since: to.addingTimeInterval(-86400), budget: budget) else {
                throw BeaconBackendError.nodeUnavailable
            }
            return BeaconNodeAnalyticsBuilder.build(history: history, requestedDays: 1, from: to.addingTimeInterval(-86400), to: to)
        }
    }
}
