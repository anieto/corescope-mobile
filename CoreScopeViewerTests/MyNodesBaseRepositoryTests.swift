import Foundation
import Testing
@testable import CoreScopeViewer

struct MyNodesBaseRepositoryTests {
    private static func key(_ index: Int) -> String { String(repeating: "a", count: 56) + String(format: "%08x", index) }
    private static func node(_ key: String) throws -> MeshNode {
        try JSONDecoder().decode(MeshNode.self, from: Data("{\"public_key\":\"\(key)\",\"role\":\"repeater\"}".utf8))
    }

    private actor Server {
        var calls = 0
        var peak = 0
        var active = 0
        var missing = false
        var rateLimited = false
        var blocked: String?
        var continuation: CheckedContinuation<Void, Never>?
        func configure(missing: Bool = false, rateLimited: Bool = false, blocked: String? = nil) {
            self.missing = missing
            self.rateLimited = rateLimited
            self.blocked = blocked
        }
        func release() { continuation?.resume(); continuation = nil; blocked = nil }
        func fetch(_ key: String, budget: MyNodesRequestBudget) async throws -> MeshNode? {
            try await budget.perform(node: key) { try await self.response(key) }
        }
        func response(_ key: String) async throws -> MeshNode? {
            calls += 1
            active += 1
            peak = max(peak, active)
            defer { active -= 1 }
            if blocked == key { await withCheckedContinuation { continuation = $0 } }
            await Task.yield()
            if rateLimited { throw APIError.http(429) }
            return missing ? nil : try MyNodesBaseRepositoryTests.node(key)
        }
    }

    private func repository(_ server: Server, session: AnalyzerSourceSession = AnalyzerSourceSession()) -> MyNodesBaseRepository {
        MyNodesBaseRepository(source: "fixture", session: session) { key, _, budget in
            try await server.fetch(key, budget: budget)
        }
    }

    private static func observer(_ id: String) throws -> MeshObserver {
        try JSONDecoder().decode(MeshObserver.self, from: Data("{\"id\":\"\(id)\"}".utf8))
    }

    @Test func observerPagesShareBudgetAndWarmCache() async throws {
        let server = Server()
        let stamp = Date.now
        let repo = MyNodesBaseRepository(source: "fixture", session: AnalyzerSourceSession(), now: { stamp },
            fetchObservers: { budget in
                try await MyNodesBaseRepository.observerPages(now: { stamp }) { cursor in
                    try await budget.perform(node: "observer-catalog") {
                        let index = Int(cursor?.value ?? "0") ?? 0
                        return AnalyzerPage(items: [try Self.observer("observer-\(index)")],
                            nextCursor: AnalyzerCursor(value: String(index + 1)), total: nil)
                    }
                }
            }) { key, _, budget in try await server.fetch(key, budget: budget) }
        let rows = try await repo.load(publicKeys: (0..<100).map(Self.key))
        #expect(rows.filter { $0.state == .fetched }.count == 16)
        #expect(await server.calls == 16)
        let catalog = try await repo.observers()
        #expect(catalog.snapshot?.observers.count == 4)
        #expect(catalog.snapshot?.completeness == .partial)
        #expect(catalog.state == .fetched)
        _ = try await repo.load(publicKeys: (0..<16).map(Self.key))
        #expect(try await repo.observers().state == .cached)
        #expect(try await repo.observers().snapshot?.fetchedAt == stamp)
        #expect(await server.calls == 16)
    }

    @Test func observerOnlyRefreshAndFailurePreservePriorEvidence() async throws {
        let stamp = Date.now
        let previous = MyNodesObserverCatalogSnapshot(observers: [try Self.observer("remembered")],
            fetchedAt: stamp.addingTimeInterval(-400), completeness: .partial)
        let repo = MyNodesBaseRepository(source: "fixture", session: AnalyzerSourceSession(),
            fetchObservers: { budget in
                try await budget.perform(node: "observer-catalog") { throw APIError.http(429) }
            }) { _, _, _ in Issue.record("Node work must stop after catalog rate limit"); return nil }
        try await repo.seedObservers(previous, source: "fixture")
        let rows = try await repo.load(publicKeys: [Self.key(0)])
        #expect(rows.first?.state == .deferred)
        #expect(try await repo.observers().state == .rateLimited)
        #expect(try await repo.observers().snapshot?.fetchedAt == previous.fetchedAt)
        await repo.clear()
        #expect(try await repo.observers().snapshot == nil)

        let empty = MyNodesBaseRepository(source: "fixture", session: AnalyzerSourceSession(),
            fetchObservers: { _ in
                try await MyNodesBaseRepository.observerPages(now: { stamp }) { _ in
                    AnalyzerPage(items: [], nextCursor: nil, total: nil)
                }
            }) { _, _, _ in nil }
        #expect(try await empty.load(publicKeys: []).isEmpty)
        #expect(try await empty.observers().snapshot?.completeness == .complete)
    }

    @Test func repeatedObserverCursorIsRejected() async throws {
        await #expect(throws: APIError.self) {
            try await MyNodesBaseRepository.observerPages(now: { .now }) { _ in
                AnalyzerPage(items: [try Self.observer("same")], nextCursor: AnalyzerCursor(value: "loop"), total: nil)
            }
        }
    }

    @Test func coldWarmAndNextBatchAcrossSelectionSizes() async throws {
        for size in [0, 1, 20, 100, 500] {
            let server = Server()
            let repo = repository(server)
            let keys = (0..<size).map(Self.key)
            let first = try await repo.load(publicKeys: keys)
            #expect(first.map(\.publicKey) == keys)
            #expect(first.filter { $0.state == .fetched }.count == min(size, 20))
            #expect(await server.calls == min(size, 20))
            #expect(await server.peak <= 2)
            let next = try await repo.load(publicKeys: keys)
            #expect(next.filter { $0.state == .cached }.count == min(size, 20))
            #expect(await server.calls == min(size, 40))
        }
    }

    @Test func largeSelectionDrainsBoundedBatchesThenLoadsEntirelyFromCache() async throws {
        let server = Server()
        let stamp = Date(timeIntervalSince1970: 1_800_000_000)
        let repo = MyNodesBaseRepository(source: "fixture", session: AnalyzerSourceSession(), now: { stamp }) { key, _, budget in
            try await server.fetch(key, budget: budget)
        }
        let keys = (0..<500).map(Self.key)
        let clock = ContinuousClock()
        let started = clock.now
        for batch in 0..<25 {
            let rows = try await repo.load(publicKeys: keys)
            #expect(rows.map(\.publicKey) == keys)
            #expect(rows.filter { $0.state == .fetched }.count == 20)
            #expect(rows.filter { $0.state == .cached }.count == batch * 20)
            #expect(rows.filter { $0.state == .deferred }.count == 500 - (batch + 1) * 20)
            #expect(await server.calls == (batch + 1) * 20)
            #expect(await server.peak <= 2)
        }
        let coldDuration = started.duration(to: clock.now)
        let warmStarted = clock.now
        let warm = try await repo.load(publicKeys: keys)
        let warmDuration = warmStarted.duration(to: clock.now)
        #expect(warm.map(\.publicKey) == keys)
        #expect(warm.allSatisfy { $0.state == .cached })
        #expect(await server.calls == 500)
        print("Synthetic 500-node loading: 25 batches=\(coldDuration), warm=\(warmDuration)")
    }

    @Test func catalogOmissionsAndUnavailableStayUnknown() async throws {
        let server = Server()
        let repo = repository(server)
        let now = Date.now
        try await repo.seedCatalog(nodes: [Self.node(Self.key(0))], fetchedAt: now, source: "fixture")
        await #expect(throws: APIError.self) {
            try await repo.seedCatalog(nodes: [], fetchedAt: now, source: "another-source")
        }
        let rows = try await repo.load(publicKeys: [Self.key(0), Self.key(1)])
        #expect(rows[0].state == .cached)
        #expect(rows[0].snapshot?.fetchedAt == now)
        #expect(rows[1].state == .fetched)
        #expect(await server.calls == 1)
        await server.configure(missing: true)
        let missing = try await repo.load(publicKeys: [Self.key(0)], forceRefresh: true)
        #expect(missing.first?.state == .unavailable)
        #expect(missing.first?.snapshot != nil)
        #expect(try await repo.cached(publicKeys: [Self.key(0)]).isEmpty)
        _ = try await repo.load(publicKeys: [Self.key(0)])
        #expect(await server.calls == 3)
    }

    @Test func staleCatalogRefreshesAndFutureCatalogIsRejected() async throws {
        let server = Server()
        let repo = repository(server)
        try await repo.seedCatalog(nodes: [Self.node(Self.key(0))], fetchedAt: .now.addingTimeInterval(-301), source: "fixture")
        #expect(try await repo.load(publicKeys: [Self.key(0)]).first?.state == .fetched)
        await #expect(throws: APIError.self) {
            try await repo.seedCatalog(nodes: [], fetchedAt: .now.addingTimeInterval(100), source: "fixture")
        }
        #expect(await server.calls == 1)
    }

    @Test func progressiveRowsArriveBeforeSlowLookup() async throws {
        let server = Server()
        await server.configure(blocked: Self.key(0))
        let repo = repository(server)
        let updates = AsyncStream<MyNodesBaseRow>.makeStream()
        let task = Task {
            try await repo.load(publicKeys: [Self.key(0), Self.key(1)]) { updates.continuation.yield($0) }
        }
        for await row in updates.stream {
            if row.publicKey == Self.key(1) {
                #expect(row.state == .fetched)
                break
            }
        }
        #expect(try await repo.cached(publicKeys: [Self.key(1)]).count == 1)
        #expect(await server.active == 1)
        await server.release()
        #expect(try await task.value.count == 2)
        updates.continuation.finish()
    }

    @Test func clearRejectsJoinedAndLateWorkAndRateLimitStopsScheduling() async throws {
        let server = Server()
        await server.configure(blocked: Self.key(0))
        let repo = repository(server)
        let first = Task { try await repo.load(publicKeys: [Self.key(0)]) }
        for _ in 0..<10000 { if await server.calls == 1 { break }; await Task.yield() }
        let joined = Task { try await repo.load(publicKeys: [Self.key(1)]) }
        for _ in 0..<10000 { if await repo.activeConsumerCount == 2 { break }; await Task.yield() }
        #expect(await repo.activeConsumerCount == 2)
        await repo.clear()
        await #expect(throws: CancellationError.self) { try await first.value }
        await #expect(throws: CancellationError.self) { try await joined.value }
        await server.release()
        #expect(try await repo.cached(publicKeys: [Self.key(0)]).isEmpty)
        await server.configure(rateLimited: true)
        let rows = try await repo.load(publicKeys: (0..<100).map(Self.key))
        #expect(rows.contains { $0.state == .rateLimited })
        #expect(await server.calls <= 3)
        #expect(rows.filter { $0.state == .deferred }.count >= 98)
    }
}
