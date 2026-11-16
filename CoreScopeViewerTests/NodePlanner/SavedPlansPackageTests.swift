import Foundation
import Testing
@testable import CoreScopeViewer

/// `.nodescope` packages against the shared cases (shared/fixtures/saved-plans/packages), as Android.
struct SavedPlansPackageTests {
    private let cases: [[String: Any]]

    init() throws {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent("shared/fixtures").path) {
            root.deleteLastPathComponent()
        }
        let file = root.appendingPathComponent("shared/fixtures/saved-plans/packages/package-cases-v1.json")
        cases = (try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as! [String: Any])["cases"] as! [[String: Any]]
    }

    private func outcome(_ data: Data) -> (String, SavedPlansPackage?) {
        do {
            let package = try SavedPlansPackage.read(data)
            return (package.unusableResults.isEmpty ? "valid" : "resultUnusable", package)
        } catch SavedPlansPackage.Failure.needsNewerVersion {
            return ("future", nil)
        } catch {
            return ("invalid", nil)
        }
    }

    @Test func everySharedPackageCaseIsReadAsExpected() throws {
        #expect(cases.count == 68)
        for test in cases {
            let name = test["name"] as! String
            let data = try #require(Data(base64Encoded: test["packageBase64"] as! String))
            let (expect, package) = outcome(data)
            #expect(expect == test["expect"] as! String, "\(name): \(expect)")
            if let package {
                #expect(package.unusableResults == Set(test["unusableStudyIDs"] as! [String]), "\(name)")
                #expect(package.studies.allSatisfy { !package.unusableResults.contains($0.id) || $0.resultRef == nil }, "\(name)")
            }
            if expect == "valid", let summary = test["summary"] as? [String: Any], let package {
                #expect(package.scope.rawValue == summary["scope"] as? String, "\(name)")
                #expect(package.studies.count == summary["studies"] as? Int, "\(name)")
                #expect(package.studyPlans.count == summary["studyPlans"] as? Int, "\(name)")
                #expect(package.candidateSitePlans.count == summary["candidateSitePlans"] as? Int, "\(name)")
                #expect(package.results.count == summary["resultFiles"] as? Int, "\(name)")
                #expect(package.omittedResults.count == summary["omittedResults"] as? Int, "\(name)")
            }
        }
    }

    @Test func aWrittenPackageReadsBackExactly() throws {
        for test in cases where test["expect"] as? String == "valid" {
            let name = test["name"] as! String
            let original = try SavedPlansPackage.read(Data(base64Encoded: test["packageBase64"] as! String)!)
            let written = try original.encoded()
            #expect(try SavedPlansPackage.read(written) == original, "\(name) changed when written again")
        }
    }

    @Test func expandingStopsAtTheDeclaredSize() throws {
        // The shared bomb declares 1,000 bytes for a stream of a mebibyte of zeros.
        let bomb = try #require(cases.first { $0["name"] as? String == "compression bomb" })
        #expect(outcome(Data(base64Encoded: bomb["packageBase64"] as! String)!).0 == "invalid")
    }
}
