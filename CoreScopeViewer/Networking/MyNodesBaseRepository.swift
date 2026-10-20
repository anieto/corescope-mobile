import Foundation

struct MyNodesBaseSnapshot: Sendable {
    let node: MeshNode
    let fetchedAt: Date

    func isReusable(at now: Date) -> Bool {
        let age = now.timeIntervalSince(fetchedAt)
        return age >= 0 && age <= 5 * 60
    }
}

struct MyNodesBaseRow: Sendable {
    enum State: Sendable { case fetched, cached, unavailable, deferred, failed, rateLimited }
    let publicKey: String
    let snapshot: MyNodesBaseSnapshot?
    let state: State
}

struct MyNodesObserverCatalogSnapshot: Sendable {
    enum Completeness: Sendable { case unknown, partial, complete }
    let observers: [MeshObserver]
    let fetchedAt: Date
    let completeness: Completeness

    func isReusable(at now: Date) -> Bool {
        let age = now.timeIntervalSince(fetchedAt)
        return age >= 0 && age <= 5 * 60
    }
}

struct MyNodesObserverCatalogRow: Sendable {
    let snapshot: MyNodesObserverCatalogSnapshot?
    let state: MyNodesBaseRow.State
}

/// Bounded selected-node metadata, independent of analytics enrichment.
/// A concurrent caller joins the current batch; additional keys wait for an explicit next batch.
actor MyNodesBaseRepository {
    typealias Fetch = @Sendable (String, Date, MyNodesRequestBudget) async throws -> MeshNode?

    typealias FetchObservers = @Sendable (MyNodesRequestBudget) async throws -> MyNodesObserverCatalogSnapshot

    let source: String
    private let session: AnalyzerSourceSession
    private let fetchObservers: FetchObservers?
    private var observerCatalog = MyNodesObserverCatalogRow(snapshot: nil, state: .deferred)
    private let fetch: Fetch
    private let now: @Sendable () -> Date
    private var cache: [String: MyNodesBaseSnapshot] = [:]
    private var attempted: Set<String> = []
    private var active: Batch?

    var activeConsumerCount: Int { active?.waiters.count ?? 0 }

    private struct Waiter {
        let keys: [String]
        let continuation: CheckedContinuation<[MyNodesBaseRow], Error>
        let onRow: (@Sendable (MyNodesBaseRow) -> Void)?
    }

    private struct Batch {
        let id: UUID
        let task: Task<Void, Never>
        let cancellationID: UUID
        var waiters: [UUID: Waiter]
    }

    init(source: String, session: AnalyzerSourceSession,
         now: @escaping @Sendable () -> Date = { .now },
         fetchObservers: FetchObservers? = nil, fetch: @escaping Fetch) {
        self.source = source
        self.session = session
        self.now = now
        self.fetch = fetch
        self.fetchObservers = fetchObservers
    }

    /// A missing observer is unknown even when the catalog traversal completed.
    func observers() throws -> MyNodesObserverCatalogRow {
        try session.check()
        return observerCatalog
    }

    func seedObservers(_ snapshot: MyNodesObserverCatalogSnapshot, source: String) throws {
        try session.check()
        guard source == self.source, snapshot.fetchedAt <= now() else { throw APIError.invalidResponse }
        if observerCatalog.snapshot.map({ $0.fetchedAt < snapshot.fetchedAt }) ?? true {
            observerCatalog = MyNodesObserverCatalogRow(snapshot: snapshot, state: .cached)
        }
    }

    /// Reuse positive catalog records only. An omitted key is never evidence of absence.
    /// The caller supplies the original successful fetch time and this source's identifier.
    func seedCatalog(nodes: [MeshNode], fetchedAt: Date, source: String) throws {
        try session.check()
        guard source == self.source, fetchedAt <= now() else { throw APIError.invalidResponse }
        for node in nodes {
            let key = node.publicKey.lowercased()
            guard key.utf8.count == 64,
                  key.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }),
                  cache[key].map({ $0.fetchedAt < fetchedAt }) ?? true else { continue }
            cache[key] = MyNodesBaseSnapshot(node: node, fetchedAt: fetchedAt)
        }
    }

    /// Callbacks should enqueue/yield the row promptly; UI consumers still check their visit lifetime.
    private func publish(_ row: MyNodesBaseRow) {
        guard session.isCurrent, let batch = active else { return }
        for waiter in batch.waiters.values where waiter.keys.contains(row.publicKey) {
            waiter.onRow?(row)
        }
    }

    /// Includes stale snapshots for immediate display; timestamps are never rewritten on reuse.
    func cached(publicKeys: [String]) throws -> [String: MyNodesBaseSnapshot] {
        try session.check()
        let selected = Set(publicKeys.map { $0.lowercased() })
        return cache.filter { selected.contains($0.key) }
    }

    func load(publicKeys: [String], forceRefresh: Bool = false,
              onRow: (@Sendable (MyNodesBaseRow) -> Void)? = nil) async throws -> [MyNodesBaseRow] {
        try session.check()
        var seen: Set<String> = []
        let keys = publicKeys.map { $0.lowercased() }.filter { seen.insert($0).inserted }
        guard keys.allSatisfy({ key in
            key.utf8.count == 64 && key.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
        }) else { throw APIError.invalidResponse }
        guard !keys.isEmpty || fetchObservers != nil else { return [] }
        let waiterID = UUID()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                guard !Task.isCancelled else {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                let waiter = Waiter(keys: keys, continuation: continuation, onRow: onRow)
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
        observerCatalog = MyNodesObserverCatalogRow(snapshot: nil, state: .deferred)
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

    private func finish(id: UUID, result: Result<[String: MyNodesBaseRow], Error>) {
        guard let batch = active, batch.id == id else { return }
        active = nil
        session.removeCancellation(batch.cancellationID)
        let result = session.isCurrent && !Task.isCancelled ? result : .failure(CancellationError())
        for waiter in batch.waiters.values {
            waiter.continuation.resume(with: result.map { rows in
                waiter.keys.map { key in
                    rows[key] ?? MyNodesBaseRow(publicKey: key, snapshot: cache[key], state: .deferred)
                }
            })
        }
    }

    private func runBatch(id: UUID, keys: [String], forceRefresh: Bool) async throws -> [String: MyNodesBaseRow] {
        try check(id)
        let budget = MyNodesRequestBudget()
        var rows: [String: MyNodesBaseRow] = [:]
        var pending: [String] = []
        for key in keys {
            if !forceRefresh, let snapshot = cache[key], snapshot.isReusable(at: now()) {
                rows[key] = MyNodesBaseRow(publicKey: key, snapshot: snapshot, state: .cached)
                publish(MyNodesBaseRow(publicKey: key, snapshot: snapshot, state: .cached))
            } else {
                pending.append(key)
            }
        }
        // Cached node rows are published before catalog IO. Reserve at most four
        // catalog attempts, then spend the remaining shared allowance on exact nodes.
        var catalogRateLimited = false
        if let fetchObservers {
            let previous = observerCatalog.snapshot
            if !forceRefresh, let previous, previous.isReusable(at: now()) {
                observerCatalog = MyNodesObserverCatalogRow(snapshot: previous, state: .cached)
            } else {
                do {
                    let snapshot = try await fetchObservers(budget)
                    try check(id)
                    guard snapshot.fetchedAt <= now() else { throw APIError.invalidResponse }
                    observerCatalog = MyNodesObserverCatalogRow(snapshot: snapshot, state: .fetched)
                } catch {
                    try check(id)
                    let state: MyNodesBaseRow.State
                    switch error {
                    case is CancellationError, MyNodesBudgetError.invalidated: throw CancellationError()
                    case BeaconAPIError.rateLimited, APIError.http(429):
                        state = .rateLimited
                        catalogRateLimited = true
                    case MyNodesBudgetError.exhausted, MyNodesBudgetError.nodeExhausted: state = .deferred
                    default: state = .failed
                    }
                    observerCatalog = MyNodesObserverCatalogRow(snapshot: previous, state: state)
                }
            }
        }
        // Load More favors never-attempted selections before retrying old failures or partial data.
        pending = pending.filter { !attempted.contains($0) } + pending.filter { attempted.contains($0) }
        let work = catalogRateLimited ? [] : pending
        try await withThrowingTaskGroup(of: MyNodesBaseRow.self) { group in
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
                        guard let node = try await fetch(key, requestedTo, budget) else {
                            return MyNodesBaseRow(publicKey: key, snapshot: previous, state: .unavailable)
                        }
                        try Task.checkCancellation()
                        guard node.publicKey.lowercased() == key else { throw APIError.invalidResponse }
                        let snapshot = MyNodesBaseSnapshot(node: node, fetchedAt: now())
                        return MyNodesBaseRow(publicKey: key, snapshot: snapshot, state: .fetched)
                    } catch is CancellationError {
                        throw CancellationError()
                    } catch {
                        try Task.checkCancellation()
                        let state: MyNodesBaseRow.State
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
                        return MyNodesBaseRow(publicKey: key, snapshot: previous, state: state)
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
                if row.state == .unavailable { cache[row.publicKey] = nil }
                publish(row)
                if row.state == .deferred || row.state == .rateLimited { stopped = true }
                if !stopped { enqueue() }
            }
        }
        try check(id)
        for key in keys where rows[key] == nil {
            let row = MyNodesBaseRow(publicKey: key, snapshot: cache[key], state: .deferred)
            rows[key] = row
            publish(row)
        }
        return rows
    }
}

extension MyNodesBaseRepository {
    /// Bounded positive evidence only; never use omissions to synthesize offline status.
    static func observerPages(now: @Sendable () -> Date,
                              page: (AnalyzerCursor?) async throws -> AnalyzerPage<MeshObserver>) async throws -> MyNodesObserverCatalogSnapshot {
        var cursor: AnalyzerCursor?
        var seen: Set<AnalyzerCursor> = []
        var records: [String: MeshObserver] = [:]
        for _ in 0..<4 {
            let result = try await page(cursor)
            try Task.checkCancellation()
            for observer in result.items { records[observer.id] = observer }
            guard let next = result.nextCursor else {
                return MyNodesObserverCatalogSnapshot(observers: records.values.sorted { $0.id < $1.id },
                    fetchedAt: now(), completeness: .complete)
            }
            guard !result.items.isEmpty, seen.insert(next).inserted else {
                throw APIError.invalidResponse
            }
            cursor = next
        }
        return MyNodesObserverCatalogSnapshot(observers: records.values.sorted { $0.id < $1.id },
            fetchedAt: now(), completeness: .partial)
    }

    static func coreScope(client: APIClient, session: AnalyzerSourceSession,
                          now: @escaping @Sendable () -> Date = { .now }) -> MyNodesBaseRepository {
        MyNodesBaseRepository(source: "corescope|\(client.cacheIdentifier)", session: session, now: now, fetchObservers: { budget in
            let response = try await CoreScopeBackend(
                client: client.budgetingRequests(budget, node: "observer-catalog"), sourceSession: session
            ).observers()
            // The legacy endpoint does not provide a traversal/completeness contract.
            return MyNodesObserverCatalogSnapshot(observers: response.observers, fetchedAt: now(), completeness: .unknown)
        }) { key, _, budget in
            do {
                return try await CoreScopeBackend(client: client.budgetingRequests(budget, node: key), sourceSession: session)
                    .nodeDetail(publicKey: key).node
            } catch APIError.http(404) { return nil }
        }
    }

    static func beacon(client: BeaconAPIClient, serverInfo: BeaconServerInfo,
                       now: @escaping @Sendable () -> Date = { .now }) throws -> MyNodesBaseRepository {
        try serverInfo.requireSupported()
        return MyNodesBaseRepository(source: client.cacheIdentifier, session: client.sourceSession, now: now, fetchObservers: { budget in
            let reader = try BeaconObserverReader(
                client: client.budgetingRequests(budget, node: "observer-catalog"), verifiedServerInfo: serverInfo)
            return try await observerPages(now: now) { cursor in
                let page = try await reader.observerPage(region: nil, cursor: cursor)
                return AnalyzerPage(items: page.items.map(\.meshObserver), nextCursor: page.nextCursor, total: page.total)
            }
        }) { key, _, budget in
            let reader = try BeaconNodeReader(client: client.budgetingRequests(budget, node: key), verifiedServerInfo: serverInfo)
            return try await reader.nodeSummary(publicKey: key)?.meshNode
        }
    }
}
