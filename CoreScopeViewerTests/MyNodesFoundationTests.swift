import Foundation
import Testing
@testable import CoreScopeViewer

struct MyNodesFoundationTests {
    struct EvidenceCase: Decodable {
        let name: String
        let completeness: AnalyticsCompleteness?
        let fetchedAt: Date
        let requestedFrom: Date
        let requestedTo: Date
        let coveredFrom: Date?
        let coveredTo: Date?
        let now: Date
        let expected: Bool
    }

    @Test func sharedEvidenceCases() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("shared/fixtures/my-nodes/evidence-cases.json")
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        let cases = try decoder.decode([EvidenceCase].self, from: Data(contentsOf: url))
        for entry in cases {
            let context = entry.completeness.map {
                AnalyticsContext(completeness: $0, countUnit: .packets, provenance: .server,
                    requestedFrom: entry.requestedFrom, requestedTo: entry.requestedTo,
                    coveredFrom: entry.coveredFrom, coveredTo: entry.coveredTo, explanation: nil)
            }
            let evidence = MyNodesEvidence(context: context, fetchedAt: entry.fetchedAt,
                requestedFrom: entry.requestedFrom, requestedTo: entry.requestedTo)
            #expect(evidence.permitsHistoryComparison(at: entry.now) == entry.expected, Comment(rawValue: entry.name))
            if context == nil { #expect(evidence.completeness == .unknown) }
        }
    }

    @Test(arguments: [0, 1, 20, 100, 500])
    func favoriteCountsStayWithinBatch(size: Int) async throws {
        let budget = MyNodesRequestBudget()
        var attempts = 0
        for index in 0..<size {
            do {
                _ = try await budget.perform(node: "node-\(index)") { true }
                attempts += 1
            } catch MyNodesBudgetError.exhausted { break }
        }
        #expect(attempts == min(size, 20))
    }

    @Test func failedAttemptsConsumeNodeAllowance() async throws {
        enum Failure: Error { case transport }
        let budget = MyNodesRequestBudget(maximumRequests: 5, maximumPerNode: 2)
        for _ in 0..<2 {
            await #expect(throws: Failure.self) {
                try await budget.perform(node: "ABC") { throw Failure.transport }
            }
        }
        await #expect(throws: MyNodesBudgetError.nodeExhausted) {
            try await budget.perform(node: "abc") { true }
        }
        #expect(try await budget.perform(node: "other") { true })
    }

    @Test func concurrentGateAndInvalidationRejectLateResults() async throws {
        let budget = MyNodesRequestBudget(maximumConcurrent: 1)
        let started = AsyncStream<Void>.makeStream()
        let release = AsyncStream<Void>.makeStream()
        let first = Task {
            try await budget.perform {
                started.continuation.yield(())
                for await _ in release.stream { break }
                return true
            }
        }
        for await _ in started.stream { break }
        await #expect(throws: MyNodesBudgetError.busy) { try await budget.perform { true } }
        await budget.invalidate()
        release.continuation.yield(())
        release.continuation.finish()
        started.continuation.finish()
        await #expect(throws: MyNodesBudgetError.invalidated) { try await first.value }
        await #expect(throws: MyNodesBudgetError.invalidated) { try await budget.perform { true } }
    }

    @Test func cancellationRejectsResultAndReleasesSlot() async throws {
        let budget = MyNodesRequestBudget(maximumRequests: 2, maximumConcurrent: 1)
        let started = AsyncStream<Void>.makeStream()
        let release = AsyncStream<Void>.makeStream()
        let task = Task {
            try await budget.perform {
                started.continuation.yield(())
                for await _ in release.stream { break }
                return true
            }
        }
        for await _ in started.stream { break }
        task.cancel()
        release.continuation.finish()
        started.continuation.finish()
        await #expect(throws: CancellationError.self) { try await task.value }
        #expect(try await budget.perform { true })
        await #expect(throws: MyNodesBudgetError.exhausted) { try await budget.perform { true } }
    }
}
