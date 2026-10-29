import Foundation
import Testing
@testable import CoreScopeViewer

struct MyNodesComparisonTests {
    struct SignalCase: Decodable {
        let name: String
        let current: MyNodesSignal
        let baseline: MyNodesSignal?
        let trend: Double?
        let observers: Int
        let significant: Bool
        let visit: Double?
        let visitObservers: Int
        let visitSignificant: Bool
    }

    struct ObserverRecord: Decodable {
        let explicitOnline: Bool?
        let statusAt: Date?
        let lastSeen: Date?
        let fetchedAt: Date
        let evaluatedAt: Date
        let successfulRecord: Bool

        var assessment: MyNodesObserverStatus {
            MyNodesObserverStatus(explicitOnline: explicitOnline, statusAt: statusAt,
                                  lastSeen: lastSeen, fetchedAt: fetchedAt, evaluatedAt: evaluatedAt,
                                  successfulRecord: successfulRecord)
        }
    }

    struct ObserverCase: Decodable {
        let name: String
        let current: ObserverRecord
        let baseline: ObserverRecord?
        let state: MyNodesObserverStatus.State
        let method: MyNodesObserverStatus.Method
        let eligible: Bool
        let changed: Bool
    }

    private func fixture<T: Decodable>(_ name: String, as type: T.Type) throws -> T {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("shared/fixtures/my-nodes/\(name).json")
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        return try decoder.decode(type, from: Data(contentsOf: url))
    }

    @Test func sharedSignalCases() throws {
        let cases = try fixture("signal-cases", as: [SignalCase].self)
        #expect(cases.count == 25)
        for entry in cases {
            let trend = entry.current.withinDayTrend()
            check(trend, expected: entry.trend, observers: entry.observers,
                  significant: entry.significant, name: entry.name)
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .millisecondsSince1970
            let data = try encoder.encode(entry.current.summary)
            #expect(!String(decoding: data, as: UTF8.self).contains("\"samples\""))
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .millisecondsSince1970
            let summary = try decoder.decode(MyNodesSignal.Summary.self, from: data)
            let visit = entry.baseline.flatMap { summary.visitChange(from: $0.summary) }
            check(visit, expected: entry.visit, observers: entry.visitObservers,
                  significant: entry.visitSignificant, name: entry.name)
        }
    }

    private func check(_ result: MyNodesSignal.Delta?, expected: Double?, observers: Int,
                       significant: Bool, name: String) {
        #expect((result == nil) == (expected == nil), Comment(rawValue: name))
        if let result, let expected {
            #expect(abs(result.decibels - expected) < 0.000001, Comment(rawValue: name))
            #expect(result.comparableObservers == observers, Comment(rawValue: name))
            #expect(result.isSignificant == significant, Comment(rawValue: name))
        }
    }

    @Test func reviewRejectsIncomparableSignalWindowsAndObserverMethods() throws {
        let namespace = MyNodesNamespace(origin: "https://fixture.invalid", backend: "corescope", selectionScope: "local", policyVersion: 1)
        let node = MyNodesEntity(kind: .node, value: String(repeating: "a", count: 64))
        for entry in try fixture("signal-cases", as: [SignalCase].self) {
            guard let old = entry.baseline else { continue }
            let metric = MyNodesMetric(signal: entry.current.summary)
            let baseline = MyNodesBaseline(revision: "fixed", metric: MyNodesMetric(signal: old.summary))
            let result = metric.review(namespace: namespace, entity: node, baseline: baseline, expired: false)
            #expect(result.state == (entry.visit == nil ? .unavailable : entry.visitSignificant ? .changed : .unchanged),
                    Comment(rawValue: entry.name))
        }
        let date = Date(timeIntervalSince1970: 10000)
        let previous = MyNodesMetric(observer: MyNodesObserverStatus(explicitOnline: true, statusAt: date,
            lastSeen: nil, fetchedAt: date, evaluatedAt: date, successfulRecord: true))
        let later = date.addingTimeInterval(10)
        let inferred = MyNodesMetric(observer: MyNodesObserverStatus(explicitOnline: nil, statusAt: nil,
            lastSeen: later, fetchedAt: later, evaluatedAt: later, successfulRecord: true))
        let observer = MyNodesEntity(kind: .observer, value: "same-observer")
        #expect(inferred.review(namespace: namespace, entity: observer,
            baseline: MyNodesBaseline(revision: "fixed", metric: previous), expired: false).state == .unavailable)
    }

    @Test func nonfiniteSamplesDoNotMeetMinimum() throws {
        let entry = try #require(fixture("signal-cases", as: [SignalCase].self).first)
        for invalid in [Double.nan, Double.infinity, -Double.infinity] {
            var samples = entry.current.samples
            let replaced = samples.removeFirst()
            samples.append(.init(timestamp: replaced.timestamp, snr: invalid, observerID: replaced.observerID))
            let signal = MyNodesSignal(method: entry.current.method, evidence: entry.current.evidence,
                                       evaluatedAt: entry.current.evaluatedAt, samples: samples)
            #expect(signal.withinDayTrend() == nil)
        }
    }

    @Test func sharedObserverCases() throws {
        let cases = try fixture("observer-cases", as: [ObserverCase].self)
        #expect(cases.count == 18)
        for entry in cases {
            let current = entry.current.assessment
            #expect(current.state == entry.state, Comment(rawValue: entry.name))
            #expect(current.method == entry.method, Comment(rawValue: entry.name))
            #expect(current.eligible == entry.eligible, Comment(rawValue: entry.name))
            let changed = entry.baseline.map { current.changed(from: $0.assessment) } ?? false
            #expect(changed == entry.changed, Comment(rawValue: entry.name))
        }
    }
}
