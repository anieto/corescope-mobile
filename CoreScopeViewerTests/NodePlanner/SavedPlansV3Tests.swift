import Foundation
import Testing
@testable import CoreScopeViewer

/// Saved-plans v3 (A1): the shared reference library and cases, migration, result files and revisions.
/// Mirrors Android's SavedPlansV3Test.
struct SavedPlansV3Tests {
    private let shared: URL
    private let cases: [String: Any]

    init() throws {
        var root = URL(fileURLWithPath: #filePath).resolvingSymlinksInPath().deletingLastPathComponent()
        while root.path != "/" && !FileManager.default.fileExists(atPath: root.appendingPathComponent("shared/fixtures").path) {
            root.deleteLastPathComponent()
        }
        shared = root.appendingPathComponent("shared/fixtures/saved-plans")
        cases = try JSONSerialization.jsonObject(with: Data(contentsOf: shared.appendingPathComponent("v3/v3-cases.json"))) as! [String: Any]
    }

    private func library() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root.appendingPathComponent("plans.json")
    }

    /// The reference library and its result files, copied into place.
    private func reference(_ file: URL) throws {
        try FileManager.default.copyItem(at: shared.appendingPathComponent("v3/reference/plans.json"), to: file)
        try FileManager.default.copyItem(at: shared.appendingPathComponent("v3/reference/results"),
                                         to: file.deletingLastPathComponent().appendingPathComponent("results"))
    }

    private func ids(_ key: String) -> [String] {
        ((cases["reference"] as! [String: Any])[key] as! [String])
    }

    @Test func everySharedCaseIsReadAsExpected() throws {
        let list = cases["cases"] as! [[String: Any]]
        #expect(list.count == 38)
        for item in list {
            let data = try JSONSerialization.data(withJSONObject: item["document"]!)
            let valid = (try? DeploymentPlanCodec.decode(data)) != nil
            #expect(valid == (item["valid"] as! Bool), "\(item["name"]!)")
        }
    }

    @Test func referenceLibraryLoadsWithEachResultState() async throws {
        let file = try library()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        try reference(file)
        let studies = Dictionary(uniqueKeysWithValues: try await DeploymentPlanStore(fileURL: file).load().studies.map { ($0.id, $0) })
        for id in ids("missingResults") { #expect(studies[id]?.resultStatus == .missing) }
        // The Android legacy result is kept but can't be shown on iOS.
        for id in ids("legacyResults") { #expect(studies[id]?.resultStatus == .unsupported) }
        // Portable coverage results (B), including one shared by an imported copy, show for their own inputs.
        for id in ids("sharedResult") {
            #expect(studies[id]?.resultStatus == .available)
            #expect(studies[id]?.coverage?.calculatedResult != nil)
        }
        for id in ids("inputsOnly") { #expect(studies[id]?.resultStatus == SavedResultStatus.none) }
        for id in ids("unknownOrigin") { #expect(studies[id]?.origin == nil) }
    }

    @Test func savingTheReferenceKeepsEveryReferenceAndFile() async throws {
        let file = try library()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        try reference(file)
        let results = file.deletingLastPathComponent().appendingPathComponent("results").path
        let before = try FileManager.default.contentsOfDirectory(atPath: results).sorted()
        let store = DeploymentPlanStore(fileURL: file)
        let loaded = try await store.load()
        try await store.save(loaded)
        let saved = try await DeploymentPlanStore(fileURL: file).load()
        #expect(saved.documentRevision == loaded.documentRevision.map { $0 + 1 })
        #expect(saved.studies.map(\.revision) == loaded.studies.map(\.revision))
        #expect(saved.studies.map(\.resultRef) == loaded.studies.map(\.resultRef))
        #expect(saved.studyPlans == loaded.studyPlans)
        #expect(try FileManager.default.contentsOfDirectory(atPath: results).sorted() == before)
    }

    /// iOS 0.10.6's own plist result moves to a file, byte for byte, and still restores.
    @Test func v2InlinePlistMovesToAFileWithItsExactBytes() async throws {
        let file = try library()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let plist = try Data(contentsOf: shared.appendingPathComponent("legacy/ios-0.10.6-coverage.plist"))
        let v2 = try JSONSerialization.jsonObject(with: Data(contentsOf: shared.deletingLastPathComponent()
            .appendingPathComponent("candidate-sites/plan-v2-cases.json"))) as! [[String: Any]]
        var document = try DeploymentPlanCodec.decode(JSONSerialization.data(withJSONObject: v2[0]["document"]!))
        let index = document.studies.firstIndex { $0.kind == .coverage }!
        document.studies[index].coverage?.calculatedResult = plist
        let original = try DeploymentPlanCodec.encode(document)
        try original.write(to: file)
        let store = DeploymentPlanStore(fileURL: file)
        let loaded = try await store.load()
        try await store.save(loaded)
        let coverage = try await DeploymentPlanStore(fileURL: file).load().studies.first { $0.kind == .coverage }!
        #expect(coverage.resultRef?.format == ResultRef.iosLegacy)
        let stored = file.deletingLastPathComponent().appendingPathComponent("results/\(coverage.resultRef!.sha256).result")
        #expect(try Data(contentsOf: stored) == plist)
        #expect(coverage.coverage?.calculatedResult == plist)
        #expect(coverage.resultStatus == .available)
        #expect(try Data(contentsOf: migrationBackup(file, original)) == original)
        #expect(!(try String(contentsOf: file, encoding: .utf8)).contains("calculatedResult"))
        #expect(try await DeploymentPlanStore(fileURL: file).load().content == loaded.content)
    }

    @Test func missingResultSurvivesRenameButNotAnInputChange() async throws {
        let file = try library()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        try reference(file)
        let store = DeploymentPlanStore(fileURL: file)
        var document = try await store.load()
        let id = ids("missingResults")[0]
        let index = document.studies.firstIndex { $0.id == id }!
        let missing = document.studies[index]
        document.studies[index].name = "Renamed"
        var renamed = try await store.save(document)
        let afterRename = renamed.studies.first { $0.id == id }!
        #expect(afterRename.resultRef == missing.resultRef)
        #expect(afterRename.resultStatus == .missing)
        #expect(afterRename.revision == missing.revision.map { $0 + 1 })
        let changed = renamed.studies.firstIndex { $0.id == id }!
        renamed.studies[changed].coverage?.reserveDb = 3
        // The old result can't be kept as a prediction for changed inputs.
        #expect(try await store.save(renamed).studies.first { $0.id == id }?.resultRef == nil)
    }

    @Test func wrongBytesAreReportedNotTrusted() async throws {
        let file = try library()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        try reference(file)
        let study = try await DeploymentPlanStore(fileURL: file).load().studies.first { $0.resultRef?.format == ResultRef.androidLegacy }!
        let path = file.deletingLastPathComponent().appendingPathComponent("results/\(study.resultRef!.sha256).result")
        let handle = try FileHandle(forWritingTo: path)
        try handle.seekToEnd(); try handle.write(contentsOf: Data(" ".utf8)); try handle.close()
        #expect(try await DeploymentPlanStore(fileURL: file).load().studies.first { $0.id == study.id }?.resultStatus == .unverified)
    }

    @Test func sharedFileStaysUntilItsLastOwnerGoes() async throws {
        let file = try library()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        try reference(file)
        let store = DeploymentPlanStore(fileURL: file)
        var document = try await store.load()
        let owners = ids("sharedResult")
        let sha = document.studies.first { $0.id == owners[0] }!.resultRef!.sha256
        let path = file.deletingLastPathComponent().appendingPathComponent("results/\(sha).result").path
        document.studies.removeAll { $0.id == owners[0] }
        document = try await store.save(document)
        #expect(FileManager.default.fileExists(atPath: path))
        document.studies.removeAll { $0.id == owners[1] }
        try await store.save(document)
        #expect(!FileManager.default.fileExists(atPath: path))
    }

    @Test func futureVersionsAreRefusedAndNeverOverwritten() async throws {
        let file = try library()
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let future = Data(#"{"schemaVersion":4,"plans":[]}"#.utf8)
        try future.write(to: file)
        let store = DeploymentPlanStore(fileURL: file)
        await #expect(throws: DeploymentPlanError.unsupportedVersion) { try await store.load() }
        await #expect(throws: DeploymentPlanStoreError.notLoaded) {
            try await store.save(DeploymentPlanDocument(schemaVersion: 3, plans: []))
        }
        #expect(try Data(contentsOf: file) == future)
    }
}
