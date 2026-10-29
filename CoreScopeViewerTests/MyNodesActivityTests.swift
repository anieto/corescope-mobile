import Foundation
import Testing
@testable import CoreScopeViewer

struct MyNodesActivityTests {
    struct Record: Decodable {
        let lastSeen: Date?
        let lastHeard: Date?
        let fetchedAt: Date
        let evaluatedAt: Date
        let successfulRecord: Bool

        var assessment: MyNodesActivity {
            MyNodesActivity(lastSeen: lastSeen, lastHeard: lastHeard, fetchedAt: fetchedAt,
                            evaluatedAt: evaluatedAt, successfulRecord: successfulRecord)
        }
    }

    struct Case: Decodable {
        let name: String
        let current: Record
        let baseline: Record?
        let expectedState: MyNodesActivity.State
        let expectedEligible: Bool
        let expectedChange: MyNodesActivity.Change?
    }

    @Test func sharedActivityCases() throws {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("shared/fixtures/my-nodes/activity-cases.json")
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        let cases = try decoder.decode([Case].self, from: Data(contentsOf: url))
        #expect(cases.count == 22)
        for entry in cases {
            let current = entry.current.assessment
            #expect(current.state == entry.expectedState, Comment(rawValue: entry.name))
            #expect(current.eligible == entry.expectedEligible, Comment(rawValue: entry.name))
            let change = entry.baseline.flatMap { current.change(from: $0.assessment) }
            #expect(change == entry.expectedChange, Comment(rawValue: entry.name))
        }
    }
}
