import Foundation
import Testing
@testable import CoreScopeViewer

struct MyNodesRepositoryTests {
    private static func key(_ index: Int) -> String {
        String(repeating: "a", count: 56) + String(format: "%08x", index)
    }

    private final class Clock: @unchecked Sendable {
        private let lock = NSLock()
        private var value = Date(timeIntervalSince1970: 1_800_000_000)
        func read() -> Date { lock.withLock { value } }
        func advance(_ seconds: TimeInterval) { lock.withLock { value = value.addingTimeInterval(seconds) } }
    }

    private actor Server {
        var calls = 0
        var active = 0
        var peak = 0
        var blocked = false
        var failureStatus: Int?
        private var releases: [CheckedContinuation<Void, Never>] = []

        func block() { blocked = true }
        func fail(status: Int = 503) { failureStatus = status }
        func unblock() {
            blocked = false
            let pending = releases
            releases.removeAll()
            pending.forEach { $0.resume() }
        }

        func fetch(key: String, to: Date, budget: MyNodesRequestBudget) async throws -> NodeAnalyticsResponse {
            try await budget.perform(node: key) { try await self.response(key: key, to: to) }
        }

        private func response(key: String, to: Date) async throws -> NodeAnalyticsResponse {
            calls += 1
            active += 1
            peak = max(peak, active)
            defer { active -= 1 }
            if blocked { await withCheckedContinuation { releases.append($0) } }
            if let failureStatus { throw APIError.http(failureStatus) }
            let body: [String: Any] = [
                "node": ["public_key": key, "role": "repeater"],
                "timeRange": ["from": to.timeIntervalSince1970 - 86400, "to": to.timeIntervalSince1970, "days": 1],
                "computedStats": ["totalPackets": 0, "avgPacketsPerDay": 0]
            ]
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .secondsSince1970
            return try decoder.decode(NodeAnalyticsResponse.self, from: JSONSerialization.data(withJSONObject: body))
        }
    }

    private static func repository(_ server: Server, clock: Clock, session: AnalyzerSourceSession = AnalyzerSourceSession()) -> MyNodesAnalyticsRepository {
        MyNodesAnalyticsRepository(source: "corescope|https://fixture.invalid", session: session, now: { clock.read() }) {
            try await server.fetch(key: $0, to: $1, budget: $2)
        }
    }

    private static func until(_ condition: () async -> Bool) async throws {
        for _ in 0..<10_000 {
            if await condition() { return }
            await Task.yield()
        }
        throw URLError(.timedOut)
    }

    @Test func coldWarmAndNextBatch() async throws {
        for size in [0, 1, 20, 100, 500] {
            let server = Server()
            let clock = Clock()
            let repository = Self.repository(server, clock: clock)
            let keys = (0..<size).map(Self.key)
            let first = try await repository.load(publicKeys: keys)
            #expect(first.map(\.publicKey) == keys)
            #expect(first.filter { $0.state == .fetched }.count == min(size, 20))
            #expect(first.filter { $0.state == .deferred }.count == max(0, size - 20))
            #expect(await server.calls == min(size, 20))
            #expect(await server.peak <= 2)
            let second = try await repository.load(publicKeys: keys)
            #expect(second.filter { $0.state == .cached }.count == min(size, 20))
            #expect(await server.calls == min(size, 40))
            #expect(second.prefix(min(size, 20)).allSatisfy { $0.snapshot?.evidence.fetchedAt == clock.read() })
            }
    }

    @Test func freshnessRetainsTimestampAndFailedRefreshRetainsStaleData() async throws {
        let server = Server()
        let clock = Clock()
        let repository = Self.repository(server, clock: clock)
        let key = Self.key(0)
        let first = try #require(await repository.load(publicKeys: [key]).first?.snapshot)
        #expect(first.evidence.completeness == .unknown)
        clock.advance(900)
        #expect(try await repository.load(publicKeys: [key]).first?.state == .cached)
        #expect(await server.calls == 1)
        clock.advance(1)
        await server.fail()
        let failed = try #require(await repository.load(publicKeys: [key]).first)
        #expect(failed.state == .failed)
        #expect(failed.snapshot?.evidence.fetchedAt == first.evidence.fetchedAt)
        #expect(failed.snapshot?.isReusable(at: clock.read()) == false)
        clock.advance(-1000)
        #expect(try await repository.load(publicKeys: [key]).first?.state == .failed)
        #expect(await server.calls == 3)
    }

    @Test func joiningForceRefreshCoalescesAndCancellationIsPerConsumer() async throws {
        let server = Server()
        await server.block()
        let repository = Self.repository(server, clock: Clock())
        let key = Self.key(0)
        let first = Task { try await repository.load(publicKeys: [key]) }
        try await Self.until { await server.calls == 1 }
        let second = Task { try await repository.load(publicKeys: [key.uppercased(), key, Self.key(1)], forceRefresh: true) }
        try await Self.until { await repository.activeConsumerCount == 2 }
        first.cancel()
        await #expect(throws: CancellationError.self) { try await first.value }
        #expect(await repository.activeConsumerCount == 1)
        await server.unblock()
        let rows = try await second.value
        #expect(rows.count == 2)
        #expect(rows.first?.state == .fetched)
        #expect(rows.last?.state == .deferred)
        #expect(rows.last?.snapshot == nil)
        #expect(await server.calls == 1)
    }

    @Test func clearRejectsLateWritesAndAllowsNewBatch() async throws {
        let server = Server()
        await server.block()
        let clock = Clock()
        let repository = Self.repository(server, clock: clock)
        let key = Self.key(0)
        let first = Task { try await repository.load(publicKeys: [key]) }
        try await Self.until { await server.calls == 1 }
        await repository.clear()
        await #expect(throws: CancellationError.self) { try await first.value }
        #expect(try await repository.cached(publicKeys: [key]).isEmpty)
        await server.unblock()
        clock.advance(60)
        let rows = try await repository.load(publicKeys: [key])
        try await Self.until { await server.active == 0 }
        #expect(rows.first?.state == .fetched)
        #expect(try await repository.cached(publicKeys: [key])[key]?.evidence.fetchedAt == clock.read())
    }

    @Test func invalidatedSourceRejectsResultsAndCacheReads() async throws {
        let server = Server()
        await server.block()
        let session = AnalyzerSourceSession()
        let repository = Self.repository(server, clock: Clock(), session: session)
        let task = Task { try await repository.load(publicKeys: [Self.key(0)]) }
        try await Self.until { await server.calls == 1 }
        session.invalidate()
        await #expect(throws: CancellationError.self) { try await task.value }
        await server.unblock()
        await #expect(throws: CancellationError.self) { try await repository.cached(publicKeys: [Self.key(0)]) }
        let other = Self.repository(server, clock: Clock())
        #expect(try await other.cached(publicKeys: [Self.key(0)]).isEmpty)
    }

    @Test func cancellingLastConsumerStopsOwnedWork() async throws {
        let server = Server()
        await server.block()
        let repository = Self.repository(server, clock: Clock())
        let task = Task { try await repository.load(publicKeys: [Self.key(0)]) }
        try await Self.until { await server.calls == 1 }
        task.cancel()
        await #expect(throws: CancellationError.self) { try await task.value }
        #expect(await repository.activeConsumerCount == 0)
        await server.unblock()
        try await Self.until { await server.active == 0 }
        #expect(try await repository.cached(publicKeys: [Self.key(0)]).isEmpty)
    }

    @Test func rateLimitStopsSchedulingWithoutInventingData() async throws {
        let server = Server()
        await server.fail(status: 429)
        let repository = Self.repository(server, clock: Clock())
        let rows = try await repository.load(publicKeys: (0..<100).map(Self.key))
        #expect(rows.count == 100)
        #expect(rows.allSatisfy { $0.snapshot == nil })
        #expect(rows.filter { $0.state == .deferred }.count >= 98)
        #expect(rows.contains { $0.state == .rateLimited })
        #expect(await server.calls <= 2)
    }

    @Test func mismatchedIdentityIsNeverCached() async throws {
        let server = Server()
        let repository = MyNodesAnalyticsRepository(source: "fixture", session: AnalyzerSourceSession()) { _, to, budget in
            try await server.fetch(key: Self.key(1), to: to, budget: budget)
        }
        #expect(try await repository.load(publicKeys: [Self.key(0)]).first?.state == .failed)
        #expect(try await repository.cached(publicKeys: [Self.key(0)]).isEmpty)
    }
}
