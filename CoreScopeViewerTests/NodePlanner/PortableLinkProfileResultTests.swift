import Foundation
import Testing
@testable import CoreScopeViewer

/// nodescope.link-profile-result v1 against the shared cases (shared/fixtures/saved-plans/results), as Android.
struct PortableLinkProfileResultTests {
    private let cases: [[String: Any]]

    init() throws {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent("shared/fixtures").path) {
            root.deleteLastPathComponent()
        }
        let file = root.appendingPathComponent("shared/fixtures/saved-plans/results/result-cases-v1.json")
        let all = try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as! [String: Any]
        cases = (all["cases"] as! [[String: Any]]).filter { $0["kind"] as? String == "linkProfile" }
    }

    /// The JSON tree with numbers compared by value, so 1000 and 1000.0 match.
    private func normalized(_ data: Data) throws -> NSObject {
        func walk(_ value: Any) -> NSObject {
            switch value {
            case let object as [String: Any]: return object.mapValues(walk) as NSDictionary
            case let array as [Any]: return array.map(walk) as NSArray
            case let number as NSNumber where CFGetTypeID(number) != CFBooleanGetTypeID(): return NSNumber(value: number.doubleValue)
            default: return value as! NSObject
            }
        }
        return walk(try JSONSerialization.jsonObject(with: data, options: .fragmentsAllowed))
    }

    @Test func everySharedLinkProfileCaseIsReadAsExpected() throws {
        #expect(cases.count == 30)
        for test in cases {
            let name = test["name"] as! String
            let study = try JSONDecoder().decode(SavedLinkProfile.self, from: JSONSerialization.data(withJSONObject: test["study"]!))
            let payload = try JSONSerialization.data(withJSONObject: test["payload"]!)
            let valid = test["valid"] as! Bool
            guard let analysis = try? PortableLinkProfileResult.decode(payload, inputs: study) else {
                #expect(!valid, "\(name) should be valid")
                continue
            }
            #expect(valid, "\(name) should be refused")
            // Writing it back gives the same payload, so a result survives any number of saves.
            let again = try #require(PortableLinkProfileResult.encode(analysis, inputs: study), "\(name)")
            #expect(PortableLinkProfileResult.isPortable(again), "\(name)")
            #expect(try normalized(again) == normalized(payload), "\(name) re-encodes differently")
        }
    }
}
